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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

/**
 * 原生 Gemini 客户端的**工具协议往返**（P0-2）。
 *
 * 旧实现请求体里没有 `tools`（function_declarations 零命中）、消息循环不认
 * toolCalls/tool 消息、响应侧也不解析 functionCall —— 而 offeredTools() 没有
 * 供应商门控，等于提示词在教模型用工具、协议却不支持。这里照 google_common.dart
 * 钉住形状：
 *  - tools → `[{function_declarations:[{name,description,parameters}]}]`
 *  - assistant(toolCalls) → model 角色 content 追加 `{functionCall:{name,args}}`
 *  - tool 消息 → user 角色 `{functionResponse:{name,response}}`（内容解不出 JSON
 *    就包 `{result: 文本}`）
 *  - 响应 parts 的 functionCall → ToolCallDelta
 */
class GeminiToolProtocolTest {

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

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(messages: List<LlmMessage>, tools: List<com.psyche.memo.llm.client.LlmToolSpec> = emptyList()): LlmRequest =
        LlmRequest(
            providerId = "gemini",
            modelId = "gemini-2.0-flash",
            messages = messages,
            apiKey = "g-test",
            baseUrl = server.url("/").toString(),
            maxTokens = 256,
            tools = tools,
        )

    @Test
    fun toolsBecomeFunctionDeclarations() = runBlocking {
        server.enqueue(
            MockResponse().setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"),
        )
        client().streamChat(
            body(
                listOf(LlmMessage(role = "user", content = "hi")),
                tools = listOf(
                    com.psyche.memo.llm.client.LlmToolSpec("get_weather", "weather", "{\"type\":\"object\"}"),
                ),
            ),
        ).toList()

        val obj = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val decls = obj["tools"]!!.jsonArray[0].jsonObject["function_declarations"]!!.jsonArray
        assertEquals("get_weather", decls[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("weather", decls[0].jsonObject["description"]!!.jsonPrimitive.content)
        assertTrue(decls[0].jsonObject.containsKey("parameters"))
    }

    @Test
    fun assistantToolCallsBecomeFunctionCallPartsAndToolResponsesAreUserRole() = runBlocking {
        server.enqueue(
            MockResponse().setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"),
        )
        client().streamChat(
            body(
                listOf(
                    LlmMessage(role = "user", content = "查天气"),
                    LlmMessage(
                        role = "assistant",
                        content = "调用中",
                        toolCalls = listOf(LlmToolCall("gemini_call_1", "get_weather", "{\"city\":\"北京\"}")),
                    ),
                    LlmMessage(role = "tool", toolCallId = "gemini_call_1", toolName = "get_weather", content = "{\"temp\":26}"),
                ),
            ),
        ).toList()

        val contents = json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["contents"]!!.jsonArray
        // 第二条 = model 角色，parts 含 text + functionCall
        val model = contents[1].jsonObject
        assertEquals("model", model["role"]!!.jsonPrimitive.content)
        val parts = model["parts"]!!.jsonArray
        assertEquals("调用中", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        val fc = parts[1].jsonObject["functionCall"]!!.jsonObject
        assertEquals("get_weather", fc["name"]!!.jsonPrimitive.content)
        assertEquals("北京", fc["args"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        // 第三条 = user 角色，parts 含 functionResponse
        val user = contents[2].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val fr = user["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("get_weather", fr["name"]!!.jsonPrimitive.content)
        assertEquals(26, fr["response"]!!.jsonObject["temp"]!!.jsonPrimitive.int)
    }

    @Test
    fun nonJsonToolResultIsWrappedAsResultField() = runBlocking {
        server.enqueue(
            MockResponse().setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"),
        )
        client().streamChat(
            body(
                listOf(
                    LlmMessage(role = "user", content = "hi"),
                    LlmMessage(
                        role = "assistant",
                        toolCalls = listOf(LlmToolCall("c1", "noop", "{}")),
                    ),
                    LlmMessage(role = "tool", toolCallId = "c1", toolName = "noop", content = "plain text result"),
                ),
            ),
        ).toList()

        val contents = json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["contents"]!!.jsonArray
        val fr = contents[2].jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["functionResponse"]!!.jsonObject
        assertEquals("plain text result", fr["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamedFunctionCallPartDecodesToToolCallDelta() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                        "{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"北京\"}}}]}}]}\n\n",
                ),
        )
        val chunks = client().streamChat(
            body(listOf(LlmMessage(role = "user", content = "weather?"))),
        ).toList()
        val calls = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertTrue("functionCall 必须解成 ToolCallDelta", calls.isNotEmpty())
        assertEquals("get_weather", calls.first().name)
        assertTrue(calls.any { it.arguments.contains("city") })
    }
}
