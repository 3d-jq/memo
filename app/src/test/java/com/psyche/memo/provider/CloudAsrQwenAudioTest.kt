package com.psyche.memo.provider

import com.psyche.memo.ui.QwenAudioAsrOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
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
 * Qwen Audio ASR（原版 `_QwenAudioAsrSession`）：run-task → 二进制 PCM →
 * result-generated → finish-task。RikkaHub 的 speech 模块没有这一家，所以按 Flutter 侧照做。
 */
class CloudAsrQwenAudioTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val base = { server.url("/").toString().trimEnd('/') }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun runTaskThenAudioThenFinishTask() {
        val received = java.util.Collections.synchronizedList(ArrayList<String>())
        val binaryFrames = java.util.concurrent.atomic.AtomicInteger(0)
        val firstAudio = CountDownLatch(1)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val action = (json?.get("header") as? kotlinx.serialization.json.JsonObject)
                        ?.get("action")?.jsonPrimitive?.content
                    when (action) {
                        "run-task" -> webSocket.send("""{"header":{"event":"task-started"}}""")
                        "finish-task" -> {
                            webSocket.send(
                                """{"header":{"event":"result-generated"},"payload":{"output":""" +
                                    """{"sentence":{"text":"最终结果","sentence_end":true}}}}""",
                            )
                            webSocket.send("""{"header":{"event":"task-finished"}}""")
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    binaryFrames.incrementAndGet()
                    firstAudio.countDown()
                }
            }),
        )

        val options = QwenAudioAsrOptions(apiKey = "k", model = "qwen-audio-asr")
        val session = CloudAsrService.startSession(client, options, endpointOverride = base())
        val partials = java.util.Collections.synchronizedList(ArrayList<String>())
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })

        session.addPcm16(ByteArray(320))
        assertTrue(firstAudio.await(5, TimeUnit.SECONDS))
        assertEquals("最终结果", session.finish())

        // 服务端先收到 run-task，再收到 finish-task。
        val actions = received.mapNotNull { text ->
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            (json?.get("header") as? kotlinx.serialization.json.JsonObject)
                ?.get("action")?.jsonPrimitive?.content
        }
        assertEquals(listOf("run-task", "finish-task"), actions)
        assertTrue(binaryFrames.get() >= 1)
    }

    @Test
    fun sentenceEndAccumulatesFinalizedSentences() {
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val action = runCatching {
                        (Json.parseToJsonElement(text).jsonObject["header"] as? kotlinx.serialization.json.JsonObject)
                            ?.get("action")?.jsonPrimitive?.content
                    }.getOrNull()
                    if (action == "run-task") {
                        serverSocket.countDown()
                        webSocket.send("""{"header":{"event":"task-started"}}""")
                    }
                    if (action == "finish-task") {
                        // 第一句已定稿，第二句是当前半句。
                        webSocket.send(
                            """{"header":{"event":"result-generated"},"payload":{"output":""" +
                                """{"sentence":{"text":"你好。","sentence_end":true}}}}""",
                        )
                        webSocket.send(
                            """{"header":{"event":"result-generated"},"payload":{"output":""" +
                                """{"sentence":{"text":"世界","sentence_end":false}}}}""",
                        )
                        webSocket.send("""{"header":{"event":"task-finished"}}""")
                    }
                }
            }),
        )
        val options = QwenAudioAsrOptions(apiKey = "k")
        val session = CloudAsrService.startSession(client, options, endpointOverride = base())
        val partials = java.util.Collections.synchronizedList(ArrayList<String>())
        session.observePartials(onPartial = { partials.add(it) }, onError = { throw it })
        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))

        // 定稿句 + 半句拼接：中文直接相接，不加空格。
        assertEquals("你好。世界", session.finish())
        assertTrue(partials.contains("你好。世界"))
    }

    @Test
    fun taskFailedSurfacesAsAnError() {
        val serverSocket = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val action = runCatching {
                        (Json.parseToJsonElement(text).jsonObject["header"] as? kotlinx.serialization.json.JsonObject)
                            ?.get("action")?.jsonPrimitive?.content
                    }.getOrNull()
                    if (action == "run-task") {
                        serverSocket.countDown()
                        webSocket.send(
                            """{"header":{"event":"task-failed","error_message":"bad key sk-secret"}}""",
                        )
                    }
                }
            }),
        )
        val options = QwenAudioAsrOptions(apiKey = "sk-secret")
        val session = CloudAsrService.startSession(client, options, endpointOverride = base())
        val errors = java.util.Collections.synchronizedList(ArrayList<Exception>())
        session.observePartials(onPartial = {}, onError = { errors.add(it) })
        assertTrue(serverSocket.await(5, TimeUnit.SECONDS))

        val deadline = System.currentTimeMillis() + 3000
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.first().message!!.contains("bad key"))
        // 密钥必须被抹掉。
        assertTrue(!errors.first().message!!.contains("sk-secret"))
    }

    // ------------------------------------------------------------ 纯函数

    @Test
    fun sentenceEndFlagAcceptsBooleansAndStrings() {
        assertTrue(CloudAsrService.isQwenSentenceEnd(Json.parseToJsonElement("""{"sentence_end":true}""").jsonObject))
        assertTrue(CloudAsrService.isQwenSentenceEnd(Json.parseToJsonElement("""{"sentence_end":"true"}""").jsonObject))
        assertTrue(CloudAsrService.isQwenSentenceEnd(Json.parseToJsonElement("""{"sentence_end":"1"}""").jsonObject))
        assertTrue(!CloudAsrService.isQwenSentenceEnd(Json.parseToJsonElement("""{"sentence_end":false}""").jsonObject))
        assertTrue(!CloudAsrService.isQwenSentenceEnd(Json.parseToJsonElement("""{}""").jsonObject))
    }

    @Test
    fun transcriptJoiningSpacesLatinButNotCjk() {
        // 拉丁词边界补空格（词尾或标点后接拉丁词）。
        assertEquals("hello world", CloudAsrService.combineQwenAudioTranscript("hello", "world"))
        assertEquals("end. Next", CloudAsrService.combineQwenAudioTranscript("end.", "Next"))
        // 中日韩直接相接。
        assertEquals("你好世界", CloudAsrService.combineQwenAudioTranscript("你好", "世界"))
        // 已经带前导空白就不再补。
        assertEquals("hello world", CloudAsrService.combineQwenAudioTranscript("hello", " world"))
        // 空串边界。
        assertEquals("abc", CloudAsrService.combineQwenAudioTranscript("", "abc"))
        assertEquals("abc", CloudAsrService.combineQwenAudioTranscript("abc", ""))
    }
}
