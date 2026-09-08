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
import com.psyche.memo.llm.stream.SseEventParser
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    private val retryOptions: AutoRetryOptions = AutoRetryOptions(),
    private val cancellations: CancellationRegistry = CancellationRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {

    override fun supports(providerId: String): Boolean = true // default backend

    override fun streamChat(request: LlmRequest): Flow<StreamChunk> = flow {
        // Flutter retryingStream semantics: retry while the attempt yielded
        // nothing and the error is retryable; cancel aborts immediately.
        val maxRetries = if (retryOptions.enabled) retryOptions.maxRetries else 0
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
                if (yielded || attemptCount > maxRetries || !shouldRetryError(e, retryOptions)) throw e
                val delayMs = backoffDelay(attemptCount - 1, retryOptions)
                emit(StreamChunk.Error("retrying in ${delayMs}ms: ${e.message}"))
                delay(delayMs)
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
        val firstChoice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val message = firstChoice?.get("message")?.jsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.content ?: ""
        val finish = (firstChoice?.get("finish_reason") as? JsonPrimitive)?.content
        val usage = obj["usage"]?.jsonObject?.let { usageJson ->
            LlmUsage(
                promptTokens = (usageJson["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
                completionTokens = (usageJson["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
                totalTokens = (usageJson["total_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
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
                throw IOException("HTTP ${response.code}")
            }
            val text = withContext(Dispatchers.IO) { response.body?.string() } ?: "{}"
            val obj = json.parseToJsonElement(text).jsonObject
            val data = obj["data"] as? JsonArray ?: return emptyList()
            data.mapNotNull { el ->
                val id = (el.jsonObject["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                com.psyche.memo.llm.client.LlmModelInfo(id = id, displayName = id)
            }
        } finally {
            response.close()
        }
    }

    private suspend fun runStream(
        request: LlmRequest,
        onChunk: suspend (StreamChunk) -> Unit,
    ) {
        val body = buildBody(request, stream = true)
        val call = newCall(request, body)
        val response = await(call)
        try {
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val source: BufferedSource = response.body?.source() ?: throw IOException("no body")
            val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
            val decoder = ChatCompletionsDecoder()
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
        val messages = buildJsonArray {
            for (m in request.messages) {
                add(messageToJson(m))
            }
        }
        val obj = buildJsonObject {
            put("model", request.modelId)
            put("messages", messages)
            put("stream", stream)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
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
     * chat_completions_api.dart `buildOpenAIChatCompletionMessages` 消息重建
     * (25-66)：assistant 带 tool_calls（openaiToolCallMaps 形态）、tool role 带
     * tool_call_id + name（仅在非空时输出）。
     */
    private fun messageToJson(m: LlmMessage): JsonObject = buildJsonObject {
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
                put("content", m.content ?: "")
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
        // Flutter 语义（openai_provider.dart:41-42）：null 用默认路径；空字符
        // 串 = 直接 POST base（如 pollinations /openai），不做 takeIf 折叠。
        val url = rawBase + (request.chatPath ?: "/chat/completions")
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
