package com.psyche.memo.provider

import com.psyche.memo.ui.ElevenLabsTtsOptions
import com.psyche.memo.ui.FishAudioTtsOptions
import com.psyche.memo.ui.GroqTtsOptions
import com.psyche.memo.ui.MiniMaxTtsOptions
import com.psyche.memo.ui.NetworkTtsKind
import com.psyche.memo.ui.OpenAiTtsOptions
import com.psyche.memo.ui.QwenAudioTtsOptions
import com.psyche.memo.ui.StepTtsOptions
import com.psyche.memo.ui.XaiTtsOptions
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `lib/core/services/tts/network_tts.dart` 的请求组装与响应解析。用 MockWebServer
 * 钉住 URL/头/body（真机联调要凭据，这里保证「发出去的东西」与原版一致），
 * 纯函数部分（WAV 头、格式→mime、切段、hex、SSE、SSML 转义）直接断言。
 */
class NetworkTtsTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val base = { server.url("/").toString().trimEnd('/') }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enqueue(code: Int = 200, body: ByteArray = byteArrayOf(1, 2, 3, 4)) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setBody(Buffer().write(body)),
        )
    }

    // ------------------------------------------------------------------ OpenAI

    @Test
    fun `openai posts to audio speech and returns mp3`() {
        enqueue()
        val options = OpenAiTtsOptions(
            id = "s1",
            enabled = true,
            name = "OpenAI",
            apiKey = "sk-test",
            baseUrl = base(),
            model = "gpt-4o-mini-tts",
            voice = "alloy",
        )
        val result = NetworkTts.synthesize(client, options, "你好")
        val recorded = server.takeRequest()

        assertEquals("/audio/speech", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o-mini-tts\""))
        assertTrue(body.contains("\"input\":\"你好\""))
        assertTrue(body.contains("\"voice\":\"alloy\""))
        assertTrue(body.contains("\"response_format\":\"mp3\""))
        assertEquals("audio/mpeg", result.mime)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), result.bytes.toList())
        assertEquals("mp3", result.extension)
    }

    @Test
    fun `openai http error becomes a TtsException with the detail`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val options = OpenAiTtsOptions("s1", true, "OpenAI", "sk", base(), "m", "alloy")
        val error = runCatching { NetworkTts.synthesize(client, options, "x") }.exceptionOrNull()
        assertTrue(error is TtsException)
        assertTrue(error!!.message!!.contains("401"))
        assertTrue(error.message!!.contains("bad key"))
    }

    // ------------------------------------------------------------------ Groq / xAI / ElevenLabs

    @Test
    fun `groq requests wav`() {
        enqueue()
        val options = GroqTtsOptions("g", true, "Groq", "k", base(), "canopylabs/orpheus-v1-english", "troy")
        val result = NetworkTts.synthesize(client, options, "hi")
        val recorded = server.takeRequest()
        assertEquals("/audio/speech", recorded.path)
        assertEquals("Bearer k", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"response_format\":\"wav\""))
        assertEquals("audio/wav", result.mime)
    }

    @Test
    fun `xai posts text voice_id and language`() {
        enqueue()
        val options = XaiTtsOptions("x", true, "xAI", "k", base(), "ara", "en")
        NetworkTts.synthesize(client, options, "hello")
        val recorded = server.takeRequest()
        assertEquals("/tts", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"text\":\"hello\""))
        assertTrue(body.contains("\"voice_id\":\"ara\""))
        assertTrue(body.contains("\"language\":\"en\""))
    }

    @Test
    fun `elevenlabs uses the voice path output format and api key header`() {
        enqueue()
        val options = ElevenLabsTtsOptions(
            id = "e",
            enabled = true,
            name = "ElevenLabs",
            apiKey = "xi",
            baseUrl = base(),
            modelId = "eleven_multilingual_v2",
            voiceId = "VOICE1",
            outputFormat = "mp3_44100_128",
        )
        val result = NetworkTts.synthesize(client, options, "hey")
        val recorded = server.takeRequest()
        assertEquals("/v1/text-to-speech/VOICE1?output_format=mp3_44100_128", recorded.path)
        assertEquals("xi", recorded.getHeader("xi-api-key"))
        assertTrue(recorded.body.readUtf8().contains("\"model_id\":\"eleven_multilingual_v2\""))
        assertEquals("audio/mpeg", result.mime)
    }

    @Test
    fun `elevenlabs pcm output is wrapped into a wav`() {
        enqueue(body = byteArrayOf(0, 0, 1, 0))
        val options = ElevenLabsTtsOptions("e", true, "E", "xi", base(), "m", "V", "pcm_24000")
        val result = NetworkTts.synthesize(client, options, "hey")
        assertEquals("audio/wav", result.mime)
        assertEquals(24000, result.sampleRate)
        assertEquals(48, result.bytes.size) // 44 字节头 + 4 字节 PCM
        assertEquals("RIFF", result.bytes.copyOfRange(0, 4).decodeToString())
    }

    // ------------------------------------------------------------------ MiniMax（SSE + hex）

    @Test
    fun `minimax concatenates hex audio from sse events`() {
        val sse = buildString {
            append("data: {\"data\":{\"audio\":\"0102\"}}\n\n")
            append("data: {\"data\":{\"audio\":\"0304\"}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse))
        val options = MiniMaxTtsOptions(
            id = "m",
            enabled = true,
            name = "MiniMax",
            apiKey = "k",
            baseUrl = base(),
            model = "speech-2.8-turbo",
            voiceId = "v1",
            emotion = "",
            speed = 1.0,
            volume = 1.0,
            pitch = 0,
            languageBoost = "",
            format = "mp3",
            sampleRate = 32000,
            bitrate = 128000,
            channel = 1,
            subtitleEnable = false,
            pronunciationDictionary = emptyList(),
        )
        val result = NetworkTts.synthesize(client, options, "你好")
        val recorded = server.takeRequest()
        assertEquals("/t2a_v2", recorded.path)
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertTrue(recorded.body.readUtf8().contains("\"output_format\":\"hex\""))
        assertEquals(listOf<Byte>(1, 2, 3, 4), result.bytes.toList())
        assertEquals("audio/mpeg", result.mime)
    }

    @Test
    fun `minimax rejects a missing voice id before any request`() {
        val options = MiniMaxTtsOptions(
            "m", true, "MiniMax", "k", base(), "model", "", "", 1.0, 1.0, 0, "", "mp3",
            32000, 128000, 1, false, emptyList(),
        )
        val error = runCatching { NetworkTts.synthesize(client, options, "x") }.exceptionOrNull()
        assertTrue(error is TtsException)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ Step（分段）

    @Test
    fun `step splits long text into 200 char requests and merges the parts`() {
        enqueue(body = byteArrayOf(1))
        enqueue(body = byteArrayOf(2))
        val options = StepTtsOptions(
            id = "s",
            enabled = true,
            name = "StepFun",
            apiKey = "k",
            baseUrl = base(),
            model = "stepaudio-2.5-tts",
            voice = "v",
            responseFormat = "pcm",
            speed = 1.0,
            volume = 1.0,
            sampleRate = 24000,
            instruction = "",
        )
        val text = "a".repeat(250)
        val result = NetworkTts.synthesize(client, options, text)

        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"input\":\"${"a".repeat(200)}\""))
        assertTrue(second.contains("\"input\":\"${"a".repeat(50)}\""))
        // pcm ⇒ 两段 PCM 合成一个 WAV（44 + 2 字节）。
        assertEquals("audio/wav", result.mime)
        assertEquals(46, result.bytes.size)
        assertEquals(24000, result.sampleRate)
    }

    @Test
    fun `step rejects flac across multiple chunks`() {
        enqueue(body = byteArrayOf(1))
        enqueue(body = byteArrayOf(2))
        val options = StepTtsOptions("s", true, "Step", "k", base(), "m", "v", "flac", 1.0, 1.0, 24000, "")
        val error = runCatching { NetworkTts.synthesize(client, options, "b".repeat(250)) }.exceptionOrNull()
        assertTrue(error is TtsException)
        assertTrue(error!!.message!!.contains("FLAC"))
    }

    // ------------------------------------------------------------------ Fish Audio

    @Test
    fun `fish audio sends the model as a header and requires a reference id`() {
        enqueue()
        val options = FishAudioTtsOptions(
            id = "f",
            enabled = true,
            name = "Fish",
            apiKey = "k",
            baseUrl = base(),
            model = "s1",
            referenceId = "ref1",
            format = "mp3",
            temperature = 0.7,
            topP = 0.7,
            speed = 1.0,
            sampleRate = 44100,
            latency = "normal",
        )
        NetworkTts.synthesize(client, options, "hi")
        val recorded = server.takeRequest()
        assertEquals("/v1/tts", recorded.path)
        assertEquals("s1", recorded.getHeader("model"))
        assertTrue(recorded.body.readUtf8().contains("\"reference_id\":\"ref1\""))

        val missing = options.copy(referenceId = "")
        val error = runCatching { NetworkTts.synthesize(client, missing, "hi") }.exceptionOrNull()
        assertTrue(error is TtsException)
    }

    // ------------------------------------------------------------------ 未接的 provider

    @Test
    fun `qwen audio websocket reports a connection failure instead of not-ported`() {
        val options = QwenAudioTtsOptions("q", true, "QwenAudio", "k", "", "cn-beijing", "m", "v", "pcm", 24000)
        assertTrue(NetworkTts.isSupported(NetworkTtsKind.qwenAudio))
        val error = runCatching { NetworkTts.synthesize(client, options, "hi") }.exceptionOrNull()
        // 测试环境连不上 DashScope：失败必须是 TtsException（不再是"未移植"）。
        assertTrue(error is TtsException)
        assertTrue(error!!.message!!.contains("not ported").not())
    }

    // ------------------------------------------------------------------ 纯函数

    @Test
    fun `pcm to wav writes a canonical 44 byte header`() {
        val wav = NetworkTts.pcmToWav(ByteArray(8), sampleRate = 16000)
        assertEquals(52, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals(44, java.nio.ByteBuffer.wrap(wav, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertEquals("fmt ", wav.copyOfRange(12, 16).decodeToString())
        assertEquals(16000, java.nio.ByteBuffer.wrap(wav, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("data", wav.copyOfRange(36, 40).decodeToString())
        assertEquals(8, java.nio.ByteBuffer.wrap(wav, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun `audio mime mapping matches the original`() {
        assertEquals("audio/wav", NetworkTts.audioMimeForFormat("WAV"))
        assertEquals("audio/pcm", NetworkTts.audioMimeForFormat("pcm"))
        assertEquals("audio/ogg", NetworkTts.audioMimeForFormat("opus"))
        assertEquals("audio/flac", NetworkTts.audioMimeForFormat("flac"))
        assertEquals("audio/mpeg", NetworkTts.audioMimeForFormat("mp3"))
        assertEquals("audio/mpeg", NetworkTts.audioMimeForFormat("whatever"))
    }

    @Test
    fun `step text splitting is a fixed length split`() {
        assertEquals(emptyList<String>(), NetworkTts.splitStepText("   ", 200))
        assertEquals(listOf("abc"), NetworkTts.splitStepText(" abc ", 200))
        assertEquals(listOf("a".repeat(200), "b".repeat(50)), NetworkTts.splitStepText("a".repeat(200) + "b".repeat(50), 200))
    }

    @Test
    fun `hex decoding and ssml escaping`() {
        assertEquals(listOf<Byte>(0x0A, 0xFF.toByte()), NetworkTts.hexToBytes("0aFF").toList())
        assertTrue(runCatching { NetworkTts.hexToBytes("abc") }.isFailure)
        assertEquals("&amp;&lt;&gt;&quot;&apos;", NetworkTts.xmlEscape("&<>\"'"))
        assertEquals("中文", NetworkTts.xmlEscape("中文"))
    }

    @Test
    fun `join url never doubles the slash`() {
        assertEquals("https://x.test/v1/tts", NetworkTts.joinUrl("https://x.test/v1/", "/tts"))
        assertEquals("https://x.test/v1/tts", NetworkTts.joinUrl("https://x.test/v1", "tts"))
    }

    @Test
    fun `combine wav audio merges data chunks and fixes the sizes`() {
        val a = NetworkTts.pcmToWav(ByteArray(4) { 1 }, 16000)
        val b = NetworkTts.pcmToWav(ByteArray(6) { 2 }, 16000)
        val merged = NetworkTts.combineWavAudio(listOf(a, b))
        assertEquals(44 + 10, merged.size)
        assertEquals(36 + 10, java.nio.ByteBuffer.wrap(merged, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(10, java.nio.ByteBuffer.wrap(merged, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1, merged[44].toInt())
        // 第 1 段数据占 44..47，第 2 段从 48 开始。
        assertEquals(1, merged[47].toInt())
        assertEquals(2, merged[48].toInt())
    }

    @Test
    fun `mimo model migration and per-request char caps`() {
        assertEquals("mimo-v2.5-tts", NetworkTts.migrateMimoModel(""))
        assertEquals("mimo-v2.5-tts", NetworkTts.migrateMimoModel("mimo-v2-tts"))
        assertEquals("mimo-v2.5-tts-voiceclone", NetworkTts.migrateMimoModel("mimo-v2.5-tts-voiceclone"))
        assertEquals(
            NetworkTts.GROQ_MAX_CHARS_PER_REQUEST,
            NetworkTts.maxCharsPerRequest(GroqTtsOptions("g", true, "G", "k", base(), "m", "v")),
        )
        assertEquals(
            NetworkTts.DEFAULT_MAX_CHARS_PER_REQUEST,
            NetworkTts.maxCharsPerRequest(OpenAiTtsOptions("o", true, "O", "k", base(), "m", "v")),
        )
    }
}
