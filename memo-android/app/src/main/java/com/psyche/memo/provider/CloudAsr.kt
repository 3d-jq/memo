package com.psyche.memo.provider

import com.psyche.memo.ui.AsrServiceOptions
import com.psyche.memo.ui.MimoAsrOptions
import com.psyche.memo.ui.StepAsrOptions
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

/** 云端 ASR 失败（原版 `AsrException`）。 */
class AsrException(message: String) : Exception(message)

/**
 * 一次云端识别会话 —— `cloud_asr_service.dart::CloudAsrSession` 的 Android 版。
 *
 * 音频以 **PCM16 单声道**分块喂进来；provider 自己决定什么时候真的发一次请求
 * （HTTP 两家按 [segmentDurationSec] 攒够一段就发，WebSocket 四家是流式直发）。
 * [partials] 每次识别出新文本都会回调一条「到目前为止的完整转写」。
 */
interface CloudAsrSession {
    /** 追加一块 PCM16 单声道音频（字节数必须是偶数）。 */
    fun addPcm16(chunk: ByteArray)

    /** 冲掉剩余缓冲并返回最终转写。 */
    fun finish(): String

    /** 中止会话（不再发请求 / 释放资源）。 */
    fun cancel()

    /** 识别过程中的完整转写快照；[onError] 收到终态错误。 */
    fun observePartials(onPartial: (String) -> Unit, onError: (Exception) -> Unit)
}

/**
 * 云端 ASR 客户端 —— 1:1 port of `cloud_asr_service.dart::CloudAsrService.startSession`。
 *
 * 已接：[MimoAsrOptions]（`/chat/completions` + WAV base64 分段）、
 * [StepAsrOptions]（`/v1/audio/asr/sse` SSE 增量 + 重试）。
 * 未接：openai_realtime / dashscope / qwen_audio / volcengine（四个 WebSocket 流式）
 * 与 sherpa_onnx（离线模型，桌面向）——[startSession] 会抛 [AsrException]。
 */
object CloudAsrService {

    /** 原版 `_MimoAsrSession`/`_StepAsrSession` 的分段上限：6MB 与按秒数换算取小。 */
    internal const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

    /** 原版 `_stepMinimumSegmentBytes`：太短的尾巴不值得发一次请求。 */
    internal const val STEP_MIN_SEGMENT_BYTES = 3200

    /** 原版 `_stepMaximumRetries`（含首次）。 */
    internal const val STEP_MAX_ATTEMPTS = 3

    internal const val COMPLETION_TIMEOUT_MS = 30_000L

    fun startSession(
        client: OkHttpClient,
        options: AsrServiceOptions,
        isCancelled: () -> Boolean = { false },
    ): CloudAsrSession = when (options) {
        is MimoAsrOptions -> MimoAsrSession(client, options, isCancelled)
        is StepAsrOptions -> StepAsrSession(client, options, isCancelled)
        else -> throw AsrException("${options.name} is not a supported cloud ASR service")
    }

    /** `_segmentByteLimit`：按秒数算的目标字节数与 6MB 取小；秒数 <= 0 就是 6MB。 */
    internal fun segmentByteLimit(sampleRate: Int, segmentDurationSec: Int): Int {
        if (segmentDurationSec <= 0) return MAX_SEGMENT_BYTES
        val timedBytes = sampleRate * 2 * segmentDurationSec
        return if (timedBytes < MAX_SEGMENT_BYTES) timedBytes else MAX_SEGMENT_BYTES
    }

    /** `_pcm16MonoToWav`：16bit 单声道 WAV 头。 */
    internal fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        val buffer = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + pcm.size)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    /** `_mimoTranscript`：choices[0].message.content。 */
    internal fun mimoTranscript(decoded: Any?): String? {
        val json = decoded as? JsonObject ?: return null
        val choices = json["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject ?: return null
        if (!message.containsKey("content")) return null
        return message["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
    }

    /** `_responseError`：只取 error.message / message，绝不回显整个 body（可能带密钥）。 */
    internal fun responseError(body: String): String {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return ""
        (json["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return json["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    /** `_redact`：错误信息里出现密钥就替换掉。 */
    internal fun redact(message: String, apiKey: String): String {
        val secret = apiKey.trim()
        return if (secret.isEmpty()) message else message.replace(secret, "[REDACTED]")
    }

    private fun joinUrl(base: String, path: String): String =
        base.trim().trimEnd('/') + (if (path.startsWith("/")) path else "/$path")

    private fun post(
        client: OkHttpClient,
        url: String,
        json: String,
        headers: Map<String, String>,
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    private fun Response.requireSuccess(label: String, apiKey: String) {
        if (code in 200..299) return
        val detail = runCatching { body?.string().orEmpty() }.getOrDefault("")
        val parsed = responseError(detail)
        val suffix = if (parsed.isEmpty()) "" else ": $parsed"
        close()
        throw AsrException(redact("$label failed with HTTP $code$suffix", apiKey))
    }

    /**
     * MiMo ASR（`_MimoAsrSession` 809-999）：攒够一段就 POST `/chat/completions`，
     * 音频是 `data:audio/wav;base64,…`，转写在 `choices[0].message.content`。
     */
    private class MimoAsrSession(
        private val client: OkHttpClient,
        private val options: MimoAsrOptions,
        private val isCancelled: () -> Boolean,
    ) : CloudAsrSession {

        private val pcm = ByteArrayOutputStream()
        private val completed = ArrayList<String>()
        private var terminal: AsrException? = null
        private var onPartial: ((String) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        private val segmentLimit get() = segmentBytes()

        override fun observePartials(onPartial: (String) -> Unit, onError: (Exception) -> Unit) {
            this.onPartial = onPartial
            this.onError = onError
            terminal?.let(onError)
        }

        override fun addPcm16(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            ensureActive()
            pcm.write(chunk)
            if (pcm.size() >= segmentLimit) {
                // 分段失败要**同时**抛给调用方并通知观察者（原版 `_terminate`
                // 把错误塞进 partialController 的 error 通道）。
                try {
                    flushSegment()
                } catch (e: AsrException) {
                    fail(e)
                    throw e
                }
            }
        }

        override fun finish(): String {
            ensureActive()
            return try {
                flushSegment()
                currentTranscript()
            } catch (e: AsrException) {
                fail(e)
                throw e
            }
        }

        override fun cancel() {
            terminal = terminal ?: AsrException("MiMo ASR session was cancelled")
            pcm.reset()
        }

        private fun segmentBytes(): Int = segmentByteLimit(options.sampleRate, options.segmentDurationSec)

        private fun flushSegment() {
            val bytes = pcm.toByteArray()
            pcm.reset()
            if (bytes.isEmpty()) return
            if (bytes.size % 2 != 0) throw AsrException("MiMo ASR received an incomplete PCM16 sample")
            val transcript = transcribe(bytes)
            if (transcript.isEmpty()) return
            completed.add(transcript)
            onPartial?.invoke(currentTranscript())
        }

        private fun transcribe(pcmBytes: ByteArray): String {
            val wav = pcm16MonoToWav(pcmBytes, options.sampleRate)
            val dataUrl = "data:audio/wav;base64," + java.util.Base64.getEncoder().encodeToString(wav)
            val body = buildJsonObject {
                put("model", options.model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "input_audio")
                                put("input_audio", buildJsonObject { put("data", dataUrl) })
                            })
                        })
                    })
                })
                if (options.language.trim().isNotEmpty()) {
                    put("asr_options", buildJsonObject { put("language", options.language) })
                }
            }
            val response = runCatching {
                post(
                    client,
                    joinUrl(options.baseUrl, "/chat/completions"),
                    body.toString(),
                    mapOf("api-key" to options.apiKey),
                )
            }.getOrElse { throw AsrException("MiMo ASR request failed") }
            if (isCancelled()) throw AsrException("MiMo ASR session was cancelled")
            response.requireSuccess("MiMo ASR", options.apiKey)
            val text = response.use { it.body?.string().orEmpty() }
            val decoded = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            return mimoTranscript(decoded)
                ?: throw AsrException("MiMo ASR response did not contain a transcript")
        }

        private fun currentTranscript(): String = completed.joinToString(" ")

        private fun ensureActive() {
            terminal?.let { throw it }
            if (isCancelled()) cancel()
            terminal?.let { throw it }
        }

        private fun fail(error: AsrException) {
            val safe = AsrException(redact(error.message ?: "MiMo ASR request failed", options.apiKey))
            terminal = safe
            onError?.invoke(safe)
        }
    }

    /**
     * Step ASR（`_StepAsrSession` 1001-1192）：攒够一段 POST `/v1/audio/asr/sse`，
     * 解析 SSE 增量转写，失败按 300ms×尝试次数退避重试。
     */
    private class StepAsrSession(
        private val client: OkHttpClient,
        private val options: StepAsrOptions,
        private val isCancelled: () -> Boolean,
    ) : CloudAsrSession {

        private val pcm = ByteArrayOutputStream()
        private val completed = ArrayList<String>()
        private var terminal: AsrException? = null
        private var onPartial: ((String) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        override fun observePartials(onPartial: (String) -> Unit, onError: (Exception) -> Unit) {
            this.onPartial = onPartial
            this.onError = onError
            terminal?.let(onError)
        }

        override fun addPcm16(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            ensureActive()
            pcm.write(chunk)
            if (pcm.size() >= segmentByteLimit(options.sampleRate, options.segmentDurationSec)) {
                // 同 MiMo：分段失败既抛给调用方，也通知观察者。
                try {
                    flushSegment()
                } catch (e: AsrException) {
                    fail(e)
                    throw e
                }
            }
        }

        override fun finish(): String {
            ensureActive()
            return try {
                flushSegment()
                currentTranscript()
            } catch (e: AsrException) {
                fail(e)
                throw e
            }
        }

        override fun cancel() {
            terminal = terminal ?: AsrException("Step ASR session was cancelled")
            pcm.reset()
        }

        private fun flushSegment() {
            val bytes = pcm.toByteArray()
            pcm.reset()
            if (bytes.isEmpty() || bytes.size < STEP_MIN_SEGMENT_BYTES) return
            if (bytes.size % 2 != 0) throw AsrException("Step ASR received an incomplete PCM16 sample")
            val transcript = transcribeWithRetries(bytes)
            if (transcript.isEmpty()) return
            completed.add(transcript)
            onPartial?.invoke(currentTranscript())
        }

        private fun transcribeWithRetries(pcmBytes: ByteArray): String {
            var last: AsrException? = null
            for (attempt in 1..STEP_MAX_ATTEMPTS) {
                try {
                    return transcribeOnce(pcmBytes)
                } catch (e: AsrException) {
                    last = AsrException(redact(e.message ?: "Step ASR request failed", options.apiKey))
                }
                if (isCancelled()) throw AsrException("Step ASR session was cancelled")
                if (attempt < STEP_MAX_ATTEMPTS) Thread.sleep(300L * attempt)
            }
            throw last ?: AsrException("Step ASR request failed")
        }

        private fun transcribeOnce(pcmBytes: ByteArray): String {
            val transcription = buildJsonObject {
                put("model", options.model)
                put("enable_itn", options.enableItn)
                put("enable_timestamp", options.enableTimestamp)
                if (options.language.trim().isNotEmpty()) put("language", options.language)
                if (options.hotwords.isNotEmpty()) {
                    put("hotwords", buildJsonArray { options.hotwords.forEach { add(JsonPrimitive(it)) } })
                }
            }
            val body = buildJsonObject {
                put("audio", buildJsonObject {
                    put("data", java.util.Base64.getEncoder().encodeToString(pcmBytes))
                    put("input", buildJsonObject {
                        put("transcription", transcription)
                        put("format", buildJsonObject {
                            put("type", "pcm")
                            put("codec", "pcm_s16le")
                            put("rate", options.sampleRate)
                            put("bits", 16)
                            put("channel", 1)
                        })
                    })
                })
            }
            val response = runCatching {
                post(
                    client,
                    joinUrl(options.baseUrl, "/v1/audio/asr/sse"),
                    body.toString(),
                    mapOf(
                        "Authorization" to "Bearer ${options.apiKey}",
                        "Accept" to "text/event-stream",
                    ),
                )
            }.getOrElse { throw AsrException("Step ASR request failed") }
            if (isCancelled()) throw AsrException("Step ASR session was cancelled")
            response.requireSuccess("Step ASR", options.apiKey)
            val text = response.use { it.body?.string().orEmpty() }
            return parseStepSseTranscript(text)
        }

        private fun currentTranscript(): String = completed.joinToString(" ")

        private fun ensureActive() {
            terminal?.let { throw it }
            if (isCancelled()) cancel()
            terminal?.let { throw it }
        }

        private fun fail(error: AsrException) {
            val safe = AsrException(redact(error.message ?: "Step ASR request failed", options.apiKey))
            terminal = safe
            onError?.invoke(safe)
        }
    }

    /**
     * `_parseStepSseTranscript`（cloud_asr_service.dart:1590-1624）：按 SSE 事件切分，
     * `transcript.text.delta` 追加、`transcript.text.done` 覆盖并结束、`error` 抛出。
     */
    internal fun parseStepSseTranscript(body: String): String {
        val transcript = StringBuilder()
        var eventType: String? = null
        val dataLines = ArrayList<String>()

        fun dispatch(): Boolean {
            if (eventType == null && dataLines.isEmpty()) return false
            val data = dataLines.joinToString("\n")
            val stop = handleStepSseEvent(eventType, data, transcript)
            eventType = null
            dataLines.clear()
            return stop
        }

        for (line in body.split("\n")) {
            if (line.isEmpty()) {
                if (dispatch()) break
                continue
            }
            if (line.startsWith(":")) continue
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            val value = if (separator < 0) {
                ""
            } else {
                line.substring(separator + 1).removePrefix(" ")
            }
            when (field) {
                "event" -> eventType = value
                "data" -> dataLines.add(value)
            }
        }
        dispatch()
        return transcript.toString().trim()
    }

    private fun handleStepSseEvent(
        eventType: String?,
        data: String,
        transcript: StringBuilder,
    ): Boolean {
        if (data == "[DONE]") return true
        val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
        val type = eventType?.trim()?.takeIf { it.isNotEmpty() } ?: json?.get("type")?.jsonPrimitive?.contentOrNull
        return when (type) {
            "transcript.text.delta" -> {
                transcript.append(extractStepText(json, if (json == null) data else ""))
                false
            }
            "transcript.text.done" -> {
                val finalText = extractStepText(json, "")
                if (finalText.trim().isNotEmpty()) {
                    transcript.setLength(0)
                    transcript.append(finalText)
                }
                true
            }
            "error" -> throw AsrException("Step ASR server error: ${extractStepError(json, data)}")
            else -> {
                val text = extractStepText(json, "")
                if (text.trim().isNotEmpty()) transcript.append(text)
                false
            }
        }
    }

    /** `_extractStepTranscriptText`：delta/text/content/transcript 依次取，支持嵌套。 */
    internal fun extractStepText(json: JsonObject?, fallback: String): String {
        if (json == null) return fallback
        for (key in listOf("delta", "text", "content", "transcript")) {
            val value = json[key] ?: continue
            if (value is JsonObject) {
                val nested = extractStepText(value, "")
                if (nested.trim().isNotEmpty()) return nested
            } else {
                val text = value.jsonPrimitive.contentOrNull.orEmpty()
                if (text.trim().isNotEmpty()) return text
            }
        }
        for (key in listOf("data", "result", "transcript")) {
            val value = json[key] as? JsonObject ?: continue
            val nested = extractStepText(value, "")
            if (nested.trim().isNotEmpty()) return nested
        }
        return fallback
    }

    private fun extractStepError(json: JsonObject?, fallback: String): String {
        if (json == null) return fallback
        (json["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.trim().isNotEmpty() }?.let { return it }
        val message = json["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (message.trim().isEmpty()) fallback else message
    }

    /** 只给测试用：把 SSE 文本喂给解析器（等价 `parseStepSseTranscript`）。 */
    internal fun parseStepSseForTest(body: String): String = parseStepSseTranscript(body)

    /** 只给测试用：从 SSE 流式读（生产路径是一次性 body 解析）。 */
    internal fun readSseData(source: BufferedSource, onData: (String) -> Unit) {
        val buffer = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (buffer.isNotEmpty()) {
                    onData(buffer.toString())
                    buffer.setLength(0)
                }
                continue
            }
            if (!line.startsWith("data:")) continue
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line.substring(5).trim())
        }
        if (buffer.isNotEmpty()) onData(buffer.toString())
    }
}
