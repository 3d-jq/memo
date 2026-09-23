package com.psyche.memo.common.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 1:1 port of `lib/core/services/logging/context_log_models.dart` (plus the
 * tail reader from `context_log_tail_reader.dart`).
 *
 * Lives in `core:common` because the tags ride on `LlmMessage` (see
 * [ContextTag]): the assembly side and the reader both need these types, and
 * `core:llm` already depends on this module. The writer is
 * `com.psyche.memo.logging.ContextLogger`; the assembler that turns tagged
 * messages into a snapshot is `com.psyche.memo.logging.ContextLogAssembler`.
 */

enum class ContextSource {
    systemPrompt, memoryRules, searchPrompt, instructionInjection, skillPrompt, workspace,
    worldBook, memorySnapshot, toolRules, chatHistory, toolCall, toolResult;

    companion object {
        fun fromWire(raw: String?): ContextSource =
            entries.firstOrNull { it.name == raw } ?: chatHistory
    }
}

/**
 * `ContextSegmentTags` — length-only tag collected while the request context
 * is assembled. Tags are applied in content order and sliced back out by
 * [ContextLogAssembler]; the last tag of a message absorbs whatever remains,
 * exactly like the original's `segmentsFromTaggedMessage`.
 */
data class ContextTag(
    val source: ContextSource,
    val length: Int,
    val meta: Map<String, String> = emptyMap(),
)

/** `ContextSegmentTags` read/write helpers. */
object ContextTags {

    fun item(source: ContextSource, length: Int, meta: Map<String, String> = emptyMap()) =
        ContextTag(source = source, length = length, meta = meta)

    fun replaceWithSingle(tags: List<ContextTag>, tag: ContextTag): List<ContextTag> = listOf(tag)

    /**
     * `ContextSegmentTags.append` — when a block is appended to an existing
     * system message the handler has already joined them with "\n\n", so the
     * separator counts toward the appended segment (L2110-2139).
     */
    fun append(tags: List<ContextTag>, tag: ContextTag): List<ContextTag> = tags + tag

    fun prepend(tags: List<ContextTag>, tag: ContextTag): List<ContextTag> = listOf(tag) + tags
}

/** `token_estimator.dart` — CJK counts 1 token, other characters 4 per token. */
object TokenEstimator {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            if (isCjk(code)) cjk++ else other++
            i += Character.charCount(code)
        }
        return cjk + (other + 3) / 4
    }

    private fun isCjk(code: Int): Boolean =
        (code in 0x3400..0x4DBF) || (code in 0x4E00..0x9FFF) || (code in 0xF900..0xFAFF) ||
            (code in 0x20000..0x2A6DF) || (code in 0x2A700..0x2B73F) || (code in 0x2B740..0x2B81F) ||
            (code in 0x3000..0x303F) || (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) ||
            (code in 0xAC00..0xD7AF) || (code in 0xFF00..0xFFEF)
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
            val messages = (obj["messages"] as? JsonArray)
                ?.mapNotNull { el -> (el as? JsonObject)?.let { fromMessage(it) } }
                ?: emptyList()
            val storedTotal = (obj["totalTokens"] as? JsonPrimitive)?.content?.toIntOrNull()
            return ContextLogSnapshot(
                timestamp = (obj["timestamp"] as? JsonPrimitive)?.content?.let { ts ->
                    runCatching { java.time.Instant.parse(ts).toEpochMilli() }
                        .getOrElse {
                            runCatching {
                                java.time.LocalDateTime.parse(ts)
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            }.getOrDefault(0L)
                        }
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
            segments = (obj["segments"] as? JsonArray)
                ?.mapNotNull { el ->
                    (el as? JsonObject)?.let { seg ->
                        ContextSegment(
                            source = ContextSource.fromWire((seg["source"] as? JsonPrimitive)?.content),
                            text = (seg["text"] as? JsonPrimitive)?.content ?: "",
                            tokens = (seg["tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                            meta = seg["meta"] as? JsonObject,
                        )
                    }
                }
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
