package com.psyche.memo.provider

import com.psyche.memo.ui.AzureTtsOptions
import com.psyche.memo.ui.ElevenLabsTtsOptions
import com.psyche.memo.ui.FishAudioTtsOptions
import com.psyche.memo.ui.GeminiTtsOptions
import com.psyche.memo.ui.GroqTtsOptions
import com.psyche.memo.ui.MimoTtsOptions
import com.psyche.memo.ui.MiniMaxTtsOptions
import com.psyche.memo.ui.NetworkTtsKind
import com.psyche.memo.ui.OpenAiTtsOptions
import com.psyche.memo.ui.QwenAudioTtsOptions
import com.psyche.memo.ui.QwenTtsOptions
import com.psyche.memo.ui.StepTtsOptions
import com.psyche.memo.ui.TtsServiceOptions
import com.psyche.memo.ui.XaiTtsOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/** 网络 TTS 失败（原版抛 `Exception('X TTS failed: …')` 的那类）。 */
class TtsException(message: String) : Exception(message)

/**
 * 一次合成的结果。[mime] 与 [sampleRate] 由 provider 决定（PCM 类会转成 WAV），
 * [extension] 供缓存文件与「保存音频」用。
 */
data class NetworkTtsResult(
    val bytes: ByteArray,
    val mime: String,
    val sampleRate: Int? = null,
) {
    val extension: String
        get() = when {
            mime.contains("wav") -> "wav"
            mime.contains("ogg") -> "ogg"
            mime.contains("flac") -> "flac"
            mime.contains("pcm") -> "pcm"
            else -> "mp3"
        }
}

/**
 * 1:1 port of `lib/core/services/tts/network_tts.dart::NetworkTtsService.synthesize`
 * —— 12 家网络 TTS 的请求组装与响应解析。
 *
 * 已接：openai / gemini / azure / minimax / qwen / groq / xai / elevenlabs /
 * mimo / step / fishAudio（11 家 HTTP）+ qwenAudio（DashScope WebSocket 双向流，
 * 2026-09-19 补齐，12/12 全通）。
 *
 * 与原版的差异：**不做客户端参数范围校验**（MiniMax 的 emotion/speed/volume/
 * pitch/format/sampleRate/bitrate 白名单、Fish 的 referenceId），只保留「必填项
 * 为空就报错」这一层，其余交给服务端返回错误。少做校验比写错范围安全。
 */
object NetworkTts {

    /** MiniMax 合法情绪（network_tts.dart:741-752），空串表示不指定。 */
    private val MINIMAX_EMOTIONS = setOf(
        "", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm", "fluent", "whipser",
    )

    /** network_tts.dart:465/642/782 —— Groq 单次 1000 字，其余 220 字。 */
    const val GROQ_MAX_CHARS_PER_REQUEST = 1000
    const val DEFAULT_MAX_CHARS_PER_REQUEST = 220

    /** Step 单次 200 字（network_tts.dart:642）。 */
    const val STEP_MAX_CHARS_PER_REQUEST = 200

    /** `migrateMimoTtsModel`（network_tts.dart:62-66）。 */
    internal fun migrateMimoModel(raw: String?): String {
        val model = raw?.trim().orEmpty()
        return if (model.isEmpty() || model == "mimo-v2-tts") "mimo-v2.5-tts" else model
    }

    /** 单次请求的推荐字数上限（`networkTtsMaxCharsPerRequest`，network_tts.dart:782）。 */
    fun maxCharsPerRequest(options: TtsServiceOptions): Int =
        if (options is GroqTtsOptions) GROQ_MAX_CHARS_PER_REQUEST else DEFAULT_MAX_CHARS_PER_REQUEST

    /**
     * 合成 [text]。同步阻塞（调用方放 IO 线程）；[isCancelled] 在每个分片/事件之间
     * 检查，命中就抛 [TtsException]（原版 `_Cancelled`）。
     */
    fun synthesize(
        client: OkHttpClient,
        options: TtsServiceOptions,
        text: String,
        isCancelled: () -> Boolean = { false },
    ): NetworkTtsResult {
        checkCancelled(isCancelled)
        return when (options) {
            is OpenAiTtsOptions -> openAi(client, options, text)
            is GeminiTtsOptions -> gemini(client, options, text)
            is AzureTtsOptions -> azure(client, options, text)
            is MiniMaxTtsOptions -> miniMax(client, options, text)
            is QwenTtsOptions -> qwen(client, options, text, isCancelled)
            is GroqTtsOptions -> groq(client, options, text)
            is XaiTtsOptions -> xai(client, options, text)
            is ElevenLabsTtsOptions -> elevenLabs(client, options, text)
            is MimoTtsOptions -> mimo(client, options, text, isCancelled)
            is StepTtsOptions -> step(client, options, text, isCancelled)
            is FishAudioTtsOptions -> fishAudio(client, options, text)
            is QwenAudioTtsOptions -> qwenAudio(client, options, text, isCancelled)
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw TtsException("TTS cancelled")
    }

    // ------------------------------------------------------------------ HTTP 公共

    private fun post(
        client: OkHttpClient,
        url: String,
        json: String,
        headers: Map<String, String>,
    ): Response {
        val builder = Request.Builder().url(url).post(json.toRequestBody(JSON_MEDIA))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    private fun Response.requireSuccess(label: String): Response {
        if (code in 200..299) return this
        val detail = runCatching { body?.string().orEmpty() }.getOrDefault("")
        close()
        throw TtsException("$label failed: $code $message $detail")
    }

    private fun Response.bytes(): ByteArray = use { it.body?.bytes() ?: ByteArray(0) }

    /** `_joinUrl`：把 base 与 path 拼起来，避免出现 `//`。 */
    internal fun joinUrl(base: String, path: String): String {
        val b = base.trim().trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }

    // ------------------------------------------------------------------ providers

    /** network_tts.dart:887-923。 */
    private fun openAi(client: OkHttpClient, opt: OpenAiTtsOptions, text: String): NetworkTtsResult {
        val body = buildJsonObject {
            put("model", opt.model)
            put("input", text)
            put("voice", opt.voice)
            put("response_format", "mp3")
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/audio/speech"),
            body.toString(),
            mapOf("Authorization" to "Bearer ${opt.apiKey}"),
        ).requireSuccess("OpenAI TTS")
        return NetworkTtsResult(response.bytes(), "audio/mpeg")
    }

    /** network_tts.dart:925-988 —— 返回 24kHz PCM，转 WAV。 */
    private fun gemini(client: OkHttpClient, opt: GeminiTtsOptions, text: String): NetworkTtsResult {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", opt.voiceName) })
                    })
                })
            })
            put("model", opt.model)
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/models/${opt.model}:generateContent"),
            body.toString(),
            mapOf("x-goog-api-key" to opt.apiKey),
        ).requireSuccess("Gemini TTS")
        val json = runCatching { Json.parseToJsonElement(response.bytes().decodeToString()).jsonObject }
            .getOrElse { throw TtsException("Gemini TTS returned invalid JSON") }
        val candidates = json["candidates"] as? JsonArray
        val parts = ((candidates?.firstOrNull() as? JsonObject)
            ?.get("content") as? JsonObject)?.get("parts") as? JsonArray
        val inline = (parts?.firstOrNull() as? JsonObject)?.get("inlineData") as? JsonObject
        val data = inline?.get("data")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (data.isEmpty()) throw TtsException("Gemini TTS returned no audio data")
        val pcm = base64Decode(data)
        return NetworkTtsResult(pcmToWav(pcm, 24000), "audio/wav", 24000)
    }

    /** network_tts.dart:991-1078 —— SSML，音频 24kHz 96kbps mp3；429/502/503 重试。 */
    private fun azure(client: OkHttpClient, opt: AzureTtsOptions, text: String): NetworkTtsResult {
        val configured = opt.baseUrl.trim()
        val base = runCatching { java.net.URI(configured) }.getOrNull()
        if (base == null || (base.scheme != "http" && base.scheme != "https") || base.host.isNullOrEmpty()) {
            throw TtsException("Azure TTS endpoint must be an absolute HTTP(S) URL")
        }
        var basePath = base.path.orEmpty().trimEnd('/')
        if (!basePath.endsWith("/cognitiveservices/v1")) basePath += "/cognitiveservices/v1"
        val url = "${base.scheme}://${base.authority}$basePath"
        val ssml = "<speak version=\"1.0\" xml:lang=\"${xmlEscape(opt.language)}\">" +
            "<voice name=\"${xmlEscape(opt.voice)}\">${xmlEscape(text)}</voice></speak>"
        val headers = mapOf(
            "Ocp-Apim-Subscription-Key" to opt.apiKey,
            "X-Microsoft-OutputFormat" to "audio-24khz-96kbitrate-mono-mp3",
            // 原版是 'Kelivo'；按品牌规则改成 Memo。
            "User-Agent" to "Memo",
        )
        val delays = listOf(200L, 600L)
        var last: Response? = null
        for (attempt in 0..delays.size) {
            val builder = Request.Builder().url(url)
                .post(ssml.toRequestBody(SSML_MEDIA))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val response = client.newCall(builder.build()).execute()
            if (response.code !in setOf(429, 502, 503) || attempt == delays.size) {
                last = response
                break
            }
            val retryAfter = response.header("retry-after")?.trim()?.toLongOrNull()?.times(1000)
            response.close()
            Thread.sleep(retryAfter ?: delays[attempt])
        }
        val response = last ?: throw TtsException("Azure TTS failed: no response")
        response.requireSuccess("Azure TTS")
        return NetworkTtsResult(response.bytes(), "audio/mpeg")
    }

    /** network_tts.dart:1082-1192 —— SSE 里 `data.audio` 是 hex；PCM 转 WAV。 */
    private fun miniMax(client: OkHttpClient, opt: MiniMaxTtsOptions, text: String): NetworkTtsResult {
        if (opt.voiceId.trim().isEmpty()) throw TtsException("MiniMax TTS requires a voice ID.")
        if (opt.emotion.trim() !in MINIMAX_EMOTIONS) {
            throw TtsException("Unsupported MiniMax emotion.")
        }
        val body = buildJsonObject {
            put("model", opt.model)
            put("text", text)
            put("stream", true)
            put("output_format", "hex")
            put("stream_options", buildJsonObject { put("exclude_aggregated_audio", true) })
            put("voice_setting", buildJsonObject {
                put("voice_id", opt.voiceId)
                put("speed", opt.speed)
                put("vol", opt.volume)
                put("pitch", opt.pitch)
                if (opt.emotion.trim().isNotEmpty()) put("emotion", opt.emotion.trim())
            })
            put("audio_setting", buildJsonObject {
                put("sample_rate", opt.sampleRate)
                put("bitrate", opt.bitrate)
                put("format", opt.format)
                put("channel", opt.channel)
            })
            if (opt.languageBoost.trim().isNotEmpty()) put("language_boost", opt.languageBoost.trim())
            if (opt.pronunciationDictionary.isNotEmpty()) {
                put("pronunciation_dict", buildJsonObject {
                    put("tone", buildJsonArray { opt.pronunciationDictionary.forEach { add(JsonPrimitive(it)) } })
                })
            }
            put("subtitle_enable", opt.subtitleEnable)
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/t2a_v2"),
            body.toString(),
            mapOf("Authorization" to "Bearer ${opt.apiKey}", "Accept" to "text/event-stream"),
        ).requireSuccess("MiniMax TTS")
        val audio = ByteArrayOutputStream()
        response.use {
            it.body?.source()?.let { source -> readSseData(source) { data ->
                if (data != "[DONE]") {
                    val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
                    if (obj != null) {
                        val status = (obj["base_resp"] as? JsonObject)?.get("status_code")
                            ?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        if (status != 0) {
                            val message = (obj["base_resp"] as? JsonObject)?.get("status_msg")
                                ?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                            throw TtsException("MiniMax TTS failed: $status $message")
                        }
                        val hex = (obj["data"] as? JsonObject)?.get("audio")
                            ?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (hex.isNotEmpty()) {
                            audio.write(runCatching { hexToBytes(hex) }
                                .getOrElse { throw TtsException("MiniMax TTS returned invalid hex audio.") })
                        }
                    }
                }
            } }
        }
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw TtsException("MiniMax TTS returned no audio data")
        val format = opt.format.lowercase()
        return if (format == "pcm") {
            NetworkTtsResult(pcmToWav(bytes, opt.sampleRate, opt.channel), "audio/wav", opt.sampleRate)
        } else {
            NetworkTtsResult(bytes, audioMimeForFormat(format), opt.sampleRate)
        }
    }

    /** network_tts.dart:1194-1249 —— SSE 的 `output.audio.data` 是 base64 PCM(24k)。 */
    private fun qwen(
        client: OkHttpClient,
        opt: QwenTtsOptions,
        text: String,
        isCancelled: () -> Boolean,
    ): NetworkTtsResult {
        val body = buildJsonObject {
            put("model", opt.model)
            put("input", buildJsonObject {
                put("text", text)
                put("voice", opt.voice)
                put("language_type", opt.languageType)
            })
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/services/aigc/multimodal-generation/generation"),
            body.toString(),
            mapOf("Authorization" to "Bearer ${opt.apiKey}", "X-DashScope-SSE" to "enable"),
        ).requireSuccess("Qwen TTS")
        val pcm = ByteArrayOutputStream()
        response.use {
            it.body?.source()?.let { source -> readSseData(source) { data ->
                checkCancelled(isCancelled)
                if (data == "[DONE]") return@readSseData
                val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@readSseData
                val b64 = (((obj["output"] as? JsonObject)?.get("audio") as? JsonObject)
                    ?.get("data")?.jsonPrimitive?.contentOrNull).orEmpty()
                if (b64.isNotEmpty()) pcm.write(base64Decode(b64))
            } }
        }
        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) throw TtsException("Qwen TTS returned no audio data")
        return NetworkTtsResult(pcmToWav(bytes, 24000), "audio/wav", 24000)
    }

    /** network_tts.dart:1251-1283。 */
    private fun groq(client: OkHttpClient, opt: GroqTtsOptions, text: String): NetworkTtsResult {
        val body = buildJsonObject {
            put("model", opt.model)
            put("input", text)
            put("voice", opt.voice)
            put("response_format", "wav")
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/audio/speech"),
            body.toString(),
            mapOf("Authorization" to "Bearer ${opt.apiKey}"),
        ).requireSuccess("Groq TTS")
        return NetworkTtsResult(response.bytes(), "audio/wav")
    }

    /** network_tts.dart:1285-1316。 */
    private fun xai(client: OkHttpClient, opt: XaiTtsOptions, text: String): NetworkTtsResult {
        val body = buildJsonObject {
            put("text", text)
            put("voice_id", opt.voiceId)
            put("language", opt.language)
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/tts"),
            body.toString(),
            mapOf("Authorization" to "Bearer ${opt.apiKey}"),
        ).requireSuccess("xAI TTS")
        return NetworkTtsResult(response.bytes(), "audio/mpeg")
    }

    /** network_tts.dart:1318-1369 —— `pcm_<rate>` 输出格式转 WAV。 */
    private fun elevenLabs(
        client: OkHttpClient,
        opt: ElevenLabsTtsOptions,
        text: String,
    ): NetworkTtsResult {
        val base = opt.baseUrl.trim().trimEnd('/')
        val outputFmt = opt.outputFormat.ifEmpty { "mp3_44100_128" }
        val apiBase = if (base.lowercase().endsWith("/v1")) base else "$base/v1"
        val url = "$apiBase/text-to-speech/${opt.voiceId}?output_format=$outputFmt"
        val body = buildJsonObject {
            put("text", text)
            put("model_id", opt.modelId)
        }
        val response = post(
            client,
            url,
            body.toString(),
            mapOf("xi-api-key" to opt.apiKey),
        ).requireSuccess("ElevenLabs TTS")
        val bytes = response.bytes()
        val lower = outputFmt.lowercase()
        if (lower.startsWith("pcm_")) {
            val rate = lower.substring(4).toIntOrNull()
            if (rate == null || rate <= 0 || bytes.size % 2 != 0) {
                throw TtsException("Invalid ElevenLabs PCM response format.")
            }
            return NetworkTtsResult(pcmToWav(bytes, rate), "audio/wav", rate)
        }
        val mime = when {
            lower.startsWith("mp3_") -> "audio/mpeg"
            lower.startsWith("opus_") -> "audio/ogg"
            else -> "application/octet-stream"
        }
        return NetworkTtsResult(bytes, mime)
    }

    /** network_tts.dart:1371-1472 —— /chat/completions，audio 走 message/delta。 */
    private fun mimo(
        client: OkHttpClient,
        opt: MimoTtsOptions,
        text: String,
        isCancelled: () -> Boolean,
    ): NetworkTtsResult {
        val model = migrateMimoModel(opt.model)
        val isVoiceDesign = model == "mimo-v2.5-tts-voicedesign"
        val isVoiceClone = model == "mimo-v2.5-tts-voiceclone"
        if (isVoiceDesign && opt.instruction.trim().isEmpty()) {
            throw TtsException("MiMo Voice Design requires a voice description.")
        }
        if (isVoiceClone) {
            if (!opt.voice.trim().startsWith("data:audio/")) {
                throw TtsException("MiMo Voice Clone requires a WAV/MP3 Base64 data URI.")
            }
        } else if (!isVoiceDesign && opt.voice.trim().isEmpty()) {
            throw TtsException("MiMo TTS requires a built-in voice.")
        }
        val useStream = opt.stream
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                if (opt.instruction.trim().isNotEmpty() || isVoiceClone) {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", opt.instruction)
                    })
                }
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", buildJsonObject {
                put("format", if (useStream) "pcm16" else "wav")
                if (isVoiceDesign) {
                    put("optimize_text_preview", opt.optimizeTextPreview)
                } else {
                    put("voice", opt.voice.trim())
                }
            })
            put("stream", useStream)
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/chat/completions"),
            body.toString(),
            mapOf("api-key" to opt.apiKey),
        ).requireSuccess("MiMo TTS")
        if (!useStream) {
            val json = runCatching { Json.parseToJsonElement(response.bytes().decodeToString()).jsonObject }
                .getOrElse { throw TtsException("MiMo TTS returned invalid JSON") }
            val message = ((json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("message") as? JsonObject
            val data = (message?.get("audio") as? JsonObject)?.get("data")
                ?.jsonPrimitive?.contentOrNull.orEmpty()
            if (data.isEmpty()) throw TtsException("MiMo TTS returned no audio data")
            return NetworkTtsResult(base64Decode(data), "audio/wav", 24000)
        }
        val pcm = ByteArrayOutputStream()
        response.use {
            it.body?.source()?.let { source -> readSseData(source) { data ->
                checkCancelled(isCancelled)
                if (data == "[DONE]") return@readSseData
                val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@readSseData
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@readSseData
                val audio = (choice["delta"] as? JsonObject)?.get("audio") as? JsonObject
                    ?: choice["message"] as? JsonObject
                val b64 = (audio as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (b64.isNotEmpty()) pcm.write(base64Decode(b64))
            } }
        }
        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) throw TtsException("MiMo TTS returned no audio chunks")
        return NetworkTtsResult(pcmToWav(bytes, 24000), "audio/wav", 24000)
    }

    /** network_tts.dart:1474-1565 —— 按 200 字切段逐个请求，再按格式合并。 */
    private fun step(
        client: OkHttpClient,
        opt: StepTtsOptions,
        text: String,
        isCancelled: () -> Boolean,
    ): NetworkTtsResult {
        val chunks = splitStepText(text, STEP_MAX_CHARS_PER_REQUEST)
        val parts = ArrayList<ByteArray>(chunks.size)
        for (chunk in chunks) {
            checkCancelled(isCancelled)
            val body = buildJsonObject {
                put("model", opt.model)
                put("input", chunk)
                put("voice", opt.voice)
                put("response_format", opt.responseFormat)
                put("speed", opt.speed)
                put("volume", opt.volume)
                put("sample_rate", opt.sampleRate)
                if (opt.model.trim() == "stepaudio-2.5-tts" && opt.instruction.trim().isNotEmpty()) {
                    put("instruction", opt.instruction.trim())
                }
            }
            val response = post(
                client,
                joinUrl(opt.baseUrl, "/audio/speech"),
                body.toString(),
                mapOf(
                    "Authorization" to "Bearer ${opt.apiKey}",
                    "Accept" to "application/octet-stream",
                ),
            ).requireSuccess("StepFun TTS")
            parts.add(response.bytes())
        }
        if (parts.isEmpty()) throw TtsException("StepFun TTS returned empty audio")
        val format = opt.responseFormat.lowercase()
        if (format == "pcm") {
            return NetworkTtsResult(
                pcmToWav(concat(parts), opt.sampleRate),
                "audio/wav",
                opt.sampleRate,
            )
        }
        if (format == "wav" && parts.size > 1) {
            return NetworkTtsResult(combineWavAudio(parts), "audio/wav", opt.sampleRate)
        }
        if (format == "flac" && parts.size > 1) {
            throw TtsException("StepFun FLAC cannot be merged across multiple text chunks.")
        }
        if (parts.size == 1) {
            return NetworkTtsResult(parts[0], audioMimeForFormat(format), opt.sampleRate)
        }
        return NetworkTtsResult(concat(parts), audioMimeForFormat(format), opt.sampleRate)
    }

    /** network_tts.dart:1567-1616 —— model 走 header，reference_id 必填。 */
    private fun fishAudio(
        client: OkHttpClient,
        opt: FishAudioTtsOptions,
        text: String,
    ): NetworkTtsResult {
        if (opt.referenceId.trim().isEmpty()) {
            throw TtsException("Fish Audio TTS requires a voice/reference ID.")
        }
        val body = buildJsonObject {
            put("text", text)
            put("format", opt.format)
            put("temperature", opt.temperature)
            put("top_p", opt.topP)
            put("prosody", buildJsonObject { put("speed", opt.speed) })
            put("sample_rate", opt.sampleRate)
            put("latency", opt.latency)
            put("reference_id", opt.referenceId.trim())
        }
        val response = post(
            client,
            joinUrl(opt.baseUrl, "/v1/tts"),
            body.toString(),
            mapOf("Authorization" to "Bearer ${opt.apiKey}", "model" to opt.model),
        ).requireSuccess("Fish Audio TTS")
        val bytes = response.bytes()
        if (bytes.isEmpty()) throw TtsException("Fish Audio TTS returned empty audio")
        val format = opt.format.lowercase()
        return if (format == "pcm") {
            NetworkTtsResult(pcmToWav(bytes, opt.sampleRate), "audio/wav", opt.sampleRate)
        } else {
            NetworkTtsResult(bytes, audioMimeForFormat(format), opt.sampleRate)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** `_sseDataStream`（network_tts.dart:2036-2056）：空行派发一次事件，只取 data: 行。 */
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

    /** `_splitStepTtsText`（network_tts.dart:1786-1798）：定长切分，不做句子对齐。 */
    internal fun splitStepText(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val out = ArrayList<String>()
        var i = 0
        while (i < trimmed.length) {
            val end = if (i + maxChars < trimmed.length) i + maxChars else trimmed.length
            out.add(trimmed.substring(i, end))
            i = end
        }
        return out
    }

    /** `_audioMimeForFormat`（network_tts.dart:1800-1815）。 */
    internal fun audioMimeForFormat(format: String): String = when (format.lowercase()) {
        "wav" -> "audio/wav"
        "pcm" -> "audio/pcm"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    /** `_pcmToWav`（network_tts.dart:2058-2089）：44 字节 RIFF 头 + PCM 数据。 */
    internal fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataLength = pcm.size
        val out = ByteArray(44 + dataLength)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataLength)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataLength)
        System.arraycopy(pcm, 0, out, 44, dataLength)
        return out
    }

    /** `combineWavAudio`（network_tts.dart:2092+）：同格式多段 WAV 拼成一段。 */
    internal fun combineWavAudio(parts: List<ByteArray>): ByteArray {
        if (parts.isEmpty()) throw TtsException("combineWavAudio requires at least one part")
        if (parts.size == 1) return parts[0]
        val header = parts[0].copyOfRange(0, minOf(44, parts[0].size))
        val total = parts.sumOf { maxOf(0, it.size - 44) }
        val out = ByteArray(44 + total)
        System.arraycopy(header, 0, out, 0, header.size)
        var offset = 44
        for (part in parts) {
            val data = part.copyOfRange(minOf(44, part.size), part.size)
            System.arraycopy(data, 0, out, offset, data.size)
            offset += data.size
        }
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(4, 36 + total)
        buffer.putInt(40, total)
        return out
    }

    internal fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** `_hexToBytes`。 */
    internal fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) throw TtsException("invalid hex audio")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((hexDigit(clean[i * 2]) shl 4) or hexDigit(clean[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw TtsException("invalid hex audio")
    }

    internal fun base64Decode(value: String): ByteArray =
        // minSdk 26 ⇒ java.util.Base64 可用（比 android.util.Base64 更好测：纯 JVM 单测也能跑）。
        runCatching { java.util.Base64.getDecoder().decode(value) }
            .getOrElse { throw TtsException("invalid base64 audio") }

    /** SSML 属性/文本转义（原版用 Dart 的 HtmlEscape）。 */
    internal fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val SSML_MEDIA = "application/ssml+xml".toMediaType()

    // -------------------------------------------------- qwenAudio（DashScope WebSocket）

    /** QwenAudioTtsOptions.websocketUrl（network_tts.dart L616-623）。 */
    private fun qwenAudioWebsocketUrl(opt: QwenAudioTtsOptions): String {
        val ws = opt.workspaceId.trim()
        val reg = opt.region.trim().ifEmpty { "cn-beijing" }
        return if (ws.isEmpty()) {
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        } else {
            "wss://$ws.$reg.maas.aliyuncs.com/api-ws/v1/inference"
        }
    }

    private const val QWEN_STARTED_TIMEOUT_MS = 30_000L
    private const val QWEN_FINISHED_TIMEOUT_MS = 120_000L

    /** Latch 等待 + 取消轮询（`_waitWithCancellation` 的阻塞版）。 */
    private fun awaitLatch(
        latch: java.util.concurrent.CountDownLatch,
        timeoutMs: Long,
        isCancelled: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
            if (isCancelled()) throw TtsException("TTS cancelled")
        }
        return latch.await(0, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * `_qwenAudioSpeech`（network_tts.dart L1618-1786）：run-task → 等
     * task-started（30s）→ continue-task（文本）+ finish-task → 等
     * task-finished（120s）；音频以二进制帧回流，PCM 格式转 WAV 后返回。
     */
    private fun qwenAudio(
        client: OkHttpClient,
        opt: QwenAudioTtsOptions,
        text: String,
        isCancelled: () -> Boolean,
    ): NetworkTtsResult {
        val session = QwenAudioTtsSession(client, opt, isCancelled)
        try {
            session.awaitStarted()
            checkCancelled(isCancelled)
            session.sendContinueAndFinish(text)
            session.awaitFinished()
            checkCancelled(isCancelled)
            session.terminal?.let { throw it }
            val bytes = session.audioBytes()
            if (bytes.isEmpty()) throw TtsException("Qwen Audio TTS returned no audio")
            val fmt = opt.format.lowercase()
            return if (fmt == "pcm") {
                NetworkTtsResult(
                    bytes = pcmToWav(bytes, opt.sampleRate),
                    mime = "audio/wav",
                    sampleRate = opt.sampleRate,
                )
            } else {
                NetworkTtsResult(
                    bytes = bytes,
                    mime = audioMimeForFormat(fmt),
                    sampleRate = opt.sampleRate,
                )
            }
        } finally {
            session.close()
        }
    }

    private class QwenAudioTtsSession(
        client: OkHttpClient,
        private val opt: QwenAudioTtsOptions,
        private val isCancelled: () -> Boolean,
    ) : okhttp3.WebSocketListener() {

        private val taskId = java.util.UUID.randomUUID().toString()
        private val started = java.util.concurrent.CountDownLatch(1)
        private val finished = java.util.concurrent.CountDownLatch(1)
        private val audioBuffer = java.io.ByteArrayOutputStream()
        @Volatile var terminal: TtsException? = null
            private set
        private var socket: okhttp3.WebSocket? = null

        init {
            val builder = okhttp3.Request.Builder()
                .url(qwenAudioWebsocketUrl(opt))
                .header("Authorization", "Bearer ${opt.apiKey}")
            if (opt.workspaceId.trim().isNotEmpty()) {
                builder.header("X-DashScope-WorkSpace", opt.workspaceId.trim())
            }
            socket = client.newWebSocket(builder.build(), this)
        }

        fun awaitStarted() {
            if (!awaitLatch(started, QWEN_STARTED_TIMEOUT_MS, isCancelled)) {
                throw terminal ?: TtsException("Qwen Audio TTS timed out waiting for task-started")
            }
            terminal?.let { throw it }
        }

        fun sendContinueAndFinish(text: String) {
            send(
                buildJsonObject {
                    put("header", buildJsonObject {
                        put("action", "continue-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    })
                    put("payload", buildJsonObject {
                        put("input", buildJsonObject { put("text", text) })
                    })
                },
            )
            send(
                buildJsonObject {
                    put("header", buildJsonObject {
                        put("action", "finish-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    })
                    put("payload", buildJsonObject { put("input", buildJsonObject { }) })
                },
            )
        }

        fun awaitFinished() {
            if (!awaitLatch(finished, QWEN_FINISHED_TIMEOUT_MS, isCancelled)) {
                throw terminal ?: TtsException("Qwen Audio TTS timed out waiting for task-finished")
            }
            terminal?.let { throw it }
        }

        fun audioBytes(): ByteArray = synchronized(audioBuffer) { audioBuffer.toByteArray() }

        fun close() {
            runCatching { socket?.close(1000, null) }
        }

        private fun fail(message: String) {
            terminal = terminal ?: TtsException(message)
            started.countDown()
            finished.countDown()
        }

        private fun send(json: JsonObject) {
            val sent = runCatching { socket?.send(json.toString()) ?: false }.getOrDefault(false)
            if (!sent) fail("Qwen Audio TTS WebSocket send failed")
        }

        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            send(
                buildJsonObject {
                    put("header", buildJsonObject {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    })
                    put("payload", buildJsonObject {
                        put("task_group", "audio")
                        put("task", "tts")
                        put("function", "SpeechSynthesizer")
                        put("model", opt.model)
                        put("parameters", buildJsonObject {
                            put("text_type", "PlainText")
                            put("voice", opt.voice)
                            put("format", opt.format)
                            put("sample_rate", opt.sampleRate)
                        })
                        put("input", buildJsonObject { })
                    })
                },
            )
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: run {
                fail("Qwen Audio TTS returned malformed lifecycle JSON.")
                return
            }
            val header = json["header"] as? JsonObject
            val name = header?.get("event")?.jsonPrimitive?.contentOrNull.orEmpty()
            when (name) {
                "task-started" -> started.countDown()
                // result-generated 只带句子/时间戳元数据；音频走二进制帧。
                "task-finished" -> finished.countDown()
                "task-failed", "error" -> {
                    val payload = json["payload"] as? JsonObject
                    val msg = (header?.get("error_message")?.jsonPrimitive?.contentOrNull
                        ?: header?.get("error_code")?.jsonPrimitive?.contentOrNull
                        ?: payload?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: name)
                    fail("Qwen Audio TTS failed: $msg")
                }
            }
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
            synchronized(audioBuffer) { audioBuffer.write(bytes.toByteArray()) }
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
            fail("Qwen Audio TTS connection failed: ${t.message ?: t}")
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            // 服务端先关流还没发 task-finished：按异常收尾。
            if (started.count > 0 || finished.count > 0) {
                fail("Qwen Audio TTS socket closed before task-finished")
            }
        }
    }

    /** 供调用方按 kind 判断是否已实现（12/12 全部接线）。 */
    fun isSupported(kind: NetworkTtsKind): Boolean = true
}
