package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmToolCall
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.retry.AutoRetryOptions
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
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

    // ---- Tool-followup transcript serialization (chat_completions_api.dart
    // buildOpenAIChatCompletionMessages 25-66 + openai_tool_transcript.dart) ----

    private fun bodyOf(req: LlmRequest): JsonObject = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        client().streamChat(req).toList()
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    private fun messagesOf(body: JsonObject): List<JsonObject> =
        (body["messages"] as JsonArray).map { it.jsonObject }

    @Test
    fun assistantWithToolCalls_serializesToolCallsAndContent() {
        val req = request(server.url("/").toString()).copy(
            messages = listOf(
                LlmMessage(role = "user", content = "hi"),
                LlmMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        LlmToolCall(
                            id = "call_1",
                            name = "get_weather",
                            argumentsJson = """{"city":"Beijing"}""",
                        ),
                    ),
                ),
            ),
        )
        val assistant = messagesOf(bodyOf(req))[1]
        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.content)
        assertEquals("", assistant["content"]?.jsonPrimitive?.content)
        val calls = assistant["tool_calls"] as JsonArray
        val call = calls[0].jsonObject
        assertEquals("call_1", call["id"]?.jsonPrimitive?.content)
        assertEquals("function", call["type"]?.jsonPrimitive?.content)
        val fn = call["function"]?.jsonObject!!
        assertEquals("get_weather", fn["name"]?.jsonPrimitive?.content)
        assertEquals("""{"city":"Beijing"}""", fn["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun assistantWithoutToolCalls_hasNoToolCallsKey() {
        val req = request(server.url("/").toString()).copy(
            messages = listOf(
                LlmMessage(role = "assistant", content = "Hello!"),
            ),
        )
        val assistant = messagesOf(bodyOf(req)).single()
        assertTrue(assistant.containsKey("content"))
        assertTrue(!assistant.containsKey("tool_calls"))
    }

    @Test
    fun toolRoleMessage_serializesLinkageFields() {
        val req = request(server.url("/").toString()).copy(
            messages = listOf(
                LlmMessage(
                    role = "tool",
                    toolCallId = "call_1",
                    toolName = "get_weather",
                    content = """{"temp":21}""",
                ),
            ),
        )
        val tool = messagesOf(bodyOf(req)).single()
        assertEquals("tool", tool["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", tool["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tool["name"]?.jsonPrimitive?.content)
        assertEquals("""{"temp":21}""", tool["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun toolRoleWithoutName_omitsNameKey() {
        val req = request(server.url("/").toString()).copy(
            messages = listOf(
                LlmMessage(role = "tool", toolCallId = "call_1", content = "ok"),
            ),
        )
        val tool = messagesOf(bodyOf(req)).single()
        assertEquals("call_1", tool["tool_call_id"]?.jsonPrimitive?.content)
        assertTrue(!tool.containsKey("name"))
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
