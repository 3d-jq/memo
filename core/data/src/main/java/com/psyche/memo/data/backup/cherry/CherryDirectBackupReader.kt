package com.psyche.memo.data.backup.cherry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets

/**
 * Reader for Cherry Studio "direct backup" archives — 1:1 port of
 * `cherry_direct_backup_reader.dart`.
 *
 * A direct backup is a ZIP whose `metadata.json` carries a format version:
 * v6 stores live data in LevelDB form (`Local Storage/leveldb` .log/.ldb
 * entries and `IndexedDB` .indexeddb.leveldb entries inside the archive),
 * which this reader reconstructs into the same JSON shape the plain-export
 * importer consumes (`localStorage` + `indexedDB` roots). v7+ stores live
 * data in SQLite and is refused with
 * [CherryUnsupportedBackupVersionException].
 *
 * The Dart original walks an `Archive`; here the caller hands an entry map
 * (name → content bytes) so both zip-sourced and hand-built fixtures share one
 * code path. Dynamic data inside entries is represented as Kotlin trees
 * (`MutableMap<String, Any?>` / `MutableList<Any?>` / `String` / `Long` /
 * `Double` / `Boolean` / null) exactly like Dart's `Map<String, dynamic>`, so
 * the reconstruction logic reads the same.
 */
class CherryUnsupportedBackupVersionException(
    val version: Int,
    val debugZipJsonProbeDecodeCount: Int = 0,
) : Exception("CherryUnsupportedBackupVersionException(version: $version)")

/** True when the bytes carry the local-file-signature of a ZIP. */
internal fun looksLikeZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
        (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())

/** True when the bytes look like GZIP (`1f 8b`). */
internal fun looksLikeGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

/**
 * Recursively turns a [JsonElement] into the dynamic tree the importer logic
 * mutates (Dart's `jsonDecode` product).
 */
internal fun anyFromJson(element: JsonElement?): Any? = when (element) {
    null, is JsonNull -> null
    is JsonObject -> {
        val map = LinkedHashMap<String, Any?>()
        element.forEach { (key, value) -> map[key] = anyFromJson(value) }
        map
    }
    is JsonArray -> {
        val list = ArrayList<Any?>(element.size)
        element.forEach { list.add(anyFromJson(it)) }
        list
    }
    is JsonPrimitive ->
        if (element.isString) element.content
        else element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
}

/** Inverse of [anyFromJson]. */
internal fun jsonFromAny(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> {
        val map = LinkedHashMap<String, JsonElement>()
        value.forEach { (k, v) -> map[k.toString()] = jsonFromAny(v) }
        JsonObject(map)
    }
    is List<*> -> JsonArray(value.map { jsonFromAny(it) })
    else -> JsonPrimitive(value.toString())
}

object CherryDirectBackupReader {

    private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

    /**
     * Reads root `metadata.json` when present. Throws
     * [CherryUnsupportedBackupVersionException] for version >= 7. Returns null
     * when the entry is missing or unreadable — legacy archives without
     * metadata must keep falling through to the JSON entry scan.
     */
    fun readMetadataOrThrowIfUnsupported(entries: Map<String, ByteArray>): Map<String, Any?>? {
        val metadata = readJsonObjectEntry(entries, "metadata.json") ?: return null
        val version = (metadata["version"] as? Number)?.toInt()
        if (version != null && version >= 7) throw CherryUnsupportedBackupVersionException(version)
        return metadata
    }

    /**
     * Reconstructs the `localStorage`/`indexedDB` roots from a v6 direct
     * backup; null when the archive is not one (the caller falls through).
     */
    fun readArchive(entries: Map<String, ByteArray>): Map<String, Any?>? {
        val metadata = readMetadataOrThrowIfUnsupported(entries)
        val version = (metadata?.get("version") as? Number)?.toInt() ?: return null
        if (version < 6) return null

        val persist = readPersistState(entries)
        if (persist.isNullOrEmpty()) return null

        val indexedDb = readIndexedDb(entries)
        return linkedMapOf(
            "version" to version,
            "localStorage" to linkedMapOf<String, Any?>("persist:cherry-studio" to persist),
            "indexedDB" to indexedDb,
        )
    }

    private fun readJsonObjectEntry(entries: Map<String, ByteArray>, name: String): Map<String, Any?>? {
        val bytes = entries[name] ?: return null
        return try {
            anyFromJson(JSON.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))) as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }

    private fun readPersistState(entries: Map<String, ByteArray>): String? {
        var best: String? = null
        for ((name, bytes) in entries) {
            val normalized = normalizeEntryPath(name).lowercase()
            if (!normalized.startsWith("local storage/leveldb/")) continue
            if (!normalized.endsWith(".ldb") && !normalized.endsWith(".log")) continue

            val candidates = if (normalized.endsWith(".log")) {
                extractPersistFromLevelDbLog(bytes).asSequence() + extractPersistCandidates(bytes).asSequence()
            } else {
                extractPersistCandidates(bytes).asSequence()
            }
            for (candidate in candidates) {
                if (best == null || candidate.length > best.length) best = candidate
            }
        }
        return best
    }

    private fun readIndexedDb(entries: Map<String, ByteArray>): Map<String, Any?> {
        val topicsById = LinkedHashMap<String, MutableMap<String, Any?>>()
        val blocksById = LinkedHashMap<String, MutableMap<String, Any?>>()
        val filesById = LinkedHashMap<String, MutableMap<String, Any?>>()
        val messagesByTopicId = LinkedHashMap<String, LinkedHashMap<String, MutableMap<String, Any?>>>()

        for ((name, bytes) in entries) {
            val normalized = normalizeEntryPath(name).lowercase()
            if (!normalized.startsWith("indexeddb/") || !normalized.contains(".indexeddb.leveldb/")) continue
            if (!normalized.endsWith(".ldb") && !normalized.endsWith(".log")) continue

            for (payload in readLevelDbPayloads(bytes, isLog = normalized.endsWith(".log"))) {
                for (value in V8ValueScanner.scan(payload)) {
                    @Suppress("UNCHECKED_CAST")
                    val object_ = value as? MutableMap<String, Any?> ?: continue
                    val id = (object_["id"] ?: "").toString()
                    if (id.isEmpty()) continue

                    if (object_["messages"] is List<*>) {
                        putBetterTopic(topicsById, id, object_)
                        continue
                    }
                    if (looksLikeMessage(object_)) {
                        val topicId = (object_["topicId"] ?: "").toString()
                        val messagesById = messagesByTopicId.getOrPut(topicId) { LinkedHashMap() }
                        putBetterMessage(messagesById, id, object_)
                        continue
                    }
                    if ((object_["messageId"] ?: "").toString().isNotEmpty() &&
                        (object_["type"] ?: "").toString().isNotEmpty()
                    ) {
                        putBetterBlock(blocksById, id, object_)
                        continue
                    }
                    if (looksLikeFileMetadata(object_)) {
                        filesById[id] = object_
                    }
                }
            }
        }

        for ((topicId, messagesById) in messagesByTopicId) {
            val standaloneMessages = messagesById.values.sortedWith(compareMessagesByDate)
            val existingTopic = topicsById[topicId]
            if (existingTopic == null) {
                topicsById[topicId] = linkedMapOf(
                    "id" to topicId,
                    "messages" to standaloneMessages,
                )
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val existingMessages = (existingTopic["messages"] as? List<*>)
                ?.filterIsInstance<MutableMap<String, Any?>>()
                ?.toMutableList()
                ?: mutableListOf()
            val existingIds = existingMessages
                .mapNotNull { message -> (message["id"] ?: "").toString().takeIf { it.isNotEmpty() } }
                .toSet()
            for (message in standaloneMessages) {
                val id = (message["id"] ?: "").toString()
                if (id.isNotEmpty() && id !in existingIds) existingMessages.add(message)
            }
            existingTopic["messages"] = existingMessages
        }

        val referencedMessageIds = HashSet<String>()
        val referencedBlockIds = HashSet<String>()
        for (topic in topicsById.values) {
            val messages = topic["messages"] as? List<*> ?: continue
            for (raw in messages) {
                @Suppress("UNCHECKED_CAST")
                val message = raw as? MutableMap<String, Any?> ?: continue
                val messageId = (message["id"] ?: "").toString()
                if (messageId.isNotEmpty()) referencedMessageIds.add(messageId)
                val blocks = message["blocks"] as? List<*> ?: continue
                for (blockId in blocks) {
                    val id = (blockId ?: "").toString()
                    if (id.isNotEmpty()) referencedBlockIds.add(id)
                }
            }
        }

        val filteredBlocks = blocksById.values.filter { block ->
            val id = (block["id"] ?: "").toString()
            val messageId = (block["messageId"] ?: "").toString()
            id in referencedBlockIds || messageId in referencedMessageIds
        }

        return linkedMapOf(
            "topics" to topicsById.values.toList(),
            "message_blocks" to filteredBlocks,
            "files" to filesById.values.toList(),
        )
    }

    private fun putBetterTopic(
        topicsById: MutableMap<String, MutableMap<String, Any?>>,
        id: String,
        candidate: MutableMap<String, Any?>,
    ) {
        val current = topicsById[id]
        if (current == null || topicScore(candidate) >= topicScore(current)) topicsById[id] = candidate
    }

    private fun topicScore(topic: Map<String, Any?>): Long {
        val messages = topic["messages"] as? List<*> ?: return 0
        var latest = 0L
        for (raw in messages) {
            @Suppress("UNCHECKED_CAST")
            val message = raw as? Map<String, Any?> ?: continue
            val createdAt = tryParseDate(
                (message["updatedAt"] ?: message["createdAt"] ?: "").toString(),
            ) ?: continue
            if (createdAt > latest) latest = createdAt
        }
        return messages.length() * 10000000000000L + latest
    }

    private fun putBetterBlock(
        blocksById: MutableMap<String, MutableMap<String, Any?>>,
        id: String,
        candidate: MutableMap<String, Any?>,
    ) {
        val current = blocksById[id]
        if (current == null || blockScore(candidate) >= blockScore(current)) blocksById[id] = candidate
    }

    private fun blockScore(block: Map<String, Any?>): Long {
        val statusScore = when ((block["status"] ?: "").toString()) {
            "success" -> 4L
            "paused" -> 3L
            "streaming" -> 2L
            "processing", "pending" -> 1L
            else -> 0L
        }
        val date = tryParseDate((block["updatedAt"] ?: block["createdAt"] ?: "").toString()) ?: 0L
        val contentScore = (block["content"] ?: "").toString().length
        return statusScore * 1000000000000000L + date * 1000L + contentScore
    }

    private fun looksLikeFileMetadata(object_: Map<String, Any?>): Boolean {
        val hasName = object_.containsKey("name") ||
            object_.containsKey("origin_name") ||
            object_.containsKey("path")
        val hasFileShape = object_.containsKey("ext") ||
            object_.containsKey("type") ||
            object_.containsKey("size") ||
            object_.containsKey("created_at")
        return hasName && hasFileShape
    }

    private fun looksLikeMessage(object_: Map<String, Any?>): Boolean {
        val topicId = (object_["topicId"] ?: "").toString()
        val role = (object_["role"] ?: "").toString()
        return topicId.isNotEmpty() &&
            (role == "user" || role == "assistant" || role == "system")
    }

    private fun putBetterMessage(
        messagesById: MutableMap<String, MutableMap<String, Any?>>,
        id: String,
        candidate: MutableMap<String, Any?>,
    ) {
        val current = messagesById[id]
        if (current == null || messageScore(candidate) >= messageScore(current)) messagesById[id] = candidate
    }

    private fun messageScore(message: Map<String, Any?>): Long {
        val date = tryParseDate((message["updatedAt"] ?: message["createdAt"] ?: "").toString()) ?: 0L
        val blocks = message["blocks"] as? List<*>
        val blockScore = blocks?.size ?: 0
        val contentScore = (message["content"] ?: "").toString().length
        return date * 1000L + blockScore * 100L + contentScore
    }

    private val compareMessagesByDate = Comparator<MutableMap<String, Any?>> { a, b ->
        val aDate = tryParseDate((a["createdAt"] ?: "").toString()) ?: 0L
        val bDate = tryParseDate((b["createdAt"] ?: "").toString()) ?: 0L
        aDate.compareTo(bDate)
    }

    /** `DateTime.tryParse` equivalent for the ISO timestamps Cherry writes. */
    internal fun tryParseDate(text: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching {
            java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    // ── LevelDB extraction ──────────────────────────────────────────────────

    private fun extractPersistFromLevelDbLog(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (entry in extractLevelDbLogWriteBatchEntries(bytes)) {
            if (containsAscii(entry.first, "persist:cherry-studio")) {
                val decoded = decodeLocalStorageValue(entry.second)
                if (isValidPersistJson(decoded)) out.add(decoded)
            }
        }
        return out
    }

    private fun readLevelDbPayloads(bytes: ByteArray, isLog: Boolean): List<ByteArray> {
        val payloads = if (isLog) extractLevelDbLogValues(bytes) else extractLevelDbTableValues(bytes)
        // Keep the raw scan as a compatibility fallback for unusual LevelDB
        // files or partially-copied backups where structured parsing fails.
        return payloads.ifEmpty { listOf(bytes) }
    }

    private fun extractLevelDbLogValues(bytes: ByteArray): List<ByteArray> =
        extractLevelDbLogWriteBatchEntries(bytes).map { it.second }

    private fun extractLevelDbLogWriteBatchEntries(bytes: ByteArray): List<Pair<ByteArray, ByteArray>> {
        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        for (payload in extractLevelDbLogWriteBatches(bytes)) {
            out.addAll(extractWriteBatchEntries(payload))
        }
        return out
    }

    private fun extractLevelDbLogWriteBatches(bytes: ByteArray): List<ByteArray> {
        val blockSize = 32768
        var offset = 0
        var fragmented: ByteArray? = null
        val out = mutableListOf<ByteArray>()

        while (offset + 7 <= bytes.size) {
            val remainingInBlock = blockSize - (offset % blockSize)
            if (remainingInBlock < 7) {
                offset += remainingInBlock
                continue
            }
            val length = (bytes[offset + 4].toInt() and 0xff) or ((bytes[offset + 5].toInt() and 0xff) shl 8)
            val type = bytes[offset + 6].toInt()
            offset += 7

            if (length == 0 && type == 0) {
                offset += blockSize - (offset % blockSize)
                continue
            }
            if (offset + length > bytes.size) break

            val payload = bytes.copyOfRange(offset, offset + length)
            offset += length

            when (type) {
                1 -> {
                    fragmented = null
                    out.add(payload)
                }
                2 -> fragmented = payload.copyOf()
                3 -> fragmented = fragmented?.let { it + payload }
                4 -> {
                    val combined = fragmented
                    if (combined != null) out.add(combined + payload)
                    fragmented = null
                }
            }
        }
        return out
    }

    private fun extractWriteBatchEntries(payload: ByteArray): List<Pair<ByteArray, ByteArray>> {
        if (payload.size < 12) return emptyList()
        var offset = 12 // 8-byte sequence number + 4-byte record count.
        val count = (payload[8].toInt() and 0xff) or
            ((payload[9].toInt() and 0xff) shl 8) or
            ((payload[10].toInt() and 0xff) shl 16) or
            ((payload[11].toInt() and 0xff) shl 24)

        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        var i = 0
        while (i < count && offset < payload.size) {
            i++
            val tag = payload[offset++].toInt()
            if (tag == 0) {
                val key = readLengthPrefixedBytes(payload, offset) ?: break
                offset = key.second
                continue
            }
            if (tag != 1) break

            val key = readLengthPrefixedBytes(payload, offset) ?: break
            offset = key.second
            val value = readLengthPrefixedBytes(payload, offset) ?: break
            offset = value.second
            out.add(key.first to value.first)
        }
        return out
    }

    private fun extractLevelDbTableValues(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < 48 || !hasLevelDbTableMagic(bytes)) return emptyList()

        val footerOffset = bytes.size - 48
        val metaIndexHandle = readLevelDbBlockHandle(bytes, footerOffset) ?: return emptyList()
        val indexHandle = readLevelDbBlockHandle(bytes, metaIndexHandle.next) ?: return emptyList()
        val indexBlock = readLevelDbPhysicalBlock(bytes, indexHandle.offset, indexHandle.size)
            ?: return emptyList()

        val out = mutableListOf<ByteArray>()
        for (indexEntry in readLevelDbBlockEntries(indexBlock)) {
            val dataHandle = readLevelDbBlockHandle(indexEntry.second, 0) ?: continue
            val dataBlock = readLevelDbPhysicalBlock(bytes, dataHandle.offset, dataHandle.size) ?: continue
            for (dataEntry in readLevelDbBlockEntries(dataBlock)) {
                out.add(dataEntry.second)
            }
        }
        return out
    }

    private fun hasLevelDbTableMagic(bytes: ByteArray): Boolean {
        val magic = intArrayOf(0x57, 0xfb, 0x80, 0x8b, 0x24, 0x75, 0x47, 0xdb)
        val offset = bytes.size - magic.size
        if (offset < 0) return false
        for (i in magic.indices) {
            if (bytes[offset + i].toInt() != magic[i]) return false
        }
        return true
    }

    private class BlockHandle(val offset: Long, val size: Long, val next: Int)

    private fun readLevelDbBlockHandle(bytes: ByteArray, offset: Int): BlockHandle? {
        val blockOffset = readVarint64(bytes, offset) ?: return null
        val blockSize = readVarint64(bytes, blockOffset.second) ?: return null
        return BlockHandle(blockOffset.first, blockSize.first, blockSize.second)
    }

    private fun readLevelDbPhysicalBlock(bytes: ByteArray, offset: Long, size: Long): ByteArray? {
        if (offset < 0 || size < 0 || offset + size + 5 > bytes.size) return null
        val start = offset.toInt()
        val end = (offset + size).toInt()
        val payload = bytes.copyOfRange(start, end)
        return when (bytes[end].toInt()) {
            0 -> payload
            1 -> decodeSnappyBlock(payload)
            else -> null
        }
    }

    private fun readLevelDbBlockEntries(block: ByteArray): List<Pair<ByteArray, ByteArray>> {
        if (block.size < 8) return emptyList()
        val restartCount = readFixed32(block, block.size - 4)
        val restartOffset = block.size - 4 - restartCount * 4
        if (restartCount <= 0 || restartOffset < 0 || restartOffset > block.size) return emptyList()

        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        var offset = 0
        var previousKey = ByteArray(0)
        while (offset < restartOffset) {
            val shared = readVarint32(block, offset) ?: return out
            offset = shared.second
            val nonShared = readVarint32(block, offset) ?: return out
            offset = nonShared.second
            val valueLength = readVarint32(block, offset) ?: return out
            offset = valueLength.second

            val keyEnd = offset + nonShared.first
            val valueEnd = keyEnd + valueLength.first
            if (shared.first > previousKey.size || valueEnd > restartOffset) return out

            val key = previousKey.take(shared.first).toByteArray() +
                block.copyOfRange(offset, keyEnd)
            val value = block.copyOfRange(keyEnd, valueEnd)
            previousKey = key
            offset = valueEnd
            out.add(key to value)
        }
        return out
    }

    private fun readFixed32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun decodeSnappyBlock(bytes: ByteArray): ByteArray? {
        val decodedLength = readVarint32(bytes, 0) ?: return null
        var offset = decodedLength.second
        val out = mutableListOf<Byte>()

        while (offset < bytes.size) {
            val tag = bytes[offset++].toInt() and 0xff
            val type = tag and 0x03
            if (type == 0) {
                var length = tag shr 2
                if (length < 60) {
                    length += 1
                } else {
                    val bytesForLength = length - 59
                    if (offset + bytesForLength > bytes.size) return null
                    var rawLength = 0
                    for (i in 0 until bytesForLength) {
                        rawLength = rawLength or ((bytes[offset++].toInt() and 0xff) shl (8 * i))
                    }
                    length = rawLength + 1
                }
                if (offset + length > bytes.size) return null
                out.addAll(bytes.copyOfRange(offset, offset + length).toList())
                offset += length
                continue
            }

            val length: Int
            val copyOffset: Int
            when (type) {
                1 -> {
                    if (offset >= bytes.size) return null
                    length = ((tag shr 2) and 0x07) + 4
                    copyOffset = ((tag and 0xe0) shl 3) or (bytes[offset++].toInt() and 0xff)
                }
                2 -> {
                    if (offset + 2 > bytes.size) return null
                    length = (tag shr 2) + 1
                    copyOffset = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
                    offset += 2
                }
                else -> {
                    if (offset + 4 > bytes.size) return null
                    length = (tag shr 2) + 1
                    copyOffset = (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                        ((bytes[offset + 3].toInt() and 0xff) shl 24)
                    offset += 4
                }
            }

            if (copyOffset <= 0 || copyOffset > out.size) return null
            for (i in 0 until length) {
                out.add(out[out.size - copyOffset])
            }
        }

        if (out.size != decodedLength.first) return null
        return out.toByteArray()
    }

    private fun readLengthPrefixedBytes(bytes: ByteArray, offset: Int): Pair<ByteArray, Int>? {
        val length = readVarint32(bytes, offset) ?: return null
        var next = length.second
        val end = next + length.first
        if (end > bytes.size) return null
        return bytes.copyOfRange(next, end) to end
    }

    private fun readVarint32(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var cursor = offset
        while (cursor < bytes.size && shift <= 28) {
            val byte = bytes[cursor++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if ((byte and 0x80) == 0) return result to cursor
            shift += 7
        }
        return null
    }

    private fun readVarint64(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var cursor = offset
        while (cursor < bytes.size && shift <= 63) {
            val byte = bytes[cursor++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if ((byte and 0x80) == 0) return result to cursor
            shift += 7
        }
        return null
    }

    private fun containsAscii(value: ByteArray, needle: String): Boolean =
        indexOfBytes(value, needle.toByteArray(StandardCharsets.US_ASCII), 0) >= 0

    private fun decodeLocalStorageValue(bytes: ByteArray): String {
        if (bytes.isNotEmpty()) {
            if (bytes[0].toInt() == 0 && (bytes.size - 1) % 2 == 0) {
                val utf16 = decodeUtf16Le(bytes.copyOfRange(1, bytes.size))
                if (isValidPersistJson(utf16)) return utf16
            }
            if (bytes[0].toInt() == 1) {
                val utf8Text = String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8)
                if (isValidPersistJson(utf8Text)) return utf8Text
            }
        }
        val utf16 = decodeUtf16Le(bytes)
        if (isValidPersistJson(utf16)) return utf16
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun decodeUtf16Le(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            sb.append((((bytes[i].toInt() and 0xff)) or ((bytes[i + 1].toInt() and 0xff) shl 8)).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun extractPersistCandidates(bytes: ByteArray): List<String> {
        val key = "persist:cherry-studio"
        val keyBytes = key.toByteArray(StandardCharsets.US_ASCII)
        val out = mutableListOf<String>()
        var index = 0
        while (index <= bytes.size - keyBytes.size) {
            val found = indexOfBytes(bytes, keyBytes, index)
            if (found < 0) break
            val searchStart = found + keyBytes.size

            out.addAll(extractUtf16JsonObjects(bytes, searchStart).filter { isValidPersistJson(it) })
            out.addAll(extractUtf8JsonObjects(bytes, searchStart).filter { isValidPersistJson(it) })

            index = searchStart
        }
        return out
    }

    private fun isValidPersistJson(value: String): Boolean = try {
        val decoded = JSON.parseToJsonElement(value)
        decoded is JsonObject && (decoded.containsKey("assistants") || decoded.containsKey("llm"))
    } catch (_: Exception) {
        false
    }

    private fun extractUtf16JsonObjects(bytes: ByteArray, start: Int): List<String> {
        val out = mutableListOf<String>()
        var i = start
        while (i + 1 < bytes.size) {
            if (bytes[i] == 0x7b.toByte() && bytes[i + 1] == 0x00.toByte()) {
                readBalancedJson { offset ->
                    val byteOffset = i + offset * 2
                    if (byteOffset + 1 >= bytes.size) return@readBalancedJson null
                    (bytes[byteOffset].toInt() and 0xff) or ((bytes[byteOffset + 1].toInt() and 0xff) shl 8)
                }?.let { out.add(it) }
            }
            i++
        }
        return out
    }

    private fun extractUtf8JsonObjects(bytes: ByteArray, start: Int): List<String> {
        val out = mutableListOf<String>()
        for (i in start until bytes.size) {
            if (bytes[i] != 0x7b.toByte()) continue
            readBalancedJson { offset ->
                val byteOffset = i + offset
                if (byteOffset >= bytes.size) return@readBalancedJson null
                bytes[byteOffset].toInt() and 0xff
            }?.let { out.add(it) }
        }
        return out
    }

    /** Reads one balanced `{...}`/`[...]` JSON span as a code-unit string. */
    private fun readBalancedJson(readUnit: (Int) -> Int?): String? {
        val buffer = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false

        var offset = 0
        while (true) {
            val unit = readUnit(offset) ?: return null
            offset++
            buffer.append(unit.toChar())

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (unit == 0x5c) {
                    escaped = true
                } else if (unit == 0x22) {
                    inString = false
                }
                continue
            }

            when (unit) {
                0x22 -> inString = true
                0x7b, 0x5b -> depth++
                0x7d, 0x5d -> {
                    depth--
                    if (depth == 0) return buffer.toString()
                    if (depth < 0) return null
                }
            }
        }
    }

    private fun indexOfBytes(bytes: ByteArray, pattern: ByteArray, start: Int): Int {
        if (pattern.isEmpty()) return start
        var i = start
        while (i <= bytes.size - pattern.size) {
            var matched = true
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }
}

private fun List<*>.length(): Long = size.toLong()

/** ZIP entry names and on-disk paths may use `\` (Windows) or `/`. */
internal fun normalizeEntryPath(name: String): String = name.replace('\\', '/')

/**
 * Finds V8 serialization values inside raw LevelDB bytes
 * (`_V8ValueScanner`): the `0xff` version header marks the start of every
 * serialized value; wrong offsets are expected while scanning, so each probe
 * is individually guarded.
 */
internal object V8ValueScanner {
    fun scan(bytes: ByteArray): List<Any?> {
        val values = mutableListOf<Any?>()
        var i = 0
        while (i + 1 < bytes.size) {
            if (bytes[i] != 0xff.toByte()) {
                i++
                continue
            }
            val versionByte = bytes[i + 1].toInt() and 0xff
            if ((versionByte and 0x80) != 0 || versionByte < 0x0d || versionByte > 0x20) {
                i++
                continue
            }
            try {
                val reader = V8ValueReader(bytes, i)
                values.add(reader.readValue())
            } catch (_: Exception) {
                // Wrong offsets are expected while scanning raw LevelDB bytes.
            }
            i++
        }
        return values
    }
}

/** `_V8ValueReader` — the subset of the V8 serialization format Cherry emits. */
private class V8ValueReader(private val bytes: ByteArray, offset: Int) {

    private var offset: Int = offset
    private val objects = mutableListOf<Any>()

    fun readValue(): Any? {
        val tag = readByte()
        return when (tag) {
            0x00 -> readValue()
            0xff -> {
                readVarint()
                readValue()
            }
            0x5f, 0x30 -> null
            0x54 -> true
            0x46 -> false
            0x49 -> decodeZigZag(readVarint())
            0x55 -> readVarint()
            0x4e -> readFloat64()
            0x22 -> readOneByteString()
            0x53 -> readUtf8String()
            0x63 -> readTwoByteString()
            0x6f -> readObject()
            0x41 -> readDenseArray()
            0x61 -> readSparseArray()
            0x5e -> readObjectReference()
            0x44 -> java.time.Instant.ofEpochMilli(readFloat64().roundToLong())
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
            else -> throw IllegalStateException("Unsupported V8 value tag: $tag")
        }
    }

    private fun readObject(): LinkedHashMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        objects.add(result)

        while (peekByte() != 0x7b) {
            val key = readValue()
            val value = readValue()
            if (key != null) result[key.toString()] = value
        }
        readByte()
        readVarint()
        return result
    }

    private fun readDenseArray(): ArrayList<Any?> {
        val length = readCollectionLength().toInt()
        val result = ArrayList<Any?>(length)
        objects.add(result)

        for (i in 0 until length) {
            result.add(readValue())
        }

        while (peekByte() != 0x24) {
            val key = readValue()
            val value = readValue()
            if (key is Long && key >= 0) {
                while (result.size <= key) result.add(null)
                result[key.toInt()] = value
            } else if (key is Int && key >= 0) {
                while (result.size <= key) result.add(null)
                result[key] = value
            }
        }
        readByte()
        readVarint()
        readVarint()
        return result
    }

    private fun readSparseArray(): ArrayList<Any?> {
        val length = readCollectionLength().toInt()
        val result = ArrayList<Any?>(length)
        objects.add(result)

        while (peekByte() != 0x40) {
            val key = readValue()
            val value = readValue()
            if (key is Long && key >= 0) {
                while (result.size <= key) result.add(null)
                result[key.toInt()] = value
            } else if (key is Int && key >= 0) {
                while (result.size <= key) result.add(null)
                result[key] = value
            }
        }
        readByte()
        readVarint()
        readVarint()
        return result
    }

    private fun readObjectReference(): Any {
        val id = readVarint()
        if (id < 0 || id >= objects.size) {
            throw IllegalStateException("Invalid V8 object reference: $id")
        }
        return objects[id.toInt()] ?: throw IllegalStateException("Invalid V8 object reference: $id")
    }

    private fun readOneByteString(): String {
        val length = readStringLength()
        val start = offset
        offset += length
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = bytes[start + i]
        return String(out, StandardCharsets.ISO_8859_1)
    }

    private fun readUtf8String(): String {
        val length = readStringLength()
        val start = offset
        offset += length
        return String(bytes, start, length, StandardCharsets.UTF_8)
    }

    private fun readTwoByteString(): String {
        val byteLength = readStringLength()
        if (byteLength % 2 != 0) throw IllegalStateException("Invalid V8 two-byte string length")
        val sb = StringBuilder(byteLength / 2)
        for (i in 0 until byteLength / 2) {
            sb.append(
                ((bytes[offset + i * 2].toInt() and 0xff) or
                    ((bytes[offset + i * 2 + 1].toInt() and 0xff) shl 8)).toChar(),
            )
        }
        offset += byteLength
        return sb.toString()
    }

    private fun readCollectionLength(): Long {
        val length = readVarint()
        if (length > 200000) throw IllegalStateException("V8 collection is too large: $length")
        return length
    }

    private fun readStringLength(): Int {
        val length = readVarint()
        ensureAvailable(length)
        return length.toInt()
    }

    private fun decodeZigZag(value: Long): Long = (value shr 1) xor -(value and 1)

    private fun readFloat64(): Double {
        ensureAvailable(8)
        val bytesCopy = bytes.copyOfRange(offset, offset + 8)
        offset += 8
        var bits = 0L
        for (i in 7 downTo 0) {
            bits = (bits shl 8) or (bytesCopy[i].toLong() and 0xff)
        }
        return Double.fromBits(bits)
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = readByte().toLong() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if ((byte and 0x80) == 0L) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("Invalid V8 varint")
        }
    }

    private fun peekByte(): Int {
        ensureAvailable(1)
        return bytes[offset].toInt() and 0xff
    }

    private fun readByte(): Int {
        ensureAvailable(1)
        return bytes[offset++].toInt() and 0xff
    }

    private fun ensureAvailable(length: Long) {
        if (length < 0 || offset + length > bytes.size) {
            throw IllegalStateException("Unexpected end of V8 value")
        }
    }
}

private fun Double.roundToLong(): Long = java.lang.Math.round(this)
