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

class OpenAiClientIntegrationTest {

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

    private fun client(): OpenAiChatCompletionsClient = OpenAiChatCompletionsClient(
        httpClient = OkHttpClient(),
        retryOptions = AutoRetryOptions(maxRetries = 0),
        cancellations = CancellationRegistry(),
    )

    private fun request(baseUrl: String): LlmRequest = LlmRequest(
        providerId = "openai",
        modelId = "gpt-4o-mini",
        messages = listOf(
            LlmMessage(role = "system", content = "You are helpful."),
            LlmMessage(role = "user", content = "hi"),
        ),
        apiKey = "sk-test",
        baseUrl = baseUrl,
    )

    @Test
    fun streamsTextDeltas() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val texts = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello world", texts)
        assertTrue(chunks.any { it is StreamChunk.Finish })
    }

    @Test
    fun streamsReasoningAndContent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val reasoning = chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text }
        val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("thinking...", reasoning)
        assertEquals("Answer", text)
    }

    @Test
    fun requestBodyHasModelAndMessagesAndStream() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        client().streamChat(request(server.url("/").toString())).toList()

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"messages\""))
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
    }

    @Test
    fun nonStreamingCompleteParsesChoice() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"id":"chatcmpl-2","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}}
                    """.trimIndent(),
                ),
        )
        val result = client().complete(request(server.url("/").toString()))

        assertEquals("Done", result.parts.single())
        assertEquals("stop", result.finishReason)
        assertEquals(13, result.usage?.totalTokens)
    }

    @Test
    fun httpErrorThrowsWithoutRetry() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        try {
            runBlocking { client().streamChat(request(server.url("/").toString())).toList() }
            throw AssertionError("expected IOException")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    // ---- URL construction: mirrors Flutter _openAICompatibleUrl (openai_provider.dart
    // L25-43): trimmed base + (chatPath ?? "/chat/completions"), never injecting /v1.
    // Regression for the HTTP 404 reported with bases like
    // https://text.pollinations.ai/openai and https://open.bigmodel.cn/api/paas/v4. ----

    @Test
    fun urlIsBaseUrlPlusDefaultPathForNonV1Base() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        client().streamChat(request(server.url("/openai").toString())).toList()

        assertEquals("/openai/chat/completions", server.takeRequest().path)
    }

    @Test
    fun urlKeepsV1WhenBaseCarriesIt() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        client().streamChat(request(server.url("/v1").toString())).toList()

        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun urlUsesConfiguredChatPathOverride() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        // chatPath replaces the whole path suffix (Flutter: "$rawBase$path").
        val req = request(server.url("/openai").toString()).copy(chatPath = "/v4/chat/completions")
        client().streamChat(req).toList()

        assertEquals("/openai/v4/chat/completions", server.takeRequest().path)
    }

    @Test
    fun urlTrimsTrailingSlashFromBase() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        client().streamChat(request(server.url("/openai/").toString())).toList()

        assertEquals("/openai/chat/completions", server.takeRequest().path)
    }

    @Test
    fun urlUsesBaseDirectlyWhenChatPathIsEmptyString() = runBlocking {
        // Flutter: '$rawBase$path' with path = chatPath ?? '/chat/completions'
        // — an EMPTY string keeps the base as the full endpoint (e.g.
        // pollinations POST /openai), only null falls back to the default.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        val req = request(server.url("/openai").toString()).copy(chatPath = "")
        client().streamChat(req).toList()

        assertEquals("/openai", server.takeRequest().path)
    }
}
