package com.psyche.memo.data.backup.chatbox

import com.psyche.memo.data.backup.BackupCancelledException
import com.psyche.memo.data.backup.BackupPhase
import com.psyche.memo.data.backup.BackupProgress
import com.psyche.memo.data.backup.BackupProgressSink
import com.psyche.memo.data.backup.RestoreMode
import com.psyche.memo.data.backup.cherry.CherryDirectBackupReader
import com.psyche.memo.data.backup.cherry.parseJsonObject
import com.psyche.memo.data.backup.cherry.stringField
import com.psyche.memo.data.backup.cherry.anyFromJson
import com.psyche.memo.data.backup.cherry.jsonFromAny
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
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.repo.ProviderRepository
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * The importer orchestration (`ChatboxImporter.importFromChatbox` +
 * `_prepareChatboxImportInIsolate` + `_parseChatboxPlanInIsolate` +
 * `_transformBusinessData`), split from the archive-reading half in
 * [ChatboxBackupArchive].
 */
object ChatboxImportRunner {

    private const val PROVIDERS_KEY = "provider_configs_v1"
    private const val PROVIDERS_ORDER_KEY = "providers_order_v1"
    private const val ASSISTANTS_KEY = "assistants_v1"
    private const val ASSIGN_KEY = "assistant_tag_map_v1"
    private const val COLLAPSED_KEY = "assistant_tag_collapsed_v1"

    private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }
    private val MODEL_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun importFromChatbox(
        file: File,
        mode: RestoreMode,
        database: MemoDatabase,
        preferenceRepository: PreferenceRepository,
        uploadDir: File,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
    ): ChatboxImportResult {
        if (!file.isFile) {
            throw ChatboxImportException("Chatbox backup file not found.")
        }
        val treatAsZip = file.extension.lowercase() == ".zip" ||
            ChatboxBackupArchive.looksLikeZipFile(file)
        val staging = if (treatAsZip) {
            File(System.getProperty("java.io.tmpdir"), "chatbox_res_${System.nanoTime()}").apply { mkdirs() }
        } else {
            null
        }
        val resourceDestDir = if (treatAsZip) File(uploadDir, "chatbox").path else null

        try {
            val conversationDao = ConversationDao(database.writableDatabase)
            val messageDao = MessageDao(database.writableDatabase)
            val existingConvs = conversationDao.getAll()
            val existingConvIds = existingConvs.map { it.id }.toSet()
            val existingMsgIds = if (mode == RestoreMode.MERGE) {
                existingConvs.flatMap { messageDao.getMessageIds(it.id) }.toSet()
            } else {
                emptySet()
            }

            val archive: ChatboxBackupReadResult? = if (treatAsZip) {
                onProgress?.report(BackupProgress(BackupPhase.EXTRACTING, 0, cancellable = true))
                ChatboxBackupArchive.readZipV2(
                    file = file,
                    stagingDir = staging!!,
                    resourceDestDir = resourceDestDir!!,
                )
            } else {
                null
            }

            val root: MutableMap<String, Any?> = archive?.root ?: run {
                onProgress?.report(BackupProgress(BackupPhase.PREPARING, 0, cancellable = true))
                val decoded = try {
                    anyFromJson(JSON.parseToJsonElement(file.readText()))
                } catch (_: Exception) {
                    throw ChatboxImportException("Invalid JSON: unable to parse Chatbox backup file.")
                }
                @Suppress("UNCHECKED_CAST")
                decoded as? MutableMap<String, Any?>
                    ?: throw ChatboxImportException("Unsupported data format: expected a JSON object.")
            }
            validateLegacyRootShape(root)

            val overwrite = mode == RestoreMode.OVERWRITE
            if (overwrite) {
                val sessionsList = root["chat-sessions-list"] as? List<*>
                if (sessionsList.isNullOrEmpty()) {
                    throw ChatboxImportException(
                        "This Chatbox export does not include chat history. " +
                            "Re-export with \"Chat History\" enabled, or use merge mode.",
                    )
                }
                var hasAnySessionObject = false
                for (meta in sessionsList) {
                    @Suppress("UNCHECKED_CAST")
                    val metaMap = meta as? Map<String, Any?> ?: continue
                    val id = (metaMap["id"] ?: "").toString().trim()
                    if (id.isEmpty()) continue
                    if (root["session:$id"] is Map<*, *>) {
                        hasAnySessionObject = true
                        break
                    }
                }
                if (!hasAnySessionObject) {
                    throw ChatboxImportException(
                        "This Chatbox export is missing session data (no \"session:*\" entries). " +
                            "Please export again and include chat history.",
                    )
                }
            }

            val plan = parseChatboxPlan(
                root, mode == RestoreMode.MERGE, existingConvIds, existingMsgIds,
                onProgress, isCancelled,
            )

            if (isCancelled()) throw BackupCancelledException()
            onProgress?.report(BackupProgress(BackupPhase.COMMITTING, 0, cancellable = false))

            if (overwrite) {
                clearAllChatData(database, uploadDir)
            }
            commitConversations(conversationDao, messageDao, plan)
            transformBusinessData(database, preferenceRepository, mode, plan)
            archive?.let { ChatboxBackupArchive.publishStagedResources(it) }

            return ChatboxImportResult(
                providers = plan.providers.size,
                assistants = plan.assistants,
                conversations = plan.conversations,
                messages = plan.messages,
            )
        } finally {
            staging?.deleteRecursively()
        }
    }

    private fun validateLegacyRootShape(root: MutableMap<String, Any?>) {
        val hasSessions = root["chat-sessions-list"] is List<*>
        @Suppress("UNCHECKED_CAST")
        val settings = root["settings"] as? Map<String, Any?>
        val hasProviders = settings?.get("providers") is Map<*, *>
        if (!hasSessions && !hasProviders) {
            throw ChatboxImportException(
                "Not a Chatbox export file (missing \"chat-sessions-list\" and \"settings.providers\").",
            )
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

    // ── plan ────────────────────────────────────────────────────────────────

    private class ChatboxPlan(
        val providers: Map<String, JsonObject>,
        val assistants: Int,
        val conversations: Int,
        val messages: Int,
        val assistantIds: List<String>,
        val assistantPayloads: List<JsonObject>,
        val conversationBatches: List<Pair<com.psyche.memo.data.model.Conversation, List<ChatMessage>>>,
        val messagesToAppend: Map<String, List<ChatMessage>>,
    )

    private fun parseChatboxPlan(
        root: MutableMap<String, Any?>,
        merge: Boolean,
        existingConvIds: Set<String>,
        existingMsgIds: Set<String>,
        onProgress: BackupProgressSink?,
        isCancelled: () -> Boolean,
    ): ChatboxPlan {
        val sessionsList = (root["chat-sessions-list"] as? List<Any?>) ?: emptyList()

        val importedAssistants = mutableListOf<JsonObject>()
        val importedAssistantIds = mutableListOf<String>()
        val conversationBatches =
            mutableListOf<Pair<com.psyche.memo.data.model.Conversation, List<ChatMessage>>>()
        val messagesToAppend = LinkedHashMap<String, MutableList<ChatMessage>>()
        var convCount = 0
        var msgCount = 0

        val exportedAt = CherryDirectBackupReader.tryParseDate(
            (root["__exported_at"] ?: "").toString(),
        ) ?: System.currentTimeMillis()

        val pendingThreads = mutableListOf<PendingThread>()
        var sessionIndex = 0
        for (meta in sessionsList) {
            if (isCancelled()) throw BackupCancelledException()
            sessionIndex++
            onProgress?.report(
                BackupProgress(
                    BackupPhase.IMPORTING_SESSIONS, sessionIndex.toLong(),
                    sessionsList.size.toLong(),
                    com.psyche.memo.data.backup.BackupProgressUnit.ITEMS,
                    cancellable = true,
                ),
            )
            @Suppress("UNCHECKED_CAST")
            val metaMap = meta as? MutableMap<String, Any?> ?: continue
            val id = (metaMap["id"] ?: "").toString().trim()
            if (id.isEmpty()) continue
            val name = (metaMap["name"] ?: id).toString()
            val avatar = (metaMap["picUrl"] ?: "").toString().trim()
            val starred = metaMap["starred"] as? Boolean ?: false

            @Suppress("UNCHECKED_CAST")
            val session = (root["session:$id"] as? MutableMap<String, Any?>) ?: linkedMapOf()
            @Suppress("UNCHECKED_CAST")
            val sessionSettings = (session["settings"] as? MutableMap<String, Any?>) ?: linkedMapOf()

            val provider = (sessionSettings["provider"] ?: "").toString().trim()
            val modelId = (sessionSettings["modelId"] ?: "").toString().trim()
            val temperature = (sessionSettings["temperature"] as? Number)?.toDouble()
            val topP = (sessionSettings["topP"] as? Number)?.toDouble()
            val maxTokens = (sessionSettings["maxTokens"] as? Number)?.toInt()
            val stream = sessionSettings["stream"] as? Boolean
            val contextCount = (sessionSettings["maxContextMessageCount"] as? Number)?.toInt()
            val thinkingBudget = extractThinkingBudget(sessionSettings)
            val sysPrompt = extractSystemPromptFromSession(
                session,
                fallback = extractDefaultPrompt(root),
            )

            val assistant = Assistant(
                id = id,
                name = name,
                avatar = avatar.takeIf { it.isNotEmpty() },
                useAssistantAvatar = false,
                useAssistantName = false,
                chatModelProvider = if (provider.isEmpty() || provider == "chatbox-ai") null else provider,
                chatModelId = if (provider.isEmpty() || provider == "chatbox-ai" || modelId.isEmpty()) {
                    null
                } else {
                    modelId
                },
                temperature = temperature,
                topP = topP,
                contextMessageSize = contextCount ?: 64,
                limitContextMessages = true,
                streamOutput = stream ?: true,
                thinkingBudget = thinkingBudget,
                maxTokens = maxTokens,
                systemPrompt = sysPrompt,
                messageTemplate = "{{ message }}",
                mcpServerIds = emptyList(),
                background = null,
                customHeaders = emptyList(),
                customBody = emptyList(),
                enableMemory = false,
                allowPastConversationRecall = false,
                presetMessages = emptyList(),
                regexRules = emptyList(),
            )
            importedAssistants.add(
                MODEL_JSON.encodeToJsonElement(Assistant.serializer(), assistant).jsonObject,
            )
            importedAssistantIds.add(id)

            val threadsRaw = session["threads"] as? List<Any?> ?: emptyList()
            val sessionMessages = session["messages"] as? List<Any?> ?: emptyList()
            fun collectIds(raw: Any?): List<String> {
                val list = raw as? List<Any?> ?: return emptyList()
                return list.mapNotNull { e ->
                    @Suppress("UNCHECKED_CAST")
                    val map = e as? Map<String, Any?>
                    (map?.get("id") ?: "").toString().trim().takeIf { it.isNotEmpty() }
                }
            }

            val parsedThreads = threadsRaw.filterIsInstance<MutableMap<String, Any?>>()
            val effectiveThreads = mutableListOf<MutableMap<String, Any?>>()
            if (parsedThreads.isEmpty()) {
                effectiveThreads.add(
                    linkedMapOf(
                        "id" to "chatbox_default_$id",
                        "name" to name,
                        "createdAt" to null,
                        "messages" to sessionMessages,
                    ),
                )
            } else {
                effectiveThreads.addAll(parsedThreads)
                // Chatbox stores the current topic in `session.messages` and
                // previous topics in `session.threads`; import both without
                // duplicating the current one.
                val currentIds = collectIds(sessionMessages)
                if (currentIds.isNotEmpty()) {
                    val currentSet = currentIds.toSet()
                    var duplicated = false
                    for (t in parsedThreads) {
                        val ids = collectIds(t["messages"])
                        if (ids.size != currentIds.size) continue
                        if (ids.toSet() == currentSet) {
                            duplicated = true
                            break
                        }
                    }
                    if (!duplicated) {
                        val threadName = (session["threadName"] ?: "").toString().trim()
                        fun systemMessageId(raw: List<Any?>): String {
                            for (e in raw) {
                                @Suppress("UNCHECKED_CAST")
                                val m = e as? Map<String, Any?> ?: continue
                                if ((m["role"] ?: "").toString() != "system") continue
                                val mid = (m["id"] ?: "").toString().trim()
                                if (mid.isNotEmpty()) return mid
                            }
                            return ""
                        }
                        val baseId = systemMessageId(sessionMessages)
                        val derivedId = if (baseId.isNotEmpty()) "chatbox_thread_$baseId" else "chatbox_current_$id"
                        effectiveThreads.add(
                            linkedMapOf(
                                "id" to derivedId,
                                "name" to threadName.ifEmpty { name },
                                "createdAt" to null,
                                "messages" to sessionMessages,
                            ),
                        )
                    }
                }
            }

            for (t in effectiveThreads) {
                val tid = (t["id"] ?: "").toString().trim()
                if (tid.isEmpty()) continue
                pendingThreads.add(PendingThread(id, name, starred, t))
            }
        }

        var totalMessages = 0L
        for (pending in pendingThreads) {
            totalMessages += (pending.thread["messages"] as? List<*>)?.size ?: 0
        }

        var messageIndex = 0L
        for (pending in pendingThreads) {
            if (isCancelled()) throw BackupCancelledException()
            val t = pending.thread
            val id = pending.assistantId
            val name = pending.assistantName
            val starred = pending.starred
            val tid = (t["id"] ?: "").toString().trim()
            if (tid.isEmpty()) continue
            val title = (t["name"] ?: "").toString().trim().ifEmpty { name }
            val threadMessagesRaw = (t["messages"] as? List<Any?>) ?: emptyList()

            val messages = mutableListOf<ChatMessage>()
            var consumedSystem = false
            var fallbackIndex = 0
            for (rawMsg in threadMessagesRaw) {
                messageIndex++
                onProgress?.report(
                    BackupProgress(
                        BackupPhase.IMPORTING_MESSAGES, messageIndex, totalMessages,
                        com.psyche.memo.data.backup.BackupProgressUnit.ITEMS, cancellable = true,
                    ),
                )
                @Suppress("UNCHECKED_CAST")
                val msg = rawMsg as? MutableMap<String, Any?> ?: continue
                val msgId = (msg["id"] ?: "").toString()
                if (msgId.isEmpty()) continue
                if (merge && msgId in existingMsgIds) continue

                val roleRaw = (msg["role"] ?: "").toString()
                val parts = extractMessageParts(msg)
                val content = textFromParts(parts)

                // The first system message becomes the assistant prompt
                // (already extracted above); later ones stay visible.
                if (roleRaw == "system") {
                    if (!consumedSystem && content.trim().isNotEmpty()) {
                        consumedSystem = true
                        continue
                    }
                }

                val role = when (roleRaw) {
                    "user" -> "user"
                    "tool" -> "tool"
                    else -> "assistant"
                }

                val ts = parseEpochMillis(msg["timestamp"]) ?: (exportedAt + fallbackIndex++)

                if (role == "tool") {
                    val toolPayload = buildToolMessagePayload(msg, fallbackText = content)
                    val attachmentParts = parts.filter { it is ImagePart || it is FilePart }
                    messages.add(
                        ChatMessage(
                            id = msgId,
                            role = "tool",
                            parts = listOf<TextPart>(TextPart(toolPayload)) + attachmentParts,
                            timestamp = ts,
                            conversationId = tid,
                            groupId = msgId,
                            messageOrder = 0,
                        ),
                    )
                } else {
                    val inferredModel = inferModelIdFromChatboxMessage(msg)
                    val providerId = (msg["aiProvider"] ?: "").toString().trim()
                    val totalTokens = (msg["tokenCount"] as? Number)?.toInt()
                        ?: (msg["tokensUsed"] as? Number)?.toInt()
                    val messageParts: List<MessagePart> = if (roleRaw == "system") {
                        listOf(
                            TextPart(if (content.isEmpty()) "[System]" else "[System]\n$content"),
                        ) + parts.filter { it !is TextPart }
                    } else {
                        parts
                    }
                    val finalParts = messageParts.ifEmpty { listOf<MessagePart>(TextPart("")) }
                    messages.add(
                        ChatMessage(
                            id = msgId,
                            role = if (roleRaw == "system") "assistant" else role,
                            parts = finalParts,
                            timestamp = ts,
                            modelId = inferredModel.takeIf { it.isNotEmpty() },
                            providerId = providerId.takeIf { it.isNotEmpty() },
                            totalTokens = totalTokens,
                            conversationId = tid,
                            groupId = msgId,
                            messageOrder = 0,
                        ),
                    )
                }
            }

            var createdAt = exportedAt
            var updatedAt = exportedAt
            if (messages.isNotEmpty()) {
                val times = messages.map { it.timestamp }.sorted()
                createdAt = times.first()
                updatedAt = times.last()
            } else {
                parseEpochMillis(t["createdAt"])?.let { created ->
                    createdAt = created
                    updatedAt = created
                }
            }

            val conv = com.psyche.memo.data.model.Conversation(
                id = tid,
                title = title,
                createdAt = createdAt,
                updatedAt = updatedAt,
                isPinned = starred,
                assistantId = id,
            )

            if (merge && tid in existingConvIds) {
                messagesToAppend.getOrPut(tid) { mutableListOf() }.addAll(messages)
                msgCount += messages.size
            } else {
                conversationBatches.add(conv to messages)
                convCount += 1
                msgCount += messages.size
            }
        }

        addCopilotAssistants(root, importedAssistants, importedAssistantIds)

        return ChatboxPlan(
            providers = parseProviders(root),
            assistants = importedAssistantIds.toSet().size,
            conversations = convCount,
            messages = msgCount,
            assistantIds = importedAssistantIds,
            assistantPayloads = importedAssistants,
            conversationBatches = conversationBatches,
            messagesToAppend = messagesToAppend,
        )
    }

    private class PendingThread(
        val assistantId: String,
        val assistantName: String,
        val starred: Boolean,
        val thread: MutableMap<String, Any?>,
    )

    // ── providers / copilots / business ────────────────────────────────────

    private fun parseProviders(root: MutableMap<String, Any?>): Map<String, JsonObject> {
        @Suppress("UNCHECKED_CAST")
        val rawSettings = root["settings"] as? MutableMap<String, Any?> ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val providers = rawSettings["providers"] as? MutableMap<String, Any?> ?: return emptyMap()

        val imported = LinkedHashMap<String, JsonObject>()
        for ((rawKey, rawCfg) in providers) {
            val key = rawKey.toString().trim()
            if (key.isEmpty() || key == "chatbox-ai") continue // not supported here
            @Suppress("UNCHECKED_CAST")
            val cfg = rawCfg as? Map<String, Any?> ?: continue

            val apiKey = (cfg["apiKey"] ?: "").toString()
            val apiHost = (cfg["apiHost"] ?: "").toString()
            val apiPath = (cfg["apiPath"] ?: "").toString()
            val endpoint = (cfg["endpoint"] ?: "").toString()

            val kind = classifyKind(key)
            val normalized = normalizeHostAndPath(kind, apiHost, apiPath, endpoint)
            val models = mutableListOf<String>()
            val rawModels = cfg["models"] as? List<Any?> ?: emptyList()
            for (m in rawModels) {
                @Suppress("UNCHECKED_CAST")
                val model = m as? Map<String, Any?> ?: continue
                val mid = (model["modelId"] ?: "").toString().trim()
                if (mid.isNotEmpty()) models.add(mid)
            }

            val config = ProviderConfig(
                id = key,
                enabled = apiKey.trim().isNotEmpty(),
                name = key,
                apiKey = apiKey,
                baseUrl = normalized.first.ifEmpty { ProviderRepository.defaultBaseUrl(key) },
                providerType = kind,
                chatPath = if (kind == "openai") normalized.second else null,
                useResponseApi = if (kind == "openai") false else null,
                vertexAI = if (kind == "google") false else null,
                models = models,
                proxyEnabled = false,
                proxyHost = "",
                proxyPort = "8080",
                proxyUsername = "",
                proxyPassword = "",
                multiKeyEnabled = false,
                keyManagement = KeyManagementConfig(),
            )
            imported[key] = MODEL_JSON.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject
        }
        return imported
    }

    /** `ProviderConfig.classify` — infer the kind from the key. */
    private fun classifyKind(key: String): String {
        val k = key.lowercase()
        return when {
            "gemini" in k || "google" in k -> "google"
            "claude" in k || "anthropic" in k -> "claude"
            else -> "openai"
        }
    }

    private fun addCopilotAssistants(
        root: MutableMap<String, Any?>,
        importedAssistants: MutableList<JsonObject>,
        importedAssistantIds: MutableList<String>,
    ) {
        val copilots = root["myCopilots"] as? List<Any?> ?: return
        val seen = importedAssistantIds.toMutableSet()
        for (raw in copilots) {
            @Suppress("UNCHECKED_CAST")
            val copilot = raw as? Map<String, Any?> ?: continue
            val id = (copilot["id"] ?: "").toString().trim()
            if (id.isEmpty() || id in seen) continue
            val name = (copilot["name"] ?: id).toString()
            var avatar = (copilot["picUrl"] ?: "").toString().trim()
            val avatarSource = copilot["avatar"] as? Map<*, *>
            if (avatar.isEmpty() && avatarSource != null) {
                if ((avatarSource["type"] ?: "").toString() == "url") {
                    avatar = (avatarSource["url"] ?: "").toString().trim()
                }
            }
            val assistant = Assistant(
                id = id,
                name = name,
                avatar = avatar.takeIf { it.isNotEmpty() },
                contextMessageSize = 64,
                limitContextMessages = true,
                systemPrompt = (copilot["prompt"] ?: "").toString(),
            )
            importedAssistants.add(
                MODEL_JSON.encodeToJsonElement(Assistant.serializer(), assistant).jsonObject,
            )
            importedAssistantIds.add(id)
            seen.add(id)
        }
    }

    private fun commitConversations(
        conversationDao: ConversationDao,
        messageDao: MessageDao,
        plan: ChatboxPlan,
    ) {
        for ((conversation, messages) in plan.conversationBatches) {
            conversationDao.insert(conversation)
            messageDao.insertAllInTransaction(
                messages.mapIndexed { index, message -> withOrder(message, index) },
            )
        }
        for ((conversationId, messages) in plan.messagesToAppend) {
            var order = messageDao.nextOrder(conversationId)
            for (message in messages) {
                messageDao.insert(withOrder(message, order))
                order += 1
            }
        }
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

    /** `_transformBusinessData` — providers / assistants / Chatbox tag. */
    private fun transformBusinessData(
        database: MemoDatabase,
        preferenceRepository: PreferenceRepository,
        mode: RestoreMode,
        plan: ChatboxPlan,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val providerDao = PayloadEntityDao(db, "provider_rows", primaryKey = "provider_key")
            val assistantDao = PayloadEntityDao(db, "assistant_rows")
            val overwrite = mode == RestoreMode.OVERWRITE

            if (overwrite) {
                // Chatbox exports without providers historically left local
                // providers intact, so preserve that behavior.
                if (plan.providers.isNotEmpty()) {
                    providerDao.replaceAll(
                        plan.providers.map { (id, payload) ->
                            PayloadEntityDao.Row(id, 0, payload.toString(), now)
                        },
                    )
                    preferenceRepository.writeJson(
                        PROVIDERS_ORDER_KEY,
                        buildJsonArray { plan.providers.keys.forEach { add(JsonPrimitive(it)) } }.toString(),
                    )
                }
                assistantDao.replaceAll(
                    plan.assistantPayloads.mapIndexed { index, payload ->
                        PayloadEntityDao.Row(
                            payload.stringField("id") ?: "",
                            index,
                            payload.toString(),
                            now,
                        )
                    },
                )
            } else {
                val currentProviders = providerDao.getAll()
                val currentById = currentProviders.associateBy { it.id }.toMutableMap()
                val nextRows = mutableListOf<PayloadEntityDao.Row>()
                val orderIds = mutableListOf<String>()
                for ((id, payload) in plan.providers) {
                    val local = currentById[id]
                    if (local == null) {
                        nextRows.add(PayloadEntityDao.Row(id, nextRows.size, payload.toString(), now))
                        orderIds.add(id)
                        continue
                    }
                    val merged = LinkedHashMap(local.payload.parseJsonObject())
                    for ((field, value) in payload) {
                        if (field == "name") continue
                        val emptyString = value is JsonPrimitive && value.isString && value.content.isBlank()
                        if (value is kotlinx.serialization.json.JsonNull || emptyString) continue
                        merged[field] = value
                    }
                    nextRows.add(PayloadEntityDao.Row(id, local.sortOrder, JsonObject(merged).toString(), now))
                    orderIds.add(id)
                }
                for (row in currentProviders) {
                    if (row.id !in plan.providers.keys) nextRows.add(row)
                }
                providerDao.replaceAll(nextRows)

                val order = mutableListOf<String>()
                runCatching {
                    preferenceRepository.readJson(PROVIDERS_ORDER_KEY)?.let {
                        (JSON.parseToJsonElement(it) as? kotlinx.serialization.json.JsonArray)
                            ?.forEach { e -> order.add(e.jsonPrimitive.content) }
                    }
                }
                for (id in orderIds) if (id !in order) order.add(id)
                preferenceRepository.writeJson(
                    PROVIDERS_ORDER_KEY,
                    buildJsonArray { order.forEach { add(JsonPrimitive(it)) } }.toString(),
                )

                val currentAssistants = assistantDao.getAll()
                val byId = currentAssistants.associateBy { it.id }.toMutableMap()
                for (assistant in plan.assistantPayloads) {
                    val id = (assistant.stringField("id") ?: "")
                    if (id.isEmpty()) continue
                    val local = byId[id]?.payload?.parseJsonObject()
                    if (local == null) {
                        byId[id] = PayloadEntityDao.Row(id, byId.size, assistant.toString(), now)
                        continue
                    }
                    val merged = LinkedHashMap(local)
                    val prompt = assistant.stringField("systemPrompt")?.trim() ?: ""
                    if (prompt.isNotEmpty()) merged["systemPrompt"] = JsonPrimitive(prompt)
                    for (key in listOf(
                        "chatModelProvider", "chatModelId", "temperature", "topP",
                        "maxTokens", "thinkingBudget",
                    )) {
                        assistant[key]?.let { merged[key] = it }
                    }
                    byId[id] = PayloadEntityDao.Row(id, byId[id]!!.sortOrder, JsonObject(merged).toString(), now)
                }
                assistantDao.replaceAll(byId.values.toList())
            }

            if (plan.assistantIds.isNotEmpty()) {
                val tagDao = PayloadEntityDao(db, "assistant_tag_rows")
                val tags: MutableList<JsonObject> = if (overwrite) {
                    mutableListOf()
                } else {
                    tagDao.getAll().map { it.payload.parseJsonObject() }.toMutableList()
                }
                val assignment: MutableMap<String, Any?> = if (overwrite) {
                    linkedMapOf()
                } else {
                    readMap(preferenceRepository, ASSIGN_KEY)
                }
                val collapsed: MutableMap<String, Any?> = if (overwrite) {
                    linkedMapOf()
                } else {
                    readMap(preferenceRepository, COLLAPSED_KEY)
                }

                var chatboxTagId: String? = null
                for (tag in tags) {
                    if ((tag.stringField("name") ?: "").trim().lowercase() != "chatbox") continue
                    val id = (tag.stringField("id") ?: "").trim()
                    if (id.isNotEmpty()) {
                        chatboxTagId = id
                        break
                    }
                }
                val tagId = chatboxTagId ?: UUID.randomUUID().toString()
                if (tags.none { it.stringField("id") == tagId }) {
                    tags.add(buildJsonObject {
                        put("id", tagId)
                        put("name", "Chatbox")
                    })
                }

                for (assistantId in plan.assistantIds) {
                    val id = assistantId.trim()
                    if (id.isEmpty()) continue
                    if (overwrite) {
                        assignment[id] = tagId
                    } else {
                        assignment.putIfAbsent(id, tagId)
                    }
                }
                if (!collapsed.containsKey(tagId)) collapsed[tagId] = false

                tagDao.replaceAll(
                    tags.mapIndexed { index, payload ->
                        PayloadEntityDao.Row(
                            payload.stringField("id") ?: "",
                            index,
                            payload.toString(),
                            now,
                        )
                    },
                )
                preferenceRepository.writeJson(ASSIGN_KEY, jsonFromAny(assignment).toString())
                preferenceRepository.writeJson(COLLAPSED_KEY, jsonFromAny(collapsed).toString())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readMap(
        preferenceRepository: PreferenceRepository,
        key: String,
    ): MutableMap<String, Any?> {
        val raw = preferenceRepository.readJson(key) ?: return linkedMapOf()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            anyFromJson(JSON.parseToJsonElement(raw)) as? MutableMap<String, Any?>
        }.getOrNull() ?: linkedMapOf()
    }

    // ── content helpers ─────────────────────────────────────────────────────

    private fun extractDefaultPrompt(root: MutableMap<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val settings = root["settings"] as? Map<String, Any?> ?: return ""
        val p = (settings["defaultPrompt"] ?: "").toString()
        return p.trim().takeIf { it.isNotEmpty() } ?: ""
    }

    private fun extractSystemPromptFromSession(
        session: MutableMap<String, Any?>,
        fallback: String,
    ): String {
        val msgs = session["messages"] as? List<Any?> ?: return fallback
        for (raw in msgs) {
            @Suppress("UNCHECKED_CAST")
            val m = raw as? Map<String, Any?> ?: continue
            if ((m["role"] ?: "").toString() != "system") continue
            val content = textFromParts(extractMessageParts(m))
            if (content.trim().isNotEmpty()) return content
        }
        return fallback
    }

    private fun extractThinkingBudget(sessionSettings: MutableMap<String, Any?>): Int? {
        val opts = sessionSettings["providerOptions"] as? Map<*, *> ?: return null
        val claude = opts["claude"] as? Map<*, *>
        if (claude != null) {
            val thinking = claude["thinking"] as? Map<*, *>
            if (thinking != null) {
                val type = (thinking["type"] ?: "").toString()
                if (type == "disabled") return 0
                val budget = (thinking["budgetTokens"] as? Number)?.toInt()
                if (budget != null) return budget
            }
        }
        val google = opts["google"] as? Map<*, *>
        if (google != null) {
            val thinkingConfig = google["thinkingConfig"] as? Map<*, *>
            if (thinkingConfig != null) {
                val budget = (thinkingConfig["thinkingBudget"] as? Number)?.toInt()
                if (budget != null) return budget
            }
        }
        return null
    }

    private fun parseEpochMillis(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong().takeIf { it > 0 }
        is String -> raw.toLongOrNull()?.takeIf { it > 0 }
        else -> null
    }

    private fun isAvailableChatboxMediaUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("data:image") || lower.startsWith("file:") ||
            url.startsWith("/") || Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(url)
    }

    private fun textFromParts(parts: List<MessagePart>): String =
        parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }.trim()

    private fun extractMessageParts(msg: Map<String, Any?>): List<MessagePart> {
        val partsRaw = msg["contentParts"]
        val out = mutableListOf<MessagePart>()
        val textChunks = mutableListOf<String>()
        var pendingContentNewline = false

        fun flushText() {
            if (textChunks.isEmpty()) return
            out.add(TextPart(textChunks.joinToString("\n")))
            textChunks.clear()
        }

        fun flushTextForAttachment() {
            flushText()
            pendingContentNewline = out.any { it is TextPart }
        }

        fun addText(s: String) {
            val t = s.replace("\r\n", "\n")
            if (t.trim().isEmpty()) return
            if (pendingContentNewline && textChunks.isEmpty()) textChunks.add("")
            pendingContentNewline = false
            textChunks.add(t)
        }

        fun mimeFor(uri: String, explicit: String?, fileName: String?): String? {
            val e = explicit?.trim()
            if (!e.isNullOrEmpty()) return e
            val source = if (!fileName.isNullOrEmpty()) fileName else uri
            return inferMediaMimeFromSource(source).ifEmpty { null }
        }

        if (partsRaw is List<*>) {
            for (p in partsRaw) {
                @Suppress("UNCHECKED_CAST")
                val part = p as? Map<String, Any?> ?: continue
                val type = (part["type"] ?: "").toString()
                when (type) {
                    "text" -> addText((part["text"] ?: "").toString())
                    "image" -> {
                        val url = (part["url"] ?: "").toString().trim()
                        val storageKey = (part["storageKey"] ?: "").toString().trim()
                        val ref = url.ifEmpty { storageKey }
                        if (ref.isEmpty()) continue
                        val available = isAvailableChatboxMediaUrl(url)
                        if (available || storageKey.isNotEmpty()) {
                            flushTextForAttachment()
                            out.add(
                                ImagePart(
                                    uri = ref,
                                    mime = mimeFor(ref, null, null),
                                    unavailable = !available,
                                ),
                            )
                        } else {
                            addText("[Chatbox image: $ref]")
                        }
                    }
                    "info" -> addText((part["text"] ?: "").toString())
                    "reasoning" -> {
                        val t = (part["text"] ?: "").toString()
                        if (t.trim().isNotEmpty()) {
                            flushText()
                            out.add(ReasoningPart(t))
                            pendingContentNewline = out.any { it is TextPart }
                        }
                    }
                    "tool-call" -> {
                        val state = (part["state"] ?: "").toString()
                        val toolName = (part["toolName"] ?: "").toString()
                        val args = part["args"]
                        if (state.isNotEmpty()) {
                            addText(
                                "[tool:$state] ${toolName.ifEmpty { "tool" }} ${
                                    if (args == null) "" else args.toString()
                                }".trim(),
                            )
                        }
                        val result = (part["result"] ?: "").toString()
                        if (result.trim().isNotEmpty()) addText(result)
                    }
                }
            }
        }

        if (out.isEmpty() && textChunks.isEmpty()) {
            val legacy = (msg["content"] ?: "").toString()
            if (legacy.trim().isNotEmpty()) addText(legacy)
        }

        val links = msg["links"] as? List<Any?>
        if (links != null) {
            for (l in links) {
                @Suppress("UNCHECKED_CAST")
                val link = l as? Map<String, Any?> ?: continue
                val url = (link["url"] ?: "").toString().trim()
                if (url.isEmpty()) continue
                val title = (link["title"] ?: "").toString().trim()
                if (title.isNotEmpty()) addText("[$title]($url)") else addText(url)
            }
        }

        val files = msg["files"] as? List<Any?>
        if (files != null) {
            for (f in files) {
                @Suppress("UNCHECKED_CAST")
                val fileMap = f as? Map<String, Any?> ?: continue
                val url = (fileMap["url"] ?: "").toString().trim()
                if (url.isEmpty()) continue
                val name = (fileMap["name"] ?: "file").toString()
                val type = (fileMap["fileType"] ?: "").toString()
                flushTextForAttachment()
                out.add(
                    FilePart(
                        uri = url,
                        name = name.ifEmpty { "file" },
                        mime = mimeFor(url, type, name) ?: "application/octet-stream",
                    ),
                )
            }
        }

        val pics = msg["pictures"] as? List<Any?>
        if (pics != null) {
            for (p in pics) {
                @Suppress("UNCHECKED_CAST")
                val picture = p as? Map<String, Any?> ?: continue
                val url = (picture["url"] ?: "").toString().trim()
                if (url.isEmpty()) continue
                flushTextForAttachment()
                out.add(ImagePart(uri = url, mime = mimeFor(url, null, null)))
            }
        }

        val err = (msg["error"] ?: "").toString()
        if (err.trim().isNotEmpty()) addText("[Error] $err")

        flushText()
        return out
    }

    private fun inferModelIdFromChatboxMessage(msg: Map<String, Any?>): String {
        val raw = (msg["model"] ?: "").toString().trim()
        if (raw.isEmpty()) return ""
        val m = Regex("\\(([^)]+)\\)\\s*$").find(raw) ?: return raw
        return m.groupValues[1].trim()
    }

    private fun buildToolMessagePayload(
        msg: Map<String, Any?>,
        fallbackText: String,
    ): String {
        var toolName = (msg["name"] ?: "").toString().trim()
        val args = LinkedHashMap<String, Any?>()
        var result = fallbackText

        val parts = msg["contentParts"] as? List<*>
        if (parts != null) {
            for (p in parts) {
                @Suppress("UNCHECKED_CAST")
                val part = p as? Map<String, Any?> ?: continue
                if ((part["type"] ?: "").toString() != "tool-call") continue
                if (toolName.isEmpty()) toolName = (part["toolName"] ?: "").toString()
                @Suppress("UNCHECKED_CAST")
                val a = part["args"] as? Map<String, Any?>
                if (a != null) args.putAll(a)
                if (part.containsKey("result")) result = (part["result"] ?: "").toString()
                break
            }
        }

        val payload = linkedMapOf<String, Any?>(
            "tool" to toolName.ifEmpty { "tool" },
            "arguments" to args,
            "result" to result,
        )
        return jsonFromAny(payload).toString()
    }

    private fun normalizeHostAndPath(
        kind: String,
        apiHost: String,
        apiPath: String,
        endpoint: String,
    ): Pair<String, String> {
        var host = apiHost.trim()
        var path = apiPath.trim()

        if (host.isEmpty() && endpoint.trim().isNotEmpty()) host = endpoint.trim()
        if (host.isNotEmpty() && host.endsWith("/")) host = host.dropLast(1)
        if (host.isNotEmpty() && !host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://$host"
        }

        if (kind == "openai") {
            if (path.isNotEmpty() && !path.startsWith("/")) path = "/$path"
            if (host.lowercase().endsWith("/chat/completions")) {
                host = host.substring(0, host.length - "/chat/completions".length)
                path = "/chat/completions"
            }
            val lower = host.lowercase()
            val hasKnownVersionSuffix = lower.endsWith("/v1") ||
                lower.endsWith("/v1beta") ||
                Regex("/api/v\\d+$").containsMatchIn(lower) ||
                lower.endsWith("/api/paas/v4") ||
                lower.endsWith("/compatible-mode/v1")
            if (path.isEmpty()) path = "/chat/completions"
            if (host.isNotEmpty() && !hasKnownVersionSuffix && !path.contains("/v1")) {
                host = "$host/v1"
            }
            if (lower.endsWith("://api.openai.com") || lower.endsWith("://api.openai.com/v1")) {
                host = "https://api.openai.com/v1"
                path = "/chat/completions"
            }
            if (lower.endsWith("://openrouter.ai") || lower.endsWith("://openrouter.ai/api")) {
                host = "https://openrouter.ai/api/v1"
                path = "/chat/completions"
            }
            return host to path
        }

        if (kind == "claude") {
            val lower = host.lowercase()
            if (host.isNotEmpty() && lower == "https://api.anthropic.com") {
                host = "$host/v1"
            } else if (host.isNotEmpty() && !lower.endsWith("/v1") && !Regex("/v\\d+$").containsMatchIn(lower)) {
                host = "$host/v1"
            }
            return host to ""
        }

        if (kind == "google") {
            val lower = host.lowercase()
            if (host.isNotEmpty() && !lower.endsWith("/v1beta")) host = "$host/v1beta"
            return host to ""
        }

        return host to path
    }

    /** `inferMediaMimeFromSource` (multimodal_input_utils.dart L88-125). */
    private fun inferMediaMimeFromSource(source: String): String {
        val lower = source.lowercase()
        if (lower.startsWith("data:")) {
            val start = lower.indexOf(':')
            val semi = lower.indexOf(';')
            if (start >= 0 && semi > start) return lower.substring(start + 1, semi)
        }
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".pcm16") -> "audio/pcm16"
            lower.endsWith(".pcm") -> "audio/pcm"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mpeg") || lower.endsWith(".mpg") -> "video/mpeg"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".wmv") -> "video/x-ms-wmv"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".3gp") || lower.endsWith(".3gpp") -> "video/3gpp"
            else -> ""
        }
    }
}

internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun String.parseJsonObject(): JsonObject =
    Json { isLenient = true; ignoreUnknownKeys = true }.parseToJsonElement(this).jsonObject
