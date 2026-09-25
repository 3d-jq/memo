package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.retry.AutoRetryOptions
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 助手「流式输出」关闭时的非流式路径（`chat_actions.dart:2109`）——
 * 一次性响应必须摊成与流式**完全一致**的 chunk 序列（文本/思考/工具调用/用量），
 * 否则生成循环拿不到工具轮次或用量。
 */
class NonStreamChatTest {

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

    private fun reasoningOf(chunks: List<StreamChunk>) =
        chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text }

    private fun finishOf(chunks: List<StreamChunk>) = chunks.filterIsInstance<StreamChunk.Finish>().lastOrNull()

    // ---- OpenAI chat completions ----

    /**
     * DeepSeek / GLM 这类兼容端在**没开思考**时照样回 `"reasoning_content": null`。
     * kotlinx 的 `JsonNull` 也是 `JsonPrimitive`，`.content` 给出字面量 `"null"` ——
     * 于是非流式分支会凭空开一张思考卡（正文里那串 "null" 就是它）。
     * 流式分支早就用 `contentOrNull` 挡过同一个坑（见 JsonNullTrapTest），这里补齐。
     */
    @Test
    fun `an explicit null reasoning_content does not open a thinking card`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"1","choices":[{"finish_reason":"stop","message":{
                  "role":"assistant","content":"正文在这里","reasoning_content":null
                }}]}
                """.trimIndent(),
            ),
        )
        val client = OpenAiChatCompletionsClient(
            httpClient = OkHttpClient(),
            retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
            cancellations = CancellationRegistry(),
        )
        val chunks = client.completeAsChunks(
            baseRequest(server.url("/").toString(), "deepseek", "deepseek-chat"),
        ).toList()

        assertEquals("正文在这里", textOf(chunks))
        assertEquals("JSON null 不是思考内容", "", reasoningOf(chunks))
        assertFalse(
            "不许出现 ReasoningDelta（哪怕一个字符）",
            chunks.any { it is StreamChunk.ReasoningDelta },
        )
    }

    @Test
    fun openAiNonStreamYieldsTextReasoningToolsAndUsage() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"1","choices":[{"finish_reason":"tool_calls","message":{
                  "role":"assistant",
                  "content":"答案",
                  "reasoning_content":"想过",
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_time_info","arguments":"{\"tz\":\"UTC\"}"}}]
                }}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                """.trimIndent(),
            ),
        )
        val client = OpenAiChatCompletionsClient(
            httpClient = OkHttpClient(),
            retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
            cancellations = CancellationRegistry(),
        )
        val chunks = client.completeAsChunks(
            baseRequest(server.url("/").toString(), "openai", "gpt-4o"),
        ).toList()

        assertEquals("答案", textOf(chunks))
        assertEquals("想过", reasoningOf(chunks))
        val call = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals("call_1", call.id)
        assertEquals("get_time_info", call.name)
        assertEquals("""{"tz":"UTC"}""", call.arguments)
        val finish = finishOf(chunks)!!
        assertEquals("tool_calls", finish.finishReason)
        assertEquals(11, finish.usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toInt())
        // 请求确实是非流式的。
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"stream\":false"))
    }

    @Test
    fun responsesNonStreamYieldsTextAndFunctionCall() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"resp_1","status":"completed","output":[
                  {"type":"reasoning","content":[{"type":"reasoning_text","text":"推理"}]},
                  {"type":"message","content":[{"type":"output_text","text":"正文"}]},
                  {"type":"function_call","call_id":"call_9","name":"search_web","arguments":"{\"q\":\"x\"}"}
                ],"usage":{"input_tokens":5,"output_tokens":6,"total_tokens":11}}
                """.trimIndent(),
            ),
        )
        val client = OpenAiChatCompletionsClient(
            httpClient = OkHttpClient(),
            retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
            cancellations = CancellationRegistry(),
        )
        val chunks = client.completeAsChunks(
            baseRequest(server.url("/").toString(), "openai", "gpt-4o").copy(chatPath = "/responses"),
        ).toList()

        assertEquals("正文", textOf(chunks))
        assertEquals("推理", reasoningOf(chunks))
        val call = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals("call_9", call.id)
        assertEquals("search_web", call.name)
        // 有 function_call 时与流式一致地报 tool_calls（ResponsesDecoder.finishChunk）。
        assertEquals("tool_calls", finishOf(chunks)?.finishReason)
        assertEquals(5, finishOf(chunks)?.usage?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
    }

    // ---- Claude ----

    @Test
    fun claudeNonStreamYieldsTextThinkingAndToolUse() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"msg_1","type":"message","role":"assistant","stop_reason":"tool_use",
                 "content":[
                   {"type":"thinking","thinking":"想想"},
                   {"type":"text","text":"回答"},
                   {"type":"tool_use","id":"toolu_1","name":"get_time_info","input":{"tz":"UTC"}}
                 ],
                 "usage":{"input_tokens":3,"output_tokens":4}}
                """.trimIndent(),
            ),
        )
        val client = ClaudeClient(
            httpClient = OkHttpClient(),
            retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
            cancellations = CancellationRegistry(),
        )
        val chunks = client.completeAsChunks(
            baseRequest(server.url("/").toString(), "anthropic", "claude-sonnet-4"),
        ).toList()

        assertEquals("回答", textOf(chunks))
        assertEquals("想想", reasoningOf(chunks))
        val call = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals("toolu_1", call.id)
        assertEquals("get_time_info", call.name)
        assertEquals("""{"tz":"UTC"}""", call.arguments)
        val finish = finishOf(chunks)!!
        assertEquals("tool_use", finish.finishReason)
        assertEquals(3, finish.usage?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":false"))
    }

    // ---- Gemini ----

    @Test
    fun geminiNonStreamYieldsTextThoughtAndUsage() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                   {"text":"先想","thought":true},
                   {"text":"再答"}
                ]}}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"totalTokenCount":11}}
                """.trimIndent(),
            ),
        )
        val client = GeminiClient(
            httpClient = OkHttpClient(),
            retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
            cancellations = CancellationRegistry(),
        )
        val chunks = client.completeAsChunks(
            baseRequest(server.url("/v1beta").toString(), "gemini", "gemini-2.0-flash"),
        ).toList()

        assertEquals("再答", textOf(chunks))
        assertEquals("先想", reasoningOf(chunks))
        val finish = finishOf(chunks)!!
        assertEquals("STOP", finish.finishReason)
        assertEquals(9, finish.usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toInt())
        val path = server.takeRequest().path.orEmpty()
        assertFalse(path.contains("streamGenerateContent"))
    }

    @Test
    fun retryableFailuresAreRetriedBeforeAnythingWasYielded() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"finish_reason":"stop","message":{"content":"重试后的回答"}}]}""",
            ),
        )
        val client = OpenAiChatCompletionsClient(
            httpClient = OkHttpClient(),
            retryOptionsProvider = {
                AutoRetryOptions(maxRetries = 1, initialDelayMs = 0, maxDelayMs = 0, jitter = false)
            },
            cancellations = CancellationRegistry(),
        )
        val chunks = client.completeAsChunks(
            baseRequest(server.url("/").toString(), "openai", "gpt-4o"),
        ).toList()

        // 2026-09-16 起 RetryPending 是结构化事件（attempt/maxRetries/retryAtMs），
        // 重试等待不再发裸 Error 文案 —— 那会被 ChatViewModel markFailed 写进消息。
        val pending = chunks.filterIsInstance<StreamChunk.RetryPending>().single()
        assertEquals(1, pending.attempt)
        assertEquals(1, pending.maxRetries)
        assertTrue(pending.errorText.contains("HTTP 429"))
        assertEquals("重试后的回答", textOf(chunks))
        assertEquals(2, server.requestCount)
    }
}
