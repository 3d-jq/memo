package com.psyche.memo.llm.stream

import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import com.psyche.memo.data.model.ToolCallPayload
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChunkHandlerTest {

    private fun toolAt(parts: List<MessagePart>, index: Int): ToolCallPayload {
        val decoded = ToolCallPart.decode((parts[index] as ToolCallPart).payloadJson)
        assertNotNull(decoded)
        return decoded!!
    }

    @Test
    fun foldsTextDeltasInOrder() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.TextDelta("Hello "))
        handler.handle(StreamChunk.TextDelta("world"))
        assertEquals("Hello world", handler.textContent())
        val parts = handler.parts
        assertEquals(1, parts.size)
        assertEquals("Hello world", (parts[0] as TextPart).text)
    }

    @Test
    fun foldsReasoningBeforeText() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ReasoningDelta("think..."))
        handler.handle(StreamChunk.TextDelta("Answer"))
        val parts = handler.parts
        assertEquals(2, parts.size)
        assertTrue(parts[0] is ReasoningPart)
        assertTrue(parts[1] is TextPart)
    }

    @Test
    fun foldsToolCallArgumentsByToolId() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("call_1", "get_weather", "{\"city\":"))
        handler.handle(StreamChunk.ToolCallDelta("call_1", "", "\"NYC\"}"))
        val parts = handler.parts
        assertEquals(1, parts.size)
        val tool = toolAt(parts, 0)
        assertEquals("call_1", tool.id)
        assertEquals("get_weather", tool.name)
        assertEquals("""{"city":"NYC"}""", tool.arguments)
        assertNull(tool.content)
        assertFalse(tool.server)
    }

    /** stream_chunk_handler.dart keeps arrival order, not reasoning→text→tools. */
    @Test
    fun keepsArrivalOrderAcrossSeries() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ReasoningDelta("think"))
        handler.handle(StreamChunk.ToolCallDelta("c1", "get_time_info", "{}"))
        handler.handle(StreamChunk.TextDelta("Answer"))
        val parts = handler.parts
        assertEquals(
            listOf(ReasoningPart::class, ToolCallPart::class, TextPart::class),
            parts.map { it::class },
        )
    }

    /**
     * stream_controller.dart 792-807 — reasoning that resumes after a tool
     * call opens a second segment instead of joining the first one.
     */
    @Test
    fun splitsReasoningAroundToolCallsAndTimesEachSegment() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ReasoningDelta("first"))
        handler.handle(StreamChunk.ToolCallDelta("c1", "search_web", "{}"))
        handler.handle(StreamChunk.ReasoningDelta(" second"))
        val parts = handler.parts
        assertEquals(3, parts.size)
        assertEquals("first", (parts[0] as ReasoningPart).text)
        assertEquals(" second", (parts[2] as ReasoningPart).text)

        val segments = handler.reasoningSegments
        assertEquals(2, segments.size)
        assertTrue("first segment closed when the tool started", segments[0].finishedAt != null)
        assertTrue(segments[0].startAt <= segments[0].finishedAt!!)
        assertEquals("the segment starts after one tool", 1, segments[1].toolStartIndex)
        assertNull("still streaming", segments[1].finishedAt)
    }

    @Test
    fun finishClosesTheOpenReasoningSegment() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ReasoningDelta("think"))
        assertNull(handler.reasoningSegments[0].finishedAt)
        handler.handle(StreamChunk.Finish("stop", null))
        assertNotNull(handler.reasoningSegments[0].finishedAt)
    }

    @Test
    fun multipleToolsAreKeptSeparate() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("t1", "a", "{\"q\""))
        handler.handle(StreamChunk.ToolCallDelta("t2", "b", "{}"))
        handler.handle(StreamChunk.ToolCallDelta("t1", "", ":1}"))
        val parts = handler.parts
        assertEquals(2, parts.size)
        assertEquals("""{"q":1}""", toolAt(parts, 0).arguments)
        assertEquals("{}", toolAt(parts, 1).arguments)
    }

    /** Partial JSON must survive as a raw string (Dart `_tryDecode`). */
    @Test
    fun keepsUnparsableArgumentsVerbatim() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("c1", "bash", "echo hi"))
        assertEquals("echo hi", toolAt(handler.parts, 0).arguments)
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
        assertTrue(handler.parts.isEmpty())
        assertFalse(handler.finished)
    }

    /** stream_chunk_handler.dart ToolCallResult path — content folds in, rest stays. */
    @Test
    fun foldToolResultWritesContentIntoTheToolPart() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("c1", "get_time_info", "{}"))
        assertNull(toolAt(handler.parts, 0).content)
        handler.foldToolResult("c1", JsonPrimitive("""{"year":2026}"""))
        val tool = toolAt(handler.parts, 0)
        assertEquals("c1", tool.id)
        assertEquals("get_time_info", tool.name)
        assertEquals("{}", tool.arguments)
        assertEquals("""{"year":2026}""", tool.content)
        assertFalse(tool.server)
    }

    @Test
    fun foldToolResultIsNoOpForUnknownOrEmptyId() {
        val handler = StreamChunkHandler()
        handler.handle(StreamChunk.ToolCallDelta("c1", "get_time_info", "{}"))
        handler.foldToolResult("missing", JsonPrimitive("x"))
        handler.foldToolResult("", JsonPrimitive("x"))
        assertNull(toolAt(handler.parts, 0).content)
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

    // ------------------------------------------------------------------
    // Mid-stream segment folding (stream_controller.dart L853 / L1232)
    // ------------------------------------------------------------------

    /** stream_controller.dart 776 — a new segment opens expanded by default. */
    @Test
    fun newSegmentStartsExpandedWhenAutoCollapseIsOff() {
        val handler = StreamChunkHandler(autoCollapse = { false })
        handler.handle(StreamChunk.ReasoningDelta("think"))
        assertTrue(handler.reasoningSegments[0].expanded)
    }

    @Test
    fun newSegmentStartsCollapsedWhenAutoCollapseIsOn() {
        val handler = StreamChunkHandler(autoCollapse = { true })
        handler.handle(StreamChunk.ReasoningDelta("think"))
        assertFalse(handler.reasoningSegments[0].expanded)
    }

    /**
     * stream_controller.dart 838-858 — the moment a tool call starts, the open
     * reasoning segment is closed and folded, so the card is already collapsed
     * while the tool runs instead of at the end of the reply.
     */
    @Test
    fun toolCallStartFoldsTheOpenSegment() {
        val handler = StreamChunkHandler(autoCollapse = { true })
        handler.handle(StreamChunk.ReasoningDelta("thinking..."))
        assertNull(handler.reasoningSegments[0].finishedAt)
        handler.handle(StreamChunk.ToolCallDelta("t1", "search_web", "{\"query\":\"x\"}"))
        val segment = handler.reasoningSegments[0]
        assertNotNull("tool start closes the segment", segment.finishedAt)
        assertFalse("and folds it when auto-collapse is on", segment.expanded)
    }

    /**
     * chat_actions.dart 2388 / stream_controller.dart 1232 — the first content
     * chunk ends the reasoning phase.
     */
    @Test
    fun firstContentDeltaFoldsTheOpenSegment() {
        val handler = StreamChunkHandler(autoCollapse = { true })
        handler.handle(StreamChunk.ReasoningDelta("thinking..."))
        assertNull(handler.reasoningSegments[0].finishedAt)
        handler.handle(StreamChunk.TextDelta("The answer"))
        val segment = handler.reasoningSegments[0]
        assertNotNull("content start closes the segment", segment.finishedAt)
        assertFalse(segment.expanded)
    }

    @Test
    fun laterContentDeltasDoNotRecloseAlreadyClosedSegment() {
        var closes = 0
        val handler = StreamChunkHandler(autoCollapse = { true }, onSegmentClosed = { closes++ })
        handler.handle(StreamChunk.ReasoningDelta("thinking..."))
        handler.handle(StreamChunk.TextDelta("x"))
        assertEquals(1, closes)
        val finishedAt = handler.reasoningSegments[0].finishedAt
        handler.handle(StreamChunk.TextDelta("y"))
        handler.handle(StreamChunk.TextDelta("z"))
        assertEquals("only the first delta closes", 1, closes)
        assertEquals("timestamp is not rewritten", finishedAt, handler.reasoningSegments[0].finishedAt)
    }

    /** Auto-collapse off: the segment still ends, but keeps its expanded state. */
    @Test
    fun autoCollapseOffOnlyStampsFinishedAt() {
        val handler = StreamChunkHandler(autoCollapse = { false })
        handler.handle(StreamChunk.ReasoningDelta("thinking..."))
        assertTrue(handler.reasoningSegments[0].expanded)
        handler.handle(StreamChunk.TextDelta("answer"))
        val segment = handler.reasoningSegments[0]
        assertNotNull(segment.finishedAt)
        assertTrue("user's expanded state survives", segment.expanded)
    }

    /** The auto-collapse flag is re-read per call, like Dart's settings read. */
    @Test
    fun autoCollapseIsReadFreshOnEveryClose() {
        var autoCollapse = false
        val handler = StreamChunkHandler(autoCollapse = { autoCollapse })
        handler.handle(StreamChunk.ReasoningDelta("first"))
        assertTrue(handler.reasoningSegments[0].expanded)
        autoCollapse = true
        handler.handle(StreamChunk.TextDelta("answer"))
        assertFalse("flipped setting applies immediately", handler.reasoningSegments[0].expanded)
    }

    @Test
    fun onSegmentClosedFiresOncePerSegment() {
        var closes = 0
        val handler = StreamChunkHandler(autoCollapse = { true }, onSegmentClosed = { closes++ })
        handler.handle(StreamChunk.ReasoningDelta("a"))
        assertEquals(0, closes)
        handler.handle(StreamChunk.ToolCallDelta("t1", "search_web", "{}"))
        assertEquals(1, closes)
        handler.handle(StreamChunk.ToolCallDelta("t2", "search_web", "{}"))
        assertEquals("no new segment yet", 1, closes)
        handler.handle(StreamChunk.ReasoningDelta("b"))
        assertEquals("fresh segment, still open", 1, closes)
        handler.handle(StreamChunk.ToolCallDelta("t3", "search_web", "{}"))
        assertEquals(2, closes)
    }

    @Test
    fun finishClosesAndFoldsTheOpenSegment() {
        val handler = StreamChunkHandler(autoCollapse = { true })
        handler.handle(StreamChunk.ReasoningDelta("thinking..."))
        handler.handle(StreamChunk.Finish("stop", null))
        val segment = handler.reasoningSegments[0]
        assertNotNull(segment.finishedAt)
        assertFalse(segment.expanded)
    }
}
