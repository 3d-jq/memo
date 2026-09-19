package com.psyche.memo.data.backup.chatbox

import com.psyche.memo.data.backup.BackupCancelledException
import com.psyche.memo.data.backup.BackupPhase
import com.psyche.memo.data.backup.BackupProgress
import com.psyche.memo.data.backup.BackupProgressSink
import com.psyche.memo.data.backup.RestoreMode
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
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Imports a Chatbox export into Memo — 1:1 port of `chatbox_importer.dart`
 * and `chatbox_backup_archive.dart`. The archive half reads the chatbox
 * v2 ZIP format (manifest.json + checksummed entries + staged resources);
 * the importer half maps sessions/threads/messages onto Memo's
 * conversations/messages and providers/assistants onto the business tables.
 *
 * Dynamic JSON uses the dynamic-tree convention (`anyFromJson`); Android has
 * no isolate runner, so everything runs inline on the caller's IO context.
 */
class ChatboxImportException(message: String) : Exception(message)

data class ChatboxImportResult(
    val providers: Int,
    val assistants: Int,
    val conversations: Int,
    val messages: Int,
)

internal class ChatboxBackupReadResult(
    val root: MutableMap<String, Any?>,
    val stagedResourceFiles: MutableList<File>,
    val resourceDestDir: String,
)

internal class ChatboxBackupArchive {

    companion object {
        const val BACKUP_FORMAT = "chatbox-backup"
        const val BACKUP_FORMAT_VERSION = 2
        const val MAX_SESSIONS = 50000
        const val MAX_RESOURCES = 50000
        const val MAX_FILE_ENTRIES = 100004
        const val MAX_JSON_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_RESOURCE_ENTRY_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_COMPRESSION_RATIO = 2000

        private const val MANIFEST_PATH = "manifest.json"
        private const val SETTINGS_PATH = "settings.json"
        private const val COPILOTS_PATH = "copilots.json"
        private const val SESSION_SETTINGS_PATH = "session-settings.json"

        private val EXPORT_ITEMS = setOf("settings", "copilots", "sessions", "resources")
        private val RESOURCE_SCOPES = setOf("session", "shared", "global")
        private val RESOURCE_ENCODINGS = setOf("utf8", "data-url-base64")
        private val RESOURCE_KINDS = setOf(
            "image", "file", "avatar", "background", "screenshot", "tool-result",
        )
        private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
        private val WINDOWS_DRIVE = Regex("^[a-zA-Z]:")

        private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

        fun looksLikeZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()

        fun looksLikeZipFile(file: File): Boolean = try {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = input.read(head, read, 4 - read)
                    if (n < 0) break
                    read += n
                }
                read == 4 && looksLikeZip(head)
            }
        } catch (_: Exception) {
            false
        }

        /**
         * Reads the v2 ZIP archive: validates every entry against the
         * manifest (sizes + sha256 checksums + graph consistency), stages
         * resources into [stagingDir] for a later
         * [publishStagedResources], and rebuilds the legacy root shape
         * (`chat-sessions-list` + `session:<id>` + `settings` + `myCopilots`)
         * the importer consumes.
         */
        fun readZipV2(file: File, stagingDir: File, resourceDestDir: String): ChatboxBackupReadResult {
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                throw ChatboxImportException("Unable to read Chatbox backup ZIP: $e")
            }
            zip.use { zf ->
                // ── entry inventory + budgets ──
                data class Inv(val entry: java.util.zip.ZipEntry, val path: String)
                val inventory = LinkedHashMap<String, Inv>()
                var totalUncompressed = 0L
                var fileCount = 0
                for (entry in zf.entries().asSequence()) {
                    if (entry.isDirectory) continue
                    val path = normalizedArchivePath(entry.name)
                    if (inventory.containsKey(path)) {
                        throw ChatboxImportException("Duplicate ZIP entry: $path")
                    }
                    fileCount++
                    if (fileCount > MAX_FILE_ENTRIES) {
                        throw ChatboxImportException("Backup contains too many entries.")
                    }
                    assertEntryBudget(path, entry.size, entry.compressedSize)
                    totalUncompressed += entry.size
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw ChatboxImportException(
                            "Backup uncompressed size exceeds the safety limit.",
                        )
                    }
                    inventory[path] = Inv(entry, path)
                }

                fun bytesOf(path: String, maxBytes: Long): ByteArray {
                    val inv = inventory.getValue(path)
                    val bound = minOf(maxBytes, if (inv.entry.size > 0) inv.entry.size else maxBytes)
                    val out = java.io.ByteArrayOutputStream()
                    zf.getInputStream(inv.entry).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > bound) throw ChatboxImportException("Backup entry is too large: $path")
                            out.write(buffer, 0, n)
                        }
                    }
                    return out.toByteArray()
                }

                fun sha256Hex(bytes: ByteArray): String =
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }

                fun isBackupSessionPath(path: String): Boolean =
                    Regex("^sessions/[^/]+/session\\.json$").containsMatchIn(path)

                fun isBackupJsonPath(path: String): Boolean =
                    path == MANIFEST_PATH || path == SETTINGS_PATH || path == COPILOTS_PATH ||
                        path == SESSION_SETTINGS_PATH || isBackupSessionPath(path)

                fun extractVerified(
                    path: String,
                    descriptor: Map<String, Any?>,
                    destFile: File?,
                ): ByteArray {
                    val entry = inventory[path]
                        ?: throw ChatboxImportException("Backup entry is missing: $path")
                    val expectedSize = (descriptor["size"] as? Number)?.toLong()
                    if (expectedSize != null && entry.entry.size != expectedSize) {
                        throw ChatboxImportException("Backup entry size mismatch: $path")
                    }
                    val limit = if (isBackupJsonPath(path)) MAX_JSON_ENTRY_BYTES else MAX_RESOURCE_ENTRY_BYTES
                    val bytes = bytesOf(path, if (expectedSize != null && expectedSize < limit) expectedSize else limit)
                    if (expectedSize != null && bytes.size.toLong() != expectedSize) {
                        throw ChatboxImportException("Backup entry size mismatch: $path")
                    }
                    val checksum = descriptor["checksum"] as? Map<*, *>
                    val expectedChecksum = (checksum?.get("value") ?: "").toString()
                    if (sha256Hex(bytes) != expectedChecksum) {
                        throw ChatboxImportException("Backup entry checksum mismatch: $path")
                    }
                    if (destFile != null) {
                        destFile.parentFile?.mkdirs()
                        destFile.writeBytes(bytes)
                    }
                    return bytes
                }

                fun decodeJsonObject(bytes: ByteArray, path: String): MutableMap<String, Any?> {
                    if (bytes.size > MAX_JSON_ENTRY_BYTES) {
                        throw ChatboxImportException("Backup JSON entry is too large: $path")
                    }
                    val decoded = try {
                        anyFromJson(JSON.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)))
                    } catch (_: Exception) {
                        throw ChatboxImportException("Invalid JSON in backup entry: $path")
                    }
                    @Suppress("UNCHECKED_CAST")
                    return decoded as? MutableMap<String, Any?>
                        ?: throw ChatboxImportException("Invalid JSON object entry: $path")
                }

                // ── manifest ──
                val manifestEntry = inventory[MANIFEST_PATH]
                    ?: throw ChatboxImportException(
                        "Not a Chatbox backup archive (missing manifest.json).",
                    )
                val manifestBytes = bytesOf(MANIFEST_PATH, MAX_JSON_ENTRY_BYTES)
                val manifestRoot = try {
                    anyFromJson(JSON.parseToJsonElement(String(manifestBytes, StandardCharsets.UTF_8)))
                } catch (_: Exception) {
                    throw ChatboxImportException("Invalid JSON in backup entry: $MANIFEST_PATH")
                }
                @Suppress("UNCHECKED_CAST")
                val manifest = manifestRoot as? MutableMap<String, Any?>
                    ?: throw ChatboxImportException(
                        "Not a Chatbox backup archive (manifest.json is not an object).",
                    )
                parseManifest(manifest)

                // Membership: every zip entry is listed, every listed entry exists.
                val expected = HashSet<String>()
                expected.add(MANIFEST_PATH)
                fun addDescriptor(raw: Any?, requiredId: Boolean) {
                    val descriptor = raw as? Map<*, *> ?: return
                    val path = (descriptor["path"] ?: "").toString()
                    if (!expected.add(path)) {
                        throw ChatboxImportException("Manifest contains a duplicate path: $path")
                    }
                    if (!inventory.containsKey(path)) {
                        throw ChatboxImportException("Backup entry is missing: $path")
                    }
                    if (requiredId && (descriptor["id"] ?: "").toString().isEmpty()) {
                        throw ChatboxImportException("Backup entry is missing an id: $path")
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val manifestData = (manifest["data"] as? Map<String, Any?>) ?: linkedMapOf()
                addDescriptor(manifestData["settings"], requiredId = false)
                addDescriptor(manifestData["copilots"], requiredId = false)
                addDescriptor(manifestData["sessionSettings"], requiredId = false)
                for (session in manifest["sessions"] as List<*>) {
                    addDescriptor(session, requiredId = true)
                }
                for (resource in manifest["resources"] as List<*>) {
                    addDescriptor(resource, requiredId = true)
                }
                for (path in inventory.keys) {
                    if (path !in expected) {
                        throw ChatboxImportException(
                            "Backup contains an entry not listed in manifest: $path",
                        )
                    }
                }
                if (expected.size != inventory.size) {
                    throw ChatboxImportException("Backup manifest entry list is incomplete.")
                }

                // ── resources ──
                val keyToUri = LinkedHashMap<String, String>()
                val keyToText = LinkedHashMap<String, String>()
                val staged = mutableListOf<File>()
                stagingDir.mkdirs()
                for (raw in manifest["resources"] as List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val resource = raw as? Map<String, Any?> ?: continue
                    val path = (resource["path"] as? String) ?: ""
                    val kind = (resource["kind"] ?: "").toString()
                    if (kind == "tool-result") {
                        val text = String(extractVerified(path, resource, null), StandardCharsets.UTF_8)
                        for (key in resource["originalStorageKeys"] as List<*>) {
                            keyToText[key.toString()] = text
                        }
                        continue
                    }
                    val fileName = resourceFileName(resource)
                    val stagedFile = File(stagingDir, fileName)
                    extractVerified(path, resource, stagedFile)
                    staged.add(stagedFile)
                    val destPath = File(resourceDestDir, fileName).path
                    for (key in resource["originalStorageKeys"] as List<*>) {
                        keyToUri[key.toString()] = destPath
                    }
                }

                val root = linkedMapOf<String, Any?>(
                    "__exported_at" to (manifest["exportedAt"] ?: "").toString(),
                )

                val settingsDesc = manifestData["settings"] as? Map<*, *>
                if (settingsDesc != null) {
                    val settingsPath = (settingsDesc["path"] ?: SETTINGS_PATH).toString()
                    root["settings"] = decodeJsonObject(extractVerified(settingsPath, settingsDesc as Map<String, Any?>, null), settingsPath)
                }

                val copilotsDesc = manifestData["copilots"] as? Map<*, *>
                if (copilotsDesc != null) {
                    val copilotsPath = (copilotsDesc["path"] ?: COPILOTS_PATH).toString()
                    val copilotsBytes = extractVerified(copilotsPath, copilotsDesc as Map<String, Any?>, null)
                    val copilots = try {
                        anyFromJson(JSON.parseToJsonElement(String(copilotsBytes, StandardCharsets.UTF_8))) as? List<Any?>
                            ?: throw ChatboxImportException("Invalid JSON array entry: $copilotsPath")
                    } catch (e: ChatboxImportException) {
                        throw e
                    } catch (_: Exception) {
                        throw ChatboxImportException("Invalid JSON array entry: $copilotsPath")
                    }
                    root["myCopilots"] = copilots.map { item ->
                        @Suppress("UNCHECKED_CAST")
                        val itemMap = item as? MutableMap<String, Any?>
                        if (itemMap != null) rewriteCopilot(itemMap, keyToUri) else item
                    }
                }

                val sessionSettingsDesc = manifestData["sessionSettings"] as? Map<*, *>
                if (sessionSettingsDesc != null) {
                    val sessionSettingsPath =
                        ((sessionSettingsDesc as Map<*, *>)["path"] as? String) ?: SESSION_SETTINGS_PATH
                    extractVerified(sessionSettingsPath, sessionSettingsDesc as Map<String, Any?>, null)
                }

                val sessionList = mutableListOf<MutableMap<String, Any?>>()
                for (raw in manifest["sessions"] as List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val descriptor = raw as? Map<String, Any?> ?: continue
                    val sessionPath = descriptor["path"] as String
                    val sessionId = descriptor["id"] as String
                    val parsed = decodeJsonObject(extractVerified(sessionPath, descriptor, null), sessionPath)
                    if (!isBackupSession(parsed)) {
                        throw ChatboxImportException("Invalid session entry: $sessionPath")
                    }
                    if ((parsed["id"] ?: "").toString() != sessionId) {
                        throw ChatboxImportException(
                            "Session id does not match manifest: $sessionPath",
                        )
                    }
                    val rewritten = rewriteSession(parsed, keyToUri, keyToText)
                    root["session:$sessionId"] = rewritten
                    sessionList.add(sessionMetaForList(descriptor, rewritten, keyToUri))
                }
                root["chat-sessions-list"] = sessionList
                return ChatboxBackupReadResult(
                    root = root,
                    stagedResourceFiles = staged,
                    resourceDestDir = resourceDestDir,
                )
            }
        }

        /** Manifest schema validation (`_parseManifest`). */
        private fun parseManifest(manifest: MutableMap<String, Any?>) {
            val format = (manifest["format"] ?: "").toString()
            if (format != BACKUP_FORMAT) {
                throw ChatboxImportException("Not a Chatbox backup archive (format is not \"chatbox-backup\").")
            }
            val version = manifest["formatVersion"]
            if (version != BACKUP_FORMAT_VERSION) {
                throw ChatboxImportException(
                    "Unsupported Chatbox backup formatVersion: $version. " +
                        "Only formatVersion $BACKUP_FORMAT_VERSION is supported.",
                )
            }
            val exportedAt = (manifest["exportedAt"] ?: "").toString().trim()
            if (exportedAt.isEmpty()) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (missing exportedAt).")
            }
            val application = manifest["application"] as? Map<*, *>
            if (application == null ||
                (application["name"] ?: "").toString() != "Chatbox" ||
                (application["version"] ?: "").toString().isEmpty() ||
                (application["platform"] ?: "").toString().isEmpty()
            ) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (application).")
            }
            val exportItems = manifest["exportItems"] as? List<*>
                ?: throw ChatboxImportException("Invalid Chatbox backup manifest (exportItems).")
            for (item in exportItems) {
                if (item.toString() !in EXPORT_ITEMS) {
                    throw ChatboxImportException("Invalid Chatbox backup export item: $item")
                }
            }
            val data = manifest["data"] as? Map<*, *>
                ?: throw ChatboxImportException("Invalid Chatbox backup manifest (data).")
            for (key in listOf("settings", "copilots", "sessionSettings")) {
                val value = data[key]
                if (value != null) requireJsonDescriptor(value, "data.$key")
            }
            val sessions = manifest["sessions"] as? List<*>
                ?: throw ChatboxImportException("Invalid Chatbox backup manifest (sessions).")
            if (sessions.size > MAX_SESSIONS) {
                throw ChatboxImportException("Backup contains too many sessions.")
            }
            for (session in sessions) requireSessionDescriptor(session)
            val resources = manifest["resources"] as? List<*>
                ?: throw ChatboxImportException("Invalid Chatbox backup manifest (resources).")
            if (resources.size > MAX_RESOURCES) {
                throw ChatboxImportException("Backup contains too many resources.")
            }
            for (resource in resources) requireResourceDescriptor(resource)
            val warnings = manifest["warnings"] as? List<*>
                ?: throw ChatboxImportException("Invalid Chatbox backup manifest (warnings).")
            val stats = manifest["stats"] as? Map<*, *>
                ?: throw ChatboxImportException("Invalid Chatbox backup manifest (stats).")
            fun nonNeg(raw: Any?): Int? = (raw as? Number)?.takeIf { it.toLong() >= 0 }?.toInt()
            if (nonNeg(stats["sessionCount"]) != sessions.size ||
                nonNeg(stats["resourceCount"]) != resources.size ||
                nonNeg(stats["warningCount"]) != warnings.size ||
                nonNeg(stats["deduplicatedResourceCount"]) == null
            ) {
                throw ChatboxImportException("Backup manifest statistics do not match its entries.")
            }
        }

        private fun requireJsonDescriptor(raw: Any?, label: String) {
            if (raw !is Map<*, *>) {
                throw ChatboxImportException("Invalid Chatbox backup manifest ($label).")
            }
            requireChecksumPath(raw, label)
        }

        private fun requireSessionDescriptor(raw: Any?) {
            if (raw !is Map<*, *>) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (session).")
            }
            requireChecksumPath(raw, "session")
            if ((raw["id"] ?: "").toString().isEmpty()) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (session.id).")
            }
            if (raw["meta"] !is Map<*, *>) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (session.meta).")
            }
            val resourceIds = raw["resourceIds"]
            if (resourceIds !is List<*> || resourceIds.size > MAX_RESOURCES) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (session.resourceIds).")
            }
        }

        private fun requireResourceDescriptor(raw: Any?) {
            if (raw !is Map<*, *>) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (resource).")
            }
            requireChecksumPath(raw, "resource")
            if ((raw["id"] ?: "").toString().isEmpty()) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (resource.id).")
            }
            val keys = raw["originalStorageKeys"]
            if (keys !is List<*> || keys.isEmpty() || keys.size > MAX_RESOURCES) {
                throw ChatboxImportException(
                    "Invalid Chatbox backup manifest (resource.originalStorageKeys).",
                )
            }
            val sessionIds = raw["sessionIds"]
            if (sessionIds !is List<*> || sessionIds.size > MAX_SESSIONS) {
                throw ChatboxImportException(
                    "Invalid Chatbox backup manifest (resource.sessionIds).",
                )
            }
            if ((raw["scope"] ?: "").toString() !in RESOURCE_SCOPES ||
                (raw["encoding"] ?: "").toString() !in RESOURCE_ENCODINGS ||
                (raw["mimeType"] ?: "").toString().isEmpty() ||
                (raw["kind"] ?: "").toString() !in RESOURCE_KINDS
            ) {
                throw ChatboxImportException("Invalid Chatbox backup manifest (resource metadata).")
            }
        }

        private fun requireChecksumPath(raw: Map<*, *>, label: String) {
            val path = (raw["path"] ?: "").toString()
            if (path.isEmpty() || path.length > 4096) {
                throw ChatboxImportException("Invalid Chatbox backup manifest ($label.path).")
            }
            assertSafeArchivePath(path)
            val size = raw["size"]
            if (size !is Number || size.toLong() < 0) {
                throw ChatboxImportException("Invalid Chatbox backup manifest ($label.size).")
            }
            val checksum = raw["checksum"]
            if (checksum !is Map<*, *> ||
                (checksum["algorithm"] ?: "").toString() != "sha256" ||
                !SHA256_HEX.matches((checksum["value"] ?: "").toString())
            ) {
                throw ChatboxImportException("Invalid Chatbox backup manifest ($label.checksum).")
            }
        }

        private fun normalizedArchivePath(raw: String): String {
            if (raw.contains('\u0000')) {
                throw ChatboxImportException("Unsafe ZIP entry path: $raw")
            }
            val path = raw.replace('\\', '/')
            assertSafeArchivePath(path)
            return path
        }

        private fun assertSafeArchivePath(path: String) {
            if (path.isEmpty()) {
                throw ChatboxImportException("Unsafe ZIP entry path: (empty)")
            }
            if (path.contains('\\') || path.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(path)) {
                throw ChatboxImportException("Unsafe ZIP entry path: $path")
            }
            val segments = path.split("/")
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw ChatboxImportException("Unsafe ZIP entry path: $path")
            }
        }

        private fun assertEntryBudget(path: String, uncompressed: Long, compressed: Long) {
            val limit = if (isBackupJsonPathStatic(path)) MAX_JSON_ENTRY_BYTES else MAX_RESOURCE_ENTRY_BYTES
            if (uncompressed > limit) {
                throw ChatboxImportException("Backup entry is too large: $path")
            }
            if (compressed > 0 && uncompressed > compressed * MAX_COMPRESSION_RATIO) {
                throw ChatboxImportException("Backup entry compression ratio is too high: $path")
            }
        }

        private fun isBackupSessionPathStatic(path: String): Boolean =
            Regex("^sessions/[^/]+/session\\.json$").containsMatchIn(path)

        private fun isBackupJsonPathStatic(path: String): Boolean =
            path == MANIFEST_PATH || path == SETTINGS_PATH || path == COPILOTS_PATH ||
                path == SESSION_SETTINGS_PATH || isBackupSessionPathStatic(path)

        private fun isBackupSession(value: MutableMap<String, Any?>): Boolean {
            val id = value["id"]
            val name = value["name"]
            return id is String && id.isNotEmpty() && name is String && value["messages"] is List<*>
        }

        /** Publishes staged resources into `upload/chatbox/` after the DB commit. */
        fun publishStagedResources(result: ChatboxBackupReadResult) {
            if (result.stagedResourceFiles.isEmpty()) return
            val destDir = File(result.resourceDestDir)
            destDir.mkdirs()
            for (staged in result.stagedResourceFiles) {
                val dest = File(destDir, staged.name)
                if (dest.isFile && dest.length() == staged.length() &&
                    sha256File(dest) == sha256File(staged)
                ) {
                    continue
                }
                val tmp = File("${dest.path}.part")
                try {
                    tmp.delete()
                    staged.copyTo(tmp, overwrite = true)
                    dest.delete()
                    if (!tmp.renameTo(dest)) {
                        throw ChatboxImportException("Unable to publish backup resource.")
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    if (e is ChatboxImportException) throw e
                    throw ChatboxImportException("Unable to publish backup resource: $e")
                }
            }
        }

        private fun sha256File(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        // ── resource-key rewriting ──────────────────────────────────────────

        private fun rewriteSession(
            session: MutableMap<String, Any?>,
            keyToUri: Map<String, String>,
            keyToText: Map<String, String>,
        ): MutableMap<String, Any?> {
            rewriteMessages(session["messages"], keyToUri, keyToText)
            val threads = session["threads"] as? List<*>
            if (threads != null) {
                for (thread in threads) {
                    @Suppress("UNCHECKED_CAST")
                    (thread as? MutableMap<String, Any?>)?.let {
                        rewriteMessages(it["messages"], keyToUri, keyToText)
                    }
                }
            }
            val forks = session["messageForksHash"] as? Map<*, *>
            if (forks != null) {
                for (fork in forks.values) {
                    @Suppress("UNCHECKED_CAST")
                    val forkMap = fork as? MutableMap<String, Any?> ?: continue
                    val lists = forkMap["lists"] as? List<*> ?: continue
                    for (list in lists) {
                        @Suppress("UNCHECKED_CAST")
                        (list as? MutableMap<String, Any?>)?.let {
                            rewriteMessages(it["messages"], keyToUri, keyToText)
                        }
                    }
                }
            }
            rewriteStorageKeyedField(session, "assistantAvatarKey", keyToUri)
            rewriteImageSourceField(session, "backgroundImage", keyToUri)
            val avatarKey = (session["assistantAvatarKey"] ?: "").toString()
            if (avatarKey.isNotEmpty() && keyToUri.containsKey(avatarKey)) {
                session["picUrl"] = keyToUri[avatarKey]
            }
            return session
        }

        private fun rewriteCopilot(
            copilot: MutableMap<String, Any?>,
            keyToUri: Map<String, String>,
        ): MutableMap<String, Any?> {
            rewriteImageSourceField(copilot, "avatar", keyToUri)
            rewriteImageSourceField(copilot, "backgroundImage", keyToUri)
            val screenshots = copilot["screenshots"] as? List<*>
            if (screenshots != null) {
                val next = mutableListOf<Any?>()
                for (item in screenshots) {
                    @Suppress("UNCHECKED_CAST")
                    val itemMap = item as? MutableMap<String, Any?>
                    if (itemMap == null) {
                        next.add(item)
                        continue
                    }
                    rewrittenImageSource(itemMap, keyToUri)?.let { next.add(it) }
                }
                copilot["screenshots"] = next
            }
            val avatar = copilot["avatar"] as? Map<*, *>
            if (avatar != null && (avatar["type"] ?: "").toString() == "url") {
                val url = (avatar["url"] ?: "").toString()
                if (url.isNotEmpty()) copilot["picUrl"] = url
            }
            return copilot
        }

        private fun rewriteMessages(
            raw: Any?,
            keyToUri: Map<String, String>,
            keyToText: Map<String, String>,
        ) {
            val messages = raw as? List<*> ?: return
            for (item in messages) {
                @Suppress("UNCHECKED_CAST")
                val message = item as? MutableMap<String, Any?> ?: continue
                val parts = message["contentParts"] as? List<*>
                if (parts != null) {
                    message["contentParts"] = parts.flatMap { part ->
                        @Suppress("UNCHECKED_CAST")
                        val partMap = part as? MutableMap<String, Any?>
                        if (partMap != null) {
                            rewriteContentPart(partMap, keyToUri, keyToText)
                        } else {
                            listOf(part)
                        }
                    }
                }
                val files = message["files"] as? List<*>
                if (files != null) {
                    for (file in files) {
                        @Suppress("UNCHECKED_CAST")
                        val fileMap = file as? MutableMap<String, Any?> ?: continue
                        fileMap.remove("localPath")
                        applyFileStorageKey(fileMap, "rawStorageKey", keyToUri)
                        applyFileStorageKey(fileMap, "storageKey", keyToUri)
                    }
                }
                val pictures = message["pictures"] as? List<*>
                if (pictures != null) {
                    message["pictures"] = pictures.flatMap { picture ->
                        @Suppress("UNCHECKED_CAST")
                        val pictureMap = picture as? MutableMap<String, Any?>
                        if (pictureMap != null) {
                            rewritePicture(pictureMap, keyToUri)
                        } else {
                            listOf(picture)
                        }
                    }
                }
                val links = message["links"] as? List<*>
                if (links != null) {
                    for (link in links) {
                        @Suppress("UNCHECKED_CAST")
                        val linkMap = link as? MutableMap<String, Any?> ?: continue
                        val key = (linkMap["storageKey"] ?: "").toString()
                        if (key.isEmpty()) continue
                        if (!keyToUri.containsKey(key)) linkMap.remove("storageKey")
                    }
                }
            }
        }

        private fun rewriteContentPart(
            part: MutableMap<String, Any?>,
            keyToUri: Map<String, String>,
            keyToText: Map<String, String>,
        ): List<MutableMap<String, Any?>> {
            val type = (part["type"] ?: "").toString()
            if (type == "image") {
                val key = (part["storageKey"] ?: "").toString()
                if (key.isEmpty()) {
                    return if ((part["url"] ?: "").toString().trim().isEmpty()) {
                        emptyList()
                    } else {
                        listOf(part)
                    }
                }
                val uri = keyToUri[key] ?: return emptyList()
                part["url"] = uri
                return listOf(part)
            }
            if (type == "tool-call") {
                val key = (part["resultStorageKey"] ?: "").toString()
                if (key.isEmpty()) return listOf(part)
                val text = keyToText[key]
                part.remove("resultStorageKey")
                if (text != null) part["result"] = text
            }
            return listOf(part)
        }

        private fun rewritePicture(
            picture: MutableMap<String, Any?>,
            keyToUri: Map<String, String>,
        ): List<MutableMap<String, Any?>> {
            val key = (picture["storageKey"] ?: "").toString()
            if (key.isEmpty()) {
                return if ((picture["url"] ?: "").toString().trim().isEmpty()) {
                    emptyList()
                } else {
                    listOf(picture)
                }
            }
            val uri = keyToUri[key]
            if (uri == null) {
                picture.remove("storageKey")
                return if ((picture["url"] ?: "").toString().trim().isEmpty()) {
                    emptyList()
                } else {
                    listOf(picture)
                }
            }
            picture["url"] = uri
            return listOf(picture)
        }

        private fun applyFileStorageKey(
            file: MutableMap<String, Any?>,
            field: String,
            keyToUri: Map<String, String>,
        ) {
            val key = (file[field] ?: "").toString()
            if (key.isEmpty()) return
            val uri = keyToUri[key]
            if (uri == null) {
                file.remove(field)
                return
            }
            if ((file["url"] ?: "").toString().trim().isEmpty()) {
                file["url"] = uri
            }
        }

        private fun rewriteStorageKeyedField(
            object_: MutableMap<String, Any?>,
            field: String,
            keyToUri: Map<String, String>,
        ) {
            val key = (object_[field] ?: "").toString()
            if (key.isEmpty()) return
            if (!keyToUri.containsKey(key)) object_.remove(field)
        }

        private fun rewriteImageSourceField(
            parent: MutableMap<String, Any?>,
            field: String,
            keyToUri: Map<String, String>,
        ) {
            val value = parent[field] as? Map<*, *> ?: return
            @Suppress("UNCHECKED_CAST")
            val source = value as? MutableMap<String, Any?> ?: return
            val rewritten = rewrittenImageSource(source, keyToUri)
            if (rewritten == null) {
                parent.remove(field)
            } else {
                parent[field] = rewritten
            }
        }

        private fun rewrittenImageSource(
            source: MutableMap<String, Any?>,
            keyToUri: Map<String, String>,
        ): MutableMap<String, Any?>? {
            if ((source["type"] ?: "").toString() != "storage-key") return source
            val key = (source["storageKey"] ?: "").toString()
            val uri = keyToUri[key] ?: return null
            return linkedMapOf("type" to "url", "url" to uri)
        }

        private fun sessionMetaForList(
            descriptor: Map<String, Any?>,
            session: MutableMap<String, Any?>,
            keyToUri: Map<String, String>,
        ): MutableMap<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val metaRaw = descriptor["meta"] as? MutableMap<String, Any?>
            val meta: MutableMap<String, Any?> = metaRaw ?: linkedMapOf(
                "id" to descriptor["id"],
                "name" to session["name"],
            )
            val avatarKey = (meta["assistantAvatarKey"] ?: "").toString()
            if (avatarKey.isNotEmpty() && keyToUri.containsKey(avatarKey)) {
                meta["picUrl"] = keyToUri[avatarKey]
            } else {
                val sessionPic = (session["picUrl"] ?: "").toString().trim()
                if (sessionPic.isNotEmpty() && (meta["picUrl"] ?: "").toString().trim().isEmpty()) {
                    meta["picUrl"] = sessionPic
                }
            }
            return meta
        }

        private fun resourceFileName(resource: Map<String, Any?>): String {
            val id = safeToken((resource["id"] ?: "resource").toString(), "resource")
            val checksumRaw = resource["checksum"] as? Map<*, *>
            val digest = checksumRaw?.let { safeToken((it["value"] ?: "").toString(), id) } ?: id
            val path = (resource["path"] ?: "").toString()
            var ext = path.substringAfterLast('.', "")
            if (ext.isEmpty() || path.endsWith('/')) {
                ext = extensionForMime((resource["mimeType"] ?: "").toString())
            } else {
                ext = ".$ext"
            }
            if (ext.isNotEmpty() && !ext.startsWith(".")) ext = ".$ext"
            val filename = (resource["filename"] ?: "").toString()
            val fromName = if (filename.isEmpty()) "" else "." + filename.substringAfterLast('.', "")
            if (ext.isEmpty() && fromName.isNotEmpty()) ext = fromName
            return "$id-$digest$ext"
        }

        private fun safeToken(raw: String, fallback: String): String {
            val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return fallback
            return cleaned
        }

        private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
            "image/png" -> ".png"
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/svg+xml" -> ".svg"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            "application/json" -> ".json"
            else -> ""
        }
    }
}

