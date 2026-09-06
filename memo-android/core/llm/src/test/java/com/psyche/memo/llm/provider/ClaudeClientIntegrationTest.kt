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

class ClaudeClientIntegrationTest {

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

    private fun client() = ClaudeClient(
        httpClient = OkHttpClient(),
        retryOptions = AutoRetryOptions(maxRetries = 0),
        cancellations = CancellationRegistry(),
    )

    private fun request(baseUrl: String) = LlmRequest(
        providerId = "anthropic",
        modelId = "claude-sonnet-4-20250514",
        messages = listOf(
            LlmMessage(role = "system", content = "Be terse."),
            LlmMessage(role = "user", content = "hi"),
        ),
        apiKey = "sk-ant-test",
        baseUrl = baseUrl,
        maxTokens = 1024,
    )

    // ---- URL construction: mirrors Flutter claude_official.dart L64
    // ("$base/messages", no injected /v1). ----

    @Test
    fun urlIsBaseUrlPlusMessagesPathWithoutForcedV1() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\n" + "data: {\"type\":\"message_stop\"}\n\n"),
        )
        client().streamChat(request(server.url("/").toString())).toList()

        assertEquals("/messages", server.takeRequest().path)
    }

    @Test
    fun urlKeepsV1WhenBaseCarriesIt() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\n" + "data: {\"type\":\"message_stop\"}\n\n"),
        )
        client().streamChat(request(server.url("/v1").toString())).toList()

        assertEquals("/v1/messages", server.takeRequest().path)
    }

    @Test
    fun streamsTextAndThinking() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: content_block_start\n" +
                        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n\n" +
                        "event: message_stop\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello world", text)
    }

    @Test
    fun finishCarriesMergedUsageFromMessageDelta() = runBlocking {
        // message_start 携带 input_tokens，message_delta 只带 output/cache
        // ——Finish 的 usage 应合并两者（cache_read + cache_creation 求和）。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message_start\n" +
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n" +
                        "event: message_delta\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2}}\n\n" +
                        "event: message_stop\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ),
        )
        val chunks = client().streamChat(request(server.url("/").toString())).toList()

        val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("end_turn", finish.finishReason)
        val usage = finish.usage!!
        assertEquals(12, usage["input_tokens"]!!.jsonPrimitive.int)
        assertEquals(7, usage["output_tokens"]!!.jsonPrimitive.int)
        // cache_read(3) + cache_creation(2) 合并进 cache_read_input_tokens。
        assertEquals(5, usage["cache_read_input_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun requestHasClaudeHeadersAndPayload() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"),
        )
        client().streamChat(request(server.url("/").toString())).toList()

        val recorded = server.takeRequest()
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        println("CLAUDE BODY: $body")
        assertTrue("body=$body", body.contains("\"model\":\"claude-sonnet-4-20250514\""))
        assertTrue("body=$body", body.contains("\"max_tokens\":1024"))
        assertTrue("body=$body", body.contains("\"system\":\"Be terse.\""))
    }

    @Test
    fun nonStreamingParsesTextBlocks() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"Done"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
                ),
        )
        val result = client().complete(request(server.url("/").toString()))

        assertEquals("Done", result.parts.single())
        assertEquals("end_turn", result.finishReason)
        assertEquals(10, result.usage?.promptTokens)
        assertEquals(5, result.usage?.completionTokens)
    }
}
