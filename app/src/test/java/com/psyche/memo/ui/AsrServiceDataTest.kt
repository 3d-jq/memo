package com.psyche.memo.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * asr_service_options.dart 1:1 data-layer tests — JSON round-trips under
 * `asr_services_v1` must stay byte-compatible with the Flutter payloads:
 * wire kind ids are snake_case, defaults mirror the Dart constructors, and
 * per-kind isConfigured matches asr_service_options.dart.
 */
class AsrServiceDataTest {

    @Test
    fun kindWireIds_matchDartSnakeCase() {
        assertEquals("sherpa_onnx", AsrServiceKind.sherpaOnnx.wire)
        assertEquals("system", AsrServiceKind.system.wire)
        assertEquals("openai_realtime", AsrServiceKind.openAiRealtime.wire)
        assertEquals("dashscope", AsrServiceKind.dashScope.wire)
        assertEquals("qwen_audio", AsrServiceKind.qwenAudio.wire)
        assertEquals("volcengine", AsrServiceKind.volcengine.wire)
        assertEquals("mimo", AsrServiceKind.mimo.wire)
        assertEquals("step", AsrServiceKind.step.wire)
    }

    @Test
    fun fromWire_acceptsBothSpellings() {
        assertEquals(AsrServiceKind.sherpaOnnx, AsrServiceKind.fromWire("sherpa_onnx"))
        assertEquals(AsrServiceKind.sherpaOnnx, AsrServiceKind.fromWire("sherpaOnnx"))
        assertEquals(AsrServiceKind.openAiRealtime, AsrServiceKind.fromWire("openAiRealtime"))
        assertEquals(AsrServiceKind.dashScope, AsrServiceKind.fromWire("dashScope"))
    }

    @Test
    fun jsonRoundTrip_system() {
        val o = SystemAsrOptions(id = "s1", name = "系统语音识别", localeId = "zh-CN")
        val back = AsrServiceOptions.fromJson(o.toJson().toString())!!
        assertEquals(o, back)
        assertTrue(back.isConfigured)
    }

    @Test
    fun jsonRoundTrip_openAiRealtime() {
        val o = OpenAiRealtimeAsrOptions(id = "o1", name = "OpenAI", apiKey = "k", language = "en", prompt = "p")
        val back = AsrServiceOptions.fromJson(o.toJson().toString())!! as OpenAiRealtimeAsrOptions
        assertEquals(o, back)
        assertTrue(back.isConfigured)
        // Unconfigured: empty apiKey.
        assertFalse(OpenAiRealtimeAsrOptions(id = "o2").isConfigured)
    }

    @Test
    fun jsonRoundTrip_dashScope() {
        val o = DashScopeAsrOptions(id = "d1", name = "DashScope", apiKey = "k")
        assertEquals(o, AsrServiceOptions.fromJson(o.toJson().toString()))
        // Dart default model.
        assertEquals("qwen3-asr-flash-realtime", o.model)
    }

    @Test
    fun jsonRoundTrip_volcengine_resourceIdDefaults() {
        val o = VolcengineAsrOptions(id = "v1", apiKey = "k")
        assertEquals(VolcengineAsrOptions.SEED_ASR_DURATION_RESOURCE_ID, o.resourceId)
        assertEquals(o, AsrServiceOptions.fromJson(o.toJson().toString()))
        // BigASR 1.0 alternative round-trips too.
        val big = o.copy(resourceId = VolcengineAsrOptions.BIG_ASR_DURATION_RESOURCE_ID)
        assertEquals(big, AsrServiceOptions.fromJson(big.toJson().toString()))
    }

    @Test
    fun qwenAudio_websocketUrlComposition() {
        // asr_service_options.dart L493-500.
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            QwenAudioAsrOptions(apiKey = "k").websocketUrl,
        )
        assertEquals(
            "wss://ws1.cn-shanghai.maas.aliyuncs.com/api-ws/v1/inference",
            QwenAudioAsrOptions(apiKey = "k", workspaceId = "ws1", region = "cn-shanghai").websocketUrl,
        )
    }

    @Test
    fun jsonRoundTrip_mimo() {
        val o = MimoAsrOptions(id = "m1", name = "MiMo", apiKey = "k", language = "zh")
        assertEquals(o, AsrServiceOptions.fromJson(o.toJson().toString()))
        // Dart default baseUrl/language.
        assertEquals("https://api.xiaomimimo.com/v1", o.baseUrl)
        assertTrue(o.isConfigured)
        assertFalse(MimoAsrOptions(id = "m2").isConfigured)
    }

    @Test
    fun jsonRoundTrip_step_hotwords() {
        val o = StepAsrOptions(id = "st1", apiKey = "k", hotwords = listOf("Memo", "液态玻璃"))
        val back = AsrServiceOptions.fromJson(o.toJson().toString()) as StepAsrOptions
        assertEquals(o, back)
        assertEquals(listOf("Memo", "液态玻璃"), back.hotwords)
        // Dart defaults.
        assertTrue(back.enableItn)
        assertFalse(back.enableTimestamp)
        assertEquals("stepaudio-2.5-asr", back.model)
    }

    @Test
    fun fromJson_emptyObject_usesDartDefaults() {
        // asr_service_options.dart fromJson: missing fields fall back per kind.
        val sys = AsrServiceOptions.fromJson(Json.parseToJsonElement("""{"kind":"system"}""").jsonObject) as SystemAsrOptions
        assertEquals("System", sys.name)
        assertTrue(sys.id.isNotEmpty()) // uuid generated
        val openai = AsrServiceOptions.fromJson(Json.parseToJsonElement("""{"kind":"openai_realtime","apiKey":"k"}""").jsonObject) as OpenAiRealtimeAsrOptions
        assertEquals("wss://api.openai.com/v1/realtime?intent=transcription", openai.websocketUrl)
        assertEquals("gpt-live-transcribe", openai.model)
        assertEquals(24000, openai.sampleRate)
        assertEquals(300, openai.prefixPaddingMs)
        assertEquals(500, openai.silenceDurationMs)
    }

    @Test
    fun editorDefaults_matchDartHelpers() {
        // _defaultEndpoint / _defaultModel / _defaultResourceId L2167-2213.
        assertEquals("wss://api.openai.com/v1/realtime?intent=transcription", asrDefaultEndpoint(AsrServiceKind.openAiRealtime))
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/realtime", asrDefaultEndpoint(AsrServiceKind.dashScope))
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel", asrDefaultEndpoint(AsrServiceKind.volcengine))
        assertEquals("", asrDefaultEndpoint(AsrServiceKind.system))
        assertEquals("gpt-live-transcribe", asrDefaultModel(AsrServiceKind.openAiRealtime))
        assertEquals("qwen-audio-3.0-asr-flash-streaming", asrDefaultModel(AsrServiceKind.qwenAudio))
        assertEquals("mimo-v2.5-asr", asrDefaultModel(AsrServiceKind.mimo))
        assertEquals("", asrDefaultModel(AsrServiceKind.system))
        assertEquals("volc.seedasr.sauc.duration", asrDefaultResourceId(AsrServiceKind.volcengine))
        assertEquals("", asrDefaultResourceId(AsrServiceKind.step))
    }

    @Test
    fun isDefaultServiceName_coversLocalizedDefaults() {
        // _isDefaultServiceName L2009-2068 — default names are not user data.
        assertTrue(asrIsDefaultServiceName(AsrServiceKind.system, "系统语音识别"))
        assertTrue(asrIsDefaultServiceName(AsrServiceKind.system, "System"))
        assertTrue(asrIsDefaultServiceName(AsrServiceKind.volcengine, "火山引擎"))
        assertTrue(asrIsDefaultServiceName(AsrServiceKind.step, "阶跃星辰语音识别"))
        assertTrue(asrIsDefaultServiceName(AsrServiceKind.qwenAudio, "Qwen Audio"))
        // A user-chosen name is kept.
        assertFalse(asrIsDefaultServiceName(AsrServiceKind.system, "我的会议转写"))
    }
}
