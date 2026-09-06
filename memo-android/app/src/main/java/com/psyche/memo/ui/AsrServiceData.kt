package com.psyche.memo.ui

import java.util.UUID
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObjectBuilder
import com.psyche.memo.data.settings.PreferenceRepository

/**
 * asr_service_options.dart 1:1 — AsrServiceOptions hierarchy. JSON field
 * names, wire kind ids (snake_case) and defaults match the Dart source
 * exactly so payloads stay interchangeable under `asr_services_v1`
 * (settings_provider.dart _asrServicesKey L402-403).
 */
enum class AsrServiceKind(val wire: String) {
    sherpaOnnx("sherpa_onnx"),
    system("system"),
    openAiRealtime("openai_realtime"),
    dashScope("dashscope"),
    qwenAudio("qwen_audio"),
    volcengine("volcengine"),
    mimo("mimo"),
    step("step");

    companion object {
        /** asr_service_options.dart fromId L17-42 — accepts both spellings. */
        fun fromWire(value: String?): AsrServiceKind = when (value) {
            "sherpa_onnx", "sherpaOnnx" -> sherpaOnnx
            "system" -> system
            "openai_realtime", "openAiRealtime" -> openAiRealtime
            "dashscope", "dashScope" -> dashScope
            "qwen_audio", "qwenAudio" -> qwenAudio
            "volcengine" -> volcengine
            "mimo" -> mimo
            "step" -> step
            else -> throw IllegalArgumentException("Unsupported ASR service kind: $value")
        }
    }
}

sealed class AsrServiceOptions {
    abstract val id: String
    abstract val name: String
    abstract val kind: AsrServiceKind

    /** asr_service_options.dart isConfigured per kind. */
    abstract val isConfigured: Boolean

    abstract fun toJson(): JsonObject

    protected fun base(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("kind", kind.wire)
        extra()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): AsrServiceOptions? = runCatching {
            fromJson(Json.parseToJsonElement(text).jsonObject)
        }.getOrNull()

        /** asr_service_options.dart fromJson L57-161 — kind dispatch with dart defaults. */
        fun fromJson(o: JsonObject): AsrServiceOptions {
            fun s(k: String, def: String = ""): String =
                o[k]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: def
            val id = s("id").ifEmpty { UUID.randomUUID().toString() }
            val kind = AsrServiceKind.fromWire(o["kind"]?.jsonPrimitive?.content)
            fun i(k: String, def: Int, positive: Boolean): Int {
                val v = o[k]?.jsonPrimitive?.intOrNull
                return if (v == null || (positive && v <= 0) || (!positive && v < 0)) def else v
            }
            fun d(k: String, def: Double): Double = o[k]?.jsonPrimitive?.doubleOrNull ?: def
            fun bool(k: String, def: Boolean): Boolean = o[k]?.jsonPrimitive?.booleanOrNull ?: def
            fun strList(k: String): List<String> =
                (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { t -> t.isNotEmpty() } } ?: emptyList()
            return when (kind) {
                AsrServiceKind.sherpaOnnx -> SherpaOnnxAsrOptions(
                    id = id, name = s("name", "Offline Model"),
                    modelId = s("modelId"), modelDirectory = s("modelDirectory"),
                    language = s("language"), sampleRate = i("sampleRate", 16000, positive = true),
                )
                AsrServiceKind.system -> SystemAsrOptions(
                    id = id, name = s("name", "System"), localeId = s("localeId"),
                )
                AsrServiceKind.openAiRealtime -> OpenAiRealtimeAsrOptions(
                    id = id, name = s("name", "OpenAI Realtime"),
                    apiKey = s("apiKey"),
                    websocketUrl = s("websocketUrl", "wss://api.openai.com/v1/realtime?intent=transcription"),
                    model = s("model", "gpt-live-transcribe"), language = s("language"),
                    prompt = s("prompt"), sampleRate = i("sampleRate", 24000, positive = true),
                    vadThreshold = d("vadThreshold", 0.0),
                    prefixPaddingMs = i("prefixPaddingMs", 300, positive = false),
                    silenceDurationMs = i("silenceDurationMs", 500, positive = false),
                )
                AsrServiceKind.dashScope -> DashScopeAsrOptions(
                    id = id, name = s("name", "DashScope"),
                    apiKey = s("apiKey"),
                    websocketUrl = s("websocketUrl", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"),
                    model = s("model", "qwen3-asr-flash-realtime"), language = s("language"),
                    sampleRate = i("sampleRate", 16000, positive = true),
                    vadThreshold = d("vadThreshold", 0.0),
                    silenceDurationMs = i("silenceDurationMs", 800, positive = false),
                )
                AsrServiceKind.volcengine -> VolcengineAsrOptions(
                    id = id, name = s("name", "Volcengine"),
                    apiKey = s("apiKey"),
                    websocketUrl = s("websocketUrl", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"),
                    resourceId = s("resourceId", VolcengineAsrOptions.SEED_ASR_DURATION_RESOURCE_ID),
                    language = s("language"),
                )
                AsrServiceKind.qwenAudio -> QwenAudioAsrOptions(
                    id = id, name = s("name", "Qwen Audio"),
                    apiKey = s("apiKey"), workspaceId = s("workspaceId"),
                    region = s("region", "cn-beijing"),
                    model = s("model", "qwen-audio-3.0-asr-flash-streaming"),
                    sampleRate = i("sampleRate", 16000, positive = true), format = s("format", "pcm"),
                )
                AsrServiceKind.mimo -> MimoAsrOptions(
                    id = id, name = s("name", "MiMo"),
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.xiaomimimo.com/v1"),
                    model = s("model", "mimo-v2.5-asr"), language = s("language", "auto"),
                    sampleRate = i("sampleRate", 16000, positive = true),
                    segmentDurationSec = i("segmentDurationSec", 30, positive = false),
                )
                AsrServiceKind.step -> StepAsrOptions(
                    id = id, name = s("name", "Step"),
                    apiKey = s("apiKey"), baseUrl = s("baseUrl", "https://api.stepfun.com"),
                    model = s("model", "stepaudio-2.5-asr"), language = s("language", "auto"),
                    sampleRate = i("sampleRate", 16000, positive = true),
                    segmentDurationSec = i("segmentDurationSec", 30, positive = false),
                    enableItn = bool("enableItn", true), enableTimestamp = bool("enableTimestamp", false),
                    hotwords = strList("hotwords"),
                )
            }
        }
    }
}

data class SherpaOnnxAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Offline Model",
    val modelId: String = "",
    val modelDirectory: String = "",
    val language: String = "",
    val sampleRate: Int = 16000,
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.sherpaOnnx
    override val isConfigured: Boolean get() = modelId.trim().isNotEmpty()
    override fun toJson(): JsonObject = base {
        put("modelId", modelId); put("modelDirectory", modelDirectory)
        put("language", language); put("sampleRate", sampleRate)
    }
}

data class SystemAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "System",
    val localeId: String = "",
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.system
    override val isConfigured: Boolean get() = true
    override fun toJson(): JsonObject = base { put("localeId", localeId) }
}

data class OpenAiRealtimeAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "OpenAI Realtime",
    val apiKey: String = "",
    val websocketUrl: String = "wss://api.openai.com/v1/realtime?intent=transcription",
    val model: String = "gpt-live-transcribe",
    val language: String = "",
    val prompt: String = "",
    val sampleRate: Int = 24000,
    val vadThreshold: Double = 0.0,
    val prefixPaddingMs: Int = 300,
    val silenceDurationMs: Int = 500,
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.openAiRealtime
    override val isConfigured: Boolean
        get() = apiKey.trim().isNotEmpty() && websocketUrl.trim().isNotEmpty()
    override fun toJson(): JsonObject = base {
        put("apiKey", apiKey); put("websocketUrl", websocketUrl); put("model", model)
        put("language", language); put("prompt", prompt); put("sampleRate", sampleRate)
        put("vadThreshold", vadThreshold); put("prefixPaddingMs", prefixPaddingMs)
        put("silenceDurationMs", silenceDurationMs)
    }
}

data class DashScopeAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "DashScope",
    val apiKey: String = "",
    val websocketUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
    val model: String = "qwen3-asr-flash-realtime",
    val language: String = "",
    val sampleRate: Int = 16000,
    val vadThreshold: Double = 0.0,
    val silenceDurationMs: Int = 800,
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.dashScope
    override val isConfigured: Boolean
        get() = apiKey.trim().isNotEmpty() && websocketUrl.trim().isNotEmpty()
    override fun toJson(): JsonObject = base {
        put("apiKey", apiKey); put("websocketUrl", websocketUrl); put("model", model)
        put("language", language); put("sampleRate", sampleRate)
        put("vadThreshold", vadThreshold); put("silenceDurationMs", silenceDurationMs)
    }
}

data class VolcengineAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Volcengine",
    val apiKey: String = "",
    val websocketUrl: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel",
    val resourceId: String = SEED_ASR_DURATION_RESOURCE_ID,
    val language: String = "",
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.volcengine
    override val isConfigured: Boolean
        get() = apiKey.trim().isNotEmpty() && websocketUrl.trim().isNotEmpty() && resourceId.trim().isNotEmpty()
    override fun toJson(): JsonObject = base {
        put("apiKey", apiKey); put("websocketUrl", websocketUrl)
        put("resourceId", resourceId); put("language", language)
    }

    companion object {
        /**
         * Compatible resource ids (official docs): ASR 2.0 (Seed-ASR) duration
         * `volc.seedasr.sauc.duration` (current default); ASR 1.0 (BigASR)
         * duration `volc.bigasr.sauc.duration`. Do not auto-migrate existing
         * user configs; wrong id -> 403 not-granted (Dart L361-367).
         */
        const val SEED_ASR_DURATION_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val BIG_ASR_DURATION_RESOURCE_ID = "volc.bigasr.sauc.duration"
        val KNOWN_DURATION_RESOURCE_IDS = listOf(SEED_ASR_DURATION_RESOURCE_ID, BIG_ASR_DURATION_RESOURCE_ID)
    }
}

data class QwenAudioAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Qwen Audio",
    val apiKey: String = "",
    val workspaceId: String = "",
    val region: String = "cn-beijing",
    val model: String = "qwen-audio-3.0-asr-flash-streaming",
    val sampleRate: Int = 16000,
    val format: String = "pcm",
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.qwenAudio
    override val isConfigured: Boolean
        get() = apiKey.trim().isNotEmpty() && model.trim().isNotEmpty()

    /** asr_service_options.dart L493-500. */
    val websocketUrl: String
        get() {
            val ws = workspaceId.trim()
            val reg = region.trim().ifEmpty { "cn-beijing" }
            return if (ws.isEmpty()) {
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
            } else {
                "wss://$ws.$reg.maas.aliyuncs.com/api-ws/v1/inference"
            }
        }

    override fun toJson(): JsonObject = base {
        put("apiKey", apiKey); put("workspaceId", workspaceId); put("region", region)
        put("model", model); put("sampleRate", sampleRate); put("format", format)
    }
}

data class MimoAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "MiMo",
    val apiKey: String = "",
    val baseUrl: String = "https://api.xiaomimimo.com/v1",
    val model: String = "mimo-v2.5-asr",
    val language: String = "auto",
    val sampleRate: Int = 16000,
    val segmentDurationSec: Int = 30,
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.mimo
    override val isConfigured: Boolean
        get() = apiKey.trim().isNotEmpty() && baseUrl.trim().isNotEmpty()
    override fun toJson(): JsonObject = base {
        put("apiKey", apiKey); put("baseUrl", baseUrl); put("model", model)
        put("language", language); put("sampleRate", sampleRate)
        put("segmentDurationSec", segmentDurationSec)
    }
}

data class StepAsrOptions(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Step",
    val apiKey: String = "",
    val baseUrl: String = "https://api.stepfun.com",
    val model: String = "stepaudio-2.5-asr",
    val language: String = "auto",
    val sampleRate: Int = 16000,
    val segmentDurationSec: Int = 30,
    val enableItn: Boolean = true,
    val enableTimestamp: Boolean = false,
    val hotwords: List<String> = emptyList(),
) : AsrServiceOptions() {
    override val kind = AsrServiceKind.step
    override val isConfigured: Boolean
        get() = apiKey.trim().isNotEmpty() && baseUrl.trim().isNotEmpty() && model.trim().isNotEmpty()
    override fun toJson(): JsonObject = base {
        put("apiKey", apiKey); put("baseUrl", baseUrl); put("model", model)
        put("language", language); put("sampleRate", sampleRate)
        put("segmentDurationSec", segmentDurationSec); put("enableItn", enableItn)
        put("enableTimestamp", enableTimestamp)
        put("hotwords", JsonArray(hotwords.map { JsonPrimitive(it) }))
    }
}

/**
 * asr_services_section.dart state + settings_provider.dart asr persistence —
 * same preference keys as Flutter (`asr_services_v1` /
 * `asr_selected_service_id_v1`), same delete semantics (removing the selected
 * service selects the first remaining one, null when empty — _deleteService
 * L106-116).
 */
class AsrServicesStore(private val prefs: PreferenceRepository) {

    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    val services: List<AsrServiceOptions> get() = store

    var selectedServiceId: String?
        get() = prefs.readJson(SELECTED_KEY)?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        set(value) {
            if (value == null) prefs.remove(SELECTED_KEY)
            else prefs.writeJson(SELECTED_KEY, JsonPrimitive(value).toString())
            version++
        }

    private val store = mutableListOf<AsrServiceOptions>()

    fun load() {
        val raw = prefs.readJson(SERVICES_KEY) ?: return
        val list = runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { AsrServiceOptions.fromJson(it.jsonObject) }
        }.getOrDefault(emptyList())
        store.clear()
        store.addAll(list)
        version++
    }

    fun setServices(list: List<AsrServiceOptions>) {
        store.clear()
        store.addAll(list)
        val arr = JsonArray(list.map { it.toJson() })
        prefs.writeJson(SERVICES_KEY, arr.toString())
        version++
    }

    fun upsert(service: AsrServiceOptions) {
        val idx = store.indexOfFirst { it.id == service.id }
        if (idx >= 0) store[idx] = service else store.add(service)
        setServices(store.toList())
    }

    fun add(service: AsrServiceOptions) {
        setServices(store + service)
        // _addService L75-77: first created service becomes selected.
        if (selectedServiceId == null) selectedServiceId = service.id
    }

    fun remove(service: AsrServiceOptions) {
        setServices(store.filterNot { it.id == service.id })
        if (selectedServiceId == service.id) {
            selectedServiceId = store.firstOrNull()?.id
        }
    }

    companion object {
        const val SERVICES_KEY = "asr_services_v1"
        const val SELECTED_KEY = "asr_selected_service_id_v1"
    }
}
