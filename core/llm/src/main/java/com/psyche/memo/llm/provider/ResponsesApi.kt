package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.MessageContent
import com.psyche.memo.llm.client.ReasoningBudget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI **Responses API** 请求体（移植 Flutter `sendOpenAIStream` 的
 * `config.useResponseApi == true` 分支，openai_provider.dart L207-539，以及
 * openai/responses_api.dart 的 `toResponsesToolsFormat` /
 * openai_vendor_compat.dart 的 `applyCompatibleResponsesReasoning`）。
 *
 * 与 chat-completions 的差异：
 * - 系统提示词走顶层 `instructions`，不进 `input`；
 * - 聊天轮次变成 `input` item 数组（`{role,content}` / 带 `input_text`·
 *   `input_image` 的多模态 parts / assistant 的 `output_text`）；
 * - 工具调用与结果**不是**消息角色，而是 `function_call` 与
 *   `function_call_output` item（`call_id` 成对）；
 * - `max_tokens` → `max_output_tokens`，工具定义要摊平成
 *   `{type:function, name, description, parameters}`。
 */
object ResponsesApi {

    private val json = Json { ignoreUnknownKeys = true }

    /** chatPath 选了 `/responses` 与 useResponseApi 标记任一成立即走 Responses。 */
    fun isResponses(request: LlmRequest): Boolean =
        request.useResponseApi || request.chatPath?.trim() == RESPONSES_PATH

    const val RESPONSES_PATH = "/responses"

    /** `toResponsesToolsFormat`：把 chat-completions 的嵌套 function 摊平。 */
    fun tools(request: LlmRequest): JsonArray = buildJsonArray {
        for (tool in request.tools) {
            add(
                buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", json.parseToJsonElement(tool.inputSchemaJson))
                },
            )
        }
    }

    fun buildBody(request: LlmRequest, stream: Boolean): JsonObject {
        val input = ArrayList<JsonObject>()
        val instructions = StringBuilder()
        val lastUserIndex = request.messages.indexOfLast { it.role == "user" }
        // 助手的图片不能进 assistant 的 output_text（Responses 只允许
        // output_text/refusal），原版把它们攒起来挂到后面那条 user 轮次上。
        val assistantImages = ArrayList<String>()

        request.messages.forEachIndexed { index, message ->
            val text = message.content.orEmpty()
            when {
                message.role == "system" -> {
                    if (text.isNotEmpty()) {
                        if (instructions.isNotEmpty()) instructions.append("\n\n")
                        instructions.append(text)
                    }
                }

                message.role == "tool" -> {
                    val callId = message.toolCallId.orEmpty()
                    if (callId.isNotEmpty()) {
                        input.add(
                            buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", callId)
                                // 带图时 output 换成 input_text/input_image 数组
                                // （照上游 ResponseAPI 的 function_call_output 分支）。
                                put(
                                    "output",
                                    com.psyche.memo.llm.client.MessageContent
                                        .responsesToolResultOutput(message, request.imageInput),
                                )
                            },
                        )
                    }
                }

                message.role == "assistant" && message.toolCalls.isNotEmpty() -> {
                    for (call in message.toolCalls) {
                        if (call.id.isEmpty() || call.name.isEmpty()) continue
                        input.add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.argumentsJson.ifEmpty { "{}" })
                            },
                        )
                    }
                    // 只带工具调用的助手轮次没有正文：原版直接跳过。
                    if (text.isBlank()) return@forEachIndexed
                    input.add(assistantMessage(text))
                    assistantImages += imageUrls(message)
                }

                message.role == "assistant" -> {
                    input.add(assistantMessage(text))
                    assistantImages += imageUrls(message)
                }

                else -> {
                    // 最后一条 user 轮次同时带上先前攒下的助手图片。
                    val own = imageUrls(message)
                    val attached =
                        if (index == lastUserIndex) own + assistantImages else own
                    if (attached.isEmpty()) {
                        input.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", text)
                            },
                        )
                    } else {
                        val parts = ArrayList<JsonObject>()
                        if (text.isNotEmpty()) {
                            parts.add(
                                buildJsonObject {
                                    put("type", "input_text")
                                    put("text", text)
                                },
                            )
                        }
                        for (url in attached.distinct()) {
                            parts.add(
                                buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", url)
                                },
                            )
                        }
                        input.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", JsonArray(parts))
                            },
                        )
                    }
                }
            }
        }

        return buildJsonObject {
            put("model", request.modelId)
            put("input", JsonArray(input))
            put("stream", stream)
            if (instructions.isNotEmpty()) put("instructions", instructions.toString())
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            request.maxTokens?.let { put("max_output_tokens", it) }
            if (request.tools.isNotEmpty()) {
                put("tools", tools(request))
                put("tool_choice", "auto")
            }
            applyReasoning(request)
            // 自定义请求体最后覆盖（custom_request 三层合并的结果）。
            request.extraBodyJson?.let { extra ->
                val obj = runCatching { json.parseToJsonElement(extra).jsonObject }.getOrNull()
                obj?.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    private fun assistantMessage(text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("role", "assistant")
        put("status", "completed")
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "output_text")
                        put("text", text)
                    },
                ),
            ),
        )
    }

    /** 可内嵌的图片地址（data:/http(s)/本地文件→base64 data URI）。 */
    private fun imageUrls(message: LlmMessage): List<String> =
        MessageContent.imagesOf(message).mapNotNull { MessageContent.dataUrlFor(it) }

    /**
     * `applyCompatibleResponsesReasoning`（openai_vendor_compat.dart L62-114）：
     * 默认 `reasoning:{summary:auto, effort}`，但小米 MiMo 只要 effort、DeepSeek
     * 只在关思考时发 `effort:none`、DashScope 用顶层 `enable_thinking`。
     */
    private fun JsonObjectBuilder.applyReasoning(request: LlmRequest) {
        if (!request.reasoning) return
        val host = runCatching { java.net.URI(request.baseUrl).host.orEmpty() }
            .getOrDefault("")
            .lowercase()
        val provider = request.providerId.lowercase()
        val model = request.modelId.lowercase()
        val off = ReasoningBudget.isOff(request.thinkingBudget)
        val effort = ReasoningBudget.openAiEffortForBudget(request.thinkingBudget, request.modelId)
        val isMimo = host.contains("xiaomimimo") || model.startsWith("mimo-") ||
            model.contains("/mimo-")
        val isDeepSeek = host.contains("deepseek") || provider.contains("deepseek") ||
            model.contains("deepseek")
        val isDashScope = host.contains("dashscope") || host.contains("aliyun")
        when {
            isMimo -> if (!off && effort != "auto") {
                putJsonObject("reasoning") { put("effort", effort) }
            } else if (off) {
                putJsonObject("reasoning") { put("effort", "none") }
            }

            isDeepSeek -> if (off) {
                putJsonObject("reasoning") { put("effort", "none") }
            }

            isDashScope -> put("enable_thinking", !off)

            else -> if (effort != "off") {
                putJsonObject("reasoning") {
                    put("summary", "auto")
                    if (effort != "auto") put("effort", effort)
                }
            }
        }
    }
}
