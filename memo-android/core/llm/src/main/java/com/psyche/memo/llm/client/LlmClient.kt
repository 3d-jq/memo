package com.psyche.memo.llm.client

import kotlinx.coroutines.flow.Flow
import com.psyche.memo.llm.stream.StreamChunk

/** Model descriptor from the /models endpoint. */
data class LlmModelInfo(val id: String, val displayName: String)

/**
 * Streaming LLM client. One instance per provider family; the provider kind
 * decides the wire protocol (OpenAI chat completions / Claude / Gemini).
 */
interface LlmClient {
    /** True when this client handles the provider id / path style. */
    fun supports(providerId: String): Boolean

    /** Streamed chat completion. [requestId] identifies cancellation. */
    fun streamChat(request: LlmRequest): Flow<StreamChunk>

    /** Single (non-streamed) completion, used by title/summary generation. */
    suspend fun complete(request: LlmRequest): LlmTextResult

    /**
     * Fetch the provider's /models list (OpenAI-compatible hosts). Claude and
     * Gemini may throw or return the static list; the UI falls back to the
     * configured models.
     */
    suspend fun listModels(baseUrl: String, apiKey: String): List<LlmModelInfo>
}

object LlmDefaults {
    fun baseUrlFor(providerId: String): String = when (providerId) {
        "openai" -> "https://api.openai.com"
        "anthropic" -> "https://api.anthropic.com"
        "gemini" -> "https://generativelanguage.googleapis.com"
        else -> "https://api.openai.com" // OpenAI-compatible proxied providers
    }
}
