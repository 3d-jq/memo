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

    /**
     * Emitted between attempts while auto-retry waits for the next attempt
     * (mirror of Dart stream_chunk.dart RetryPending).
     *
     * Not message content — consumers must NOT fold this into parts and must
     * NOT mark the message failed. UI shows an in-bubble countdown instead.
     *
     * [attempt] is the 1-based extra-attempt index, matching "retry (2/3)".
     * [retryAtMs] is the absolute epoch-ms deadline when backoff ends, stamped
     * when the sleep starts — the countdown must use this instead of
     * `now + delay` after a delayed consumer applies the event.
     */
    class RetryPending(
        val attempt: Int,
        val maxRetries: Int,
        val delayMs: Long,
        val retryAtMs: Long,
        val errorText: String = "",
    ) : StreamChunk()

    /** Emitted when backoff has finished and the next attempt is starting; UI must clear the countdown (Dart RetryAttemptStart). */
    object RetryAttemptStart : StreamChunk()
}

/** Result of feeding one SSE event to a decoder. */
class DecodeResult(
    val chunks: List<StreamChunk>,
    /** Provider protocol has ended; transport may close. */
    val completed: Boolean = false,
)
