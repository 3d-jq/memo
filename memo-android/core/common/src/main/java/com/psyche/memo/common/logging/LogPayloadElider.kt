package com.psyche.memo.common.logging

/**
 * 1:1 port of lib/core/services/logging/log_payload_elider.dart.
 *
 * Replaces inline base64 payloads with short placeholders so logs stay small
 * enough to write, parse and render (covers OpenAI/Claude/Gemini shapes).
 * Hand scanning instead of regex, matching the Dart source: a quantifier over
 * a multi-megabyte payload overflows regex backtracking.
 *
 * Lives in `core:common` because both the writer (RequestLogger) and the
 * reader (com.psyche.memo.ui.LogData) need it.
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

data class LogPayloadRef(val mime: String, val base64Chars: Int) {
    val byteLength: Int get() = base64Chars * 3 / 4
}

data class LogPayloadElision(val text: String, val refs: List<LogPayloadRef>)
