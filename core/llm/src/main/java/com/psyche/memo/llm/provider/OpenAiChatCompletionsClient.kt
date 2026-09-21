package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmClient
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmTextResult
import com.psyche.memo.llm.client.LlmUsage
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.retry.AutoRetryOptions
import com.psyche.memo.llm.retry.backoffDelay
import com.psyche.memo.llm.retry.shouldRetryError
import com.psyche.memo.llm.stream.SseEvent
import com.psyche.memo.llm.stream.SseEventParser
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI Chat Completions client (also covers OpenAI-compatible hosts).
 * Wire format mirrors Flutter `sendOpenAIStream` with the standard
 * /chat/completions path, Authorization Bearer auth and SSE streaming.
 */
class OpenAiChatCompletionsClient(
    private val httpClient: OkHttpClient,
    private val retryOptionsProvider: () -> AutoRetryOptions = { AutoRetryOptions() },

    private val cancellations: CancellationRegistry = CancellationRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {
    /** Per-request read: the container supplies live settings (auto_retry_options). */
    private fun retryOptions(): AutoRetryOptions = retryOptionsProvider()

    override fun supports(providerId: String): Boolean = true // default backend

    override fun streamChat(request: LlmRequest): Flow<StreamChunk> = flow {
        // Flutter retryingStream semantics: retry while the attempt yielded
        // nothing and the error is retryable; cancel aborts immediately.
        val maxRetries = if (retryOptions().enabled) retryOptions().maxRetries else 0
        var attemptCount = 0
        while (true) {
            if (isCancelled(request)) throw kotlinx.coroutines.CancellationException("cancelled")
            attemptCount++
            var yielded = false
            try {
                runStream(request) { chunk ->
                    yielded = true
                    emit(chunk)
                }
                return@flow
            } catch (e: Throwable) {
                if (isCancelled(request)) throw kotlinx.coroutines.CancellationException("cancelled")
                if (yielded || attemptCount > maxRetries || !shouldRetryError(e, retryOptions())) throw e
                val delayMs = backoffDelay(attemptCount - 1, retryOptions())
                // Dart chat_api_service.dart:215-222 —— 结构化 RetryPending 事件，
                // UI 显示「N 秒后重试 (attempt/maxRetries)」倒计时；绝不发裸 Error
                //（裸 Error 会被 ChatViewModel markFailed 当成真失败终止整条流）。
                emit(
                    StreamChunk.RetryPending(
                        attempt = attemptCount,
                        maxRetries = maxRetries,
                        delayMs = delayMs,
                        retryAtMs = System.currentTimeMillis() + delayMs,
                        errorText = e.toString(),
                    ),
                )
                delay(delayMs)
                // Dart attemptStartEvent —— 退避结束、下一次尝试开始，UI 清倒计时。
                emit(StreamChunk.RetryAttemptStart)
            }
        }
    }.flowOn(Dispatchers.IO) // 阻塞式 SSE 读必须离开收集者线程（NetworkOnMainThreadException 根因）

    override suspend fun complete(request: LlmRequest): LlmTextResult {
        // Non-stream: one-shot; no retry requested here (callers handle).
        val body = buildBody(request, stream = false)
        val call = newCall(request, body)
        val response = await(call)
        val text = withContext(Dispatchers.IO) { response.body?.string() }
        response.close()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} ${text ?: ""}")
        }
        val obj = json.parseToJsonElement(text ?: "{}").jsonObject ?: return LlmTextResult(
            parts = emptyList(),
            usage = null,
            finishReason = null,
        )
        // Responses API 的非流式回答在 output[].content[].text，用量字段是
        // input_tokens/output_tokens（ResponsesDecoder.responsesOutputText）。
        if (ResponsesApi.isResponses(request)) {
            val usage = (obj["usage"] as? JsonObject)?.let { usageJson ->
                LlmUsage(
                    promptTokens = (usageJson["input_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    completionTokens = (usageJson["output_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    totalTokens = (usageJson["total_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                )
            }
            val content = responsesOutputText(obj)
            return LlmTextResult(
                parts = if (content.isNotEmpty()) listOf(content) else emptyList(),
                usage = usage,
                finishReason = (obj["status"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        val firstChoice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull ?: ""
        val finish = (firstChoice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        val usage = obj["usage"]?.jsonObject?.let { usageJson ->
            LlmUsage(
                promptTokens = (usageJson["prompt_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                completionTokens = (usageJson["completion_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                totalTokens = (usageJson["total_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }
        return LlmTextResult(
            parts = if (content.isNotEmpty()) listOf(content) else emptyList(),
            usage = usage,
            finishReason = finish,
        )
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): List<com.psyche.memo.llm.client.LlmModelInfo> {
        val url = baseUrl.trimEnd('/') + "/models"
        val call = httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build(),
        )
        val response = await(call)
        return try {
            if (!response.isSuccessful) {
                // 必须带响应体：429/401 的真正原因（限流说明、余额、key 无效）全在 body 里，
                // 裸 `HTTP 429` 用户和日志都无从下手（同 httpFailure 的理由）。
                throw httpFailure(response)
            }
            val text = withContext(Dispatchers.IO) { response.body?.string() } ?: "{}"
            val obj = json.parseToJsonElement(text).jsonObject
            val data = obj["data"] as? JsonArray ?: return emptyList()
            data.mapNotNull { el ->
                val id = (el.jsonObject["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                com.psyche.memo.llm.client.LlmModelInfo(id = id, displayName = id)
            }
        } finally {
            response.close()
        }
    }

    override fun completeAsChunks(request: LlmRequest): Flow<StreamChunk> = flow {
        // 与流式同样的重试语义（retryingStream）：没吐过 chunk 且错误可重试才重试，
        // 取消立刻中止 —— 非流式请求撞上 429/5xx 不该直接判死整条回复。
        val maxRetries = if (retryOptions().enabled) retryOptions().maxRetries else 0
        var attemptCount = 0
        while (true) {
            if (isCancelled(request)) throw kotlinx.coroutines.CancellationException("cancelled")
            attemptCount++
            var yielded = false
            try {
                for (chunk in runNonStream(request)) {
                    yielded = true
                    emit(chunk)
                }
                return@flow
            } catch (e: Throwable) {
                if (isCancelled(request)) throw kotlinx.coroutines.CancellationException("cancelled")
                if (yielded || attemptCount > maxRetries || !shouldRetryError(e, retryOptions())) throw e
                val delayMs = backoffDelay(attemptCount - 1, retryOptions())
                // Dart chat_api_service.dart:215-222 —— 结构化 RetryPending 事件，
                // UI 显示「N 秒后重试 (attempt/maxRetries)」倒计时；绝不发裸 Error
                //（裸 Error 会被 ChatViewModel markFailed 当成真失败终止整条流）。
                emit(
                    StreamChunk.RetryPending(
                        attempt = attemptCount,
                        maxRetries = maxRetries,
                        delayMs = delayMs,
                        retryAtMs = System.currentTimeMillis() + delayMs,
                        errorText = e.toString(),
                    ),
                )
                delay(delayMs)
                // Dart attemptStartEvent —— 退避结束、下一次尝试开始，UI 清倒计时。
                emit(StreamChunk.RetryAttemptStart)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun runNonStream(request: LlmRequest): List<StreamChunk> {
        val body = buildBody(request, stream = false)
        val call = newCall(request, body)
        val response = await(call)
        val text = withContext(Dispatchers.IO) { response.body?.string() }
        response.close()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text ?: ""}")
        val obj = json.parseToJsonElement(text ?: "{}").jsonObject
        val out = ArrayList<StreamChunk>()
        if (ResponsesApi.isResponses(request)) {
            responsesReasoningText(obj).takeIf { it.isNotEmpty() }
                ?.let { out.add(StreamChunk.ReasoningDelta(it)) }
            responsesOutputText(obj).takeIf { it.isNotEmpty() }
                ?.let { out.add(StreamChunk.TextDelta(it)) }
            val wrapped = buildJsonObject {
                put("type", "response.completed")
                put("response", obj)
            }
            val decoder = ResponsesDecoder(providerLabel = request.providerId)
            out.addAll(decoder.accept(SseEvent(null, null, wrapped.toString(), null)).chunks)
            return out
        }
        val decoder = ChatCompletionsDecoder(providerLabel = request.providerId)
        out.addAll(decoder.accept(SseEvent(null, null, obj.toString(), null)).chunks)
        out.addAll(decoder.accept(SseEvent(null, null, "[DONE]", null)).chunks)
        return out
    }

    private suspend fun runStream(
        request: LlmRequest,
        onChunk: suspend (StreamChunk) -> Unit,
    ) {
        val body = buildBody(request, stream = true)
        val call = newCall(request, body)
        val response = await(call)
        try {
            // 非 2xx 要带上响应体（原版 `HTTP ${statusCode}: $errorBody`）——见 httpFailure。
            if (!response.isSuccessful) throw httpFailure(response)
            val source: BufferedSource = response.body?.source() ?: throw IOException("no body")
            val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
            // Responses 的 SSE 事件名/字段与 chat-completions 完全不同 —— 走
            // 各自的解码器（Flutter openai_provider.dart L856-882）。
            val isResponses = ResponsesApi.isResponses(request)
            val decoder = if (isResponses) {
                ResponsesDecoder(providerLabel = request.providerId)
            } else {
                ChatCompletionsDecoder(providerLabel = request.providerId)
            }
            var completed = false
            while (!completed && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                for (event in parser.add(line + "\n")) {
                    val result = decoder.accept(event)
                    for (chunk in result.chunks) onChunk(chunk)
                    if (result.completed) completed = true
                }
            }
            for (event in parser.close()) {
                val result = decoder.accept(event)
                for (chunk in result.chunks) onChunk(chunk)
                if (result.completed) completed = true
            }
            if (!completed) {
                for (chunk in decoder.onClosed()) onChunk(chunk)
            }
        } finally {
            response.close()
        }
    }

    private fun buildBody(request: LlmRequest, stream: Boolean): String {
        // Responses API 的请求体完全不同（input items + instructions +
        // max_output_tokens），见 ResponsesApi（openai_provider.dart L207-539）。
        if (ResponsesApi.isResponses(request)) {
            return ResponsesApi.buildBody(request, stream).toString()
        }
        val messages = buildJsonArray {
            for (m in request.messages) {
                add(messageToJson(m, request.imageInput))
            }
        }
        val obj = buildJsonObject {
            put("model", request.modelId)
            put("messages", messages)
            put("stream", stream)
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            // applyVendorReasoningKnobs —— 各厂商的推理字段不同：智谱/小米/火山
            // 用 thinking:{type}，DashScope 用 enable_thinking，OpenRouter 用
            // reasoning:{enabled}，Laguna 用 chat_template_kwargs；只有通用
            // OpenAI 兼容端点才下发 reasoning_effort（智谱收到它会 400）。
            applyReasoningKnobs(request)
            applyOpenRouterClaudePromptCaching(request)
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", json.parseToJsonElement(tool.inputSchemaJson))
                            }
                        })
                    }
                })
                put("tool_choice", "auto")
            }
            request.extraBodyJson?.let {
                val extra = json.parseToJsonElement(it).jsonObject
                for (entry in extra.entries) {
                    put(entry.key, entry.value) // custom body override wins below
                }
            }
        }
        return obj.toString()
    }

    /**
     * `applyOpenRouterClaudePromptCaching`（openai_vendor_compat.dart L358-376）——
     * OpenAI 兼容路径上的三重门：开关开 + 是 OpenRouter + 模型是 Claude，三个都满足
     * 才加 body 顶层的 `cache_control`（形状与 ClaudeClient 一致：5m 无 ttl，1h 带 ttl）。
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.applyOpenRouterClaudePromptCaching(
        request: LlmRequest,
    ) {
        if (!request.claudePromptCaching) return
        val provider = request.providerId.lowercase()
        val host = runCatching { java.net.URI(request.baseUrl).host ?: "" }.getOrDefault("")
        if (!provider.contains("openrouter") && !host.contains("openrouter.ai")) return
        val model = request.modelId.lowercase()
        if (!model.contains("claude") && !model.contains("anthropic/")) return
        put("cache_control", buildJsonObject {
            put("type", "ephemeral")
            if (request.claudePromptCachingTtl == "1h") put("ttl", "1h")
        })
    }

    /** applyVendorReasoningKnobs（openai_vendor_compat.dart L503-570）。 */
    private fun kotlinx.serialization.json.JsonObjectBuilder.applyReasoningKnobs(request: LlmRequest) {
        for ((key, value) in com.psyche.memo.llm.client.ReasoningBudget.vendorReasoningFields(
            providerId = request.providerId,
            baseUrl = request.baseUrl,
            modelId = request.modelId,
            thinkingBudget = request.thinkingBudget,
            reasoning = request.reasoning,
        )) {
            put(key, value)
        }
    }

    /**
     * chat_completions_api.dart `buildOpenAIChatCompletionMessages` 消息重建
     * (25-66)：assistant 带 tool_calls（openaiToolCallMaps 形态）、tool role 带
     * tool_call_id + name（仅在非空时输出）。
     */
    private fun messageToJson(m: LlmMessage, supportsImageInput: Boolean): JsonObject = buildJsonObject {
        put("role", m.role)
        when {
            m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                put("content", m.content ?: "")
                put("tool_calls", buildJsonArray {
                    for (call in m.toolCalls) {
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        })
                    }
                })
            }
            m.toolCallId != null -> {
                put("tool_call_id", m.toolCallId)
                if (!m.toolName.isNullOrEmpty()) put("name", m.toolName)
                // 工具结果带图时 content 换成 content-part 数组（照上游
                // ChatCompletionsAPI.toToolResultContent）；不带图仍是字符串。
                put(
                    "content",
                    com.psyche.memo.llm.client.MessageContent
                        .openAiToolResultContent(m, supportsImageInput),
                )
            }
            // Multimodal user turns: text-only messages stay plain strings,
            // attachments switch the field to the content-part array shape.
            m.content != null || m.parts.isNotEmpty() ->
                put("content", com.psyche.memo.llm.client.MessageContent.openAiContent(m))
            else -> put("content", "")
        }
    }

    private fun newCall(request: LlmRequest, body: String): Call {
        // Mirror Flutter _openAICompatibleUrl (openai_provider.dart L25-43):
        // trimmed base + (chatPath ?? "/chat/completions"), never injecting
        // /v1 — hosts like https://text.pollinations.ai/openai or
        // https://open.bigmodel.cn/api/paas/v4 404 with a forced /v1.
        val rawBase = request.baseUrl.trimEnd('/')
        // Responses 模式下端点固定 `/responses`（chatPath 被忽略）；否则
        // Flutter 语义（openai_provider.dart:41-42）：null 用默认路径；空字符
        // 串 = 直接 POST base（如 pollinations /openai），不做 takeIf 折叠。
        val url = if (ResponsesApi.isResponses(request)) {
            rawBase + ResponsesApi.RESPONSES_PATH
        } else {
            rawBase + (request.chatPath ?: "/chat/completions")
        }
        val builder = Request.Builder()
            .url(url.toHttpUrl())
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${request.apiKey}")
        for ((k, v) in request.extraHeaders) builder.header(k, v)
        return httpClient.newCall(builder.build())
    }

    private fun isCancelled(request: LlmRequest): Boolean =
        cancellations.isCancelled(request.providerId, request.modelId, request.messages.hashCode())

    private suspend fun await(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
}
