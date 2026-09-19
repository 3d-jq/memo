package com.psyche.memo.data.backup.cherry

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.backup.BackupCancelledException
import com.psyche.memo.data.backup.BackupPhase
import com.psyche.memo.data.backup.BackupProgressSink
import com.psyche.memo.data.backup.RestoreMode
import com.psyche.memo.data.db.ConversationDao
import com.psyche.memo.data.db.MessageDao
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ApiKeyConfig
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.KeyManagementConfig
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Imports a Cherry Studio backup into Memo — 1:1 port of
 * `cherry_importer.dart` (orchestration `importFromCherryStudio` L48-137, the
 * file-shape probes, the parse body, `_parseProviders` / `_parseAssistants`,
 * `_importBusinessData`, `_materializeFilesSync`, `_buildTypedCherryTopics`,
 * `_commitTypedCherryTopics` and the attachment resolution).
 *
 * The Dart original runs parse/materialize inside isolates; on Android the
 * caller already invokes this from `Dispatchers.IO`, so the work runs inline.
 * Dynamic backup data uses the same dynamic-tree convention as
 * [CherryDirectBackupReader] (`anyFromJson`).
 */

/** Counts of what one import wrote (`CherryImportResult`). */
data class CherryImportResult(
    val providers: Int,
    val assistants: Int,
    val conversations: Int,
    val messages: Int,
    val files: Int,
)

internal class CherryParsedBackup(
    val providers: Map<String, JsonObject>,
    val assistants: List<JsonObject>,
    val topics: List<CherryTypedTopic>,
    val filesById: Map<String, MutableMap<String, Any?>>,
    val usedFileIds: Set<String>,
)

internal class CherryTypedTopic(
    val mergeIntoExisting: Boolean,
    val conversation: com.psyche.memo.data.model.Conversation,
    val messages: List<CherryTypedMessage>,
)

internal class CherryTypedMessage(
    val message: ChatMessage,
    val pendingWrites: List<PendingAttachmentRef>,
)

internal class PendingAttachmentRef(
    val fileId: String? = null,
    val dataUrl: String? = null,
    val url: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val originPath: String? = null,
    val isImage: Boolean = true,
)

object CherryImporter {

    // Published backup keys used by the business settings router.
    private const val PROVIDERS_KEY = "provider_configs_v1"
    private const val PROVIDERS_ORDER_KEY = "providers_order_v1"
    private const val ASSISTANTS_KEY = "assistants_v1"

    /** Cap for *speculative* ZIP-entry probes (unknown / non-`.json` names). */
    const val DEFAULT_SPECULATIVE_JSON_PROBE_BYTES = 32 * 1024 * 1024

    /** Absolute ceiling for in-archive identified `.json` entries only. */
    const val DEFAULT_IDENTIFIED_ARCHIVE_JSON_BYTES = 256 * 1024 * 1024

    private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

    private val MODEL_JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val BLOCKED_SPECULATIVE_PROBE_EXTENSIONS = setOf(
        ".sqlite", ".db", ".ldb", ".log", ".png", ".jpg", ".jpeg", ".gif",
        ".webp", ".pdf", ".bin", ".exe", ".dll", ".so", ".dylib", ".wasm",
        ".mp3", ".mp4", ".wav", ".zip", ".gz",
    )

    private val IMAGE_EXT = Regex("\\.(png|jpg|jpeg|gif|webp)")
    private val IMAGE_URL_EXT = Regex("\\.(png|jpg|jpeg|gif|webp)(?:$|[?#])")
    private val DATA_IMAGE_URL = Regex("data:image/[a-zA-Z0-9.+-]+;base64,[a-zA-Z0-9+/=\r\n]+")
    private val VERSION_SUFFIX = Regex("/v\\d([a-z0-9._-]+)?$")

    /** Archive entries ending in `.json` are identified JSON targets. */
    internal fun isIdentifiedJsonEntryName(name: String): Boolean =
        entryBaseName(name).endsWith(".json")

    /** True when [name] has no directory component (archive-root entry). */
    internal fun isArchiveRootEntryName(name: String): Boolean {
        var normalized = name.replace('\\', '/')
        while (normalized.startsWith("./")) normalized = normalized.substring(2)
        while (normalized.startsWith("/")) normalized = normalized.substring(1)
        return normalized.isNotEmpty() && !normalized.contains('/')
    }

    /** Whether a non-`.json` ZIP entry may be probed as JSON, size-capped. */
    internal fun isSpeculativeJsonEntryCandidate(name: String, size: Int): Boolean {
        if (size <= 0 || size > DEFAULT_SPECULATIVE_JSON_PROBE_BYTES) return false
        if (isIdentifiedJsonEntryName(name)) return false
        val base = entryBaseName(name)
        for (ext in BLOCKED_SPECULATIVE_PROBE_EXTENSIONS) {
            if (base.endsWith(ext)) return false
        }
        return true
    }

    /** Whether an in-archive `.json` entry may be decompressed (size-capped). */
    internal fun isIdentifiedArchiveJsonEntryCandidate(name: String, size: Int): Boolean {
        if (size <= 0 || size > DEFAULT_IDENTIFIED_ARCHIVE_JSON_BYTES) return false
        return isIdentifiedJsonEntryName(name)
    }

    private fun entryBaseName(name: String): String =
        normalizeEntryPath(name).lowercase().split("/").last()

    // ── orchestration ───────────────────────────────────────────────────────

    fun importFromCherryStudio(
        file: File,
        mode: RestoreMode,
        database: MemoDatabase,
        preferenceRepository: PreferenceRepository,
        uploadDir: File,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
    ): CherryImportResult {
        val conversationDao = ConversationDao(database.writableDatabase)
        val messageDao = MessageDao(database.writableDatabase)
        val existingConvs = conversationDao.getAll()
        val existingConvIds = existingConvs.map { it.id }.toSet()
        val existingMsgIds = if (mode == RestoreMode.MERGE) {
            existingConvs.flatMap { messageDao.getMessageIds(it.id) }.toSet()
        } else {
            emptySet()
        }

        val parsed = parseCherryBackup(file, mode == RestoreMode.MERGE, existingConvIds, existingMsgIds)

        if (isCancelled()) throw BackupCancelledException()
        onProgress?.report(
            com.psyche.memo.data.backup.BackupProgress(BackupPhase.COMMITTING, 0, cancellable = false),
        )
        importBusinessData(database, preferenceRepository, mode, parsed.providers, parsed.assistants)

        // If overwrite, clear chats/files BEFORE writing any uploads so the
        // clear cannot delete the files we just unpacked.
        if (mode == RestoreMode.OVERWRITE) {
            clearAllChatData(database, uploadDir)
        }

        val pathsByFileId = materializeFiles(parsed.filesById, parsed.usedFileIds, file, uploadDir)

        var convCount = 0
        var msgCount = 0
        var extraSaved = 0
        for (topic in parsed.topics) {
            val resolved = mutableListOf<ChatMessage>()
            for (typed in topic.messages) {
                val parts = mutableListOf<MessagePart>(*typed.message.parts.toTypedArray())
                for (ref in typed.pendingWrites) {
                    val part = resolveCherryAttachment(ref, pathsByFileId, uploadDir)
                    if (ref.dataUrl != null) {
                        val unavailable = (part as? ImagePart)?.unavailable == true ||
                            (part as? FilePart)?.unavailable == true
                        if (!unavailable) extraSaved += 1
                    }
                    parts.add(part)
                }
                val original = typed.message
                resolved.add(
                    ChatMessage(
                        id = original.id,
                        role = original.role,
                        parts = parts,
                        timestamp = original.timestamp,
                        modelId = original.modelId,
                        providerId = original.providerId,
                        totalTokens = original.totalTokens,
                        conversationId = original.conversationId,
                        groupId = original.groupId,
                        messageOrder = original.messageOrder,
                    ),
                )
            }

            if (topic.mergeIntoExisting) {
                var order = messageDao.nextOrder(topic.conversation.id)
                for (message in resolved) {
                    messageDao.insert(withOrder(message, order))
                    order += 1
                    msgCount += 1
                }
            } else {
                conversationDao.insert(topic.conversation)
                messageDao.insertAllInTransaction(
                    resolved.mapIndexed { index, message -> withOrder(message, index) },
                )
                convCount += 1
                msgCount += resolved.size
            }
        }

        return CherryImportResult(
            providers = parsed.providers.size,
            assistants = parsed.assistants.size,
            conversations = convCount,
            messages = msgCount,
            files = pathsByFileId.size + extraSaved,
        )
    }

    // ── file-shape probes ───────────────────────────────────────────────────

    private fun parseCherryBackup(
        file: File,
        merge: Boolean,
        existingConvIds: Set<String>,
        existingMsgIds: Set<String>,
    ): CherryParsedBackup {
        val bytes = file.readBytes()
        var zipJsonProbeDecodeCount = 0

        val root: MutableMap<String, Any?> = run {
            // Whole-file JSON (not ZIP/GZIP): uncapped, allowMalformed.
            if (!looksLikeZip(bytes) && !looksLikeGzip(bytes)) {
                tryParseBackupJsonBytes(bytes)?.let { return@run it }
            }

            // ZIP: version-gate from metadata.json before any entry probe.
            if (looksLikeZip(bytes)) {
                val entries = readZipEntries(bytes)
                try {
                    CherryDirectBackupReader.readMetadataOrThrowIfUnsupported(entries)
                    for ((name, content) in entries) {
                        if (!isArchiveRootEntryName(name)) continue
                        val obj = tryParseZipJsonEntry(name, content, identified = true) {
                            zipJsonProbeDecodeCount++
                        }
                        if (obj != null) return@run obj
                    }
                    for ((name, content) in entries) {
                        if (isArchiveRootEntryName(name)) continue
                        val obj = tryParseZipJsonEntry(name, content, identified = true) {
                            zipJsonProbeDecodeCount++
                        }
                        if (obj != null) return@run obj
                    }
                    for ((name, content) in entries) {
                        val obj = tryParseZipJsonEntry(name, content, identified = false) {
                            zipJsonProbeDecodeCount++
                        }
                        if (obj != null) return@run obj
                    }
                    CherryDirectBackupReader.readArchive(entries)?.let { return@run it.toMutableMap() }
                } catch (unsupported: CherryUnsupportedBackupVersionException) {
                    throw CherryUnsupportedBackupVersionException(
                        unsupported.version,
                        debugZipJsonProbeDecodeCount = zipJsonProbeDecodeCount,
                    )
                } catch (_: Exception) {
                    // not a readable zip, fall through to gzip
                }
            }

            // GZIP JSON payload: uncapped, allowMalformed.
            if (looksLikeGzip(bytes)) {
                try {
                    val gunzipped = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
                    tryParseBackupJsonBytes(gunzipped)?.let { return@run it }
                } catch (_: Exception) {
                }
            }

            throw IllegalStateException("Unable to read Cherry backup file")
        }

        val version = (root["version"] as? Number)?.toInt() ?: 0
        if (version < 2) {
            throw IllegalStateException("Unsupported Cherry backup version: $version")
        }

        @Suppress("UNCHECKED_CAST")
        val localStorage = (root["localStorage"] as? MutableMap<String, Any?>) ?: linkedMapOf()
        val persistStr = (localStorage["persist:cherry-studio"] ?: "") as? String ?: ""
        if (persistStr.isEmpty()) {
            throw IllegalStateException("Missing localStorage[\"persist:cherry-studio\"]")
        }
        val persistObj = try {
            anyFromJson(JSON.parseToJsonElement(persistStr)) as? MutableMap<String, Any?>
                ?: throw IllegalStateException("Invalid persist:cherry-studio JSON")
        } catch (_: IllegalStateException) {
            throw IllegalStateException("Invalid persist:cherry-studio JSON")
        } catch (_: Exception) {
            throw IllegalStateException("Invalid persist:cherry-studio JSON")
        }

        @Suppress("UNCHECKED_CAST")
        val assistantsSlice = (persistObj["assistants"] as? MutableMap<String, Any?>)
            ?: linkedMapOf()
        @Suppress("UNCHECKED_CAST")
        val llmSlice = (persistObj["llm"] as? MutableMap<String, Any?>) ?: linkedMapOf()

        val cherryProviders = (llmSlice["providers"] as? List<Any?>) ?: emptyList()
        val cherryAssistants = (assistantsSlice["assistants"] as? List<Any?>) ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val indexedDB = (root["indexedDB"] as? MutableMap<String, Any?>) ?: linkedMapOf()
        val cherryFiles = (indexedDB["files"] as? List<Any?>) ?: emptyList()
        val cherryTopicsWithMessages = (indexedDB["topics"] as? List<Any?>) ?: emptyList()
        val cherryMessageBlocks = (indexedDB["message_blocks"] as? List<Any?>) ?: emptyList()

        // Topic metadata, with the owning assistant id folded in.
        val topicMeta = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (raw in cherryAssistants) {
            @Suppress("UNCHECKED_CAST")
            val a = raw as? MutableMap<String, Any?> ?: continue
            val topics = (a["topics"] as? List<Any?>) ?: continue
            for (t in topics) {
                @Suppress("UNCHECKED_CAST")
                val topic = t as? MutableMap<String, Any?> ?: continue
                val id = (topic["id"] ?: "").toString()
                if (id.isEmpty()) continue
                topicMeta[id] = topic
                val parentAssistantId = (a["id"] ?: "").toString()
                val topicAssistantId = (topic["assistantId"] ?: "").toString()
                val ownerAssistantId = parentAssistantId.ifEmpty { topicAssistantId }
                if (ownerAssistantId.isNotEmpty()) {
                    topic["assistantId"] = ownerAssistantId
                }
            }
        }

        val topicMessages = LinkedHashMap<String, MutableList<MutableMap<String, Any?>>>()
        for (raw in cherryTopicsWithMessages) {
            @Suppress("UNCHECKED_CAST")
            val e = raw as? MutableMap<String, Any?> ?: continue
            val id = (e["id"] ?: "").toString()
            if (id.isEmpty()) continue
            val msgs = (e["messages"] as? List<Any?>) ?: emptyList()
            topicMessages[id] = msgs.filterIsInstance<MutableMap<String, Any?>>().toMutableList()
        }

        // Message blocks → markdown-ish text per message.
        val blockTextByMessageId = LinkedHashMap<String, String>()
        for (raw in cherryMessageBlocks) {
            @Suppress("UNCHECKED_CAST")
            val b = raw as? MutableMap<String, Any?> ?: continue
            val type = (b["type"] ?: "").toString()
            val messageId = (b["messageId"] ?: "").toString()
            if (messageId.isEmpty()) continue
            val content = (b["content"] ?: "").toString()
            if (content.isEmpty() && type != "code") continue
            val wrapped = when (type) {
                "main_text" -> content
                "code" -> {
                    if (content.isEmpty()) continue
                    "```" + (b["language"] ?: "").toString() + "\n" + content + "\n```"
                }
                "error" -> {
                    if (content.isEmpty()) continue
                    "> Error\n> " + content.replace("\n", "\n> ")
                }
                "thinking" -> {
                    if (content.isEmpty()) continue
                    "<think>\n" + content + "\n</think>"
                }
                else -> continue
            }
            val prev = blockTextByMessageId[messageId]
            blockTextByMessageId[messageId] = if (prev.isNullOrEmpty()) wrapped else "$prev\n$wrapped"
        }

        val filesById = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (raw in cherryFiles) {
            @Suppress("UNCHECKED_CAST")
            val f = raw as? MutableMap<String, Any?> ?: continue
            val id = (f["id"] ?: "").toString()
            if (id.isNotEmpty()) filesById[id] = f
        }

        val usedFileIds = HashSet<String>()
        for (entry in topicMessages.values) {
            for (m in entry) {
                val files = (m["files"] as? List<Any?>) ?: continue
                for (rf in files) {
                    @Suppress("UNCHECKED_CAST")
                    val fileMap = rf as? MutableMap<String, Any?> ?: continue
                    val id = (fileMap["id"] ?: "").toString()
                    if (id.isNotEmpty()) usedFileIds.add(id)
                }
            }
        }

        // Image/file blocks reference files too.
        val pendingAttachmentsByMessage =
            LinkedHashMap<String, MutableList<PendingAttachmentRef>>()
        for (raw in cherryMessageBlocks) {
            @Suppress("UNCHECKED_CAST")
            val b = raw as? MutableMap<String, Any?> ?: continue
            val type = (b["type"] ?: "").toString()
            val messageId = (b["messageId"] ?: "").toString()
            if (messageId.isEmpty()) continue
            @Suppress("UNCHECKED_CAST")
            val fileObj = b["file"] as? MutableMap<String, Any?>?
            val fid = (fileObj?.get("id") ?: "").toString()
            if (fid.isNotEmpty()) usedFileIds.add(fid)
            val url = (b["url"] ?: "").toString()
            val isImageType = type.lowercase().contains("image") ||
                (fileObj?.get("type")?.toString()?.lowercase()?.startsWith("image") == true)
            if (fileObj != null && fid.isNotEmpty()) {
                val originPath = (fileObj["path"] ?: "").toString().trim()
                pendingAttachmentsByMessage.getOrPut(messageId) { mutableListOf() }.add(
                    PendingAttachmentRef(
                        fileId = fid,
                        name = (fileObj["origin_name"] ?: fileObj["name"] ?: "").toString(),
                        mime = (fileObj["type"] ?: "").toString(),
                        originPath = originPath.takeIf { it.isNotEmpty() },
                        isImage = isImageType,
                    ),
                )
            } else if (url.isNotEmpty()) {
                if (url.startsWith("data:image")) {
                    pendingAttachmentsByMessage.getOrPut(messageId) { mutableListOf() }
                        .add(PendingAttachmentRef(dataUrl = url, isImage = true))
                } else {
                    pendingAttachmentsByMessage.getOrPut(messageId) { mutableListOf() }
                        .add(PendingAttachmentRef(url = url, isImage = isImageType))
                }
            }
        }

        return CherryParsedBackup(
            providers = parseProviders(cherryProviders),
            assistants = parseAssistants(cherryAssistants),
            topics = buildTypedCherryTopics(
                topicMeta = topicMeta,
                topicMessages = topicMessages,
                blockTexts = blockTextByMessageId,
                pendingAttachmentsByMessage = pendingAttachmentsByMessage,
                merge = merge,
                existingConvIds = existingConvIds,
                existingMsgIds = existingMsgIds,
            ),
            filesById = filesById,
            usedFileIds = usedFileIds,
        )
    }

    private fun readZipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = normalizeEntryPath(entry.name)
                if (!entry.isDirectory) {
                    entries[name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private inline fun tryParseZipJsonEntry(
        name: String,
        content: ByteArray,
        identified: Boolean,
        countDecode: () -> Unit,
    ): MutableMap<String, Any?>? {
        if (identified) {
            if (!isIdentifiedArchiveJsonEntryCandidate(name, content.size)) return null
        } else if (!isSpeculativeJsonEntryCandidate(name, content.size)) {
            return null
        }
        return try {
            countDecode()
            tryParseBackupJsonBytes(content)
        } catch (_: Exception) {
            null
        }
    }

    /** `_tryParseBackupJson`: must carry the localStorage+indexedDB roots. */
    private fun tryParseBackupJsonBytes(raw: ByteArray): MutableMap<String, Any?>? = try {
        val obj = anyFromJson(JSON.parseToJsonElement(decodeUtf8(raw)))
        if (obj is MutableMap<*, *> && obj.containsKey("localStorage") && obj.containsKey("indexedDB")) {
            @Suppress("UNCHECKED_CAST")
            obj as MutableMap<String, Any?>
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** Dart's `utf8.decode(..., allowMalformed: true)`. */
    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    // ── providers / assistants ──────────────────────────────────────────────

    /** Splits Cherry's comma-separated API key string (`\,` escapes). */
    internal fun splitApiKeyString(keyStr: String): List<String> {
        if (keyStr.isBlank()) return emptyList()
        val placeholder = "\u0000"
        val escaped = keyStr.replace("\\,", placeholder)
        return escaped.split(",")
            .map { it.replace(placeholder, ",").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseProviders(cherryProviders: List<Any?>): Map<String, JsonObject> {
        val imported = LinkedHashMap<String, JsonObject>()
        for (raw in cherryProviders) {
            @Suppress("UNCHECKED_CAST")
            val p = raw as? Map<String, Any?> ?: continue
            val id = (p["id"] ?: "").toString()
            if (id.isEmpty()) continue
            val type = (p["type"] ?: "").toString().lowercase()
            val name = (p["name"] ?: id).toString()
            val apiKeyRaw = (p["apiKey"] ?: "").toString()
            val apiHostRaw = (p["apiHost"] ?: "").toString().trim()

            val apiKeys = splitApiKeyString(apiKeyRaw)
            val apiKey = apiKeys.firstOrNull() ?: ""
            val multiKeyEnabled = apiKeys.size > 1

            val kind = when (type) {
                "openai" -> "openai"
                "anthropic" -> "claude"
                "gemini" -> "google"
                else -> "openai"
            }

            val models = mutableListOf<String>()
            val mlist = (p["models"] as? List<Any?>) ?: emptyList()
            for (m in mlist) {
                @Suppress("UNCHECKED_CAST")
                val model = m as? Map<String, Any?> ?: continue
                val modelId = model["id"]
                if (modelId != null) models.add(modelId.toString())
            }

            // Cherry semantics: a host without a trailing slash gets its
            // default version appended for openai/claude/google.
            var base = apiHostRaw
            if (base.isNotEmpty()) {
                if (base.endsWith("/")) {
                    base = base.dropLast(1)
                } else if (!VERSION_SUFFIX.containsMatchIn(base.lowercase())) {
                    base = when (kind) {
                        "google" -> "$base/v1beta"
                        "openai", "claude" -> "$base/v1"
                        else -> base
                    }
                }
            }

            val config = ProviderConfig(
                id = id,
                enabled = (p["enabled"] as? Boolean) ?: apiKey.isNotEmpty(),
                name = name,
                apiKey = apiKey,
                baseUrl = base.ifEmpty {
                    when (kind) {
                        "google" -> "https://generativelanguage.googleapis.com/v1beta"
                        "claude" -> "https://api.anthropic.com/v1"
                        else -> "https://api.openai.com/v1"
                    }
                },
                providerType = kind,
                chatPath = if (kind == "openai") "/chat/completions" else null,
                useResponseApi = if (kind == "openai") false else null,
                vertexAI = if (kind == "google") false else null,
                models = models,
                proxyEnabled = false,
                proxyHost = "",
                proxyPort = "8080",
                proxyUsername = "",
                proxyPassword = "",
                multiKeyEnabled = multiKeyEnabled,
                apiKeys = if (multiKeyEnabled) {
                    val now = System.currentTimeMillis()
                    apiKeys.map { key ->
                        ApiKeyConfig(
                            id = UUID.randomUUID().toString(),
                            key = key,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                } else {
                    null
                },
                keyManagement = KeyManagementConfig(),
            )
            imported[id] = MODEL_JSON.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject
        }
        return imported
    }

    private fun parseAssistants(cherryAssistants: List<Any?>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (raw in cherryAssistants) {
            @Suppress("UNCHECKED_CAST")
            val a = raw as? Map<String, Any?> ?: continue
            val id = (a["id"] ?: "").toString()
            if (id.isEmpty()) continue
            val name = (a["name"] ?: id).toString()
            val prompt = (a["prompt"] ?: "").toString()
            @Suppress("UNCHECKED_CAST")
            val settings = a["settings"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val model = a["model"] as? Map<String, Any?>

            val temperature = (settings?.get("temperature") as? Number)?.toDouble()
            val topP = (settings?.get("topP") as? Number)?.toDouble()
            val ctxCount = (settings?.get("contextCount") as? Number)?.toInt()
            val streamOutput = settings?.get("streamOutput") as? Boolean
            val enableMaxTokens = settings?.get("enableMaxTokens") as? Boolean ?: false
            val maxTokens = if (enableMaxTokens) (settings?.get("maxTokens") as? Number)?.toInt() else null

            val assistant = Assistant(
                id = id,
                name = name,
                avatar = null,
                useAssistantAvatar = false,
                useAssistantName = false,
                chatModelProvider = model?.get("provider")?.toString(),
                chatModelId = model?.get("id")?.toString(),
                temperature = temperature,
                topP = topP,
                contextMessageSize = ctxCount ?: 64,
                limitContextMessages = true,
                streamOutput = streamOutput ?: true,
                thinkingBudget = null,
                maxTokens = maxTokens,
                systemPrompt = prompt,
                messageTemplate = "{{ message }}",
                mcpServerIds = emptyList(),
                background = null,
                customHeaders = emptyList(),
                customBody = emptyList(),
                enableMemory = false,
                allowPastConversationRecall = false,
            )
            out.add(MODEL_JSON.encodeToJsonElement(Assistant.serializer(), assistant).jsonObject)
        }
        return out
    }

    // ── business data (providers / assistants / order) ──────────────────────

    private fun importBusinessData(
        database: MemoDatabase,
        preferenceRepository: PreferenceRepository,
        mode: RestoreMode,
        providers: Map<String, JsonObject>,
        assistants: List<JsonObject>,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val providerDao = PayloadEntityDao(db, "provider_rows", primaryKey = "provider_key")
            val assistantDao = PayloadEntityDao(db, "assistant_rows")
            if (mode == RestoreMode.OVERWRITE) {
                providerDao.replaceAll(
                    providers.map { (id, payload) ->
                        PayloadEntityDao.Row(id, 0, payload.toString(), now)
                    },
                )
                assistantDao.replaceAll(
                    assistants.mapIndexed { index, payload ->
                        PayloadEntityDao.Row(
                            payload.stringField("id") ?: "",
                            index,
                            payload.toString(),
                            now,
                        )
                    },
                )
                preferenceRepository.writeJson(
                    PROVIDERS_ORDER_KEY,
                    buildJsonArray { providers.keys.forEach { add(JsonPrimitive(it)) } }.toString(),
                )
            } else {
                // Providers: imported non-empty fields fill / override local.
                val current = providerDao.getAll()
                val currentById = current.associateBy { it.id }
                val nextRows = mutableListOf<PayloadEntityDao.Row>()
                val nextOrderIds = mutableListOf<String>()
                for ((index, entry) in providers.entries.withIndex()) {
                    val id = entry.key
                    val payload = entry.value
                    val local = currentById[id]
                    if (local == null) {
                        nextRows.add(PayloadEntityDao.Row(id, index, payload.toString(), now))
                        nextOrderIds.add(id)
                        continue
                    }
                    val merged = LinkedHashMap(local.payload.parseJsonObject())
                    for ((field, value) in payload) {
                        val emptyString = value is JsonPrimitive && value.isString && value.content.isBlank()
                        if (value is kotlinx.serialization.json.JsonNull || emptyString) continue
                        merged[field] = value
                    }
                    nextRows.add(PayloadEntityDao.Row(id, local.sortOrder, JsonObject(merged).toString(), now))
                    nextOrderIds.add(id)
                }
                // Local-only providers keep their rows and their order slots.
                for (row in current) {
                    if (row.id !in providers.keys) {
                        nextRows.add(row)
                        nextOrderIds.add(row.id)
                    }
                }
                providerDao.replaceAll(nextRows)

                val orderJson = preferenceRepository.readJson(PROVIDERS_ORDER_KEY)
                val order = mutableListOf<String>()
                runCatching {
                    orderJson?.let { json -> JSON.parseToJsonElement(json).jsonArray.forEach { order.add(it.jsonPrimitive.content) } }
                }
                for (id in nextOrderIds) if (id !in order) order.add(id)
                preferenceRepository.writeJson(
                    PROVIDERS_ORDER_KEY,
                    buildJsonArray { order.forEach { add(JsonPrimitive(it)) } }.toString(),
                )

                // Assistants: same id → imported prompt/model win when set.
                val currentAssistants = assistantDao.getAll()
                val byId = currentAssistants.associateBy { it.id }.toMutableMap()
                for (assistant in assistants) {
                    val id = assistant.stringField("id") ?: ""
                    val localRow = byId[id]
                    val local = localRow?.payload?.parseJsonObject()
                    if (local == null) {
                        byId[id] = PayloadEntityDao.Row(id, 0, assistant.toString(), now)
                        continue
                    }
                    val merged = LinkedHashMap(local)
                    val prompt = assistant.stringField("systemPrompt")?.trim()
                    if (!prompt.isNullOrEmpty()) merged["systemPrompt"] = JsonPrimitive(prompt)
                    assistant["chatModelProvider"]?.let { merged["chatModelProvider"] = it }
                    assistant["chatModelId"]?.let { merged["chatModelId"] = it }
                    byId[id] = PayloadEntityDao.Row(id, byId[id]!!.sortOrder, JsonObject(merged).toString(), now)
                }
                assistantDao.replaceAll(byId.values.toList())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** `chatService.clearAllData` for the overwrite path: chats + files. */
    private fun clearAllChatData(database: MemoDatabase, uploadDir: File) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf(
                "message_part_rows",
                "provider_artifact_rows",
                "generation_run_rows",
                "message_asset_rows",
                "message_prompt_rows",
                "message_rows",
                "conversation_mcp_server_rows",
                "asset_reference_dirty_rows",
                "conversation_rows",
            )) {
                db.delete(table, null, null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        uploadDir.listFiles()?.forEach { it.delete() }
    }

    // ── file materialization ────────────────────────────────────────────────

    private class FilesIndex(
        val byBase: MutableMap<String, ByteArray>,
        val byRel: MutableMap<String, ByteArray>,
        val byId: MutableMap<String, ByteArray>,
    )

    private fun materializeFiles(
        filesById: Map<String, MutableMap<String, Any?>>,
        usedIds: Set<String>,
        backupArchive: File?,
        uploadDir: File,
    ): Map<String, String> {
        uploadDir.mkdirs()
        val result = LinkedHashMap<String, String>()

        var zipIndex: FilesIndex? = null
        if (backupArchive != null && looksLikeZip(backupArchive.readBytes())) {
            zipIndex = buildZipIndex(backupArchive)
        }

        for (id in usedIds) {
            val meta = filesById[id] ?: continue
            val name = (meta["origin_name"] ?: meta["name"] ?: "file").toString()
            val ext = (meta["ext"] ?: "").toString()
            val safeName = name.replace(Regex("[/\\\\\u0000]"), "_")
            val fn = safeName.ifEmpty { if (ext.isNotEmpty()) "file.$ext" else "file" }
            // `id` comes straight from the imported archive's JSON — sanitize
            // like the display name so a `x/../../y` id cannot escape upload.
            val safeId = id.replace(Regex("[/\\\\\u0000]"), "_")
            val outPath = File(uploadDir, "cherry_${safeId}_$fn")

            if (outPath.isFile) {
                result[id] = outPath.path
                continue
            }

            val base64Str = (meta["base64"] ?: "") as? String ?: ""
            val contentStr = (meta["content"] ?: "") as? String ?: ""

            if (base64Str.isNotEmpty()) {
                try {
                    var b64 = base64Str
                    val idx = b64.indexOf("base64,")
                    if (idx != -1) b64 = b64.substring(idx + 7)
                    outPath.writeBytes(Base64.getMimeDecoder().decode(b64))
                    result[id] = outPath.path
                    continue
                } catch (_: Exception) {
                }
            }

            if (contentStr.isNotEmpty()) {
                try {
                    outPath.writeText(contentStr)
                    result[id] = outPath.path
                    continue
                } catch (_: Exception) {
                }
            }

            // Relative path resolution against the archive's files/ tree.
            val mp = (meta["path"] ?: "").toString()
            if (mp.isNotEmpty()) {
                var rel = normalizeEntryPath(mp).trim()
                if (rel.startsWith("file://")) rel = rel.substring("file://".length)
                if (rel.startsWith("/")) rel = rel.substring(1)
                val lowerRel = rel.lowercase()
                val relKeys = setOf(
                    lowerRel,
                    if (lowerRel.startsWith("files/")) lowerRel else "files/$lowerRel",
                    if (lowerRel.startsWith("data/files/")) lowerRel else "data/files/$lowerRel",
                )
                val index = zipIndex
                var done = false
                for (key in relKeys) {
                    if (!done && index != null && index.byRel.containsKey(key)) {
                        runCatching { outPath.writeBytes(index.byRel.getValue(key)) }
                            .onSuccess { result[id] = outPath.path; done = true }
                    }
                    if (done) break
                }
                if (done) continue
            }

            // Base-name candidates.
            val candidates = LinkedHashSet<String>()
            for (key in listOf("name", "origin_name", "path")) {
                val value = (meta[key] ?: "").toString()
                if (value.isNotBlank()) candidates.add(value.replace('\\', '/').split("/").last())
            }
            var done = false
            val index = zipIndex
            for (base in candidates) {
                if (!done && index != null && index.byBase.containsKey(base)) {
                    runCatching { outPath.writeBytes(index.byBase.getValue(base)) }
                        .onSuccess { result[id] = outPath.path; done = true }
                }
                if (done) break
            }
            if (done) continue

            // Id (with extension) as a last resort.
            var fileExt = ext.trim()
            if (fileExt.isEmpty()) {
                val base = (meta["name"] ?: "").toString().replace('\\', '/').split("/").last()
                if (base.contains('.')) fileExt = base.substring(base.lastIndexOf('.') + 1)
            }
            val extNoDot = fileExt.removePrefix(".")
            val idPlus = if (extNoDot.isNotEmpty()) "$id.$extNoDot" else id
            if (index != null) {
                val bytes = index.byId[id] ?: index.byBase[idPlus]
                if (bytes != null) {
                    runCatching { outPath.writeBytes(bytes) }
                        .onSuccess { result[id] = outPath.path; continue }
                }
            }
        }
        return result
    }

    private fun buildZipIndex(backupArchive: File): FilesIndex? = try {
        val byBase = LinkedHashMap<String, ByteArray>()
        val byRel = LinkedHashMap<String, ByteArray>()
        val byId = LinkedHashMap<String, ByteArray>()
        val uuidLike = Regex("^[0-9a-fA-F-]{10,}$")
        ZipInputStream(ByteArrayInputStream(backupArchive.readBytes())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val content = zip.readBytes()
                    val norm = normalizeEntryPath(entry.name)
                    val base = norm.split("/").last()
                    byBase[base] = content
                    val l = norm.lowercase()
                    var idx = l.indexOf("/data/files/")
                    if (idx != -1) byRel[l.substring(idx + 1)] = content
                    idx = l.indexOf("/files/")
                    if (idx != -1) byRel[l.substring(idx + 1)] = content
                    val noExt = if (base.contains('.')) base.substring(0, base.lastIndexOf('.')) else base
                    if (uuidLike.matches(noExt)) byId[noExt] = content
                }
                zip.closeEntry()
            }
        }
        if (byBase.isEmpty() && byRel.isEmpty() && byId.isEmpty()) null else FilesIndex(byBase, byRel, byId)
    } catch (_: Exception) {
        null
    }

    // ── typed topics ────────────────────────────────────────────────────────

    private fun cherryModelId(message: Map<String, Any?>): String? {
        val raw = message["modelId"]
        if (raw != null) return raw.toString()
        @Suppress("UNCHECKED_CAST")
        val model = message["model"] as? Map<String, Any?> ?: return null
        val id = model["id"] ?: return ""
        return id.toString()
    }

    private fun buildTypedCherryTopics(
        topicMeta: Map<String, MutableMap<String, Any?>>,
        topicMessages: Map<String, List<MutableMap<String, Any?>>>,
        blockTexts: Map<String, String>,
        pendingAttachmentsByMessage: Map<String, List<PendingAttachmentRef>>,
        merge: Boolean,
        existingConvIds: Set<String>,
        existingMsgIds: Set<String>,
    ): List<CherryTypedTopic> {
        val topicIds = LinkedHashSet<String>()
        topicIds.addAll(topicMeta.keys)
        topicIds.addAll(topicMessages.keys)

        val topics = mutableListOf<CherryTypedTopic>()
        for (topicId in topicIds) {
            val msgsRaw = topicMessages[topicId] ?: emptyList()
            val meta = topicMeta[topicId] ?: linkedMapOf()
            val title = (meta["name"] ?: "Imported").toString()
            val pinned = meta["pinned"] as? Boolean ?: false
            val assistantId = (meta["assistantId"] ?: "").toString().trim().ifEmpty { null }
            var createdAt = tryParseDateFlexible((meta["createdAt"] ?: "").toString())
                ?: System.currentTimeMillis()
            var updatedAt = tryParseDateFlexible((meta["updatedAt"] ?: "").toString()) ?: createdAt

            val messages = mutableListOf<CherryTypedMessage>()
            for (m in msgsRaw) {
                val msgId = (m["id"] ?: "").toString()
                if (msgId.isEmpty()) continue
                if (merge && msgId in existingMsgIds) continue
                val roleRaw = (m["role"] ?: "user").toString()
                val role = if (roleRaw == "system") "assistant" else roleRaw
                var content = (m["content"] ?: "").toString()
                if (content.isBlank()) content = blockTexts[msgId] ?: ""
                val ts = tryParseDateFlexible((m["createdAt"] ?: "").toString())
                    ?: System.currentTimeMillis()

                val modelId = cherryModelId(m)
                val providerId = (m["model"] as? Map<*, *>)?.get("provider")?.toString()
                val usage = m["usage"] as? Map<*, *>
                val totalTokens = (usage?.get("total_tokens") as? Number)?.toInt()

                val pendingWrites = mutableListOf<PendingAttachmentRef>()
                val files = (m["files"] as? List<Any?>) ?: emptyList()
                for (f in files) {
                    @Suppress("UNCHECKED_CAST")
                    val fileMap = f as? Map<String, Any?> ?: continue
                    val fid = (fileMap["id"] ?: "").toString()
                    if (fid.isEmpty()) continue
                    val fname = (fileMap["origin_name"] ?: fileMap["name"] ?: "file").toString()
                    val mime = (fileMap["type"] ?: "").toString()
                    val url = (fileMap["url"] ?: "").toString()
                    val originPath = (fileMap["path"] ?: "").toString().trim()
                    val lowerUrl = url.lowercase()
                    val isImage = mime.lowercase().startsWith("image") ||
                        (fname.contains('.') && IMAGE_EXT.containsMatchIn(fname.lowercase())) ||
                        (url.isNotEmpty() &&
                            (mime.lowercase().startsWith("image/") ||
                                IMAGE_URL_EXT.containsMatchIn(lowerUrl)))
                    pendingWrites.add(
                        PendingAttachmentRef(
                            fileId = fid,
                            url = url.takeIf { it.isNotEmpty() },
                            originPath = originPath.takeIf { it.isNotEmpty() },
                            name = fname,
                            mime = mime,
                            isImage = isImage,
                        ),
                    )
                }

                pendingAttachmentsByMessage[msgId]?.let { pendingWrites.addAll(it) }

                val metadata = m["metadata"] as? Map<*, *>
                val gen = metadata?.get("generateImageResponse") as? Map<*, *>
                if (gen != null) {
                    val imgs = gen["images"] as? List<Any?> ?: emptyList()
                    for (item in imgs) {
                        val s = (item ?: "").toString()
                        if (s.isEmpty()) continue
                        when {
                            s.startsWith("data:image") -> pendingWrites.add(
                                PendingAttachmentRef(dataUrl = s, isImage = true),
                            )
                            s.startsWith("http://") || s.startsWith("https://") -> pendingWrites.add(
                                PendingAttachmentRef(url = s, name = "image", mime = "image/png"),
                            )
                            else -> pendingWrites.add(
                                PendingAttachmentRef(
                                    dataUrl = "data:image/png;base64,$s",
                                    isImage = true,
                                ),
                            )
                        }
                    }
                }

                if (role == "assistant" && content.contains("data:image")) {
                    val dataUrls = DATA_IMAGE_URL.findAll(content).map { it.value }.toList()
                    if (dataUrls.isNotEmpty()) {
                        for (du in dataUrls) {
                            pendingWrites.add(PendingAttachmentRef(dataUrl = du, isImage = true))
                        }
                        content = DATA_IMAGE_URL.replace(content, "")
                    }
                }

                messages.add(
                    CherryTypedMessage(
                        message = ChatMessage(
                            id = msgId,
                            role = role,
                            parts = listOf(TextPart(content)),
                            timestamp = ts,
                            modelId = modelId,
                            providerId = providerId,
                            totalTokens = totalTokens,
                            conversationId = topicId,
                            groupId = msgId,
                            messageOrder = 0,
                        ),
                        pendingWrites = pendingWrites,
                    ),
                )
            }

            if (messages.isNotEmpty()) {
                val times = messages.map { it.message.timestamp }.sorted()
                createdAt = times.first()
                updatedAt = times.last()
            }

            topics.add(
                CherryTypedTopic(
                    mergeIntoExisting = merge && topicId in existingConvIds,
                    conversation = com.psyche.memo.data.model.Conversation(
                        id = topicId,
                        title = title,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        isPinned = pinned,
                        assistantId = assistantId,
                    ),
                    messages = messages,
                ),
            )
        }
        return topics
    }

    private fun withOrder(message: ChatMessage, order: Int): ChatMessage = ChatMessage(
        id = message.id,
        role = message.role,
        parts = message.parts,
        timestamp = message.timestamp,
        modelId = message.modelId,
        providerId = message.providerId,
        totalTokens = message.totalTokens,
        conversationId = message.conversationId,
        groupId = message.groupId,
        messageOrder = order,
    )

    private fun tryParseDateFlexible(text: String): Long? =
        CherryDirectBackupReader.tryParseDate(text)

    // ── attachments ─────────────────────────────────────────────────────────

    private fun resolveCherryAttachment(
        ref: PendingAttachmentRef,
        filePaths: Map<String, String>,
        uploadDir: File,
    ): MessagePart {
        val fileName = ref.name ?: if (ref.isImage) "image" else "file"
        val fileMime = ref.mime ?: if (ref.isImage) "image/png" else "application/octet-stream"

        ref.dataUrl?.let { dataUrl ->
            val savedPath = saveDataUrlToUpload(dataUrl, uploadDir)
            if (savedPath != null) {
                return attachmentPart(ref.isImage, savedPath, fileName, fileMime)
            }
            return attachmentPart(ref.isImage, "cherry-missing:data-url", fileName, fileMime, unavailable = true)
        }

        ref.fileId?.let { fileId ->
            val savedPath = filePaths[fileId]
            if (!savedPath.isNullOrEmpty()) {
                return attachmentPart(ref.isImage, savedPath, fileName, fileMime)
            }
            if (!ref.url.isNullOrEmpty()) {
                return attachmentPart(ref.isImage, ref.url!!, fileName, fileMime)
            }
            if (!ref.originPath.isNullOrEmpty()) {
                return attachmentPart(ref.isImage, ref.originPath!!, fileName, fileMime, unavailable = true)
            }
            return attachmentPart(
                ref.isImage, "cherry-missing:$fileId", fileName, fileMime, unavailable = true,
            )
        }

        if (!ref.url.isNullOrEmpty()) {
            return attachmentPart(ref.isImage, ref.url!!, fileName, fileMime)
        }
        if (!ref.originPath.isNullOrEmpty()) {
            return attachmentPart(ref.isImage, ref.originPath!!, fileName, fileMime, unavailable = true)
        }
        return attachmentPart(ref.isImage, "cherry-missing:unknown", fileName, fileMime, unavailable = true)
    }

    private fun attachmentPart(
        isImage: Boolean,
        target: String,
        name: String,
        mime: String,
        unavailable: Boolean = false,
    ): MessagePart = if (isImage) {
        ImagePart(uri = target, mime = mime.ifEmpty { null }, unavailable = unavailable.takeIf { it })
    } else {
        FilePart(
            uri = target,
            name = name.ifEmpty { "file" },
            mime = mime.ifEmpty { "application/octet-stream" },
            unavailable = unavailable.takeIf { it },
        )
    }

    private fun saveDataUrlToUpload(dataUrl: String, uploadDir: File): String? = try {
        uploadDir.mkdirs()
        var mime = "image/png"
        var payload = dataUrl
        val colon = dataUrl.indexOf(':')
        val semi = dataUrl.indexOf(';')
        val base = dataUrl.indexOf("base64,")
        if (colon >= 0 && semi > colon) mime = dataUrl.substring(colon + 1, semi)
        if (base >= 0) payload = dataUrl.substring(base + 7)
        val bytes = Base64.getMimeDecoder().decode(payload.replace("\n", ""))
        val ext = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
        val fname = "cherry_img_${System.currentTimeMillis()}_${bytes.size}.$ext"
        val out = File(uploadDir, fname)
        out.writeBytes(bytes)
        out.path
    } catch (_: Exception) {
        null
    }
}

/** Field reader for dynamic JsonObject payloads (Dart's `x['k']` as String). */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun String.parseJsonObject(): JsonObject =
    Json { isLenient = true; ignoreUnknownKeys = true }.parseToJsonElement(this).jsonObject
