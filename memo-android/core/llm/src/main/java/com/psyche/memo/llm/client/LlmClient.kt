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

    /**
     * 助手「流式输出」关闭时的**非流式**请求（`chat_actions.dart:2109` 的
     * `!ctx.streamOutput` 分支）：同一个请求体但 `stream = false`，拿到整份响应后
     * **喂给同一个解码器**，产出的 chunk 序列与 [streamChat] 完全一致（文本/思考/
     * 工具调用/用量），上层生成循环因此不需要第二条分支。
     */
    fun completeAsChunks(request: LlmRequest): Flow<StreamChunk>

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
