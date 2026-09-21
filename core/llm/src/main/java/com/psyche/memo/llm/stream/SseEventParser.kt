package com.psyche.memo.llm.stream

import com.psyche.memo.common.logging.FlutterLogger

/**
 * Incremental SSE framer (mirror of Dart SseEventParser in
 * lib/core/services/api/stream/sse_framing.dart).
 *
 * One instance per response stream. Handles id:/event:/data: (multiline joined
 * with `\n`)/retry:, CRLF, comments, a BOM, and a final frame lacking a
 * trailing newline.
 *
 * [recoverAdjacentJsonDataRecords] is the compatibility mode for providers that
 * omit the blank delimiter between adjacent, data-only JSON events.
 */
class SseEventParser(
    private val recoverAdjacentJsonDataRecords: Boolean = false,
    private val onRecovery: (Int) -> Unit = { count ->
        // sse_framing.dart L235-240 —— 恢复过的畸形流在应用日志里留一条（每个
        // parser 只报一次）；排查"某个供应商偶发丢字"时这是唯一的线索。
        FlutterLogger.log(
            "recoveredAdjacentJsonDataRecords count=$count",
            tag = "SseFramingRecovery",
        )
    },
) {
    private val carry = StringBuilder()
    private val dataLines = ArrayList<String>()
    private var id: String? = null
    private var event: String? = null
    private var retryMillis: Long? = null
    private var started = false
    private var lastLineWasBlank = false
    private var reportedAdjacentJsonRecovery = false

    fun add(chunk: String): List<SseEvent> {
        if (chunk.isEmpty()) return emptyList()
        var text = chunk
        if (!started) {
            started = true
            if (text.startsWith('\uFEFF')) text = text.substring(1)
        }
        carry.append(text)
        return drain(flushIncompleteLine = false)
    }

    fun close(): List<SseEvent> {
        val events = drain(flushIncompleteLine = true).toMutableList()
        events.addAll(takeEvents())
        return events
    }

    private fun drain(flushIncompleteLine: Boolean): List<SseEvent> {
        var buffer = carry.toString()
        carry.setLength(0)
        val deferTrailingCR = !flushIncompleteLine && buffer.endsWith('\r')
        if (deferTrailingCR) {
            buffer = buffer.substring(0, buffer.length - 1)
        }
        buffer = buffer.replace("\r\n", "\n").replace('\r', '\n')
        val events = ArrayList<SseEvent>()
        while (true) {
            val index = buffer.indexOf('\n')
            if (index < 0) {
                if (flushIncompleteLine) {
                    if (buffer.isNotEmpty()) processLine(buffer, events)
                } else if (buffer.isNotEmpty()) {
                    carry.append(buffer)
                }
                break
            }
            processLine(buffer.substring(0, index), events)
            buffer = buffer.substring(index + 1)
        }
        if (deferTrailingCR) carry.append('\r')
        return events
    }

    private fun processLine(line: String, events: MutableList<SseEvent>) {
        if (shouldReleasePendingJsonBefore(line)) {
            reportRecovery(1)
            events.addAll(takeEvents())
        }
        handleLine(line)
        if (lastLineWasBlank || shouldReleaseDoneNow()) {
            events.addAll(takeEvents())
        }
    }

    private fun shouldReleasePendingJsonBefore(line: String): Boolean {
        if (!recoverAdjacentJsonDataRecords ||
            id != null || event != null || retryMillis != null || dataLines.isEmpty()
        ) {
            return false
        }
        val nextData = dataValueOf(line) ?: return false
        if (nextData.trim().isEmpty()) return false
        return isStandaloneJsonObjectOrDone(dataLines.joinToString("\n"))
    }

    private fun shouldReleaseDoneNow(): Boolean =
        recoverAdjacentJsonDataRecords &&
            id == null && event == null && retryMillis == null &&
            dataLines.size == 1 && dataLines[0] == "[DONE]"

    fun takeEventsBeforeError(): List<SseEvent> {
        val recovered = ArrayList<SseEvent>()
        if (carry.toString().endsWith('\r')) {
            recovered.addAll(drain(flushIncompleteLine = true))
        }
        if (!recoverAdjacentJsonDataRecords ||
            id != null || event != null || retryMillis != null ||
            dataLines.isEmpty() || !isStandaloneJsonObject(dataLines.joinToString("\n"))
        ) {
            return recovered
        }
        reportRecovery(1)
        recovered.addAll(takeEvents())
        return recovered
    }

    private fun handleLine(line: String) {
        lastLineWasBlank = line.isEmpty()
        if (line.isEmpty()) return
        if (line.startsWith(":")) return

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)

        when (field) {
            "id" -> if (!value.contains('\u0000')) id = value
            "event" -> event = value
            "data" -> dataLines.add(value)
            "retry" -> retryMillis = value.toLongOrNull()
        }
    }

    private fun takeEvents(): List<SseEvent> {
        if (id == null && event == null && retryMillis == null && dataLines.isEmpty()) {
            return emptyList()
        }
        val joined = dataLines.joinToString("\n")
        val splitJsonRecords = recoverAdjacentJsonDataRecords &&
            id == null && event == null && retryMillis == null &&
            dataLines.size > 1 &&
            !isStandaloneJsonObjectOrDone(joined) &&
            dataLines.all(::isStandaloneJsonObjectOrDone)
        val payloads = if (splitJsonRecords) ArrayList(dataLines) else listOf(joined)
        if (splitJsonRecords) reportRecovery(payloads.size)
        val events = payloads.map { SseEvent(id, event, it, retryMillis) }
        resetFields()
        return events
    }

    private fun resetFields() {
        id = null
        event = null
        retryMillis = null
        dataLines.clear()
        lastLineWasBlank = false
    }

    private fun reportRecovery(count: Int) {
        if (reportedAdjacentJsonRecovery) return
        reportedAdjacentJsonRecovery = true
        onRecovery(count)
    }

    companion object {
        private fun dataValueOf(line: String): String? {
            if (line.isEmpty() || line.startsWith(":")) return null
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            if (field != "data") return null
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
            return value
        }

        private fun isStandaloneJsonObjectOrDone(data: String): Boolean {
            if (data == "[DONE]") return true
            return isStandaloneJsonObject(data)
        }

        private fun isStandaloneJsonObject(data: String): Boolean {
            // Cheap check: trim and require an object wrapper; exact JSON
            // validity is validated by the consumer's Json decoder.
            val t = data.trim()
            return t.startsWith("{") && t.endsWith("}")
        }
    }
}
