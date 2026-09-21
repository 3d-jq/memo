package com.psyche.memo.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * 带内错误帧（`chat_api_helpers.dart:857-919`）。这类帧的 HTTP 状态码是 200，
 * 不认就会被当正常收尾 —— 半成品回答当成功落库，连报错都没有。
 */
class InBandStreamErrorTest {

    private fun expectError(data: String, expected: String) {
        try {
            throwIfInBandStreamError(data)
            fail("应当抛出：$data")
        } catch (e: IOException) {
            assertEquals(expected, e.message)
        }
    }

    @Test
    fun healthyChunksPassThrough() {
        throwIfInBandStreamError("""{"choices":[{"delta":{"content":"hi"}}]}""")
        // 没有 error 键、error 为 null / 空对象 / 空串，都是健康 chunk 的形状。
        throwIfInBandStreamError("""{"error":null,"choices":[]}""")
        throwIfInBandStreamError("""{"error":{},"choices":[]}""")
        throwIfInBandStreamError("""{"error":"","choices":[]}""")
        throwIfInBandStreamError("""{"error":false}""")
        throwIfInBandStreamError("""{"error":0}""")
        // 正文里出现 "error" 这个词但不是 JSON 对象：解析不了就放行。
        throwIfInBandStreamError("""data: not json at all "error" """)
    }

    @Test
    fun openAiStyleErrorObjectThrowsWithCodeAndMessage() {
        expectError(
            """{"error":{"message":"rate limit reached","code":"rate_limit_exceeded"}}""",
            "Provider error (rate_limit_exceeded): rate limit reached",
        )
        // 没有 code 时用 type；都没有就把整个对象按 JSON 原样带出来（上游 jsonEncode）。
        expectError(
            """{"error":{"message":"bad key","type":"authentication_error"}}""",
            "Provider error (authentication_error): bad key",
        )
        expectError(
            """{"error":{"status":400}}""",
            """Provider error: {"status":400}""",
        )
    }

    @Test
    fun anthropicStyleErrorFrameReadsTheNestedPayload() {
        expectError(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            "Provider error (overloaded_error): Overloaded",
        )
        // Responses API 把 code/message 平铺在帧上。
        expectError(
            """{"type":"error","code":"context_length_exceeded","message":"too long"}""",
            "Provider error (context_length_exceeded): too long",
        )
    }

    @Test
    fun stringErrorPayloadThrows() {
        expectError("""{"error":"upstream blew up"}""", "Provider error: upstream blew up")
    }

    @Test
    fun responsesApiTerminalFailuresNeverLookLikeSuccess() {
        expectError(
            """{"type":"response.failed","response":{"error":{"message":"server_error"}}}""",
            "Provider error: server_error",
        )
        expectError(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
            "Provider error: response incomplete (max_output_tokens)",
        )
        // 载荷解不出来的失败事件也必须抛，不能被当正常收尾。
        expectError(
            """{"type":"response.failed","response":{}}""",
            "Provider error: response.failed",
        )
    }
}
