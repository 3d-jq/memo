package com.psyche.memo.llm.client

import com.psyche.memo.common.logging.ContextTag

/**
 * Provider-agnostic request payload (P1 subset: text + images as data URIs,
 * tool definitions for P2 tool calls).
 */
data class LlmMessage(
    val role: String, // "user" | "assistant" | "system" | "tool"
    val content: String? = null,
    /** Conversation part payloads: text/reasoning/tool_call come as JSON strings. */
    val parts: List<String> = emptyList(),
    /** Assistant transcript for a tool-followup round (openai tool_calls). */
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /**
     * Context-log tags collected while the request was assembled
     * (`context_log_models.dart` `_kelivo_ctx_segments`). Never sent: the
     * provider clients build their JSON field by field, so unlike the
     * original's map payload nothing has to be stripped before the request.
     */
    val contextTags: List<ContextTag> = emptyList(),
)

/** openai_tool_transcript.dart `openaiToolCallMaps` 单条 —— 上行 assistant tool_call。 */
data class LlmToolCall(
    val id: String,
    val name: String,
    /** jsonEncode(arguments) 形态：对象 JSON 字符串。 */
    val argumentsJson: String,
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
    /** `top_p` 采样参数（chat_actions.dart:2114 assistant.topP）。 */
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val thinking: Boolean = false,
    /**
     * thinking_budget_v1 semantics: null/-1 auto, 0 off, >0 explicit budget.
     * Mapped to provider fields by [ReasoningBudget].
     */
    val thinkingBudget: Int? = null,
    /** Model-level reasoning trait (ModelRegistry) — gates reasoning_effort. */
    val reasoning: Boolean = false,
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
    /**
     * OpenAI **Responses API** 开关（mirrors Flutter `ProviderConfig.useResponseApi`）：
     * 置 true 时端点固定 `/responses`，请求体与流式解码都换成 Responses 形态
     * （见 `ResponsesApi` / `ResponsesDecoder`）。
     */
    val useResponseApi: Boolean = false,
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
