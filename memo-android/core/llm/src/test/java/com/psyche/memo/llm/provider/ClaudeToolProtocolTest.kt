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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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

/**
 * 原生 Claude 客户端的**工具协议往返**（P0-2）。
 *
 * 旧实现请求体只发 role/content：assistant 轮不发 tool_use 块、tool 轮降级成
 * user 文本。工具定义倒是发了，于是模型「能调用」但永远看不到自己调用过、也看不到
 * 结果。这里钉住 claude_history.dart 的形状：
 *  - assistant(tool_calls) → content 块 [text?] + [{type:"tool_use", id, name, input}]
 *  - tool 消息 → 并进上一条 user 的 tool_result 块（无上条 user 则独立成条）
 *  - 空结果折成 "(no output)"
 */
class ClaudeToolProtocolTest {

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
        retryOptionsProvider = { AutoRetryOptions(maxRetries = 0) },
        cancellations = CancellationRegistry(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun assistantToolCallsBecomeToolUseBlocksAndToolResultsMergeIntoPrevUser() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\n" + "data: {\"type\":\"message_stop\"}\n\n"),
        )
        val request = LlmRequest(
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            messages = listOf(
                LlmMessage(role = "user", content = "帮我查天气"),
                LlmMessage(
                    role = "assistant",
                    content = "我来调用工具。",
                    toolCalls = listOf(LlmToolCall("call_1", "get_weather", "{\"city\":\"北京\"}")),
                ),
                LlmMessage(role = "tool", toolCallId = "call_1", toolName = "get_weather", content = "{\"temp\":26}"),
            ),
            apiKey = "sk-ant-test",
            baseUrl = server.url("/").toString(),
            maxTokens = 1024,
        )
        client().streamChat(request).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        // 上一条 user 仍带原文；assistant 变块数组。
        val user = messages[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val blocks = assistant["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        val toolUse = blocks[1].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals("北京", toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        // tool 结果并进这条 assistant 之后的 user（内容为 tool_result 块）。
        val toolUser = messages[2].jsonObject
        assertEquals("user", toolUser["role"]!!.jsonPrimitive.content)
        val resultBlock = toolUser["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", resultBlock["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", resultBlock["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("{\"temp\":26}", resultBlock["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyToolResultBecomesNoOutputPlaceholder() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\n" + "data: {\"type\":\"message_stop\"}\n\n"),
        )
        val request = LlmRequest(
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            messages = listOf(
                LlmMessage(role = "user", content = "hi"),
                LlmMessage(
                    role = "assistant",
                    toolCalls = listOf(LlmToolCall("call_1", "noop", "{}")),
                ),
                LlmMessage(role = "tool", toolCallId = "call_1", toolName = "noop", content = ""),
            ),
            apiKey = "sk-ant-test",
            baseUrl = server.url("/").toString(),
            maxTokens = 1024,
        )
        client().streamChat(request).toList()

        val messages = json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray
        // 空正文的 assistant 不带 text 块，content 直接是 tool_use 列表。
        val blocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("tool_use", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        val results = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals("(no output)", results[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResultsFollowUserTurnInsteadOfStandaloneMessage() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\n" + "data: {\"type\":\"message_stop\"}\n\n"),
        )
        val request = LlmRequest(
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            messages = listOf(
                LlmMessage(
                    role = "assistant",
                    toolCalls = listOf(LlmToolCall("call_1", "noop", "{}")),
                ),
                LlmMessage(role = "tool", toolCallId = "call_1", toolName = "noop", content = "x"),
            ),
            apiKey = "sk-ant-test",
            baseUrl = server.url("/").toString(),
            maxTokens = 1024,
        )
        client().streamChat(request).toList()

        val messages = json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray
        // 没有上一条 user 时，tool 结果独立成条 user（不挂在 assistant 后面）。
        assertEquals(2, messages.size)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject["content"] is kotlinx.serialization.json.JsonArray)
    }

    @Test
    fun toolDefinitionIsSentAndStreamedToolUseDecodes() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: content_block_start\n" +
                        "data: {\"type\":\"content_block_start\",\"index\":0," +
                        "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0," +
                        "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"北京\\\"}\"}}\n\n" +
                        "event: message_stop\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ),
        )
        val request = LlmRequest(
            providerId = "anthropic",
            modelId = "claude-sonnet-4-20250514",
            messages = listOf(LlmMessage(role = "user", content = " weather?")),
            tools = listOf(
                com.psyche.memo.llm.client.LlmToolSpec(
                    name = "get_weather",
                    description = "weather",
                    inputSchemaJson = "{\"type\":\"object\"}",
                ),
            ),
            apiKey = "sk-ant-test",
            baseUrl = server.url("/").toString(),
            maxTokens = 1024,
        )
        val chunks = client().streamChat(request).toList()
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tools = body["tools"]!!.jsonArray
        assertEquals("get_weather", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(tools[0].jsonObject.containsKey("input_schema"))
        val calls = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertTrue("tool_use 必须解成 ToolCallDelta（名字+增量参数）", calls.isNotEmpty())
        assertEquals("get_weather", calls.first().name)
        assertTrue(calls.any { it.arguments.contains("city") })
    }
}
