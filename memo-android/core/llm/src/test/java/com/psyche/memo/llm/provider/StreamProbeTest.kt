package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.probeStream
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.retry.AutoRetryOptions
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 流式自检（原版 `ProviderManager.testConnection(useStream: true)`）：只有服务端
 * 真的回了 SSE 数据才算「支持流式」——供应商详情页的测试对话框与批量检测的
 * 「使用流式」开关都吃这个结论。
 */
class StreamProbeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OpenAiChatCompletionsClient(
        httpClient = OkHttpClient(),
        retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
        cancellations = CancellationRegistry(),
    )

    private fun request() = LlmRequest(
        providerId = "openai",
        modelId = "gpt-4o-mini",
        messages = listOf(LlmMessage(role = "user", content = "hi")),
        apiKey = "sk-test",
        baseUrl = server.url("/").toString(),
    )

    @Test
    fun sseResponseCountsAsStreamingSupport() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"H\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"i\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        assertTrue(client().probeStream(request()))
        // 请求确实是流式的（body 里 stream:true）。
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun plainJsonResponseIsNotStreaming() = runBlocking {
        // 兼容端把流式请求当成普通请求处理：回一坨 JSON —— 没有 SSE 数据块。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"stream not supported"}}"""),
        )
        assertFalse(client().probeStream(request()))
    }

    @Test
    fun emptyStreamIsNotStreaming() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(""),
        )
        assertFalse(client().probeStream(request()))
    }
}
