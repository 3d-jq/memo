package com.psyche.memo.llm.stream

import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.ReasoningSegment
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Folds [StreamChunk] events into an ordered [MessagePart] list (mirrors
 * stream_chunk_handler.dart). One instance per response stream; a [Finish]
 * marks the stream done and later chunks are ignored.
 *
 * Parts keep **arrival order** — each series is located by id so interleaved
 * arrivals do not clobber the last part. The Dart original also folds
 * image/server-tool events; the P1 [StreamChunk] set has no such chunks yet,
 * so only the three shared series are handled here.
 *
 * Reasoning segment timing lives here too (Dart keeps it in
 * `stream_controller.handleReasoningChunk`, right next to the same split
 * rule): the first reasoning delta after a tool call opens a new segment, and
 * the first fragment of the next tool call closes the previous one.
 *
 * **Finishing a segment also collapses it.** The Dart original does the same
 * three-step move — stamp `finishedAt`, then (when "auto-collapse thinking" is
 * on) set `expanded = false` — at every point the reasoning phase ends, so the
 * card folds the moment the model moves on instead of waiting for the whole
 * reply to finish. [autoCollapse] carries that user setting into the fold, and
 * [onSegmentClosed] lets the caller mirror the change to its UI state.
 */
class StreamChunkHandler(
    /** `display_auto_collapse_thinking_v1` — fresh reads keep a mid-stream
     *  settings change from being ignored (Dart reads the provider each time). */
    private val autoCollapse: () -> Boolean = { true },
    /** Invoked after a segment gains its `finishedAt` (and possibly collapsed). */
    private val onSegmentClosed: (() -> Unit)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val folded = ArrayList<MessagePart>()
    private val textIndex = HashMap<String, Int>()
    private val reasoningIndex = HashMap<String, Int>()
    private val toolIndex = HashMap<String, Int>()
    private val toolBuffers = LinkedHashMap<String, ToolBuffer>()
    private val segmentOfPart = HashMap<Int, Int>()
    private val timed = ArrayList<ReasoningSegment>()

    var finished = false
        private set
    var finishReason: String? = null
    var usage: com.psyche.memo.llm.client.LlmUsage? = null

    /** Timing per reasoning segment, in the order the segments were opened. */
    val reasoningSegments: List<ReasoningSegment> get() = timed.toList()

    /** Dart `get parts` — a snapshot the UI can diff safely. */
    val parts: List<MessagePart> get() = folded.toList()

    fun handle(chunk: StreamChunk) {
        if (finished) return
        when (chunk) {
            is StreamChunk.TextDelta -> appendText(chunk.text)
            is StreamChunk.ReasoningDelta -> appendReasoning(chunk.text)
            is StreamChunk.ToolCallDelta -> upsertTool(chunk)
            is StreamChunk.Finish -> {
                finished = true
                finishReason = chunk.finishReason
                closeOpenSegment()
            }
            is StreamChunk.Error -> Unit // surfaced by caller
            // 重试不是内容也不是失败：内容折叠、失败标记都不碰（Dart 侧
            // RetryPending/RetryAttemptStart 同样不进 parts）。倒计时 UI 由
            // ChatViewModel 直接消费这两个事件驱动。
            is StreamChunk.RetryPending -> Unit
            is StreamChunk.RetryAttemptStart -> Unit
        }
    }

    private fun appendText(delta: String) {
        if (delta.isEmpty()) return
        // stream_controller.dart 1232 / chat_actions.dart 2388 — the first
        // content chunk ends the reasoning phase: the model has moved on to
        // the answer, so the thought card folds right there.
        if (textIndex[TEXT_SERIES] == null) closeOpenSegment()
        val index = textIndex[TEXT_SERIES]
        if (index == null || folded[index] !is TextPart) {
            folded.add(TextPart(delta))
            textIndex[TEXT_SERIES] = folded.size - 1
        } else {
            folded[index] = TextPart((folded[index] as TextPart).text + delta)
        }
    }

    private fun appendReasoning(delta: String) {
        if (delta.isEmpty()) return
        val index = reasoningIndex[REASONING_SERIES]
        val open = index != null && folded.getOrNull(index) is ReasoningPart &&
            timed.getOrNull(segmentOfPart[index] ?: -1)?.finishedAt == null
        if (index != null && open) {
            folded[index] = ReasoningPart((folded[index] as ReasoningPart).text + delta)
            return
        }
        folded.add(ReasoningPart(delta))
        reasoningIndex[REASONING_SERIES] = folded.size - 1
        segmentOfPart[folded.size - 1] = timed.size
        timed.add(
            ReasoningSegment(
                startAt = System.currentTimeMillis(),
                finishedAt = null,
                // stream_controller.dart 776 — a brand-new segment starts
                // expanded unless the user asked thinking to auto-collapse.
                expanded = !autoCollapse(),
                toolStartIndex = toolBuffers.size,
            ),
        )
    }

    /** stream_chunk_handler.dart `_upsertTool` 313-357. */
    private fun upsertTool(chunk: StreamChunk.ToolCallDelta) {
        val id = chunk.id ?: return
        val isNew = !toolBuffers.containsKey(id)
        val buffer = toolBuffers.getOrPut(id) { ToolBuffer() }
        if (isNew) closeOpenSegment()
        if (chunk.name.isNotEmpty()) buffer.name += chunk.name
        if (chunk.arguments.isNotEmpty()) buffer.input.append(chunk.arguments)
        val arguments = buffer.arguments ?: tryDecode(buffer.input.toString())
        val part = ToolCallPart.encode(
            id = id,
            name = buffer.name,
            arguments = arguments,
            content = buffer.content,
            server = buffer.server,
            metadata = buffer.metadata,
        )
        val existing = toolIndex[id]
        if (existing != null && folded[existing] is ToolCallPart) {
            folded[existing] = part
        } else {
            folded.add(part)
            toolIndex[id] = folded.size - 1
        }
    }

    /**
     * Ends the still-open last segment and — when auto-collapse is on — folds
     * it immediately. Mirrors the Dart three-step move that runs at every
     * "reasoning phase is over" point (stream_controller.dart L853 tool call
     * starts / L1232 content starts / L1280 stream ends / cancellation /
     * error / the `finishReasoningIfNeeded` catch-all). No-op when the last
     * segment is already finished.
     */
    private fun closeOpenSegment() {
        val last = timed.lastOrNull() ?: return
        if (last.finishedAt != null) return
        last.finishedAt = System.currentTimeMillis()
        if (autoCollapse()) last.expanded = false
        onSegmentClosed?.invoke()
    }

    /** `_tryDecode` 438-…: empty input means "no arguments", bad JSON stays raw. */
    private fun tryDecode(raw: String): JsonElement {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            JsonPrimitive(raw)
        }
    }

    fun textContent(): String =
        folded.filterIsInstance<TextPart>().joinToString("") { it.text }

    /**
     * stream_chunk_handler.dart `_upsertTool` — fold an executed tool's result
     * into its ToolCallPart (the ToolCallResult emit path of the round loop).
     * Keeps id/name/arguments/server/metadata; `content` is replaced and
     * [images] (tool-result attachments, Memo-only payload key) is set.
     */
    fun foldToolResult(
        id: String,
        content: JsonElement,
        images: List<com.psyche.memo.data.model.ToolImage> = emptyList(),
    ) {
        val index = toolIndex[id] ?: return
        val existing = folded.getOrNull(index) as? ToolCallPart ?: return
        val payload = ToolCallPart.decode(existing.payloadJson) ?: return
        folded[index] = ToolCallPart.encode(
            id = payload.id,
            name = payload.name,
            arguments = tryDecode(payload.arguments),
            content = content,
            server = payload.server,
            metadata = payload.metadata,
            images = images,
        )
    }

    private class ToolBuffer {
        var name = ""
        val input = StringBuilder()
        var arguments: JsonElement? = null
        var content: JsonElement? = null
        var server = false
        var metadata: JsonObject? = null
    }

    companion object {
        private const val TEXT_SERIES = "text"
        private const val REASONING_SERIES = "reasoning"

        /** Fold a completed stream: returns the final parts and marks finished. */
        fun collect(chunks: List<StreamChunk>): StreamChunkHandler =
            StreamChunkHandler().also { handler ->
                chunks.forEach(handler::handle)
            }
    }
}
