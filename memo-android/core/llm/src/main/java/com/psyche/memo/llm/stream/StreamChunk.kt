package com.psyche.memo.llm.stream

import kotlinx.serialization.json.JsonObject

/**
 * Provider-agnostic stream events (mirror of Flutter StreamChunk family).
 */
sealed class StreamChunk {
    /** Incremental text delta. */
    class TextDelta(val text: String) : StreamChunk()

    /** Incremental reasoning / thinking delta. */
    class ReasoningDelta(val text: String) : StreamChunk()

    /** Tool call delta: one per tool call index, accumulated by id. */
    class ToolCallDelta(
        val id: String?,
        val name: String,
        val arguments: String,
    ) : StreamChunk()

    /**
     * Terminal usage / finish info. [finishReason] may be null when a provider
     * only reports usage. Everything is a provider JSON fragment kept raw.
     */
    class Finish(
        val finishReason: String?,
        val usage: JsonObject?,
    ) : StreamChunk()

    /** Non-fatal in-band error surfaced after [Finish] or on close. */
    class Error(val message: String) : StreamChunk()
}

/** Result of feeding one SSE event to a decoder. */
class DecodeResult(
    val chunks: List<StreamChunk>,
    /** Provider protocol has ended; transport may close. */
    val completed: Boolean = false,
)
