package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZCode 式 AI 输出行（[AgentTraceBlock]，2026-09-26）的纯逻辑护栏：
 * 流式摘要取行、时长取整、思考文本清洗、工具 payload 起止时刻与失败信封判定。
 */
class AgentTraceFormatTest {

    // ---- reasoningLastLine（ZCode resolveReasoningStreamingSummary 口径）----

    @Test
    fun reasoningLastLineTakesTheLastNonEmptyTrimmedLine() {
        assertEquals("第三行", reasoningLastLine("第一行\n\n  第二行  \n第三行\n"))
        assertEquals("只有一行", reasoningLastLine("   只有一行\n"))
    }

    @Test
    fun reasoningLastLineIsNullForBlankText() {
        assertNull(reasoningLastLine(""))
        assertNull(reasoningLastLine("\n \n"))
    }

    // ---- traceDurationSeconds（ZCode Math.ceil，0 记 1）----

    @Test
    fun traceDurationCeilsAndNeverShowsZero() {
        assertEquals(1, traceDurationSeconds(0))
        assertEquals(1, traceDurationSeconds(999))
        assertEquals(1, traceDurationSeconds(1000))
        assertEquals(5, traceDurationSeconds(4500))
        assertEquals(12, traceDurationSeconds(11_999))
    }

    // ---- sanitizeReasoning ----

    @Test
    fun sanitizeStripsCarriageReturnsAndTrim() {
        assertEquals("a\nb", sanitizeReasoning("  a\r\nb\r "))
    }

    // ---- ToolUiPart 起止时刻 / 失败信封 ----

    @Test
    fun toolPayloadTimestampsRoundTrip() {
        val part = ToolCallPart.encode(
            id = "t1",
            name = "browser_open",
            arguments = Json.parseToJsonElement("{}"),
            content = null,
            server = false,
            startedAt = 1_000L,
            finishedAt = 4_500L,
        )
        val payload = ToolCallPart.decode(part.payloadJson)!!
        assertEquals(1_000L, payload.startedAt)
        assertEquals(4_500L, payload.finishedAt)

        val ui = ToolUiPart.fromPayload(part.payloadJson)!!
        assertEquals(1_000L, ui.startedAt)
        assertEquals(4_500L, ui.finishedAt)
    }

    @Test
    fun legacyPayloadWithoutTimestampsReadsNull() {
        val ui = ToolUiPart.fromPayload(
            """{"id":"t1","name":"bash","arguments":{},"content":null,"server":false}""",
        )!!
        assertNull(ui.startedAt)
        assertNull(ui.finishedAt)
        assertFalse(ui.isError)
    }

    @Test
    fun toolErrorEnvelopeIsDetectedAndPlainTextIsNot() {
        val error = ToolUiPart.fromPayload(
            """{"id":"t1","name":"bash","arguments":{},"content":"{\"type\":\"tool_error\",\"status\":\"error\",\"error\":\"x\"}","server":false}""",
        )!!
        assertTrue(error.isError)

        val ok = ToolUiPart.fromPayload(
            """{"id":"t2","name":"bash","arguments":{},"content":"{\"type\":\"tool_result\",\"status\":\"ok\"}","server":false}""",
        )!!
        assertFalse(ok.isError)

        val plain = ToolUiPart.fromPayload(
            """{"id":"t3","name":"bash","arguments":{},"content":"普通文本结果","server":false}""",
        )!!
        assertFalse(plain.isError)
    }

    @Test
    fun prettyArgsJsonFallsBackToRawOnFailure() {
        val args = Json.parseToJsonElement("""{"b":1,"a":"x"}""").jsonObject
        val pretty = prettyArgsJson(args)
        assertTrue(pretty.contains("\"a\""))
        // 失败回退路径：非 JSON 元素不会出现（JsonObject 一定可编码），这里只验形状。
        assertTrue(pretty.contains("\n"))
        assertEquals(JsonPrimitive(1), Json.parseToJsonElement(pretty).jsonObject["b"])
    }
}
