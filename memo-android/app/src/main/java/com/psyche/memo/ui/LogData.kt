package com.psyche.memo.ui

import com.psyche.memo.common.logging.LogPayloadElider
import com.psyche.memo.common.logging.LogPayloadElision
import com.psyche.memo.common.logging.LogPayloadRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1:1 port of the log data layer:
 *   lib/features/settings/logs/request_log_parser.dart
 *   lib/core/services/logging/context_log_models.dart (snapshot model only —
 *     the assembly-side tag helpers belong to the chat pipeline batch)
 *   lib/core/services/logging/context_log_tail_reader.dart
 *
 * `log_payload_elider.dart` lives in `com.psyche.memo.common.logging` because
 * both the writer (RequestLogger) and this reader need it.
 */

// —— request_log_parser.dart ————————————————————————————————————————————

class RequestLogEntry(
    val id: Int,
    val sequence: Int,
) {
    var startedAt: Long? = null
    var lastEventAt: Long? = null
    var method: String? = null
    var rawUrl: String? = null
    var requestHeaders: JsonObject? = null
    var requestBody: String? = null
    var statusCode: Int? = null
    var responseHeaders: JsonObject? = null
    var responseBody: String? = null
    var requestBodyTruncated: Int = 0
    var responseBodyTruncated: Int = 0
    val attachments = mutableListOf<LogPayloadRef>()
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    val hasError: Boolean
        get() = errors.isNotEmpty() || (statusCode ?: 0) >= 400
    val hasWarning: Boolean
        get() = warnings.isNotEmpty() || statusCode?.let { it in 300..399 } == true

    val durationMs: Long?
        get() {
            val s = startedAt ?: return null
            val e = lastEventAt ?: return null
            return e - s
        }
}

object RequestLogParser {
    private const val DEFAULT_MAX_BODY_CHARS = 256 * 1024

    private val TS_RE = Regex(
        "^\\[(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\]\\s+(.*)$",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val REQ_START_RE = Regex("^\\[REQ (\\d+)]\\s+([A-Z]+)\\s+(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val REQ_HEADERS_RE = Regex("^\\[REQ (\\d+)]\\s+headers=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val REQ_BODY_RE = Regex("^\\[REQ (\\d+)]\\s+body=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val RES_STATUS_RE = Regex("^\\[RES (\\d+)]\\s+status=(\\d+)\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val RES_HEADERS_RE = Regex("^\\[RES (\\d+)]\\s+headers=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val RES_BODY_RE = Regex("^\\[RES (\\d+)]\\s+body=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val RES_CHUNK_RE = Regex("^\\[RES (\\d+)]\\s+chunk=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val RES_DONE_RE = Regex("^\\[RES (\\d+)]\\s+done\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val RES_ERR_RE = Regex("^\\[RES (\\d+)]\\s+error=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val RES_DIO_ERR_RE = Regex("^\\[RES (\\d+)]\\s+dio_error=(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val ESCAPE_RE = Regex("\\\\[nrt\\\\]")

    private class LogRecord(val ts: Long?, var message: String)

    fun parse(content: String, elide: Boolean = true, maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS): List<RequestLogEntry> {
        val records = toRecords(content)
        val entries = mutableListOf<RequestLogEntry>()
        val currentIndexById = HashMap<Int, Int>()
        val chunkBuffers = HashMap<Int, StringBuilder>()
        var seq = 0

        fun prepareBody(body: String, entry: RequestLogEntry): Pair<String, Int> {
            val result = LogPayloadElider.process(body, rewrite = elide)
            entry.attachments.addAll(result.refs)
            val text = result.text
            if (maxBodyChars > 0 && text.length > maxBodyChars) {
                return text.substring(0, maxBodyChars) to (text.length - maxBodyChars)
            }
            return text to 0
        }

        fun ensureEntry(id: Int): RequestLogEntry {
            val idx = currentIndexById[id]
            if (idx != null) return entries[idx]
            val e = RequestLogEntry(id = id, sequence = ++seq)
            entries.add(e)
            currentIndexById[id] = entries.size - 1
            return e
        }

        fun touch(e: RequestLogEntry, ts: Long?) {
            e.lastEventAt = ts ?: return
            if (e.startedAt == null) e.startedAt = ts
        }

        val json = Json { ignoreUnknownKeys = true }

        fun decodeJsonMap(text: String): JsonObject? = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull()

        outer@ for (record in records) {
            val msg = record.message
            val ts = record.ts

            if (REQ_START_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = RequestLogEntry(id = id, sequence = ++seq)
                    e.startedAt = ts
                    e.lastEventAt = ts
                    e.method = m.groupValues[2].trim()
                    e.rawUrl = m.groupValues[3].trim()
                    entries.add(e)
                    currentIndexById[id] = entries.size - 1
                    true
                } == true) continue@outer

            if (REQ_HEADERS_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    val jsonText = m.groupValues[2].trim()
                    e.requestHeaders = decodeJsonMap(jsonText)
                    if (e.requestHeaders == null && jsonText.isNotEmpty()) {
                        e.warnings.add("Failed to parse request headers JSON")
                    }
                    true
                } == true) continue@outer

            if (REQ_BODY_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    val prepared = prepareBody(unescape(m.groupValues[2].trim()), e)
                    e.requestBody = prepared.first
                    e.requestBodyTruncated = prepared.second
                    true
                } == true) continue@outer

            if (RES_STATUS_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    e.statusCode = m.groupValues[2].toIntOrNull()
                    true
                } == true) continue@outer

            if (RES_HEADERS_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    val jsonText = m.groupValues[2].trim()
                    e.responseHeaders = decodeJsonMap(jsonText)
                    if (e.responseHeaders == null && jsonText.isNotEmpty()) {
                        e.warnings.add("Failed to parse response headers JSON")
                    }
                    true
                } == true) continue@outer

            if (RES_BODY_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    val prepared = prepareBody(unescape(m.groupValues[2].trim()), e)
                    val body = prepared.first
                    e.responseBody = body
                    e.responseBodyTruncated = prepared.second
                    if (body.isNotEmpty() && (e.statusCode == null || e.statusCode!! >= 400) &&
                        (e.errors.isEmpty() || e.errors.last() != body)
                    ) {
                        e.errors.add(body)
                    }
                    true
                } == true) continue@outer

            if (RES_CHUNK_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    // Buffered rather than prev+chunk: per-chunk concatenation
                    // is O(n^2) over a long streamed response.
                    val buf = chunkBuffers.getOrPut(e.sequence) { StringBuilder(e.responseBody ?: "") }
                    buf.append(unescape(m.groupValues[2]))
                    true
                } == true) continue@outer

            if (RES_DONE_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    touch(ensureEntry(id), ts)
                    true
                } == true) continue@outer

            if (RES_ERR_RE.find(msg)?.let { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@let false
                    val e = ensureEntry(id)
                    touch(e, ts)
                    val err = unescape(m.groupValues[2].trim())
                    if (err.isNotEmpty()) e.errors.add(err)
                    true
                } == true) continue@outer

            RES_DIO_ERR_RE.find(msg)?.let { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return@let
                val e = ensureEntry(id)
                touch(e, ts)
                val err = unescape(m.groupValues[2].trim())
                if (err.isNotEmpty()) e.errors.add(err)
            }
        }

        // Elide the reassembled stream once, so payloads split across chunk
        // boundaries are caught too (L325-333).
        for (e in entries) {
            val buf = chunkBuffers[e.sequence] ?: continue
            val prepared = prepareBody(buf.toString(), e)
            e.responseBody = prepared.first
            e.responseBodyTruncated = prepared.second
        }

        // Newest first (when possible) L336-345.
        return entries.sortedWith(
            compareByDescending<RequestLogEntry> { it.startedAt ?: it.lastEventAt ?: Long.MIN_VALUE }
                .thenByDescending { it.sequence },
        )
    }

    private fun toRecords(content: String): List<LogRecord> {
        val out = mutableListOf<LogRecord>()
        val cal = java.util.Calendar.getInstance()
        for (rawLine in content.split('\n')) {
            val line = rawLine.trimEnd()
            val m = TS_RE.find(line)
            if (m != null) {
                fun g(i: Int) = m.groupValues[i].toIntOrNull() ?: 0
                // DateTime(y,mo,d,h,mi,s,ms) — local wall clock like the Dart parser.
                cal.set(g(1), g(2) - 1, g(3), g(4), g(5), g(6))
                cal.set(java.util.Calendar.MILLISECOND, g(7))
                out.add(LogRecord(cal.timeInMillis, m.groupValues[8]))
                continue
            }
            if (out.isEmpty()) continue
            out.last().message += "\n$line"
        }
        return out
    }

    /** Reverses the writer-side escape() (handles \\, \\r, \\n, \\t). */
    fun unescape(input: String): String = ESCAPE_RE.replace(input) { m ->
        when (m.value[1]) {
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            else -> "\\"
        }
    }
}

// —— context_log_models.dart（读取侧模型）————————————————————————————

enum class ContextSource {
    systemPrompt, memoryRules, searchPrompt, instructionInjection,
    worldBook, memorySnapshot, chatHistory, toolCall, toolResult;

    companion object {
        fun fromWire(raw: String?): ContextSource =
            entries.firstOrNull { it.name == raw } ?: chatHistory
    }
}

data class ContextSegment(
    val source: ContextSource,
    val text: String,
    val tokens: Int,
    val meta: JsonObject? = null,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "source" to JsonPrimitive(source.name),
            "text" to JsonPrimitive(text),
            "tokens" to JsonPrimitive(tokens),
            "meta" to (meta ?: JsonObject(emptyMap())),
        )
    )
}

data class ContextLogMessage(val role: String, val segments: List<ContextSegment>) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "role" to JsonPrimitive(role),
            "segments" to JsonArray(segments.map { it.toJson() }),
        )
    )
}

data class ContextLogSnapshot(
    val timestamp: Long,
    val conversationId: String,
    val assistantName: String,
    val provider: String,
    val model: String,
    val messages: List<ContextLogMessage>,
    val totalTokens: Int,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "timestamp" to JsonPrimitive(java.time.Instant.ofEpochMilli(timestamp).toString()),
            "conversationId" to JsonPrimitive(conversationId),
            "assistantName" to JsonPrimitive(assistantName),
            "provider" to JsonPrimitive(provider),
            "model" to JsonPrimitive(model),
            "messages" to JsonArray(messages.map { it.toJson() }),
            "totalTokens" to JsonPrimitive(totalTokens),
        )
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(obj: JsonObject): ContextLogSnapshot {
            val messages = (obj["messages"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el -> (el as? JsonObject)?.let { fromMessage(it) } }
                ?: emptyList()
            val storedTotal = (obj["totalTokens"] as? JsonPrimitive)?.content?.toIntOrNull()
            return ContextLogSnapshot(
                timestamp = (obj["timestamp"] as? JsonPrimitive)?.content?.let { ts ->
                    runCatching { java.time.Instant.parse(ts).toEpochMilli() }
                        .getOrElse { runCatching { java.time.LocalDateTime.parse(ts).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0L) }
                } ?: 0L,
                conversationId = (obj["conversationId"] as? JsonPrimitive)?.content ?: "",
                assistantName = (obj["assistantName"] as? JsonPrimitive)?.content ?: "",
                provider = (obj["provider"] as? JsonPrimitive)?.content ?: "",
                model = (obj["model"] as? JsonPrimitive)?.content ?: "",
                messages = messages,
                totalTokens = storedTotal ?: messages.sumOf { m -> m.segments.sumOf { it.tokens } },
            )
        }

        private fun fromMessage(obj: JsonObject): ContextLogMessage = ContextLogMessage(
            role = (obj["role"] as? JsonPrimitive)?.content ?: "",
            segments = (obj["segments"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el -> (el as? JsonObject)?.let { seg ->
                    ContextSegment(
                        source = ContextSource.fromWire((seg["source"] as? JsonPrimitive)?.content),
                        text = (seg["text"] as? JsonPrimitive)?.content ?: "",
                        tokens = (seg["tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                        meta = seg["meta"] as? JsonObject,
                    )
                } }
                ?: emptyList(),
        )
    }
}

// —— context_log_tail_reader.dart ———————————————————————————————————————

/** Resume point for backward JSONL reads. */
data class ContextLogTailCursor(
    val position: Long? = null,
    val pending: ByteArray = ByteArray(0),
) {
    val isExhausted: Boolean get() = position != null && position <= 0 && pending.isEmpty()
}

data class ContextLogTailPage(val snapshots: List<ContextLogSnapshot>, val cursor: ContextLogTailCursor) {
    val hasMore: Boolean get() = !cursor.isExhausted
}

/** Reads context-log JSONL from the tail without loading the whole file. */
object ContextLogTailReader {
    const val DEFAULT_PAGE_SIZE = 50
    const val DEFAULT_CHUNK_SIZE = 128 * 1024

    fun readPage(
        path: String,
        cursor: ContextLogTailCursor = ContextLogTailCursor(),
        limit: Int = DEFAULT_PAGE_SIZE,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): ContextLogTailPage {
        if (limit <= 0) return ContextLogTailPage(emptyList(), cursor)
        val file = java.io.File(path)
        if (!file.exists()) return ContextLogTailPage(emptyList(), ContextLogTailCursor(0))

        val length = file.length()
        var pos = (cursor.position ?: length).coerceIn(0L, length)
        var buffer: ByteArray = cursor.pending
        val snapshots = mutableListOf<ContextLogSnapshot>()

        while (snapshots.size < limit) {
            val extracted = takeCompleteLinesFromEnd(buffer, limit - snapshots.size)
            snapshots.addAll(extracted.first)
            buffer = extracted.second
            if (snapshots.size >= limit) break
            if (pos <= 0) {
                parseLine(buffer)?.let { snapshots.add(it) }
                buffer = ByteArray(0)
                break
            }
            val readSize = minOf(chunkSize.toLong(), pos).toInt()
            pos -= readSize
            val chunk = ByteArray(readSize)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(pos)
                val read = raf.read(chunk)
                if (read <= 0) {
                    pos = 0
                    return@use
                }
                buffer = concat(if (read == readSize) chunk else chunk.copyOf(read), buffer)
            }
        }

        return ContextLogTailPage(snapshots, ContextLogTailCursor(pos, buffer))
    }

    private fun takeCompleteLinesFromEnd(buffer: ByteArray, maxLines: Int): Pair<List<ContextLogSnapshot>, ByteArray> {
        if (maxLines <= 0 || buffer.isEmpty()) return emptyList<ContextLogSnapshot>() to buffer
        val lines = mutableListOf<ContextLogSnapshot>()
        var end = buffer.size
        while (lines.size < maxLines) {
            var nl = -1
            for (i in end - 1 downTo 0) {
                if (buffer[i] == 10.toByte()) { nl = i; break }
            }
            if (nl < 0) break
            parseLine(buffer.copyOfRange(nl + 1, end))?.let { lines.add(it) }
            end = nl
        }
        return lines to (if (end == buffer.size) buffer else buffer.copyOfRange(0, end))
    }

    private fun parseLine(lineBytes: ByteArray): ContextLogSnapshot? = runCatching {
        if (lineBytes.isEmpty()) return null
        val line = String(lineBytes, Charsets.UTF_8).trim()
        if (line.isEmpty()) return null
        ContextLogSnapshot.fromJson(Json.parseToJsonElement(line).jsonObject)
    }.getOrNull()

    private fun concat(head: ByteArray, tail: ByteArray): ByteArray {
        if (head.isEmpty()) return tail
        if (tail.isEmpty()) return head
        val out = ByteArray(head.size + tail.size)
        head.copyInto(out)
        tail.copyInto(out, head.size)
        return out
    }
}
