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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
 * Google Gemini client via `:streamGenerateContent?alt=sse` (mirrors Dart
 * google_common.dart). System messages become systemInstruction; assistant
 * role is remapped to `model`.
 */
class GeminiClient(
    private val httpClient: OkHttpClient,
    private val retryOptionsProvider: () -> AutoRetryOptions = { AutoRetryOptions() },

    private val cancellations: CancellationRegistry = CancellationRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {
    /** Per-request read: the container supplies live settings (auto_retry_options). */
    private fun retryOptions(): AutoRetryOptions = retryOptionsProvider()

    override fun supports(providerId: String): Boolean =
        providerId == "gemini" || providerId.contains("google")

    override fun streamChat(request: LlmRequest): Flow<StreamChunk> = flow {
        val maxRetries = if (retryOptions().enabled) retryOptions().maxRetries else 0
        var attemptCount = 0
        while (true) {
            if (cancellations.isCancelled(request.providerId)) throw kotlinx.coroutines.CancellationException("cancelled")
            attemptCount++
            var yielded = false
            try {
                runStream(request) { chunk ->
                    yielded = true
                    emit(chunk)
                }
                return@flow
            } catch (e: Throwable) {
                if (cancellations.isCancelled(request.providerId)) throw kotlinx.coroutines.CancellationException("cancelled")
                if (yielded || attemptCount > maxRetries || !shouldRetryError(e, retryOptions())) throw e
                val delayMs = backoffDelay(attemptCount - 1, retryOptions())
                // Dart chat_api_service.dart:215-222 —— 结构化 RetryPending 事件
                //（见 OpenAiChatCompletionsClient 同段注释）。
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
        val body = buildBody(request)
        val call = newCall(request, body, stream = false)
        val response = await(call)
        val text = withContext(Dispatchers.IO) { response.body?.string() }
        response.close()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text ?: ""}")
        val obj = json.parseToJsonElement(text ?: "{}").jsonObject ?: return LlmTextResult(emptyList(), null, null)
        return decodeCandidate(obj)
    }

    override fun completeAsChunks(request: LlmRequest): Flow<StreamChunk> = flow {
        val maxRetries = if (retryOptions().enabled) retryOptions().maxRetries else 0
        var attemptCount = 0
        while (true) {
            if (cancellations.isCancelled(request.providerId)) throw kotlinx.coroutines.CancellationException("cancelled")
            attemptCount++
            var yielded = false
            try {
                for (chunk in runNonStream(request)) {
                    yielded = true
                    emit(chunk)
                }
                return@flow
            } catch (e: Throwable) {
                if (cancellations.isCancelled(request.providerId)) throw kotlinx.coroutines.CancellationException("cancelled")
                if (yielded || attemptCount > maxRetries || !shouldRetryError(e, retryOptions())) throw e
                val delayMs = backoffDelay(attemptCount - 1, retryOptions())
                // Dart chat_api_service.dart:215-222 —— 结构化 RetryPending 事件
                //（见 OpenAiChatCompletionsClient 同段注释）。
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
        val body = buildBody(request)
        val call = newCall(request, body, stream = false)
        val response = await(call)
        val text = withContext(Dispatchers.IO) { response.body?.string() }
        response.close()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text ?: ""}")
        val obj = json.parseToJsonElement(text ?: "{}").jsonObject
        val state = GeminiStreamState()
        val out = ArrayList<StreamChunk>()
        out.addAll(
            decodeEvent(com.psyche.memo.llm.stream.SseEvent(null, null, obj.toString(), null), state),
        )
        // finishReason 缺失时兜一条 Finish，别让生成循环等一个永远不会来的终止块。
        if (!state.finishEmitted) out.add(StreamChunk.Finish(state.finishReason, state.usageJson))
        return out
    }

    /** Gemini list via /v1beta/models?key=... — limited support; UI uses configured models too. */
    override suspend fun listModels(baseUrl: String, apiKey: String): List<com.psyche.memo.llm.client.LlmModelInfo> {
        val url = if (baseUrl.endsWith("/v1beta")) "$baseUrl/models?key=$apiKey" else "$baseUrl/v1beta/models?key=$apiKey"
        val call = httpClient.newCall(Request.Builder().url(url).get().build())
        val response = await(call)
        return try {
            if (!response.isSuccessful) return emptyList()
            val obj = json.parseToJsonElement(withContext(Dispatchers.IO) { response.body?.string() } ?: "{}").jsonObject
            (obj["models"] as? JsonArray)?.mapNotNull { el ->
                val id = (el.jsonObject["name"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast("/")
                    ?: return@mapNotNull null
                com.psyche.memo.llm.client.LlmModelInfo(id = id, displayName = id)
            } ?: emptyList()
        } finally {
            response.close()
        }
    }

    private suspend fun runStream(request: LlmRequest, onChunk: suspend (StreamChunk) -> Unit) {
        val body = buildBody(request)
        val call = newCall(request, body, stream = true)
        val response = await(call)
        try {
            // 非 2xx 要带上响应体（原版 `HTTP ${statusCode}: $errorBody`）——见 httpFailure。
            if (!response.isSuccessful) throw httpFailure(response)
            val source: BufferedSource = response.body?.source() ?: throw IOException("no body")
            val parser = SseEventParser(recoverAdjacentJsonDataRecords = false)
            val state = GeminiStreamState()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                for (event in parser.add(line + "\n")) {
                    for (chunk in decodeEvent(event, state)) onChunk(chunk)
                }
            }
            for (event in parser.close()) {
                for (chunk in decodeEvent(event, state)) onChunk(chunk)
            }
            // 兜底：usageMetadata 出现在最后却没有携带 finishReason（或流被
            // 服务端提前关闭）时补发 Finish，保证 token 展示不丢。
            if (!state.finishEmitted) {
                val usage = state.usageJson
                val finish = state.finishReason
                if (usage != null || !finish.isNullOrEmpty()) {
                    state.finishEmitted = true
                    onChunk(StreamChunk.Finish(finish, usage))
                }
            }
        } finally {
            response.close()
        }
    }

    /** Per-response decode state (usageMetadata accumulates across chunks). */
    private class GeminiStreamState {
        var usageJson: JsonObject? = null
        var finishReason: String? = null
        var finishEmitted = false
    }

    /**
     * usageMetadata → usage object in the field shape ChatViewModel.parseUsage
     * reads: prompt_tokens / completion_tokens / total_tokens (mirrors
     * google_decoder._parseEvent which merges promptTokenCount /
     * candidatesTokenCount / totalTokenCount).
     */
    private fun buildGeminiUsageJson(um: JsonObject): JsonObject {
        fun intOf(key: String): Int? =
            (um[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return buildJsonObject {
            intOf("promptTokenCount")?.let { put("prompt_tokens", it) }
            intOf("candidatesTokenCount")?.let { put("completion_tokens", it) }
            intOf("totalTokenCount")?.let { put("total_tokens", it) }
        }
    }

    private fun decodeEvent(event: com.psyche.memo.llm.stream.SseEvent, state: GeminiStreamState): List<StreamChunk> {
        val data = event.data
        if (data.isEmpty()) return emptyList()
        // 带内错误帧（200 OK 里塞 {"error":…}）必须在解析正文前抛出，
        // 否则半成品会被当成功收尾。chat_api_helpers.dart:857-919。
        throwIfInBandStreamError(data)
        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            // chat_api_helpers.dart:839-845 —— 畸形事件不打断流，但要留痕。
            com.psyche.memo.common.logging.FlutterLogger.log(
                "provider=gemini eventType=${event.event ?: "message"} error=$e",
                tag = "DecoderParseError",
            )
            return emptyList()
        }
        val out = ArrayList<StreamChunk>()
        // Usage first so a Finish emitted below carries the freshest counters.
        (obj["usageMetadata"] as? JsonObject)?.let { um ->
            state.usageJson = buildGeminiUsageJson(um)
        }
        obj["candidates"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject?.let { cand ->
            val finish = (cand["finishReason"] as? JsonPrimitive)?.contentOrNull
            if (!finish.isNullOrEmpty()) {
                state.finishReason = finish
                if (!state.finishEmitted) {
                    state.finishEmitted = true
                    out.add(StreamChunk.Finish(finish, state.usageJson))
                }
            }
            cand["content"]?.jsonObject?.get("parts")?.let { it as? JsonArray }?.forEach { p ->
                val part = p.jsonObject ?: return@forEach
                val isThought = (part["thought"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true
                val text = (part["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (text.isNotEmpty()) {
                    out.add(if (isThought) StreamChunk.ReasoningDelta(text) else StreamChunk.TextDelta(text))
                }
            }
        }
        return out
    }

    private fun decodeCandidate(obj: JsonObject): LlmTextResult {
        val textParts = ArrayList<String>()
        var thought = ""
        obj["candidates"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject?.let { cand ->
            if (cand["finishReason"] is JsonPrimitive) {
                // surfaced via caller-side finishReason; no-arg
            }
            cand["content"]?.jsonObject?.get("parts")?.let { it as? JsonArray }?.forEach { p ->
                val part = p.jsonObject ?: return@forEach
                val isThought = (part["thought"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true
                val text = (part["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (text.isNotEmpty()) {
                    if (isThought) thought += text else textParts.add(text)
                }
            }
        }
        val usage = obj["usageMetadata"]?.let { it.jsonObject }?.let { u ->
            LlmUsage(
                promptTokens = (u["promptTokenCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                completionTokens = (u["candidatesTokenCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                totalTokens = (u["totalTokenCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }
        val finish = obj["candidates"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject
            ?.let { (it["finishReason"] as? JsonPrimitive)?.contentOrNull }
        val parts = ArrayList<String>()
        if (thought.isNotEmpty()) parts.add(thought)
        parts.addAll(textParts)
        return LlmTextResult(parts = parts, usage = usage, finishReason = finish)
    }

    private fun buildBody(request: LlmRequest): String {
        var systemInstruction: String? = null
        val contents = buildJsonArray {
            for (msg in request.messages) {
                if (msg.role == "system") {
                    msg.content?.let { s ->
                        systemInstruction = if (systemInstruction.isNullOrEmpty()) s else "$systemInstruction\n\n$s"
                    }
                } else {
                    val role = if (msg.role == "assistant") "model" else "user"
                    add(buildJsonObject {
                        put("role", role)
                        put("parts", com.psyche.memo.llm.client.MessageContent.geminiParts(msg))
                    })
                }
            }
        }
        val generationConfig = buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("topP", it) }
            request.maxTokens?.let { put("maxOutputTokens", it) }
            // google_common.dart L710-715 —— reasoning 时写入 thinkingConfig。
            if (request.reasoning) {
                val thinking = com.psyche.memo.llm.client.ReasoningBudget
                    .googleThinkingConfig(request.modelId, request.thinkingBudget)
                if (thinking.isNotEmpty()) put("thinkingConfig", thinking)
            }
        }
        return buildJsonObject {
            put("contents", contents)
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
            systemInstruction?.let { si ->
                if (si.isNotEmpty()) {
                    putJsonObject("systemInstruction") {
                        putJsonObject("parts") { put("text", si) }
                    }
                }
            }
        }.toString()
    }

    private fun newCall(request: LlmRequest, body: String, stream: Boolean): Call {
        // Mirror Flutter google_common.dart L949: "$base/models/$model:…"
        // — trimmed base, no injected /v1beta (custom bases already carry it).
        val base = request.baseUrl.trimEnd('/')
        val url = "$base/models/${request.modelId}:${if (stream) "streamGenerateContent" else "generateContent"}?alt=sse"
        return httpClient.newCall(
            Request.Builder()
                .url(url.toHttpUrl())
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("x-goog-api-key", request.apiKey)
                .apply { for ((k, v) in request.extraHeaders) header(k, v) }
                .build(),
        )
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
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
