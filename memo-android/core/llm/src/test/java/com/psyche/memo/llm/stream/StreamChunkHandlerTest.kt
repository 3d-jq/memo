package com.psyche.memo.llm.stream

import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChunkHandlerTest {

    @Test
    fun foldsTextDeltasInOrder() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.TextDelta("Hello "))
        handler.handle(StreamChunk.TextDelta("world"))
        assertEquals("Hello world", handler.textContent())
        val parts = handler.parts()
        assertEquals(1, parts.size)
        assertEquals("Hello world", (parts[0] as TextPart).text)
    }

    @Test
    fun foldsReasoningBeforeText() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ReasoningDelta("think..."))
        handler.handle(StreamChunk.TextDelta("Answer"))
        val parts = handler.parts()
        assertEquals(2, parts.size)
        assertTrue(parts[0] is ReasoningPart)
        assertTrue(parts[1] is TextPart)
    }

    @Test
    fun foldsToolCallArgumentsByToolId() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("call_1", "get_weather", "{\"city\":"))
        handler.handle(StreamChunk.ToolCallDelta("call_1", "", "\"NYC\"}"))
        val parts = handler.parts()
        assertEquals(1, parts.size)
        val tool = parts[0] as ToolCallPart
        assertTrue(tool.payloadJson.contains("NYC"))
        assertTrue(tool.payloadJson.contains("call_1"))
    }

    @Test
    fun multipleToolsAreKeptSeparate() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("t1", "a", "{}"))
        handler.handle(StreamChunk.ToolCallDelta("t2", "b", "{}"))
        assertEquals(2, handler.parts().size)
    }

    @Test
    fun finishIgnoresLaterChunks() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.TextDelta("A"))
        handler.handle(StreamChunk.Finish("stop", null))
        assertTrue(handler.finished)
        handler.handle(StreamChunk.TextDelta("B"))
        assertEquals("A", handler.textContent())
    }

    @Test
    fun emptyStreamProducesNoParts() {
        val handler = StreamChunkHandler()
        assertTrue(handler.parts().isEmpty())
        assertFalse(handler.finished)
    }

    @Test
    fun collectFinishesStream() {
        val handler = StreamChunkHandler.collect(
            listOf(StreamChunk.TextDelta("Hello"), StreamChunk.Finish("stop", null)),
        )
        assertTrue(handler.finished)
        assertEquals("Hello", handler.textContent())
        assertEquals("stop", handler.finishReason)
    }
}
