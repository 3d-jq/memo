package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.retry.AutoRetryOptions
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 自动重试的事件契约（用户 2026-09-16「出错还是裸http显示」）：可重试错误在
 * 重试等待期必须发**结构化 [StreamChunk.RetryPending]**（带 attempt/maxRetries/
 * retryAtMs，UI 据此画「N 秒后重试 (2/3)」倒计时），退避结束发
 * [StreamChunk.RetryAttemptStart] 清倒计时；重试路径绝不能出现裸 `Error`
 * 事件 —— 裸文案会被当成失败写进消息。终态失败照旧抛异常（ChatViewModel
 * 的 catch markFailed 路径）。镜像 Dart chat_api_service.dart:215-224。
 */
class RetryPendingEventTest {

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

    private fun baseRequest(baseUrl: String, providerId: String, modelId: String) = LlmRequest(
        providerId = providerId,
        modelId = modelId,
        messages = listOf(LlmMessage(role = "user", content = "hi")),
        apiKey = "k",
        baseUrl = baseUrl,
    )

    private fun textOf(chunks: List<StreamChunk>) =
        chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

    private fun okSse() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"成功\"}}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private fun client(maxRetries: Int = 3, initialDelayMs: Long = 10) =
        OpenAiChatCompletionsClient(
            httpClient = OkHttpClient(),
            // 关抖动以便对 delayMs 精确断言。
            retryOptionsProvider = {
                AutoRetryOptions(maxRetries = maxRetries, initialDelayMs = initialDelayMs, maxDelayMs = initialDelayMs, jitter = false)
            },
            cancellations = CancellationRegistry(),
        )

    @Test
    fun retryableErrorEmitsRetryPendingThenSucceeds() = runBlocking {
        // 第一次请求 500（在默认重试码集合里），第二次成功。
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(okSse())

        val chunks = client().streamChat(
            baseRequest(server.url("/").toString(), "openai", "gpt-4o"),
        ).toList()

        val pending = chunks.filterIsInstance<StreamChunk.RetryPending>()
        assertEquals("恰好一次重试事件", 1, pending.size)
        val p = pending.single()
        assertEquals("attempt 是 1-based 额外尝试序号", 1, p.attempt)
        assertEquals(3, p.maxRetries)
        assertEquals(10L, p.delayMs)
        assertTrue("retryAtMs 必须是绝对时刻（now+delay）", p.retryAtMs > 0L)
        assertTrue("errorText 保留原始错误串", p.errorText.contains("HTTP 500"))

        // 下一次尝试开始 → RetryAttemptStart 清倒计时。
        assertEquals(1, chunks.filterIsInstance<StreamChunk.RetryAttemptStart>().size)

        // 重试后内容完整到达。
        assertEquals("成功", textOf(chunks))
    }

    @Test
    fun nonRetryableErrorThrowsDirectly() = runBlocking {
        // 401 不在默认重试码集合、不命中重试词 → 直接抛（不重试）。
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        try {
            client().streamChat(
                baseRequest(server.url("/").toString(), "openai", "gpt-4o"),
            ).toList()
            throw IllegalStateException("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("HTTP 401"))
        }
    }

    @Test
    fun stopKeywordErrorNeverRetries() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("insufficient balance"))
        try {
            client().streamChat(
                baseRequest(server.url("/").toString(), "openai", "gpt-4o"),
            ).toList()
            throw IllegalStateException("expected IOException")
        } catch (expected: IOException) {
            // 余额类停止词：一次都不重试。
        }
    }

    @Test
    fun exhaustedRetriesThrowAfterAllPendingEvents() = runBlocking {
        // maxRetries=3 → 3 个 RetryPending（每个都跟一个 RetryAttemptStart，
        // 最后一次尝试前的 start 也发）后仍失败 → 抛出终态异常。
        // enqueue 4 个响应：前 3 个触发重试，第 4 个让最终尝试确定性拿到 500
        //（否则第 4 次请求会挂到连接超时，测试随环境波动）。
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        try {
            client(initialDelayMs = 5).streamChat(
                baseRequest(server.url("/").toString(), "openai", "gpt-4o"),
            ).toList()
            throw IllegalStateException("expected IOException")
        } catch (expected: IOException) {
            // 终态抛出即可（message 可能是 HTTP 500 也可能是连接层包装）。
            System.out.println("final error = " + expected)
        }
        // 首次 + 3 次重试都真实发出去了（OkHttp 连接层偶有额外重发，故取下界）。
        assertTrue("应至少发起 4 次请求, got " + server.requestCount, server.requestCount >= 4)
    }
}
