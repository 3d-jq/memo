package com.psyche.memo.provider

import com.psyche.memo.ui.AsrServiceOptions
import com.psyche.memo.ui.DashScopeAsrOptions
import com.psyche.memo.ui.MimoAsrOptions
import com.psyche.memo.ui.OpenAiRealtimeAsrOptions
import com.psyche.memo.ui.QwenAudioAsrOptions
import com.psyche.memo.ui.StepAsrOptions
import com.psyche.memo.ui.VolcengineAsrOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import okio.ByteString.Companion.toByteString

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
        /** 仅供测试：覆盖 Qwen Audio 的 endpoint（它的 URL 由 workspace/region 拼出来，没法指到 mock）。 */
        endpointOverride: String? = null,
    ): CloudAsrSession = when (options) {
        is MimoAsrOptions -> MimoAsrSession(client, options, isCancelled)
        is StepAsrOptions -> StepAsrSession(client, options, isCancelled)
        is VolcengineAsrOptions -> VolcengineAsrSession(client, options, isCancelled)
        is QwenAudioAsrOptions -> QwenAudioAsrSession(client, options, isCancelled, endpointOverride)
        is OpenAiRealtimeAsrOptions -> RealtimeAsrSession(            client = client,
            url = openAiEndpoint(options.websocketUrl),
            headers = mapOf("Authorization" to "Bearer ${options.apiKey}"),
            label = "OpenAI Realtime",
            apiKey = options.apiKey,
            sessionUpdate = openAiSessionUpdate(options),
            addEvent = { audio, _ ->
                buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", audio)
                }
            },
            commitEvent = { _ ->
                buildJsonObject { put("type", "input_audio_buffer.commit") }
            },
            finishEvent = null,
            waitForSessionFinished = false,
            isCancelled = isCancelled,
        )
        is DashScopeAsrOptions -> RealtimeAsrSession(
            client = client,
            url = dashScopeEndpoint(options.websocketUrl, options.model),
            headers = mapOf("Authorization" to "Bearer ${options.apiKey}"),
            label = "DashScope",
            apiKey = options.apiKey,
            sessionUpdate = dashScopeSessionUpdate(options),
            addEvent = { audio, eventId ->
                buildJsonObject {
                    put("event_id", eventId)
                    put("type", "input_audio_buffer.append")
                    put("audio", audio)
                }
            },
            // 开了 VAD 就由服务端断句，不再显式 commit（原版 `vadThreshold > 0 ? null : …`）。
            commitEvent = if (options.vadThreshold > 0) {
                null
            } else {
                { eventId ->
                    buildJsonObject {
                        put("event_id", eventId)
                        put("type", "input_audio_buffer.commit")
                    }
                }
            },
            finishEvent = { eventId ->
                buildJsonObject {
                    put("event_id", eventId)
                    put("type", "session.finish")
                }
            },
            waitForSessionFinished = true,
            isCancelled = isCancelled,
        )
        else -> throw AsrException("${options.name} is not a supported cloud ASR service")
    }

    /** `_openAiEndpoint`：没带 `intent` 就补上 `transcription`。 */
    internal fun openAiEndpoint(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("intent=")) return trimmed
        val separator = if (trimmed.contains("?")) "&" else "?"
        return "$trimmed${separator}intent=transcription"
    }

    /** `_dashScopeEndpoint`：没带 `model` 就补上。 */
    internal fun dashScopeEndpoint(raw: String, model: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("model=")) return trimmed
        val separator = if (trimmed.contains("?")) "&" else "?"
        return "$trimmed$separator" + "model=" + java.net.URLEncoder.encode(model, "UTF-8")
    }

    /** `_openAiSessionUpdate`（cloud_asr_service.dart:1700-1734）。 */
    internal fun openAiSessionUpdate(options: OpenAiRealtimeAsrOptions): JsonObject = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "transcription")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", options.sampleRate)
                    })
                    put("transcription", buildJsonObject {
                        put("model", options.model)
                        if (options.language.trim().isNotEmpty()) {
                            // gpt-live-transcribe 用 languages 数组，其它模型用 language。
                            if (options.model == "gpt-live-transcribe") {
                                put("languages", buildJsonArray { add(JsonPrimitive(options.language)) })
                            } else {
                                put("language", options.language)
                            }
                        }
                        if (options.prompt.trim().isNotEmpty()) put("prompt", options.prompt)
                    })
                    put("noise_reduction", buildJsonObject { put("type", "near_field") })
                    if (options.vadThreshold > 0) {
                        put("turn_detection", buildJsonObject {
                            put("type", "server_vad")
                            put("threshold", options.vadThreshold)
                            put("prefix_padding_ms", options.prefixPaddingMs)
                            put("silence_duration_ms", options.silenceDurationMs)
                        })
                    } else {
                        put("turn_detection", kotlinx.serialization.json.JsonNull)
                    }
                })
            })
        })
    }

    /** `_dashScopeSessionUpdate`（cloud_asr_service.dart:1736-1753）。 */
    internal fun dashScopeSessionUpdate(options: DashScopeAsrOptions): JsonObject = buildJsonObject {
        put("event_id", "memo_asr_session_update")
        put("type", "session.update")
        put("session", buildJsonObject {
            put("input_audio_format", "pcm")
            put("sample_rate", options.sampleRate)
            put("input_audio_transcription", buildJsonObject {
                if (options.language.trim().isNotEmpty()) put("language", options.language)
            })
            if (options.vadThreshold > 0) {
                put("turn_detection", buildJsonObject {
                    put("type", "server_vad")
                    put("threshold", options.vadThreshold)
                    put("silence_duration_ms", options.silenceDurationMs)
                })
            } else {
                put("turn_detection", kotlinx.serialization.json.JsonNull)
            }
        })
    }

    /**
     * Qwen Audio ASR（原版 `_QwenAudioAsrSession` 1196-1400）：DashScope
     * `/api-ws/v1/inference` 的 run-task / 二进制 PCM / result-generated / finish-task 协议。
     * `result-generated` 只给**当前句**，所以按 `sentence_end` 累积已定稿句子 + 当前半句。
     * （RikkaHub 的 speech 模块没有这一家，只能按 Flutter 侧照做。）
     */
    private class QwenAudioAsrSession(
        client: OkHttpClient,
        private val options: QwenAudioAsrOptions,
        private val isCancelled: () -> Boolean,
        endpointOverride: String? = null,
    ) : CloudAsrSession, okhttp3.WebSocketListener() {

        private val taskId = java.util.UUID.randomUUID().toString()
        private val started = java.util.concurrent.CountDownLatch(1)
        private val finished = java.util.concurrent.CountDownLatch(1)
        @Volatile private var finalized = ""
        @Volatile private var transcript = ""
        @Volatile private var terminal: AsrException? = null
        @Volatile private var cleanedUp = false
        private var socket: okhttp3.WebSocket? = null
        private var onPartial: ((String) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        init {
            val builder = Request.Builder().url(endpointOverride ?: options.websocketUrl)
                .addHeader("Authorization", "Bearer ${options.apiKey}")
            if (options.workspaceId.trim().isNotEmpty()) {
                builder.addHeader("X-DashScope-WorkSpace", options.workspaceId.trim())
            }
            socket = client.newWebSocket(builder.build(), this)
        }

        override fun observePartials(onPartial: (String) -> Unit, onError: (Exception) -> Unit) {
            this.onPartial = onPartial
            this.onError = onError
            terminal?.let(onError)
        }

        override fun addPcm16(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            ensureActive()
            // 服务端要先回 task-started 才能收音频。
            if (started.count > 0) {
                val ok = started.await(COMPLETION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!ok) throw AsrException("Qwen Audio ASR timed out waiting for task-started")
            }
            ensureActive()
            val sent = runCatching { socket?.send(okio.ByteString.of(*chunk)) ?: false }.getOrDefault(false)
            if (!sent) throw AsrException("Qwen Audio ASR WebSocket send failed")
        }

        override fun finish(): String {
            ensureActive()
            if (started.count > 0) {
                started.await(COMPLETION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            sendJson(
                buildJsonObject {
                    put("header", buildJsonObject {
                        put("action", "finish-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    })
                    put("payload", buildJsonObject { put("input", buildJsonObject { }) })
                },
            )
            val ok = finished.await(COMPLETION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            terminal?.let { cleanup(); throw it }
            cleanup()
            if (!ok) throw AsrException("Qwen Audio ASR timed out")
            return transcript
        }

        override fun cancel() {
            terminal = terminal ?: AsrException("Qwen Audio ASR session was cancelled")
            started.countDown()
            finished.countDown()
            cleanup()
        }

        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            runCatching {
                sendJson(
                    buildJsonObject {
                        put("header", buildJsonObject {
                            put("action", "run-task")
                            put("task_id", taskId)
                            put("streaming", "duplex")
                        })
                        put("payload", buildJsonObject {
                            put("task_group", "audio")
                            put("task", "asr")
                            put("function", "recognition")
                            put("model", options.model)
                            put("parameters", buildJsonObject {
                                put("format", options.format)
                                put("sample_rate", options.sampleRate)
                            })
                            put("input", buildJsonObject { })
                        })
                    },
                )
            }.onFailure { fail(AsrException("Qwen Audio ASR session setup failed")) }
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            if (cleanedUp) return
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val header = json["header"] as? JsonObject ?: return
            when (header["event"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
                "task-started" -> started.countDown()
                "result-generated" -> {
                    val sentence = (((json["payload"] as? JsonObject)?.get("output") as? JsonObject)
                        ?.get("sentence") as? JsonObject) ?: return
                    val value = sentence["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (value.isEmpty()) return
                    if (isQwenSentenceEnd(sentence)) {
                        finalized = combineQwenAudioTranscript(finalized, value)
                        transcript = finalized
                    } else {
                        transcript = combineQwenAudioTranscript(finalized, value)
                    }
                    onPartial?.invoke(transcript)
                }
                "task-finished" -> finished.countDown()
                "task-failed" -> {
                    val detail = header["error_message"]?.jsonPrimitive?.contentOrNull
                        ?: header["error_code"]?.jsonPrimitive?.contentOrNull
                        ?: "task-failed"
                    fail(AsrException(redact("Qwen Audio ASR failed: $detail", options.apiKey)))
                }
            }
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            fail(AsrException("Qwen Audio ASR failed"))
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            if (!cleanedUp && terminal == null) finished.countDown()
        }

        private fun sendJson(event: JsonObject) {
            val sent = runCatching { socket?.send(event.toString()) ?: false }.getOrDefault(false)
            if (!sent) throw AsrException("Qwen Audio ASR WebSocket send failed")
        }

        private fun ensureActive() {
            terminal?.let { throw it }
            if (isCancelled()) cancel()
            terminal?.let { throw it }
        }

        private fun fail(error: AsrException) {
            if (terminal != null) return
            val safe = AsrException(redact(error.message ?: "Qwen Audio ASR failed", options.apiKey))
            terminal = safe
            started.countDown()
            finished.countDown()
            onError?.invoke(safe)
            cleanup()
        }

        private fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            runCatching { socket?.close(1000, "finished") }
            socket = null
        }
    }

    /** 原版 `_isQwenAudioSentenceEnd`：布尔或 "true"/"1" 都算句子结束。 */
    internal fun isQwenSentenceEnd(sentence: JsonObject): Boolean {
        val flag = sentence["sentence_end"] ?: return false
        val text = flag.jsonPrimitive.contentOrNull?.trim()?.lowercase()
        return when (flag.jsonPrimitive.contentOrNull) {
            null -> false
            else -> text == "true" || text == "1"
        }
    }

    /**
     * 原版 `combineQwenAudioTranscript`（cloud_asr_service.dart:1420-1430）：拉丁词边界补空格，
     * 中日韩直接相接。
     */
    internal fun combineQwenAudioTranscript(prefix: String, next: String): String {
        if (prefix.isEmpty()) return next
        if (next.isEmpty()) return prefix
        if (next.first().isWhitespace()) return prefix + next
        // 注意：原版用 `[A-Za-z0-9]`（**ASCII**），不能用 isLetterOrDigit ——
        // 后者对汉字也返回 true，会把「你好」+「世界」拼成「你好 世界」。
        val prefixEndsLatinWord = isAsciiAlnum(prefix.last())
        val prefixEndsLatinPunct = prefix.last() in ".!?…,;:'\")]"
        val nextStartsLatinWord = isAsciiAlnum(next.first())
        val needsSpace = nextStartsLatinWord && (prefixEndsLatinWord || prefixEndsLatinPunct)
        return if (needsSpace) "$prefix $next" else prefix + next
    }

    private fun isAsciiAlnum(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    /**
     * Volcengine ASR（RikkaHub `speech/.../VolcengineASRController.kt` 的协议移植；
     * 原版 Flutter 侧是同一套二进制帧，见 `cloud_asr_service.dart::_VolcengineAsrSession`）。
     *
     * 帧格式：4 字节头（0x11 / (msgType<<4|flags) / (serialization<<4|compression) / 0）
     * + 4 字节大端 payload 长度 + payload；配置帧 gzip 压缩、JSON 序列化，
     * 音频帧不压缩；`MSG_SERVER_RESPONSE`(0x09) 的 `result.text` 是「到目前的完整文本」，
     * 带 `FLAG_LAST_PACKET` 的那一帧表示收尾；`MSG_ERROR`(0x0F) 是 4 字节错误码 + 消息。
     */
    private class VolcengineAsrSession(
        client: OkHttpClient,
        private val options: VolcengineAsrOptions,
        private val isCancelled: () -> Boolean,
    ) : CloudAsrSession, okhttp3.WebSocketListener() {

        private val finished = java.util.concurrent.CountDownLatch(1)
        @Volatile private var transcript = ""
        @Volatile private var terminal: AsrException? = null
        @Volatile private var cleanedUp = false
        private var socket: okhttp3.WebSocket? = null
        private var onPartial: ((String) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        init {
            val request = Request.Builder()
                .url(options.websocketUrl.trim())
                .addHeader("X-Api-Key", options.apiKey)
                .addHeader("X-Api-Resource-Id", options.resourceId)
                .addHeader("X-Api-Request-Id", java.util.UUID.randomUUID().toString())
                .addHeader("X-Api-Sequence", "-1")
                .build()
            socket = client.newWebSocket(request, this)
        }

        override fun observePartials(onPartial: (String) -> Unit, onError: (Exception) -> Unit) {
            this.onPartial = onPartial
            this.onError = onError
            terminal?.let(onError)
        }

        override fun addPcm16(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            ensureActive()
            val ws = socket ?: throw AsrException("Volcengine ASR WebSocket is closed")
            // 队列积压就丢帧（RikkaHub 同款背压，避免内存涨爆）。
            if (ws.queueSize() > MAX_WS_QUEUE_BYTES) return
            sendFrame(MSG_AUDIO_ONLY, flags = 0, serialization = SER_NONE, compression = COMP_NONE, payload = chunk)
        }

        override fun finish(): String {
            ensureActive()
            sendFrame(
                MSG_AUDIO_ONLY,
                flags = FLAG_LAST_PACKET,
                serialization = SER_NONE,
                compression = COMP_NONE,
                payload = ByteArray(0),
            )
            val ok = finished.await(COMPLETION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            terminal?.let { cleanup(); throw it }
            cleanup()
            if (!ok) throw AsrException("Volcengine ASR timed out waiting for the final transcript")
            return transcript.trim()
        }

        override fun cancel() {
            terminal = terminal ?: AsrException("Volcengine ASR session was cancelled")
            finished.countDown()
            cleanup()
        }

        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            runCatching {
                val payload = volcengineGzip(configPayload())
                sendFrame(MSG_FULL_CLIENT_REQUEST, 0, SER_JSON, COMP_GZIP, payload)
            }.onFailure { fail(AsrException("Volcengine ASR session setup failed")) }
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
            if (cleanedUp) return
            handleBinaryResponse(bytes.toByteArray())
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            fail(AsrException("Volcengine ASR WebSocket failed"))
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            // 服务端先关：已经有结论就正常收尾，否则报错。
            if (!cleanedUp && terminal == null) {
                if (transcript.isNotEmpty()) finished.countDown()
                else fail(AsrException("Volcengine ASR connection closed before the final transcript"))
            }
        }

        private fun configPayload(): ByteArray {
            val audio = buildJsonObject {
                put("format", "pcm")
                put("rate", SAMPLE_RATE)
                put("bits", 16)
                put("channel", 1)
                if (options.language.trim().isNotEmpty()) put("language", options.language)
            }
            val json = buildJsonObject {
                // RikkaHub 用 "rikkahub"、Flutter 用 "kelivo"：按品牌规则用 memo。
                put("user", buildJsonObject { put("uid", "memo") })
                put("audio", audio)
                put("request", buildJsonObject {
                    put("model_name", "bigmodel")
                    put("enable_itn", true)
                    put("enable_punc", true)
                    put("show_utterances", true)
                    put("result_type", "full")
                })
            }
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        private fun handleBinaryResponse(data: ByteArray) {
            if (data.size < 4) return
            val byte1 = data[1].toInt() and 0xFF
            val byte2 = data[2].toInt() and 0xFF
            val messageType = (byte1 shr 4) and 0x0F
            val messageFlags = byte1 and 0x0F
            val compression = byte2 and 0x0F
            var offset = 4

            when (messageType) {
                MSG_SERVER_RESPONSE -> {
                    if ((messageFlags and FLAG_HAS_SEQUENCE) != 0) offset += 4
                    if (offset + 4 > data.size) return
                    val payloadSize = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                    offset += 4
                    if (payloadSize <= 0 || offset + payloadSize > data.size) return
                    var payload = data.copyOfRange(offset, offset + payloadSize)
                    if (compression == COMP_GZIP) {
                        payload = runCatching { volcengineGunzip(payload) }.getOrElse { return }
                    }
                    val json = runCatching { Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject }
                        .getOrNull() ?: return
                    val text = (json["result"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotEmpty() && text != transcript) {
                        transcript = text
                        onPartial?.invoke(text)
                    }
                    // 带 FLAG_LAST_PACKET 的响应 = 服务端收尾。
                    if ((messageFlags and FLAG_LAST_PACKET) != 0) finished.countDown()
                }
                MSG_ERROR -> {
                    if (offset + 4 > data.size) return
                    offset += 4 // 错误码
                    if (offset + 4 > data.size) return
                    val msgSize = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                    offset += 4
                    val message = if (msgSize > 0 && offset + msgSize <= data.size) {
                        String(data, offset, msgSize, Charsets.UTF_8)
                    } else {
                        "Volcengine ASR error"
                    }
                    fail(AsrException(redact(message, options.apiKey)))
                }
                else -> Unit
            }
        }

        private fun sendFrame(
            messageType: Int,
            flags: Int,
            serialization: Int,
            compression: Int,
            payload: ByteArray,
        ) {
            val header = byteArrayOf(
                0x11.toByte(),
                ((messageType shl 4) or (flags and 0x0F)).toByte(),
                ((serialization shl 4) or (compression and 0x0F)).toByte(),
                0x00,
            )
            val size = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
            val sent = runCatching {
                socket?.send((header + size + payload).toByteString()) ?: false
            }.getOrDefault(false)
            if (!sent && !cleanedUp) throw AsrException("Volcengine ASR WebSocket send failed")
        }

        private fun ensureActive() {
            terminal?.let { throw it }
            if (isCancelled()) cancel()
            terminal?.let { throw it }
        }

        private fun fail(error: AsrException) {
            if (terminal != null) return
            val safe = AsrException(redact(error.message ?: "Volcengine ASR failed", options.apiKey))
            terminal = safe
            finished.countDown()
            onError?.invoke(safe)
            cleanup()
        }

        private fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            runCatching { socket?.close(1000, "finished") }
            socket = null
        }

        companion object {
            const val SAMPLE_RATE = 16000
            private const val MSG_FULL_CLIENT_REQUEST = 0x01
            private const val MSG_AUDIO_ONLY = 0x02
            private const val MSG_SERVER_RESPONSE = 0x09
            private const val MSG_ERROR = 0x0F
            private const val SER_NONE = 0x00
            private const val SER_JSON = 0x01
            private const val COMP_NONE = 0x00
            private const val COMP_GZIP = 0x01
            private const val FLAG_HAS_SEQUENCE = 0x01
            private const val FLAG_LAST_PACKET = 0x02
            private const val MAX_WS_QUEUE_BYTES = 100_000L
        }
    }

    /**
     * OpenAI Realtime / DashScope 共用的实时会话（原版 `_RealtimeAsrSession` 519-807）：
     * `input_audio_buffer.append` 送音频，`commit`/`session.finish` 收尾；转写按
     * `item_id` 累积（delta 增量、text 覆盖当前项、completed 定型），最终文本按项顺序拼接。
     */
    private class RealtimeAsrSession(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        private val label: String,
        private val apiKey: String,
        private val sessionUpdate: JsonObject,
        private val addEvent: (audioBase64: String, eventId: String) -> JsonObject,
        private val commitEvent: ((eventId: String) -> JsonObject)?,
        private val finishEvent: ((eventId: String) -> JsonObject)?,
        private val waitForSessionFinished: Boolean,
        private val isCancelled: () -> Boolean,
    ) : CloudAsrSession, okhttp3.WebSocketListener() {

        private val partialByItem = LinkedHashMap<String, String>()
        private val completedByItem = LinkedHashMap<String, String>()
        private val itemOrder = ArrayList<String>()
        private var eventSequence = 0
        private var hasAudio = false
        private var cleanedUp = false
        @Volatile private var terminal: AsrException? = null
        private var onPartial: ((String) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null
        private val finished = java.util.concurrent.CountDownLatch(1)
        private var socket: okhttp3.WebSocket? = null

        init {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            socket = client.newWebSocket(builder.build(), this)
            send(sessionUpdate)
        }

        override fun observePartials(onPartial: (String) -> Unit, onError: (Exception) -> Unit) {
            this.onPartial = onPartial
            this.onError = onError
            terminal?.let(onError)
        }

        override fun addPcm16(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            ensureActive()
            hasAudio = true
            val audio = java.util.Base64.getEncoder().encodeToString(chunk)
            send(addEvent(audio, nextEventId()))
        }

        override fun finish(): String {
            ensureActive()
            if (!hasAudio && finishEvent == null) {
                cleanup()
                return currentTranscript()
            }
            if (hasAudio) commitEvent?.let { send(it(nextEventId())) }
            finishEvent?.let { send(it(nextEventId())) }
            val ok = finished.await(COMPLETION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok && terminal == null) {
                cleanup()
                throw AsrException("$label ASR timed out waiting for the final transcript")
            }
            terminal?.let { cleanup(); throw it }
            cleanup()
            return currentTranscript()
        }

        override fun cancel() {
            terminal = terminal ?: AsrException("$label ASR session was cancelled")
            finished.countDown()
            cleanup()
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            if (cleanedUp) return
            handleMessage(text)
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
            if (cleanedUp) return
            handleMessage(bytes.utf8())
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            fail(AsrException("$label ASR WebSocket failed"))
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            if (!cleanedUp && terminal == null) {
                // 服务端先关：只要已经收到过结论就照常收尾，否则报错（原版 `_handleSocketDone`）。
                if (completedByItem.isNotEmpty() || waitForSessionFinished) finished.countDown()
                else fail(AsrException("$label ASR connection closed before the final transcript"))
            }
        }

        private fun handleMessage(text: String) {
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (json == null) {
                fail(AsrException("$label ASR returned invalid JSON"))
                return
            }
            when (json["type"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
                "conversation.item.input_audio_transcription.delta" -> {
                    val item = itemId(json)
                    val delta = json["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (delta.isNotEmpty()) {
                        rememberItem(item)
                        partialByItem[item] = partialByItem[item].orEmpty() + delta
                        publish()
                    }
                }
                "conversation.item.input_audio_transcription.text" -> {
                    val item = itemId(json)
                    val value = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (value.isNotEmpty()) {
                        rememberItem(item)
                        partialByItem[item] = value
                        publish()
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val item = itemId(json)
                    rememberItem(item)
                    val transcript = json["transcript"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: partialByItem[item]?.trim().orEmpty()
                    partialByItem.remove(item)
                    completedByItem[item] = transcript
                    publish()
                    // 不等 session.finished 的 provider（OpenAI）在这里就算结束。
                    if (!waitForSessionFinished) finished.countDown()
                }
                "conversation.item.input_audio_transcription.failed" -> {
                    val detail = (json["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: (json["error"]?.jsonPrimitive?.contentOrNull)
                    fail(AsrException(redact("$label ASR transcription failed${detail?.let { ": $it" } ?: ""}", apiKey)))
                }
                "session.finished" -> finished.countDown()
                "error" -> {
                    val detail = (json["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: json["error"]?.jsonPrimitive?.contentOrNull
                    fail(AsrException(redact("$label ASR server error${detail?.let { ": $it" } ?: ""}", apiKey)))
                }
            }
        }

        private fun rememberItem(item: String) {
            if (!itemOrder.contains(item)) itemOrder.add(item)
        }

        private fun itemId(json: JsonObject): String =
            json["item_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "default"

        private fun currentTranscript(): String = itemOrder
            .map { completedByItem[it] ?: partialByItem[it].orEmpty() }
            .filter { it.trim().isNotEmpty() }
            .joinToString(" ")

        private fun publish() {
            onPartial?.invoke(currentTranscript())
        }

        private fun nextEventId(): String {
            eventSequence++
            return "memo_asr_$eventSequence"
        }

        private fun send(event: JsonObject) {
            val sent = runCatching { socket?.send(event.toString()) ?: false }.getOrDefault(false)
            if (!sent) throw AsrException("$label ASR WebSocket send failed")
        }

        private fun ensureActive() {
            terminal?.let { throw it }
            if (isCancelled()) cancel()
            terminal?.let { throw it }
        }

        private fun fail(error: AsrException) {
            if (terminal != null) return
            val safe = AsrException(redact(error.message ?: "$label ASR failed", apiKey))
            terminal = safe
            finished.countDown()
            onError?.invoke(safe)
            cleanup()
        }

        private fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            runCatching { socket?.close(1000, "finished") }
            socket = null
        }
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

    // ------------------------------------------------------------------ Volcengine 二进制帧
    // 协议来自 RikkaHub `speech/.../VolcengineASRController.kt`（同 Flutter 侧
    // `cloud_asr_service.dart::_VolcengineAsrSession`）：放到外层是为了能直接单测。

    internal const val VOLC_MSG_FULL_CLIENT_REQUEST = 0x01
    internal const val VOLC_MSG_AUDIO_ONLY = 0x02
    internal const val VOLC_MSG_SERVER_RESPONSE = 0x09
    internal const val VOLC_MSG_ERROR = 0x0F
    internal const val VOLC_SER_NONE = 0x00
    internal const val VOLC_SER_JSON = 0x01
    internal const val VOLC_COMP_NONE = 0x00
    internal const val VOLC_COMP_GZIP = 0x01

    /** `buildFrame`：4 字节头 + 4 字节大端长度 + payload。 */
    internal fun volcengineFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        payload: ByteArray,
    ): ByteArray {
        val header = byteArrayOf(
            0x11.toByte(),
            ((messageType shl 4) or (flags and 0x0F)).toByte(),
            ((serialization shl 4) or (compression and 0x0F)).toByte(),
            0x00,
        )
        val size = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        return header + size + payload
    }

    internal fun volcengineGzip(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    internal fun volcengineGunzip(data: ByteArray): ByteArray =
        java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }

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
