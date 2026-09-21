package com.psyche.memo.provider

import com.psyche.memo.ui.DashScopeAsrOptions
import com.psyche.memo.ui.OpenAiRealtimeAsrOptions
import com.psyche.memo.ui.VolcengineAsrOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时（WebSocket）ASR：OpenAI Realtime / DashScope 的会话更新与事件流，以及
 * Volcengine 的二进制帧。协议来源：Flutter `cloud_asr_service.dart` 的
 * `_RealtimeAsrSession` / `_VolcengineAsrSession`，Volcengine 的帧格式与
 * RikkaHub `speech/.../VolcengineASRController.kt` 对齐（两边同一套协议）。
 */
class CloudAsrRealtimeTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val base = { server.url("/").toString().trimEnd('/') }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ------------------------------------------------------------ endpoint 拼装

    @Test
    fun endpointsAppendTheMissingQueryParameters() {
        assertEquals(
            "wss://api.openai.com/v1/realtime?intent=transcription",
            CloudAsrService.openAiEndpoint("wss://api.openai.com/v1/realtime"),
        )
        // 已经带了就不动。
        assertEquals(
            "wss://x/v1/realtime?intent=other",
            CloudAsrService.openAiEndpoint("wss://x/v1/realtime?intent=other"),
        )
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime",
            CloudAsrService.dashScopeEndpoint(
                "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
                "qwen3-asr-flash-realtime",
            ),
        )
        assertEquals(
            "wss://x/realtime?foo=1&model=m",
            CloudAsrService.dashScopeEndpoint("wss://x/realtime?foo=1", "m"),
        )
    }

    // ------------------------------------------------------------ session.update 形状

    @Test
    fun openAiSessionUpdateCarriesFormatTranscriptionAndVad() {
        val options = OpenAiRealtimeAsrOptions(
            apiKey = "k",
            model = "gpt-live-transcribe",
            language = "zh",
            prompt = "科技",
            sampleRate = 24000,
            vadThreshold = 0.5,
            prefixPaddingMs = 300,
            silenceDurationMs = 500,
        )
        val update = CloudAsrService.openAiSessionUpdate(options)
        assertEquals("session.update", update["type"]?.jsonPrimitive?.content)
        val input = update["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        val format = input["format"]!!.jsonObject
        assertEquals("audio/pcm", format["type"]?.jsonPrimitive?.content)
        assertEquals(24000, format["rate"]?.jsonPrimitive?.content?.toInt())
        val transcription = input["transcription"]!!.jsonObject
        assertEquals("gpt-live-transcribe", transcription["model"]?.jsonPrimitive?.content)
        // gpt-live-transcribe 用 languages 数组。
        assertEquals("zh", transcription["languages"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("科技", transcription["prompt"]?.jsonPrimitive?.content)
        assertEquals("near_field", input["noise_reduction"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        val vad = input["turn_detection"]!!.jsonObject
        assertEquals("server_vad", vad["type"]?.jsonPrimitive?.content)
        assertEquals(300, vad["prefix_padding_ms"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun openAiSessionUpdateUsesLanguageForNonLiveModelsAndNullVadWhenOff() {
        val options = OpenAiRealtimeAsrOptions(apiKey = "k", model = "whisper-1", language = "en")
        val update = CloudAsrService.openAiSessionUpdate(options)
        val input = update["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("en", input["transcription"]!!.jsonObject["language"]?.jsonPrimitive?.content)
        // vadThreshold = 0 → turn_detection 是 JSON null（键仍在）。
        assertTrue(input.containsKey("turn_detection"))
        assertEquals("null", input["turn_detection"].toString())
    }

    @Test
    fun dashScopeSessionUpdateCarriesRateAndLanguage() {
        val options = DashScopeAsrOptions(apiKey = "k", language = "zh", sampleRate = 16000, vadThreshold = 0.0)
        val update = CloudAsrService.dashScopeSessionUpdate(options)
        assertEquals("session.update", update["type"]?.jsonPrimitive?.content)
        val session = update["session"]!!.jsonObject
        assertEquals("pcm", session["input_audio_format"]?.jsonPrimitive?.content)
        assertEquals(16000, session["sample_rate"]?.jsonPrimitive?.content?.toInt())
        assertEquals("zh", session["input_audio_transcription"]!!.jsonObject["language"]?.jsonPrimitive?.content)
        assertEquals("null", session["turn_detection"].toString())
    }

    // ------------------------------------------------------------ Volcengine 帧

    @Test
    fun volcengineFrameHeaderAndBigEndianLength() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = CloudAsrService.volcengineFrame(
            messageType = CloudAsrService.VOLC_MSG_AUDIO_ONLY,
            flags = 0x02,
            serialization = CloudAsrService.VOLC_SER_NONE,
            compression = CloudAsrService.VOLC_COMP_NONE,
            payload = payload,
        )
        assertEquals(8 + 3, frame.size)
        assertEquals(0x11, frame[0].toInt() and 0xFF)
        // msgType 0x02 << 4 | flags 0x02
        assertEquals(0x22, frame[1].toInt() and 0xFF)
        // serialization 0 << 4 | compression 0
        assertEquals(0x00, frame[2].toInt() and 0xFF)
        assertEquals(0x00, frame[3].toInt() and 0xFF)
        // 大端长度 = 3
        assertEquals(0, frame[4].toInt())
        assertEquals(0, frame[5].toInt())
        assertEquals(0, frame[6].toInt())
        assertEquals(3, frame[7].toInt())
        assertEquals(listOf<Byte>(1, 2, 3), frame.copyOfRange(8, 11).toList())
    }

    @Test
    fun volcengineGzipRoundTrips() {
        val data = "{\"hello\":\"世界\"}".toByteArray(Charsets.UTF_8)
        val compressed = CloudAsrService.volcengineGzip(data)
        assertTrue(compressed.size != data.size)
        assertEquals(data.toList(), CloudAsrService.volcengineGunzip(compressed).toList())
    }

    // ------------------------------------------------------------ 实时会话端到端

    @Test
    fun openAiRealtimeSessionSendsAudioAndReturnsTheTranscript() {
        val received = java.util.Collections.synchronizedList(ArrayList<String>())
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    if (json["type"]?.jsonPrimitive?.content == "input_audio_buffer.commit") {
                        // 提交后回一条 completed（OpenAI 的收尾事件）。
                        webSocket.send(
                            """{"type":"conversation.item.input_audio_transcription.completed",""" +
                                """"item_id":"i1","transcript":"你好世界"}""",
                        )
                    }
                }
            }),
        )

        val options = OpenAiRealtimeAsrOptions(apiKey = "k", websocketUrl = base())
        val session = CloudAsrService.startSession(client, options)
        val partials = ArrayList<String>()
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })

        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))
        session.addPcm16(ByteArray(320))
        val transcript = session.finish()

        assertEquals("你好世界", transcript)
        // 服务端按顺序收到 session.update → append → commit。
        val types = received.mapNotNull {
            runCatching { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        }
        assertEquals(
            listOf("session.update", "input_audio_buffer.append", "input_audio_buffer.commit"),
            types,
        )
    }

    @Test
    fun openAiRealtimeSessionPublishesDeltaPartials() {
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.countDown()
                }

                // 等客户端把 session.update 发出来再回事件：否则事件可能早于
                // observePartials 注册（客户端还没订阅就收到 delta）。
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = runCatching {
                        Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
                    }.getOrNull()
                    if (type != "session.update") return
                    webSocket.send(
                        """{"type":"conversation.item.input_audio_transcription.delta","item_id":"i1","delta":"你好"}""",
                    )
                    webSocket.send(
                        """{"type":"conversation.item.input_audio_transcription.delta","item_id":"i1","delta":"世界"}""",
                    )
                    webSocket.send(
                        """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"你好世界"}""",
                    )
                }
            }),
        )

        val options = OpenAiRealtimeAsrOptions(apiKey = "k", websocketUrl = base())
        val session = CloudAsrService.startSession(client, options)
        val partials = java.util.Collections.synchronizedList(ArrayList<String>())
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })

        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))
        session.addPcm16(ByteArray(64))
        val transcript = session.finish()

        assertEquals("你好世界", transcript)
        // delta 累积过程中的快照（「你好」→「你好世界」）。
        assertTrue("expected an intermediate partial, got $partials", partials.contains("你好"))
        assertTrue("expected the final partial, got $partials", partials.contains("你好世界"))
    }

    @Test
    fun dashScopeFinishWaitsForSessionFinished() {
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = runCatching {
                        Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
                    }.getOrNull()
                    if (type == "session.finish") {
                        webSocket.send(
                            """{"type":"conversation.item.input_audio_transcription.completed",""" +
                                """"item_id":"i1","transcript":"最终文本"}""",
                        )
                        webSocket.send("""{"type":"session.finished"}""")
                    }
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.countDown()
                }
            }),
        )

        // vadThreshold > 0 ⇒ 不发 commit，只发 session.finish（原版语义）。
        val options = DashScopeAsrOptions(apiKey = "k", websocketUrl = base(), vadThreshold = 0.5)
        val session = CloudAsrService.startSession(client, options)
        session.observePartials(onPartial = {}, onError = { throw it })
        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))
        session.addPcm16(ByteArray(64))

        assertEquals("最终文本", session.finish())
    }

    @Test
    fun realtimeErrorEventSurfacesToTheObserver() {
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = runCatching {
                        Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
                    }.getOrNull()
                    if (type == "session.update") {
                        webSocket.send("""{"type":"error","error":{"message":"bad key"}}""")
                    }
                }
            }),
        )
        val options = OpenAiRealtimeAsrOptions(apiKey = "sk-secret", websocketUrl = base())
        val session = CloudAsrService.startSession(client, options)
        val errors = java.util.Collections.synchronizedList(ArrayList<Exception>())
        session.observePartials(onPartial = {}, onError = { errors.add(it) })
        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))

        // 事件是异步到的，等一小会儿；错误信息里不应出现密钥。
        val deadline = System.currentTimeMillis() + 3000
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.first().message!!.contains("bad key"))
        assertTrue(!errors.first().message!!.contains("sk-secret"))
    }

    @Test
    fun volcengineSessionSendsConfigFrameOnOpen() {
        val firstFrame = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (firstFrame.get() == null) firstFrame.set(bytes.toByteArray())
                }
            }),
        )
        val options = VolcengineAsrOptions(apiKey = "k", websocketUrl = base(), language = "zh")
        val session = CloudAsrService.startSession(client, options)
        session.observePartials(onPartial = {}, onError = { throw it })
        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))

        val deadline = System.currentTimeMillis() + 3000
        while (firstFrame.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val frame = firstFrame.get()
        assertTrue(frame != null)
        // 配置帧：MSG_FULL_CLIENT_REQUEST + SER_JSON + COMP_GZIP。
        assertEquals(0x11, frame!![0].toInt() and 0xFF)
        assertEquals(0x10, frame[1].toInt() and 0xFF) // 0x01 << 4 | 0
        assertEquals(0x11, frame[2].toInt() and 0xFF) // 0x01 << 4 | 0x01
        val payloadSize = java.nio.ByteBuffer.wrap(frame, 4, 4)
            .order(java.nio.ByteOrder.BIG_ENDIAN).int
        assertEquals(frame.size - 8, payloadSize)
        val json = Json.parseToJsonElement(
            String(CloudAsrService.volcengineGunzip(frame.copyOfRange(8, frame.size)), Charsets.UTF_8),
        ).jsonObject
        assertEquals("memo", json["user"]!!.jsonObject["uid"]?.jsonPrimitive?.content)
        assertEquals("pcm", json["audio"]!!.jsonObject["format"]?.jsonPrimitive?.content)
        assertEquals("zh", json["audio"]!!.jsonObject["language"]?.jsonPrimitive?.content)
        session.cancel()
    }

    @Test
    fun volcengineTranscriptFrameUpdatesThePartial() {
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.countDown()
                    val body = """{"result":{"text":"识别结果"}}""".toByteArray(Charsets.UTF_8)
                    val compressed = CloudAsrService.volcengineGzip(body)
                    // MSG_SERVER_RESPONSE + FLAG_LAST_PACKET + COMP_GZIP。
                    val frame = CloudAsrService.volcengineFrame(
                        messageType = CloudAsrService.VOLC_MSG_SERVER_RESPONSE,
                        flags = 0x02,
                        serialization = CloudAsrService.VOLC_SER_NONE,
                        compression = CloudAsrService.VOLC_COMP_GZIP,
                        payload = compressed,
                    )
                    webSocket.send(ByteString.of(*frame))
                }
            }),
        )
        val options = VolcengineAsrOptions(apiKey = "k", websocketUrl = base())
        val session = CloudAsrService.startSession(client, options)
        val partials = java.util.Collections.synchronizedList(ArrayList<String>())
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })
        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))

        // 收到带 last-packet 标记的转写帧后 finish() 直接返回该文本。
        assertEquals("识别结果", session.finish())
        assertTrue(partials.contains("识别结果"))
    }
}
