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
     * 工具结果附带的图片（照 RikkaHub：上游的工具结果就是 part 列表，Text 与 Image
     * 混排；Memo 的工具结果正文是字符串，图片单列在这里）。只有 `role == "tool"`
     * 的消息会用它；**仅在 [LlmRequest.imageInput] 为真时才发出去**，否则以文本占位，
     * 免得把图片塞给不支持图片输入的模型直接 400。
     */
    val toolImages: List<LlmImage> = emptyList(),
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

/** 一张待发送的图片：uri 可以是 http(s) / data: / 本地文件路径。 */
data class LlmImage(val uri: String, val mime: String? = null)

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
    /**
     * Claude 提示词缓存（provider 级两键 `claudePromptCachingEnabled` /
     * `_claudePromptCachingTtl`，取值只有 '5m' / '1h'）。上游在请求体**顶层**加一个
     * `cache_control` 键（claude_official.dart:357-360），这里原样透传形状。
     */
    val claudePromptCaching: Boolean = false,
    val claudePromptCachingTtl: String = "5m",
    /**
     * 当前模型是否支持图片输入（`ModelOverrideResolver` 的 `visionInput`，即上游的
     * `Modality.IMAGE in supportInputModalities`）。只用来决定**工具结果里的图片**
     * 发不发；不支持时按上游换成 `[Image output omitted: …]` 文本占位。
     */
    val imageInput: Boolean = false,
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
