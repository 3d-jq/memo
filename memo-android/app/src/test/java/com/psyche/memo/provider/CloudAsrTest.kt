package com.psyche.memo.provider

import com.psyche.memo.ui.MimoAsrOptions
import com.psyche.memo.ui.StepAsrOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端 ASR 的 HTTP 两家（MiMo / Step）—— 请求组装、SSE 解析、分段与错误路径。
 * 原版对照 `lib/core/services/asr/cloud_asr_service.dart`（`_MimoAsrSession`
 * 809-999 / `_StepAsrSession` 1001-1192 + 末尾的解析辅助函数）。
 */
class CloudAsrTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val base = { server.url("/").toString().trimEnd('/') }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun pcm(bytes: Int) = ByteArray(bytes) { 1 }

    // ------------------------------------------------------------ 纯函数

    @Test
    fun segmentLimitIsTheSmallerOfSixMegabytesAndTheTimedBytes() {
        // 16k 单声道 16bit × 30s = 960000 字节。
        assertEquals(960_000, CloudAsrService.segmentByteLimit(16000, 30))
        // 秒数 <= 0 → 直接 6MB。
        assertEquals(CloudAsrService.MAX_SEGMENT_BYTES, CloudAsrService.segmentByteLimit(16000, 0))
        // 超长秒数夹到 6MB。
        assertEquals(CloudAsrService.MAX_SEGMENT_BYTES, CloudAsrService.segmentByteLimit(24000, 600))
    }

    @Test
    fun pcm16MonoToWavWritesTheCanonicalHeader() {
        val wav = CloudAsrService.pcm16MonoToWav(ByteArray(4), 16000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        val buffer = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, buffer.getInt(4)) // 36 + 4
        assertEquals(1, buffer.getShort(22).toInt()) // mono
        assertEquals(16000, buffer.getInt(24))
        assertEquals(32000, buffer.getInt(28)) // byte rate = rate × 2
        assertEquals(4, buffer.getInt(40))
    }

    @Test
    fun mimoTranscriptReadsChoicesMessageContent() {
        val decoded = Json.parseToJsonElement(
            """{"choices":[{"message":{"role":"assistant","content":" 你好 "}}]}""",
        )
        assertEquals("你好", CloudAsrService.mimoTranscript(decoded))
        assertNull(CloudAsrService.mimoTranscript(Json.parseToJsonElement("""{"choices":[]}""")))
        assertNull(CloudAsrService.mimoTranscript(Json.parseToJsonElement("""{"choices":[{"message":{}}]}""")))
        assertNull(CloudAsrService.mimoTranscript("not json"))
    }

    @Test
    fun responseErrorNeverEchoesTheWholeBody() {
        assertEquals(
            "bad key",
            CloudAsrService.responseError("""{"error":{"message":"bad key"}}"""),
        )
        assertEquals("slow down", CloudAsrService.responseError("""{"message":"slow down"}"""))
        // 非 JSON / 没有 message → 空串（原版刻意不回显 body，可能带密钥）。
        assertEquals("", CloudAsrService.responseError("internal error, key=sk-123"))
    }

    @Test
    fun redactHidesTheApiKey() {
        assertEquals("failed [REDACTED]", CloudAsrService.redact("failed sk-abc", "sk-abc"))
        assertEquals("failed sk-abc", CloudAsrService.redact("failed sk-abc", ""))
    }

    @Test
    fun stepSseDeltasAccumulateAndDoneReplaces() {
        val delta = buildString {
            append("event: transcript.text.delta\n")
            append("data: {\"delta\":\"你好\"}\n\n")
            append("event: transcript.text.delta\n")
            append("data: {\"delta\":\"世界\"}\n\n")
        }
        assertEquals("你好世界", CloudAsrService.parseStepSseTranscript(delta))

        val done = buildString {
            append("event: transcript.text.delta\n")
            append("data: {\"delta\":\"你好\"}\n\n")
            append("event: transcript.text.done\n")
            append("data: {\"text\":\"你好世界\"}\n\n")
        }
        assertEquals("你好世界", CloudAsrService.parseStepSseTranscript(done))
    }

    @Test
    fun stepSseStopsAtDoneMarkerAndThrowsOnError() {
        val stopped = buildString {
            append("data: {\"text\":\"first\"}\n\n")
            append("data: [DONE]\n\n")
            append("data: {\"text\":\"ignored\"}\n\n")
        }
        assertEquals("first", CloudAsrService.parseStepSseTranscript(stopped))

        val error = runCatching {
            CloudAsrService.parseStepSseTranscript("event: error\ndata: {\"error\":{\"message\":\"boom\"}}\n\n")
        }.exceptionOrNull()
        assertTrue(error is AsrException)
        assertTrue(error!!.message!!.contains("boom"))
    }

    @Test
    fun stepTextExtractionHandlesNestedShapes() {
        assertEquals("hi", CloudAsrService.extractStepText(Json.parseToJsonElement("""{"delta":"hi"}""").jsonObject, ""))
        assertEquals(
            "deep",
            CloudAsrService.extractStepText(Json.parseToJsonElement("""{"data":{"text":"deep"}}""").jsonObject, ""),
        )
        assertEquals("fb", CloudAsrService.extractStepText(null, "fb"))
        assertEquals("", CloudAsrService.extractStepText(Json.parseToJsonElement("""{"other":1}""").jsonObject, ""))
    }

    // ------------------------------------------------------------ MiMo 会话

    @Test
    fun mimoFlushesASegmentAndPublishesTheTranscript() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"你好"}}]}"""),
        )
        val options = MimoAsrOptions(
            apiKey = "k",
            baseUrl = base(),
            model = "mimo-v2.5-asr",
            language = "zh",
            sampleRate = 1000,
            segmentDurationSec = 1, // 上限 = 2000 字节
        )
        val session = CloudAsrService.startSession(client, options)
        val partials = ArrayList<String>()
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })

        session.addPcm16(pcm(2000)) // 正好触发一次分段
        assertEquals(listOf("你好"), partials)

        val recorded = server.takeRequest()
        assertEquals("/chat/completions", recorded.path)
        assertEquals("k", recorded.getHeader("api-key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("data:audio/wav;base64,"))
        assertTrue(body.contains("\"asr_options\":{\"language\":\"zh\"}"))
        assertTrue(body.contains("\"model\":\"mimo-v2.5-asr\""))

        assertEquals("你好", session.finish())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mimoRejectsAnIncompletePcm16Sample() {
        val options = MimoAsrOptions(apiKey = "k", baseUrl = base(), sampleRate = 1000, segmentDurationSec = 1)
        val session = CloudAsrService.startSession(client, options)
        // 奇数长度：分段时报「incomplete PCM16 sample」，不会发请求。
        val error = runCatching {
            session.addPcm16(pcm(2001))
        }.exceptionOrNull()
        assertTrue(error is AsrException)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------ Step 会话

    @Test
    fun stepSkipsShortSegmentsAndPostsOnceAboveTheMinimum() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("event: transcript.text.delta\ndata: {\"delta\":\"你好\"}\n\n"),
        )
        val options = StepAsrOptions(
            apiKey = "k",
            baseUrl = base(),
            model = "stepaudio-2.5-asr",
            language = "zh",
            sampleRate = 16000,
            segmentDurationSec = 1, // 分段上限 = 32000 字节
            hotwords = listOf("Memo"),
        )
        val session = CloudAsrService.startSession(client, options)
        val partials = ArrayList<String>()
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })

        // 分段由「字节上限」触发：到 32000 才发一次。
        session.addPcm16(pcm(20000))
        assertEquals(0, server.requestCount)
        session.addPcm16(pcm(12000))
        assertEquals(listOf("你好"), partials)

        val recorded = server.takeRequest()
        assertEquals("/v1/audio/asr/sse", recorded.path)
        assertEquals("Bearer k", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"codec\":\"pcm_s16le\""))
        assertTrue(body.contains("\"rate\":16000"))
        assertTrue(body.contains("\"hotwords\":[\"Memo\"]"))
        assertTrue(body.contains("\"enable_itn\":true"))

        // 尾巴不足最小分段（3200）→ finish 也不发请求。
        session.addPcm16(pcm(1000))
        assertEquals("你好", session.finish())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun stepRetriesAndSurfacesTheServerMessage() {
        repeat(CloudAsrService.STEP_MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"upstream busy"}}"""))
        }
        val options = StepAsrOptions(apiKey = "sk-secret", baseUrl = base(), sampleRate = 16000, segmentDurationSec = 1)
        val session = CloudAsrService.startSession(client, options)
        var reported: Exception? = null
        session.observePartials(onPartial = {}, onError = { reported = it })

        val error = runCatching { session.addPcm16(pcm(32000)) }.exceptionOrNull()
        assertTrue(error is AsrException)
        assertTrue(error!!.message!!.contains("upstream busy"))
        assertEquals(CloudAsrService.STEP_MAX_ATTEMPTS, server.requestCount)
        assertTrue(reported is AsrException)
    }

    @Test
    fun unsupportedKindsAreRejectedExplicitly() {
        val options = com.psyche.memo.ui.SystemAsrOptions()
        val error = runCatching { CloudAsrService.startSession(client, options) }.exceptionOrNull()
        assertTrue(error is AsrException)
        assertTrue(error!!.message!!.contains("not a supported cloud ASR service"))
    }
}
