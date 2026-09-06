package com.psyche.memo.llm.client

/**
 * Provider-agnostic request payload (P1 subset: text + images as data URIs,
 * tool definitions for P2 tool calls).
 */
data class LlmMessage(
    val role: String, // "user" | "assistant" | "system" | "tool"
    val content: String? = null,
    /** Conversation part payloads: text/reasoning/tool_call come as JSON strings. */
    val parts: List<String> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

data class LlmToolSpec(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
)

data class LlmRequest(
    val providerId: String,
    val modelId: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val thinking: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBodyJson: String? = null,
    val apiKey: String,
    val baseUrl: String,
    /**
     * OpenAI-compatible override for the chat endpoint path (mirrors Flutter
     * ProviderConfig.chatPath). Null → client default "/chat/completions".
     * The final URL is always `baseUrl` + path with no injected /v1.
     */
    val chatPath: String? = null,
)

data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

/** Result of one streamed generation. */
data class LlmTextResult(
    val parts: List<String>, // ordered message part payloads (text/reasoning/tool_call)
    val usage: LlmUsage?,
    val finishReason: String?,
)
