package com.psyche.memo.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 1:1 port of the log data layer:
 *   lib/core/services/logging/log_payload_elider.dart
 *   lib/features/settings/logs/request_log_parser.dart
 *   lib/core/services/logging/context_log_models.dart (snapshot model only —
 *     the assembly-side tag helpers belong to the chat pipeline batch)
 *   lib/core/services/logging/context_log_tail_reader.dart
 */

// —— log_payload_elider.dart ————————————————————————————————————————————

data class LogPayloadRef(val mime: String, val base64Chars: Int) {
    val byteLength: Int get() = base64Chars * 3 / 4
}

data class LogPayloadElision(val text: String, val refs: List<LogPayloadRef>)

/**
 * Replaces inline base64 payloads with short placeholders so logs stay small
 * enough to write, parse and render (covers OpenAI/Claude/Gemini shapes).
 * Hand scanning instead of regex, matching the Dart source: a quantifier over
 * a multi-megabyte payload overflows regex backtracking.
 */
object LogPayloadElider {
    const val BARE_BASE64_THRESHOLD = 4096
    const val FALLBACK_MIME = "application/octet-stream"
    private const val B64_MARKER = ";base64,"
    private const val MIME_LOOKBACK = 128
    private const val MIME_HINT_WINDOW = 200

    private val MIME_HINT_RE = Regex("\"(?:mime_type|mimeType|media_type|mediaType)\"\\s*:\\s*\"([^\"]{1,120})\"")

    fun elide(text: String): String = bareBase64Pass(elideDataUris(text), null)

    fun elideDataUris(text: String): String = dataUriPass(text, null)

    fun elideBareBase64(text: String): String = bareBase64Pass(text, null)

    fun placeholder(chars: Int): String = "<omitted $chars chars>"

    /** Elides and reports in one walk; rewrite=false reports without replacing. */
    fun process(text: String, rewrite: Boolean = true): LogPayloadElision {
        val refs = mutableListOf<LogPayloadRef>()
        val afterUris = dataUriPass(text, refs, rewrite)
        val out = bareBase64Pass(afterUris, refs, rewrite)
        return LogPayloadElision(out, refs)
    }

    private fun dataUriPass(text: String, refs: MutableList<LogPayloadRef>?, rewrite: Boolean = true): String {
        val len = text.length
        val out = StringBuilder()
        var last = 0
        var from = 0
        while (from < len) {
            val marker = text.indexOf(B64_MARKER, from)
            if (marker < 0) break
            val uriStart = dataUriStart(text, marker)
            if (uriStart < 0) {
                from = marker + B64_MARKER.length
                continue
            }
            val payloadStart = marker + B64_MARKER.length
            val mime = text.substring(uriStart + 5, marker)
            val existing = readPlaceholder(text, payloadStart)
            if (existing != null) {
                refs?.add(LogPayloadRef(mime, existing.first))
                from = existing.second
                continue
            }
            val payloadEnd = payloadEnd(text, payloadStart)
            if (payloadEnd <= payloadStart) {
                from = payloadStart
                continue
            }
            val chars = payloadEnd - payloadStart
            refs?.add(LogPayloadRef(mime, chars))
            from = payloadEnd
            if (!rewrite) continue
            out.append(text, last, payloadStart)
            out.append(placeholder(chars))
            last = payloadEnd
        }
        if (out.isEmpty()) return text
        out.append(text, last, len)
        return out.toString()
    }

    private fun bareBase64Pass(text: String, refs: MutableList<LogPayloadRef>?, rewrite: Boolean = true): String {
        val len = text.length
        val out = StringBuilder()
        var last = 0
        var i = 0
        while (i < len) {
            if (text[i].code.toByte() != 0x22.toByte()) { // "
                i++
                continue
            }
            val existing = readPlaceholder(text, i + 1)
            if (existing != null && existing.second < len && text[existing.second].code.toByte() == 0x22.toByte()) {
                refs?.add(LogPayloadRef(mimeBefore(text, i + 1), existing.first))
                i = existing.second + 1
                continue
            }
            if (len <= BARE_BASE64_THRESHOLD) {
                i++
                continue
            }
            val start = i + 1
            var end = start
            while (end < len && isBase64Unit(text[end].code.toByte())) end++
            var close = end
            var pad = 0
            while (close < len && pad < 2 && text[close].code.toByte() == 0x3D.toByte()) { // =
                close++
                pad++
            }
            val isPayload = close < len && text[close].code.toByte() == 0x22.toByte() &&
                (end - start) >= BARE_BASE64_THRESHOLD
            if (isPayload) {
                val chars = close - start
                refs?.add(LogPayloadRef(mimeBefore(text, start), chars))
                i = close + 1
                if (rewrite) {
                    out.append(text, last, start)
                    out.append(placeholder(chars))
                    last = close
                }
                continue
            }
            i = if (close > start) close else i + 1
        }
        if (out.isEmpty()) return text
        out.append(text, last, len)
        return out.toString()
    }

    private fun dataUriStart(text: String, marker: Int): Int {
        val lo = maxOf(0, marker - MIME_LOOKBACK)
        var i = marker
        while (i > lo) {
            val c = text[i - 1].code.toByte()
            if (c == 0x3B.toByte() || c == 0x2C.toByte() || isSpace(c)) break // ; ,
            i--
        }
        for (p in i until marker - 5) {
            if (isDataPrefix(text, p)) return p
        }
        return -1
    }

    private fun payloadEnd(text: String, start: Int): Int {
        val len = text.length
        var end = start
        while (end < len) {
            val c = text[end].code.toByte()
            if (isBase64Unit(c) || c == 0x3D.toByte() || isSpace(c)) { // =
                end++
                continue
            }
            break
        }
        return end
    }

    /** Reads a `<omitted N chars>` placeholder at [start]. */
    private fun readPlaceholder(text: String, start: Int): Pair<Int, Int>? {
        val head = "<omitted "
        val tail = " chars>"
        if (!text.startsWith(head, start)) return null
        var p = start + head.length
        val digitsFrom = p
        while (p < text.length) {
            val c = text[p].code.toByte()
            if (c < 0x30.toByte() || c > 0x39.toByte()) break
            p++
        }
        if (p == digitsFrom) return null
        if (!text.startsWith(tail, p)) return null
        val chars = text.substring(digitsFrom, p).toIntOrNull() ?: return null
        return chars to (p + tail.length)
    }

    private fun mimeBefore(text: String, start: Int): String {
        val from = maxOf(0, start - MIME_HINT_WINDOW)
        val slice = text.substring(from, start)
        return MIME_HINT_RE.findAll(slice).lastOrNull()?.groupValues?.get(1) ?: FALLBACK_MIME
    }

    private fun isDataPrefix(t: String, p: Int): Boolean {
        val d = "data:".map { it }
        val s = t.getOrNull(p) ?: return false
        return s.equals('d', true) &&
            t.getOrNull(p + 1)?.equals('a', true) == true &&
            t.getOrNull(p + 2)?.equals('t', true) == true &&
            t.getOrNull(p + 3)?.equals('a', true) == true &&
            t.getOrNull(p + 4) == ':'
    }

    private fun isBase64Unit(c: Byte): Boolean =
        c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 || c == 0x2B.toByte() || c == 0x2F.toByte()

    private fun isSpace(c: Byte): Boolean =
        c == 0x20.toByte() || c == 0x09.toByte() || c == 0x0A.toByte() ||
            c == 0x0D.toByte() || c == 0x0B.toByte() || c == 0x0C.toByte()
}

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
)

data class ContextLogMessage(val role: String, val segments: List<ContextSegment>)

data class ContextLogSnapshot(
    val timestamp: Long,
    val conversationId: String,
    val assistantName: String,
    val provider: String,
    val model: String,
    val messages: List<ContextLogMessage>,
    val totalTokens: Int,
) {
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
