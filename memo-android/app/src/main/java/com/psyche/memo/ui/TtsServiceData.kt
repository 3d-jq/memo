package com.psyche.memo.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.ui.chat.SystemTtsConfig
import com.psyche.memo.ui.chat.parseSystemTtsConfig

/**
 * network_tts.dart 1:1 — TtsServiceOptions hierarchy. JSON field names and
 * defaults match the Dart source exactly so payloads stay interchangeable
 * under `tts_services_v1` (settings_provider.dart _ttsServicesKey).
 */
enum class NetworkTtsKind(val wire: String, val display: String) {
    openai("openai", "OpenAI"),
    gemini("gemini", "Gemini"),
    azure("azure", "Azure"),
    minimax("minimax", "MiniMax"),
    qwen("qwen", "Qwen"),
    qwenAudio("qwenAudio", "Qwen Audio"),
    groq("groq", "Groq"),
    xai("xai", "xAI"),
    elevenlabs("elevenlabs", "ElevenLabs"),
    mimo("mimo", "MiMo"),
    step("step", "StepFun"),
    fishAudio("fishAudio", "Fish Audio"),
}

/** network_tts.dart L62-66 — retired MiMo model migration. */
fun migrateMimoTtsModel(raw: String?): String {
    val model = raw?.trim() ?: ""
    return if (model.isEmpty() || model == "mimo-v2-tts") "mimo-v2.5-tts" else model
}

sealed class TtsServiceOptions {
    abstract val id: String
    abstract val enabled: Boolean
    abstract val name: String
    abstract val kind: NetworkTtsKind
    abstract val apiKey: String

    abstract fun toJson(): JsonObject

    protected fun base(kind: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("id", id)
        put("enabled", enabled)
        put("name", name)
        put("kind", kind)
        extra()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): TtsServiceOptions? = runCatching {
            fromJson(Json.parseToJsonElement(text).jsonObject)
        }.getOrNull()

        /** network_tts.dart L83-266 — kind dispatch with dart defaults. */
        fun fromJson(o: JsonObject): TtsServiceOptions {
            fun s(k: String, def: String = ""): String =
                o[k]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: def
            val id = s("id")
            val enabledRaw = o["enabled"] as? JsonPrimitive
            val enabled = enabledRaw?.booleanOrNull ?: (enabledRaw?.content == "true")
            val name = s("name")
            fun d(k: String, def: Double): Double = o[k]?.jsonPrimitive?.doubleOrNull ?: def
            fun i(k: String, def: Int): Int = o[k]?.jsonPrimitive?.intOrNull ?: def
            fun bool(k: String): Boolean = o[k]?.jsonPrimitive?.content == "true"
            fun strList(k: String): List<String> =
                (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            return when (s("kind")) {
                "openai" -> OpenAiTtsOptions(id, enabled, name.ifEmpty { "OpenAI TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.openai.com/v1"),
                    model = s("model", "gpt-4o-mini-tts"), voice = s("voice", "alloy"))
                "gemini" -> GeminiTtsOptions(id, enabled, name.ifEmpty { "Gemini TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://generativelanguage.googleapis.com/v1beta"),
                    model = s("model", "gemini-3.1-flash-tts-preview"), voiceName = s("voiceName", "Kore"))
                "azure" -> AzureTtsOptions(id, enabled, name.ifEmpty { "Azure TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl"),
                    language = s("language", "zh-CN"), voice = s("voice", "zh-CN-XiaoxiaoNeural"))
                "minimax" -> MiniMaxTtsOptions(id, enabled, name.ifEmpty { "MiniMax TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.minimaxi.com/v1"),
                    model = s("model", "speech-2.8-turbo"), voiceId = s("voiceId", "female-shaonv"),
                    emotion = s("emotion"), speed = d("speed", 1.0), volume = d("volume", 1.0),
                    pitch = i("pitch", 0), languageBoost = s("languageBoost"),
                    format = s("format", "mp3"), sampleRate = i("sampleRate", 32000),
                    bitrate = i("bitrate", 128000), channel = i("channel", 1),
                    subtitleEnable = bool("subtitleEnable"),
                    pronunciationDictionary = strList("pronunciationDictionary"))
                "qwen" -> QwenTtsOptions(id, enabled, name.ifEmpty { "Qwen TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://dashscope.aliyuncs.com/api/v1"),
                    model = s("model", "qwen3-tts-flash"), voice = s("voice", "Cherry"),
                    languageType = s("languageType", "Auto"))
                "qwen_audio", "qwenAudio" -> QwenAudioTtsOptions(id, enabled, name.ifEmpty { "Qwen Audio TTS" },
                    apiKey = s("apiKey"), workspaceId = s("workspaceId"), region = s("region", "cn-beijing"),
                    model = s("model", "qwen-audio-3.0-tts-flash"), voice = s("voice", "longanhuan_v3.6"),
                    format = s("format", "mp3"), sampleRate = i("sampleRate", 22050))
                "groq" -> GroqTtsOptions(id, enabled, name.ifEmpty { "Groq TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.groq.com/openai/v1"),
                    model = s("model", "canopylabs/orpheus-v1-english"), voice = s("voice", "austin"))
                "xai" -> XaiTtsOptions(id, enabled, name.ifEmpty { "xAI TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.x.ai/v1"),
                    voiceId = s("voiceId", "eve"), language = s("language", "auto"))
                "elevenlabs" -> ElevenLabsTtsOptions(id, enabled, name.ifEmpty { "ElevenLabs TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.elevenlabs.io"),
                    modelId = s("modelId", "eleven_multilingual_v2"), voiceId = s("voiceId"),
                    outputFormat = s("outputFormat", "mp3_44100_128"))
                "mimo" -> MimoTtsOptions(id, enabled, name.ifEmpty { "MiMo TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.xiaomimimo.com/v1"),
                    model = migrateMimoTtsModel(s("model")), voice = s("voice", "mimo_default"),
                    instruction = s("instruction"), stream = o["stream"]?.jsonPrimitive?.content != "false",
                    optimizeTextPreview = bool("optimizeTextPreview"))
                "step" -> StepTtsOptions(id, enabled, name.ifEmpty { "StepFun TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.stepfun.com/v1"),
                    model = s("model", "stepaudio-2.5-tts"), voice = s("voice", "cixingnansheng"),
                    responseFormat = s("responseFormat", "mp3"), speed = d("speed", 1.0),
                    volume = d("volume", 1.0), sampleRate = i("sampleRate", 24000),
                    instruction = s("instruction"))
                "fish_audio", "fishAudio" -> FishAudioTtsOptions(id, enabled, name.ifEmpty { "Fish Audio TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.fish.audio"),
                    model = s("model", "s2.1-pro"), referenceId = s("referenceId"),
                    format = s("format", "mp3"), temperature = d("temperature", 0.7),
                    topP = d("topP", 0.7), speed = d("speed", 1.0),
                    sampleRate = i("sampleRate", 44100), latency = s("latency", "normal"))
                // network_tts.dart L254-264 — fallback to OpenAI shape.
                else -> OpenAiTtsOptions(id, enabled, name.ifEmpty { "OpenAI TTS" },
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.openai.com/v1"),
                    model = s("model", "gpt-4o-mini-tts"), voice = s("voice", "alloy"))
            }
        }

        private val kotlinx.serialization.json.JsonPrimitive.booleanOrNullExt: Boolean?
            get() = content == "true"
    }
}

data class OpenAiTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voice: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.openai
    override fun toJson(): JsonObject = base("openai") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voice", voice)
    }
}

data class GeminiTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voiceName: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.gemini
    override fun toJson(): JsonObject = base("gemini") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voiceName", voiceName)
    }
}

data class AzureTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val language: String,
    val voice: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.azure
    override fun toJson(): JsonObject = base("azure") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("language", language)
        put("voice", voice)
    }
}

data class MiniMaxTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voiceId: String,
    val emotion: String,
    val speed: Double,
    val volume: Double,
    val pitch: Int,
    val languageBoost: String,
    val format: String,
    val sampleRate: Int,
    val bitrate: Int,
    val channel: Int,
    val subtitleEnable: Boolean,
    val pronunciationDictionary: List<String>,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.minimax
    override fun toJson(): JsonObject = base("minimax") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voiceId", voiceId)
        put("emotion", emotion)
        put("speed", speed)
        put("volume", volume)
        put("pitch", pitch)
        put("languageBoost", languageBoost)
        put("format", format)
        put("sampleRate", sampleRate)
        put("bitrate", bitrate)
        put("channel", channel)
        put("subtitleEnable", subtitleEnable)
        put("pronunciationDictionary", JsonArray(pronunciationDictionary.map { JsonPrimitive(it) }))
    }
}

data class QwenTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voice: String,
    val languageType: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.qwen
    override fun toJson(): JsonObject = base("qwen") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voice", voice)
        put("languageType", languageType)
    }
}

data class QwenAudioTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val workspaceId: String,
    val region: String,
    val model: String,
    val voice: String,
    val format: String,
    val sampleRate: Int,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.qwenAudio
    override fun toJson(): JsonObject = base("qwen_audio") {
        put("apiKey", apiKey)
        put("workspaceId", workspaceId)
        put("region", region)
        put("model", model)
        put("voice", voice)
        put("format", format)
        put("sampleRate", sampleRate)
    }
}

data class GroqTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voice: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.groq
    override fun toJson(): JsonObject = base("groq") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voice", voice)
    }
}

data class XaiTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val voiceId: String,
    val language: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.xai
    override fun toJson(): JsonObject = base("xai") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("voiceId", voiceId)
        put("language", language)
    }
}

data class ElevenLabsTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val modelId: String,
    val voiceId: String,
    val outputFormat: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.elevenlabs
    override fun toJson(): JsonObject = base("elevenlabs") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("modelId", modelId)
        put("voiceId", voiceId)
        put("outputFormat", outputFormat)
    }
}

data class MimoTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voice: String,
    val instruction: String,
    val stream: Boolean,
    val optimizeTextPreview: Boolean,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.mimo
    override fun toJson(): JsonObject = base("mimo") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voice", voice)
        put("instruction", instruction)
        put("stream", stream)
        put("optimizeTextPreview", optimizeTextPreview)
    }
}

data class StepTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val voice: String,
    val responseFormat: String,
    val speed: Double,
    val volume: Double,
    val sampleRate: Int,
    val instruction: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.step
    override fun toJson(): JsonObject = base("step") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("voice", voice)
        put("responseFormat", responseFormat)
        put("speed", speed)
        put("volume", volume)
        put("sampleRate", sampleRate)
        put("instruction", instruction)
    }
}

data class FishAudioTtsOptions(
    override val id: String,
    override val enabled: Boolean,
    override val name: String,
    override val apiKey: String,
    val baseUrl: String,
    val model: String,
    val referenceId: String,
    val format: String,
    val temperature: Double,
    val topP: Double,
    val speed: Double,
    val sampleRate: Int,
    val latency: String,
) : TtsServiceOptions() {
    override val kind = NetworkTtsKind.fishAudio
    override fun toJson(): JsonObject = base("fish_audio") {
        put("apiKey", apiKey)
        put("baseUrl", baseUrl)
        put("model", model)
        put("referenceId", referenceId)
        put("format", format)
        put("temperature", temperature)
        put("topP", topP)
        put("speed", speed)
        put("sampleRate", sampleRate)
        put("latency", latency)
    }
}

/**
 * TTS services store (settings_provider.dart `_ttsServicesKey` /
 * `_ttsSelectedServiceIdKey`).
 *
 * The service list is the `tts_services_v1` **entity**, so it lives in
 * `tts_service_rows` — routing it through [PreferenceRepository] silently
 * dropped every write (`classifyBusinessKey` files entity keys as ENTITY, for
 * which `writeJson` is a no-op). The selected id is an ordinary preference key
 * and stays on the repository.
 */
class TtsServicesStore(
    db: android.database.sqlite.SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {

    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    val services: List<TtsServiceOptions> get() = store

    private val dao = com.psyche.memo.data.db.PayloadEntityDao(db, TABLE)

    var selectedServiceId: String?
        get() = prefs.readJson(SELECTED_KEY)?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        set(value) {
            if (value == null) prefs.remove(SELECTED_KEY)
            else prefs.writeJson(SELECTED_KEY, JsonPrimitive(value).toString())
            version++
        }

    val usingSystemTts: Boolean get() = selectedServiceId == null

    private val store = mutableListOf<TtsServiceOptions>()

    fun load() {
        val list = dao.getAll().mapNotNull { row ->
            runCatching { TtsServiceOptions.fromJson(Json.parseToJsonElement(row.payload).jsonObject) }
                .getOrNull()
        }
        store.clear()
        store.addAll(list)
        version++
    }

    fun setServices(list: List<TtsServiceOptions>) {
        store.clear()
        store.addAll(list)
        val now = System.currentTimeMillis()
        dao.replaceAll(
            list.mapIndexed { index, service ->
                com.psyche.memo.data.db.PayloadEntityDao.Row(
                    id = service.id,
                    sortOrder = index,
                    payload = service.toJson().toString(),
                    updatedAt = now,
                )
            },
        )
        version++
    }

    fun upsert(service: TtsServiceOptions) {
        val idx = store.indexOfFirst { it.id == service.id }
        if (idx >= 0) store[idx] = service else store.add(service)
        setServices(store.toList())
    }

    fun removeAt(index: Int) {
        if (index !in store.indices) return
        val removed = store.removeAt(index)
        setServices(store.toList())
        if (selectedServiceId == removed.id) selectedServiceId = null
    }

    /**
     * 系统语音的四个偏好键（tts_provider.dart `_rateKey`…`_langKey`）——它们是普通
     * PREFERENCE 键，走 [PreferenceRepository] 没问题（只有服务列表是实体键）。
     */
    fun systemTtsConfig(): SystemTtsConfig = parseSystemTtsConfig(
        rateJson = prefs.readJson(SystemTtsConfig.RATE_KEY),
        pitchJson = prefs.readJson(SystemTtsConfig.PITCH_KEY),
        engineJson = prefs.readJson(SystemTtsConfig.ENGINE_KEY),
        languageJson = prefs.readJson(SystemTtsConfig.LANGUAGE_KEY),
    )

    /** 整份读-改-写，免得每个调用方各抄一遍键名。 */
    fun setSystemTtsConfig(config: SystemTtsConfig) {
        prefs.writeJson(SystemTtsConfig.RATE_KEY, JsonPrimitive(config.speechRate).toString())
        prefs.writeJson(SystemTtsConfig.PITCH_KEY, JsonPrimitive(config.pitch).toString())
        writeOptional(SystemTtsConfig.ENGINE_KEY, config.engineId)
        writeOptional(SystemTtsConfig.LANGUAGE_KEY, config.languageTag)
    }

    private fun writeOptional(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.writeJson(key, JsonPrimitive(value).toString())
    }

    /** 「使用缓存复播」开关（tts_provider.dart `_cacheNetworkAudioForReplayKey`）。 */
    var cacheNetworkAudioForReplay: Boolean
        get() = prefs.readJson(SystemTtsConfig.CACHE_REPLAY_KEY)?.trim()?.removeSurrounding("\"")?.toBoolean() ?: false
        set(value) {
            prefs.writeJson(SystemTtsConfig.CACHE_REPLAY_KEY, JsonPrimitive(value).toString())
        }

    /**
     * 「朗读取哪部分文本」（`tts_text_selection_mode_v1`，存枚举名）。上游那是
     * SettingsProvider 上的活值，所以朗读时现读，别在组合期缓存。
     */
    fun textSelectionMode(): String? =
        prefs.readJson(SystemTtsConfig.TEXT_SELECTION_KEY)
            ?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    companion object {
        /** BusinessEntityKind.ttsService.tableName. */
        const val TABLE = "tts_service_rows"

        const val SELECTED_KEY = "tts_selected_service_id_v1"

        /** tts_services_page.dart _defaultBaseUrl L2223-2250. */
        fun defaultBaseUrl(k: NetworkTtsKind): String = when (k) {
            NetworkTtsKind.openai -> "https://api.openai.com/v1"
            NetworkTtsKind.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            NetworkTtsKind.azure -> ""
            NetworkTtsKind.minimax -> "https://api.minimaxi.com/v1"
            NetworkTtsKind.qwen -> "https://dashscope.aliyuncs.com/api/v1"
            NetworkTtsKind.groq -> "https://api.groq.com/openai/v1"
            NetworkTtsKind.xai -> "https://api.x.ai/v1"
            NetworkTtsKind.elevenlabs -> "https://api.elevenlabs.io"
            NetworkTtsKind.mimo -> "https://api.xiaomimimo.com/v1"
            NetworkTtsKind.qwenAudio -> "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
            NetworkTtsKind.step -> "https://api.stepfun.com/v1"
            NetworkTtsKind.fishAudio -> "https://api.fish.audio"
        }

        /** tts_services_page.dart _defaultModel L2252-2279. */
        fun defaultModel(k: NetworkTtsKind): String = when (k) {
            NetworkTtsKind.openai -> "gpt-4o-mini-tts"
            NetworkTtsKind.gemini -> "gemini-3.1-flash-tts-preview"
            NetworkTtsKind.azure -> ""
            NetworkTtsKind.minimax -> "speech-2.8-turbo"
            NetworkTtsKind.qwen -> "qwen3-tts-flash"
            NetworkTtsKind.groq -> "canopylabs/orpheus-v1-english"
            NetworkTtsKind.xai -> ""
            NetworkTtsKind.elevenlabs -> "eleven_multilingual_v2"
            NetworkTtsKind.mimo -> "mimo-v2.5-tts"
            NetworkTtsKind.qwenAudio -> "qwen-audio-3.0-tts-flash"
            NetworkTtsKind.step -> "stepaudio-2.5-tts"
            NetworkTtsKind.fishAudio -> "s2.1-pro"
        }

        /** tts_services_page.dart _defaultVoice L2281-2308. */
        fun defaultVoice(k: NetworkTtsKind): String = when (k) {
            NetworkTtsKind.openai -> "alloy"
            NetworkTtsKind.gemini -> "Kore"
            NetworkTtsKind.azure -> "zh-CN-XiaoxiaoNeural"
            NetworkTtsKind.minimax -> "female-shaonv"
            NetworkTtsKind.qwen -> "Cherry"
            NetworkTtsKind.groq -> "austin"
            NetworkTtsKind.xai -> "eve"
            NetworkTtsKind.elevenlabs -> ""
            NetworkTtsKind.mimo -> "mimo_default"
            NetworkTtsKind.qwenAudio -> "longanhuan_v3.6"
            NetworkTtsKind.step -> "cixingnansheng"
            NetworkTtsKind.fishAudio -> ""
        }
    }
}
