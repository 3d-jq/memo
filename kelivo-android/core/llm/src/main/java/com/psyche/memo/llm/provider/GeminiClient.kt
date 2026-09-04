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
    private val retryOptions: AutoRetryOptions = AutoRetryOptions(),
    private val cancellations: CancellationRegistry = CancellationRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmClient {

    override fun supports(providerId: String): Boolean =
        providerId == "gemini" || providerId.contains("google")

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
        val body = buildBody(request)
        val call = newCall(request, body, stream = false)
        val response = await(call)
        val text = response.body?.string()
        response.close()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text ?: ""}")
        val obj = json.parseToJsonElement(text ?: "{}").jsonObject ?: return LlmTextResult(emptyList(), null, null)
        return decodeCandidate(obj)
    }

    /** Gemini list via /v1beta/models?key=... — limited support; UI uses configured models too. */
    override suspend fun listModels(baseUrl: String, apiKey: String): List<com.psyche.memo.llm.client.LlmModelInfo> {
        val url = if (baseUrl.endsWith("/v1beta")) "$baseUrl/models?key=$apiKey" else "$baseUrl/v1beta/models?key=$apiKey"
        val call = httpClient.newCall(Request.Builder().url(url).get().build())
        val response = await(call)
        return try {
            if (!response.isSuccessful) return emptyList()
            val obj = json.parseToJsonElement(response.body?.string() ?: "{}").jsonObject
            (obj["models"] as? JsonArray)?.mapNotNull { el ->
                val id = (el.jsonObject["name"] as? JsonPrimitive)?.content?.substringAfterLast("/")
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
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source: BufferedSource = response.body?.source() ?: throw IOException("no body")
            val parser = SseEventParser(recoverAdjacentJsonDataRecords = false)
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                for (event in parser.add(line + "\n")) {
                    for (chunk in decodeEvent(event)) onChunk(chunk)
                }
            }
            for (event in parser.close()) {
                for (chunk in decodeEvent(event)) onChunk(chunk)
            }
        } finally {
            response.close()
        }
    }

    private fun decodeEvent(event: com.psyche.memo.llm.stream.SseEvent): List<StreamChunk> {
        val data = event.data
        if (data.isEmpty()) return emptyList()
        val obj = try { json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return emptyList() }
        val out = ArrayList<StreamChunk>()
        obj["candidates"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject?.let { cand ->
            cand["content"]?.jsonObject?.get("parts")?.let { it as? JsonArray }?.forEach { p ->
                val part = p.jsonObject ?: return@forEach
                val isThought = (part["thought"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
                val text = (part["text"] as? JsonPrimitive)?.content ?: ""
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
                val isThought = (part["thought"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
                val text = (part["text"] as? JsonPrimitive)?.content ?: ""
                if (text.isNotEmpty()) {
                    if (isThought) thought += text else textParts.add(text)
                }
            }
        }
        val usage = obj["usageMetadata"]?.let { it.jsonObject }?.let { u ->
            LlmUsage(
                promptTokens = (u["promptTokenCount"] as? JsonPrimitive)?.content?.toIntOrNull(),
                completionTokens = (u["candidatesTokenCount"] as? JsonPrimitive)?.content?.toIntOrNull(),
                totalTokens = (u["totalTokenCount"] as? JsonPrimitive)?.content?.toIntOrNull(),
            )
        }
        val finish = obj["candidates"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject
            ?.let { (it["finishReason"] as? JsonPrimitive)?.content }
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
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", msg.content ?: "") })
                        }
                    })
                }
            }
        }
        val generationConfig = buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("maxOutputTokens", it) }
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
        val base = request.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1beta")) {
            "$base/models/${request.modelId}:${if (stream) "streamGenerateContent" else "generateContent"}?alt=sse"
        } else {
            "$base/v1beta/models/${request.modelId}:${if (stream) "streamGenerateContent" else "generateContent"}?alt=sse"
        }
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
