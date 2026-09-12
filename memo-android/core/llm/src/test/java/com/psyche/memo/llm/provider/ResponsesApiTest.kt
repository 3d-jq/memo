package com.psyche.memo.llm.provider

import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmToolCall
import com.psyche.memo.llm.client.LlmToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses API 请求体（用户 2026-09-12：「补上」—— 选 `/responses` 之前只换了
 * URL，body 还是 chat-completions 的；Flutter 走的是 openai_provider.dart
 * L207-539 那套 input items）。
 */
class ResponsesApiTest {

    private fun request(
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec> = emptyList(),
        maxTokens: Int? = null,
        reasoning: Boolean = false,
        thinkingBudget: Int? = null,
        providerId: String = "openai",
        baseUrl: String = "https://api.openai.com/v1",
    ) = LlmRequest(
        providerId = providerId,
        modelId = "gpt-5",
        messages = messages,
        tools = tools,
        maxTokens = maxTokens,
        reasoning = reasoning,
        thinkingBudget = thinkingBudget,
        apiKey = "sk-test",
        baseUrl = baseUrl,
        chatPath = "/responses",
        useResponseApi = true,
    )

    @Test
    fun responsesModeIsDetectedFromTheFlagOrThePath() {
        assertTrue(ResponsesApi.isResponses(request(listOf(LlmMessage("user", content = "hi")))))
        assertTrue(
            ResponsesApi.isResponses(
                request(listOf(LlmMessage("user", content = "hi")))
                    .copy(useResponseApi = false),
            ),
        )
        assertFalse(
            ResponsesApi.isResponses(
                request(listOf(LlmMessage("user", content = "hi")))
                    .copy(useResponseApi = false, chatPath = "/chat/completions"),
            ),
        )
    }

    @Test
    fun systemMessagesBecomeInstructionsAndStayOutOfTheInput() {
        val body = ResponsesApi.buildBody(
            request(
                listOf(
                    LlmMessage("system", content = "You are helpful."),
                    LlmMessage("system", content = "Answer in Chinese."),
                    LlmMessage("user", content = "hi"),
                ),
            ),
            stream = true,
        )
        assertEquals("You are helpful.\n\nAnswer in Chinese.", body.str("instructions"))
        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("hi", input[0].jsonObject.str("content"))
        assertEquals("user", input[0].jsonObject.str("role"))
        assertEquals(JsonPrimitive(true), body["stream"])
    }

    @Test
    fun toolRoundsBecomeFunctionCallItemsAndMaxTokensIsRenamed() {
        val body = ResponsesApi.buildBody(
            request(
                messages = listOf(
                    LlmMessage("system", content = "sys"),
                    LlmMessage("user", content = "weather?"),
                    LlmMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(LlmToolCall("call_1", "get_weather", "{\"city\":\"SH\"}")),
                    ),
                    LlmMessage(role = "tool", content = "sunny", toolCallId = "call_1", toolName = "get_weather"),
                ),
                maxTokens = 256,
            ),
            stream = false,
        )
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        // 只有工具调用、没有正文的助手轮次不产生 message item。
        assertEquals(listOf("user", "function_call", "function_call_output"), input.map { it.str("type") ?: it.str("role") })
        assertEquals("call_1", input[1].str("call_id"))
        assertEquals("get_weather", input[1].str("name"))
        assertEquals("{\"city\":\"SH\"}", input[1].str("arguments"))
        assertEquals("call_1", input[2].str("call_id"))
        assertEquals("sunny", input[2].str("output"))
        assertEquals(256, body["max_output_tokens"]!!.toString().toInt())
        assertNull(body["max_tokens"])
    }

    @Test
    fun assistantTextBecomesAnOutputTextMessage() {
        val body = ResponsesApi.buildBody(
            request(
                listOf(
                    LlmMessage("user", content = "hi"),
                    LlmMessage("assistant", content = "hello"),
                    LlmMessage("user", content = "again"),
                ),
            ),
            stream = true,
        )
        val assistant = body["input"]!!.jsonArray[1].jsonObject
        assertEquals("message", assistant.str("type"))
        assertEquals("assistant", assistant.str("role"))
        assertEquals("completed", assistant.str("status"))
        val part = assistant["content"]!!.jsonArray[0].jsonObject
        assertEquals("output_text", part.str("type"))
        assertEquals("hello", part.str("text"))
    }

    @Test
    fun toolsAreFlattenedAndReasoningIsRequested() {
        val body = ResponsesApi.buildBody(
            request(
                messages = listOf(LlmMessage("user", content = "hi")),
                tools = listOf(LlmToolSpec("t", "desc", "{\"type\":\"object\"}")),
                reasoning = true,
                thinkingBudget = 8000,
            ),
            stream = true,
        )
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool.str("type"))
        assertEquals("t", tool.str("name"))
        assertEquals("desc", tool.str("description"))
        // 摊平后不能再有嵌套 function（Responses 只认平铺形态）。
        assertNull(tool["function"])
        assertEquals("auto", body.str("tool_choice"))
        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("auto", reasoning.str("summary"))
        assertEquals("medium", reasoning.str("effort"))
    }

    @Test
    fun vendorReasoningOverridesMatchTheFlutterCompatTable() {
        // DashScope：顶层 enable_thinking，没有 reasoning 对象。
        val dashscope = ResponsesApi.buildBody(
            request(
                messages = listOf(LlmMessage("user", content = "hi")),
                reasoning = true,
                providerId = "DashScope",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ),
            stream = true,
        )
        assertNull(dashscope["reasoning"])
        assertEquals(JsonPrimitive(true), dashscope["enable_thinking"])

        // DeepSeek：思考关闭时才补 effort=none。
        val deepseek = ResponsesApi.buildBody(
            request(
                messages = listOf(LlmMessage("user", content = "hi")),
                reasoning = true,
                thinkingBudget = 0,
                providerId = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
            ),
            stream = true,
        )
        assertEquals("none", deepseek["reasoning"]!!.jsonObject.str("effort"))
    }

    @Test
    fun customBodyOverridesWin() {
        val body = ResponsesApi.buildBody(
            request(listOf(LlmMessage("user", content = "hi")))
                .copy(extraBodyJson = "{\"store\":true,\"temperature\":0.3}"),
            stream = true,
        )
        assertEquals(JsonPrimitive(true), body["store"])
        assertEquals("0.3", body["temperature"].toString())
    }

    @Test
    fun userImagesBecomeInputImageParts() {
        val imagePart = """{"uri":"data:image/png;base64,AAAA","mime":"image/png"}"""
        val body = ResponsesApi.buildBody(
            request(
                listOf(
                    LlmMessage(role = "user", content = "look", parts = listOf(imagePart)),
                ),
            ),
            stream = true,
        )
        val content = body["input"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals("input_text", content[0].jsonObject.str("type"))
        assertEquals("look", content[0].jsonObject.str("text"))
        assertEquals("input_image", content[1].jsonObject.str("type"))
        assertEquals("data:image/png;base64,AAAA", content[1].jsonObject.str("image_url"))
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString || it.content != "null") it.content else null }
}
