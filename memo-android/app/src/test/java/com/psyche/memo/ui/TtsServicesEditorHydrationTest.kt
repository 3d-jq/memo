package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editor hydration tests for NetworkTtsEditorOverlay — editing an existing
 * TTS service used to seed extra1/extra2/languageType/stream with defaults,
 * so saving an edit wiped the stored values (e.g. MiniMax emotion, FishAudio
 * latency, Azure language). extra1Of/extra2Of/languageTypeOf/streamOf mirror
 * _NetworkTtsEditorPageState.initState (tts_services_page.dart L810-922).
 *
 * Round-trip guarantee under test: for every kind, hydration extractors read
 * back exactly the values the stored options carry.
 */
class TtsServicesEditorHydrationTest {

    private fun azure() = AzureTtsOptions("id", true, "Azure", "k", "https://eastus.api.cognitive.microsoft.com", "en-US", "alloy")

    private fun minimax() = MiniMaxTtsOptions(
        "id", true, "MiniMax", "k", "https://api.minimax.chat", "speech-01", "female-shaonv",
        emotion = "happy", speed = 1.2, volume = 0.8, pitch = 3, languageBoost = "auto",
        format = "flac", sampleRate = 44100, bitrate = 256000, channel = 2,
        subtitleEnable = true, pronunciationDictionary = listOf("a=b"),
    )

    private fun qwen() = QwenTtsOptions("id", true, "Qwen", "k", "https://dashscope.aliyuncs.com", "qwen-tts", "Cherry", languageType = "Chinese")

    private fun qwenAudio() = QwenAudioTtsOptions("id", true, "QwenAudio", "k", workspaceId = "ws-1", region = "cn-shanghai", model = "qwen-audio", voice = "v", format = "wav", sampleRate = 48000)

    private fun xai() = XaiTtsOptions("id", true, "xAI", "k", "https://api.x.ai", "eve", language = "en")

    private fun elevenlabs() = ElevenLabsTtsOptions("id", true, "ElevenLabs", "k", "https://api.elevenlabs.io", "eleven_turbo_v2_5", "nina", outputFormat = "mp3_22050_32")

    private fun mimo() = MimoTtsOptions("id", true, "Mimo", "k", "https://api.mimo", "mimo-tts", "v", instruction = "calm voice", stream = false, optimizeTextPreview = true)

    private fun step() = StepTtsOptions("id", true, "Step", "k", "https://api.stepfun.com", "step-tts", "v", responseFormat = "wav", speed = 0.9, volume = 1.1, sampleRate = 44100, instruction = "energetic")

    private fun fishAudio() = FishAudioTtsOptions("id", true, "Fish", "k", "https://api.fish.audio", "speech", "ref-1", format = "opus", temperature = 0.5, topP = 0.6, speed = 1.1, sampleRate = 48000, latency = "balanced")

    @Test
    fun nullInitial_seedsDefaults() {
        // Add mode (initial == null): empty extras, "Auto" language, streaming on.
        assertEquals("", extra1Of(null))
        assertEquals("", extra2Of(null))
        assertEquals("Auto", languageTypeOf(null))
        assertTrue(streamOf(null))
    }

    @Test
    fun azure_hydratesLanguageIntoExtra1() {
        assertEquals("en-US", extra1Of(azure()))
        assertEquals("", extra2Of(azure()))
    }

    @Test
    fun minimax_hydratesEmotionAndLanguageBoost() {
        assertEquals("happy", extra1Of(minimax()))
        assertEquals("auto", extra2Of(minimax()))
    }

    @Test
    fun qwen_hydratesLanguageType() {
        assertEquals("Chinese", languageTypeOf(qwen()))
        assertEquals("", extra1Of(qwen()))
        assertEquals("", extra2Of(qwen()))
    }

    @Test
    fun qwenAudio_hydratesWorkspaceAndRegion() {
        assertEquals("ws-1", extra1Of(qwenAudio()))
        assertEquals("cn-shanghai", extra2Of(qwenAudio()))
    }

    @Test
    fun xai_hydratesLanguage() {
        assertEquals("en", extra1Of(xai()))
    }

    @Test
    fun elevenlabs_hydratesOutputFormat() {
        assertEquals("mp3_22050_32", extra1Of(elevenlabs()))
    }

    @Test
    fun mimo_hydratesInstructionAndStream() {
        assertEquals("calm voice", extra1Of(mimo()))
        assertFalse(streamOf(mimo()))
    }

    @Test
    fun step_hydratesFormatAndInstruction() {
        assertEquals("wav", extra1Of(step()))
        assertEquals("energetic", extra2Of(step()))
    }

    @Test
    fun fishAudio_hydratesLatency() {
        assertEquals("balanced", extra1Of(fishAudio()))
    }

    @Test
    fun nonKindSpecificSlots_stayEmpty() {
        // OpenAI/Gemini/Groq have no extra fields — hydration must not leak
        // values between kinds.
        val openai = OpenAiTtsOptions("id", true, "OpenAI", "k", "https://api.openai.com", "tts-1", "alloy")
        assertEquals("", extra1Of(openai))
        assertEquals("", extra2Of(openai))
        assertEquals("Auto", languageTypeOf(openai))
        assertTrue(streamOf(openai))
    }
}
