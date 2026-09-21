package com.psyche.memo.llm.provider

import com.psyche.memo.llm.stream.SseEvent
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses SSE 解码（用户 2026-09-12：「补上」—— `/responses` 之前只换 URL，
 * 解码仍按 chat-completions 走，正文/思考/工具/用量全部丢掉）。
 */
class ResponsesDecoderTest {

    private fun event(data: String) = SseEvent(id = null, event = null, data = data, retryMillis = null)

    @Test
    fun textAndReasoningDeltasAreDecoded() {
        val decoder = ResponsesDecoder()
        val chunks = ArrayList<StreamChunk>()
        chunks += decoder.accept(
            event("""{"type":"response.reasoning_summary_text.delta","delta":"思考"}"""),
        ).chunks
        chunks += decoder.accept(
            event("""{"type":"response.output_text.delta","delta":"你好"}"""),
        ).chunks
        chunks += decoder.accept(event("""{"type":"response.output_text.delta","delta":"！"}""")).chunks

        assertEquals("思考", chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text })
        assertEquals("你好！", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun functionCallIsAssembledFromAddedDeltaAndDone() {
        val decoder = ResponsesDecoder()
        val chunks = ArrayList<StreamChunk>()
        chunks += decoder.accept(
            event(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_weather","arguments":""}}""",
            ),
        ).chunks
        chunks += decoder.accept(
            event("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"city\":"}"""),
        ).chunks
        chunks += decoder.accept(
            event("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"\"SH\"}"}"""),
        ).chunks
        chunks += decoder.accept(
            event(
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\"city\":\"SH\"}"}}""",
            ),
        ).chunks

        // 一次 name + 两段参数，done 不许把完整参数再拼一遍。
        val calls = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
        assertEquals("call_1", calls.first().id)
        assertEquals("get_weather", calls.joinToString("") { it.name })
        assertEquals("{\"city\":\"SH\"}", calls.joinToString("") { it.arguments })
        assertTrue(decoder.hasFunctionCalls)
    }

    @Test
    fun callIdPrefersTheVendorCallIdSoFollowUpsMatch() {
        val decoder = ResponsesDecoder()
        val chunks = decoder.accept(
            event(
                """{"type":"response.output_item.added","output_index":3,"item":{"type":"function_call","id":"fc_9","name":"t","arguments":"{}"}}""",
            ),
        ).chunks
        // 没有 call_id 时退回 item id，保证 id 非空（ToolCallPart.id 必需）。
        assertEquals("fc_9", (chunks[0] as StreamChunk.ToolCallDelta).id)
    }

    @Test
    fun completedCarriesUsageAndFinishes() {
        val decoder = ResponsesDecoder()
        val result = decoder.accept(
            event(
                """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":12,"output_tokens":7,"total_tokens":19},"output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]}}""",
            ),
        )
        assertTrue(result.completed)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("stop", finish.finishReason)
        assertEquals(JsonPrimitive(12), finish.usage!!["input_tokens"])
        assertEquals(JsonPrimitive(19), finish.usage!!["total_tokens"])
    }

    @Test
    fun functionCallsOnlyPresentInTheTerminalEventStillSurface() {
        val decoder = ResponsesDecoder()
        val result = decoder.accept(
            event(
                """{"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"call_7","name":"t","arguments":"{\"a\":1}"}]}}""",
            ),
        )
        val call = result.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals("call_7", call.id)
        assertEquals("t", call.name)
        assertEquals("{\"a\":1}", call.arguments)
        assertEquals("tool_calls", result.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun doneMarkerFinishesEvenWithoutATerminalEvent() {
        val decoder = ResponsesDecoder()
        val result = decoder.accept(event("[DONE]"))
        assertTrue(result.completed)
        assertTrue(result.chunks.single() is StreamChunk.Finish)
    }

    @Test
    fun closedStreamStillReportsAFinish() {
        val decoder = ResponsesDecoder()
        decoder.accept(event("""{"type":"response.output_text.delta","delta":"partial"}"""))
        val tail = decoder.onClosed()
        assertEquals(1, tail.size)
        assertTrue(tail.single() is StreamChunk.Finish)
        // 二次收尾不重复吐。
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun jsonNullFieldsNeverBecomeTheLiteralNull() {
        val decoder = ResponsesDecoder()
        val chunks = decoder.accept(
            event("""{"type":"response.output_text.delta","delta":null}"""),
        ).chunks
        assertTrue(chunks.isEmpty())
    }
}
