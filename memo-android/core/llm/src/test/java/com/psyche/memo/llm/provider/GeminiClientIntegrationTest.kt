package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.retry.AutoRetryOptions
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiClientIntegrationTest {

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

    private fun client() = GeminiClient(
        httpClient = OkHttpClient(),
        retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
        cancellations = CancellationRegistry(),
    )

    private fun request(baseUrl: String) = LlmRequest(
        providerId = "gemini",
        modelId = "gemini-2.0-flash",
        messages = listOf(
            LlmMessage(role = "system", content = "Be terse."),
            LlmMessage(role = "user", content = "hi"),
            LlmMessage(role = "assistant", content = "Hello!"),
        ),
        apiKey = "g-test",
        baseUrl = baseUrl,
        maxTokens = 256,
    )

    // ---- URL construction: mirrors Flutter google_common.dart L949
    // ("$base/models/$model:streamGenerateContent", no injected /v1beta). ----

    @Test
    fun urlIsBaseUrlPlusModelsPathWithoutForcedV1Beta() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n"),
        )
        client().streamChat(request(server.url("/").toString())).toList()

        assertEquals("/models/gemini-2.0-flash:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test
    fun urlKeepsV1BetaWhenBaseCarriesIt() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n"),
        )
        client().streamChat(request(server.url("/v1beta").toString())).toList()

        assertEquals("/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test
    fun streamsTextAndThought() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Thinking…\",\"thought\":true}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}],\"usageMetadata\":{\"promptTokenCount\":4,\"candidatesTokenCount\":6,\"totalTokenCount\":10}}\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val reasoning = chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text }
        val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("Thinking…", reasoning)
        assertEquals("Hello", text)
    }

    @Test
    fun finishCarriesUsageFromUsageMetadata() = runBlocking {
        // usageMetadata 与 finishReason 同 chunk —— Finish 应携带 usage。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" there\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":5,\"totalTokenCount\":14}}\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("STOP", finish.finishReason)
        val usage = finish.usage!!
        assertEquals(9, usage["prompt_tokens"]!!.jsonPrimitive.int)
        assertEquals(5, usage["completion_tokens"]!!.jsonPrimitive.int)
        assertEquals(14, usage["total_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun emitsFinishFromAccumulatedUsageWhenStreamEndsWithoutFinishReason() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n\n" +
                        "data: {\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5}}\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals(5, finish.usage!!["total_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun requestHasGeminiHeadersAndPayload() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {}\n\n"),
        )
        client().streamChat(request(server.url("/").toString())).toList()

        val recorded = server.takeRequest()
        assertEquals("g-test", recorded.getHeader("x-goog-api-key"))
        val body = recorded.body.readUtf8()
        assertTrue("body=$body", body.contains("\"systemInstruction\""))
        assertTrue("body=$body", body.contains("\"role\":\"model\""))
    }
}
