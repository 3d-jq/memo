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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * Anthropic Claude client via `/v1/messages` SSE (mirrors Dart
 * claude_official.dart). Headers: x-api-key + anthropic-version 2023-06-01.
 * SSE events: message_start / content_block_start / content_block_delta
 * (text_delta | thinking_delta | input_json_delta) / message_delta /
 * message_stop.
 */
class ClaudeClient(
    private val httpClient: OkHttpClient,
    private val retryOptions: AutoRetryOptions = AutoRetryOptions(),
    private val cancellations: CancellationRegistry = CancellationRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {

    override fun supports(providerId: String): Boolean =
        providerId == "anthropic" || providerId.contains("claude")

    override fun streamChat(request: LlmRequest): Flow<StreamChunk> = flow {
        val maxRetries = if (retryOptions.enabled) retryOptions.maxRetries else 0
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
                if (yielded || attemptCount > maxRetries || !shouldRetryError(e, retryOptions)) throw e
                val delayMs = backoffDelay(attemptCount - 1, retryOptions)
                emit(StreamChunk.Error("retrying in ${delayMs}ms: ${e.message}"))
                delay(delayMs)
            }
        }
    }

    override suspend fun complete(request: LlmRequest): LlmTextResult {
        val body = buildBody(request, stream = false)
        val call = newCall(request, body)
        val response = await(call)
        val text = response.body?.string()
        response.close()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text ?: ""}")
        val obj = json.parseToJsonElement(text ?: "{}").jsonObject ?: return LlmTextResult(emptyList(), null, null)
        return decodeResponseObj(obj)
    }

    /** Claude has no public /models list; the UI uses configured models. */
    override suspend fun listModels(baseUrl: String, apiKey: String): List<com.psyche.memo.llm.client.LlmModelInfo> = emptyList()

    private suspend fun runStream(request: LlmRequest, onChunk: suspend (StreamChunk) -> Unit) {
        val body = buildBody(request, stream = true)
        val call = newCall(request, body)
        val response = await(call)
        try {
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source: BufferedSource = response.body?.source() ?: throw IOException("no body")
            val parser = SseEventParser(recoverAdjacentJsonDataRecords = false)
            val state = ClaudeStreamState()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                for (event in parser.add(line + "\n")) {
                    for (chunk in decodeEvent(event, state)) onChunk(chunk)
                }
            }
            for (event in parser.close()) {
                for (chunk in decodeEvent(event, state)) onChunk(chunk)
            }
        } finally {
            response.close()
        }
    }

    /**
     * Per-response decode state. [startUsage] keeps the message_start usage so
     * the message_delta Finish can merge input tokens in (the delta usage only
     * carries output/cache counters, mirroring claude_decoder._onMessageDelta).
     */
    private class ClaudeStreamState {
        var startUsage: JsonObject? = null
        var finishEmitted = false
    }

    /**
     * Merged usage object in the field shape ChatViewModel.parseUsage reads:
     * input_tokens / output_tokens / cache_read_input_tokens (cache_read +
     * cache_creation summed, same as claudeUsageFromMap).
     */
    private fun buildClaudeUsageJson(start: JsonObject?, delta: JsonObject?): JsonObject? {
        if (start == null && delta == null) return null
        fun intOf(o: JsonObject?, key: String): Int? =
            (o?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()
        val input = intOf(delta, "input_tokens") ?: intOf(start, "input_tokens")
        val output = intOf(delta, "output_tokens") ?: intOf(start, "output_tokens")
        if (input == null && output == null) return null
        val cached = (intOf(delta, "cache_read_input_tokens")
            ?: intOf(start, "cache_read_input_tokens")) ?: 0
        val cacheCreated = (intOf(delta, "cache_creation_input_tokens")
            ?: intOf(start, "cache_creation_input_tokens")) ?: 0
        return buildJsonObject {
            input?.let { put("input_tokens", it) }
            output?.let { put("output_tokens", it) }
            if (cached + cacheCreated > 0) put("cache_read_input_tokens", cached + cacheCreated)
        }
    }

    private fun decodeEvent(event: com.psyche.memo.llm.stream.SseEvent, state: ClaudeStreamState): List<StreamChunk> {
        val data = event.data
        if (data.isEmpty()) return emptyList()
        val obj = try { json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return emptyList() }
        val out = ArrayList<StreamChunk>()
        when (obj["type"]?.let { (it as? JsonPrimitive)?.content } ?: "") {
            "message_start" -> {
                state.startUsage = (obj["message"] as? JsonObject)
                    ?.get("usage") as? JsonObject
            }
            "content_block_delta" -> {
                val delta = obj["delta"]?.jsonObject ?: return out
                when (delta["type"]?.let { (it as? JsonPrimitive)?.content } ?: "") {
                    "text_delta" -> {
                        val text = (delta["text"] as? JsonPrimitive)?.content ?: ""
                        if (text.isNotEmpty()) out.add(StreamChunk.TextDelta(text))
                    }
                    "thinking_delta" -> {
                        val text = (delta["thinking"] as? JsonPrimitive)?.content ?: ""
                        if (text.isNotEmpty()) out.add(StreamChunk.ReasoningDelta(text))
                    }
                    "input_json_delta" -> {
                        val args = (delta["partial_json"] as? JsonPrimitive)?.content ?: ""
                        if (args.isNotEmpty()) out.add(StreamChunk.ToolCallDelta(null, "", args))
                    }
                }
            }
            "content_block_start" -> {
                val cb = obj["content_block"]?.jsonObject
                if (cb?.get("type")?.let { (it as? JsonPrimitive)?.content } == "tool_use") {
                    val id = (cb["id"] as? JsonPrimitive)?.content ?: ""
                    val name = (cb["name"] as? JsonPrimitive)?.content ?: ""
                    if (name.isNotEmpty()) out.add(StreamChunk.ToolCallDelta(id, name, ""))
                }
            }
            "message_delta" -> {
                // usage may sit on the delta itself or on a nested message
                // holder (same fallback chain as claude_decoder._onMessageDelta).
                val deltaUsage = obj["usage"] as? JsonObject
                val messageUsage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
                val usageJson = buildClaudeUsageJson(state.startUsage, deltaUsage ?: messageUsage)
                val delta = obj["delta"] as? JsonObject
                val stopReason = delta
                    ?.let { (it["stop_reason"] ?: it["stopReason"]) }
                    ?.let { (it as? JsonPrimitive)?.content }
                if (!state.finishEmitted && (usageJson != null || !stopReason.isNullOrEmpty())) {
                    state.finishEmitted = true
                    out.add(StreamChunk.Finish(stopReason, usageJson))
                }
            }
        }
        return out
    }

    private fun decodeResponseObj(obj: JsonObject): LlmTextResult {
        val parts = ArrayList<String>()
        var usage: LlmUsage? = null
        obj["content"]?.let { it as? kotlinx.serialization.json.JsonArray }?.forEach { p ->
            p.jsonObject?.let { part ->
                when (part["type"]?.let { (it as? JsonPrimitive)?.content } ?: "") {
                    "text" -> (part["text"] as? JsonPrimitive)?.content?.let { parts.add(it) }
                    "thinking" -> (part["thinking"] as? JsonPrimitive)?.content?.let { parts.add(it) }
                }
            }
        }
        obj["usage"]?.jsonObject?.let { u ->
            usage = LlmUsage(
                promptTokens = (u["input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
                completionTokens = (u["output_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
                totalTokens = null,
            )
        }
        val finish = (obj["stop_reason"] as? JsonPrimitive)?.content
        return LlmTextResult(parts = parts, usage = usage, finishReason = finish)
    }

    private fun buildBody(request: LlmRequest, stream: Boolean): String {
        val messages = buildJsonArray {
            for (m in request.messages) {
                if (m.role == "system") continue // handled as system field
                add(buildJsonObject {
                    put("role", if (m.role == "assistant") "assistant" else "user")
                    put("content", m.content ?: "")
                })
            }
        }
        val system = request.messages.filter { it.role == "system" && !it.content.isNullOrBlank() }
            .joinToString("\n\n") { it.content!! }
        return buildJsonObject {
            put("model", request.modelId)
            put("messages", messages)
            put("stream", stream)
            put("max_tokens", request.maxTokens?.coerceAtLeast(1) ?: 4096)
            request.temperature?.let { put("temperature", it) }
            if (system.isNotEmpty()) put("system", system)
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.parseToJsonElement(tool.inputSchemaJson))
                        })
                    }
                })
            }
        }.toString()
    }

    private fun newCall(request: LlmRequest, body: String): Call {
        val base = request.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        return httpClient.newCall(
            Request.Builder()
                .url(url.toHttpUrl())
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("x-api-key", request.apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
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
