package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.ReasoningSegment
import com.psyche.memo.data.model.ReasoningSegmentCodec
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import com.psyche.memo.ui.BuiltInToolCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assistant-timeline projection and visibility tests — translations of
 * timeline_projection.dart / timeline_visibility.dart. If one of these fails,
 * the ordering or hiding rules diverged from the Flutter source.
 */
class ChatTimelineTest {

    private fun tool(
        id: String = "t1",
        name: String,
        content: String? = null,
    ): ToolCallPart =
        ToolCallPart.encode(
            id = id,
            name = name,
            arguments = JsonObject(emptyMap()),
            content = content?.let { JsonPrimitive(it) },
            server = false,
        )

    @Test
    fun reasoningAndToolsGroupUntilTextBreaks() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                ReasoningPart("step a"),
                tool(name = "get_weather", content = "sunny"),
                ReasoningPart("step b"),
                TextPart("answer text"),
                ReasoningPart("step c"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(3, blocks.size)
        val first = (blocks[0] as AssistantBlock.Thinking).steps
        assertEquals(3, first.size)
        assertTrue(first[0] is TimelineStep.Reasoning)
        assertTrue(first[1] is TimelineStep.Tool)
        assertTrue(first[2] is TimelineStep.Reasoning)
        assertEquals("answer text", (blocks[1] as AssistantBlock.Text).text)
        assertEquals(1, (blocks[2] as AssistantBlock.Thinking).steps.size)
    }

    @Test
    fun imagePartBreaksAThinkingBlock() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                ReasoningPart("a"),
                ImagePart(uri = "file:///x.png"),
                ReasoningPart("b"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[0] as AssistantBlock.Thinking).steps.size)
        assertEquals(1, (blocks[1] as AssistantBlock.Thinking).steps.size)
    }

    @Test
    fun unavailableOrBlankImageDoesNotBreak() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                ReasoningPart("a"),
                ImagePart(uri = "", unavailable = true),
                ImagePart(uri = "  "),
                ReasoningPart("b"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks[0] as AssistantBlock.Thinking).steps.size)
    }

    @Test
    fun blankTextDoesNotEmitOrBreak() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                ReasoningPart("a"),
                TextPart("   "),
                ReasoningPart("b"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks[0] as AssistantBlock.Thinking).steps.size)
    }

    @Test
    fun emptyReasoningIsSkippedWithoutConsumingIndex() {
        // Only non-empty reasoning parts advance reasoningIndex; a leading empty
        // part must not shift the overlay index of the following one.
        val json = ReasoningSegmentCodec.encode(
            listOf(
                ReasoningSegment(startAt = 1000, finishedAt = 2000, expanded = true),
                ReasoningSegment(startAt = 3000, finishedAt = null, expanded = false),
            ),
        )
        val blocks = projectAssistantBlocks(
            parts = listOf(
                ReasoningPart(""),
                ReasoningPart("first"),
                ReasoningPart("second"),
            ),
            segmentsJson = json,
            isStreaming = true,
        )
        val steps = (blocks[0] as AssistantBlock.Thinking).steps
        assertEquals(2, steps.size)
        val first = steps[0] as TimelineStep.Reasoning
        assertEquals(0, first.segmentIndex)
        assertEquals(2000L, first.finishedAt)
        assertTrue(first.expanded)
        // Second segment: no finishedAt + streaming → still loading; expanded=false
        // from the stored segment.
        val second = steps[1] as TimelineStep.Reasoning
        assertEquals(1, second.segmentIndex)
        assertNull(second.finishedAt)
        assertTrue(second.loading)
        assertFalse(second.expanded)
    }

    @Test
    fun noSegmentsMeansDefaults() {
        val blocks = projectAssistantBlocks(
            parts = listOf(ReasoningPart("x")),
            segmentsJson = null,
            isStreaming = false,
        )
        val step = (blocks[0] as AssistantBlock.Thinking).steps[0] as TimelineStep.Reasoning
        assertEquals(true, step.expanded)
        assertNull(step.startAt)
        assertNull(step.finishedAt)
        assertFalse(step.loading)
    }

    @Test
    fun startedExpandedWhenAutoCollapseOff() {
        // collapsed segments made by the stream controller with auto-collapse on.
        val json = ReasoningSegmentCodec.encode(
            listOf(ReasoningSegment(startAt = 10, expanded = false)),
        )
        val blocks = projectAssistantBlocks(
            parts = listOf(ReasoningPart("x")),
            segmentsJson = json,
            isStreaming = false,
        )
        val step = (blocks[0] as AssistantBlock.Thinking).steps[0] as TimelineStep.Reasoning
        assertFalse(step.expanded)
        assertEquals(10L, step.startAt)
    }

    @Test
    fun builtinSearchToolsAreDropped() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                tool(id = "a", name = "builtin_search", content = "x"),
                tool(id = "b", name = "get_weather", content = "y"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        val steps = (blocks[0] as AssistantBlock.Thinking).steps
        assertEquals(1, steps.size)
        assertEquals("get_weather", (steps[0] as TimelineStep.Tool).part.toolName)
    }

    @Test
    fun unparseableToolPayloadIsDropped() {
        val blocks = projectAssistantBlocks(
            parts = listOf(ToolCallPart("not json")),
            segmentsJson = null,
            isStreaming = false,
        )
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun toolLoadingDerivesFromMissingContent() {
        val a = ToolCallPart.encode("a", "calc", JsonObject(emptyMap()), null, false)
        val b = ToolCallPart.encode("b", "calc", JsonObject(emptyMap()), JsonPrimitive(""), false)
        val c = ToolCallPart.encode("c", "calc", JsonObject(emptyMap()), JsonPrimitive("1"), false)
        val blocks = projectAssistantBlocks(
            parts = listOf(a, b, c),
            segmentsJson = null,
            isStreaming = false,
        )
        val steps = (blocks[0] as AssistantBlock.Thinking).steps
        val loading = steps.map { (it as TimelineStep.Tool).part.loading }
        assertEquals(listOf(true, true, false), loading)
    }

    // ---- visibility (timeline_visibility.dart) ----

    @Test
    fun toolVisibility_honoursShowToolCards() {
        assertTrue(isTimelineToolVisible("get_weather", loading = true, showToolCards = true))
        assertFalse(isTimelineToolVisible("get_weather", loading = true, showToolCards = false))
    }

    @Test
    fun toolVisibility_askUserAlwaysShown() {
        assertTrue(
            isTimelineToolVisible(
                toolName = BuiltInToolCatalog.LocalToolNames.ASK_USER,
                loading = true,
                showToolCards = false,
            ),
        )
        assertTrue(
            isTimelineToolVisible(
                toolName = BuiltInToolCatalog.LocalToolNames.ASK_USER,
                loading = false,
                showToolCards = false,
            ),
        )
    }

    @Test
    fun toolVisibility_loadingOnlyKeptForApproval() {
        // No approval service in native → pendingApproval always false, so an
        // unresolved tool is hidden when cards are off.
        assertFalse(
            isTimelineToolVisible(
                toolName = "get_weather",
                loading = true,
                showToolCards = false,
            ),
        )
    }

    @Test
    fun toolVisibility_filtersBuiltinSearchUnlessDisabled() {
        assertFalse(isTimelineToolVisible("builtin_search", loading = false, showToolCards = true))
        assertTrue(
            isTimelineToolVisible(
                "builtin_search",
                loading = false,
                showToolCards = true,
                filterBuiltinSearch = false,
            ),
        )
    }

    // ---- collapse (timeline_visibility.dart collapseTimelineSteps) ----

    @Test
    fun collapse_leavesTwoOrFewerAlone() {
        assertEquals(0, collapseTimelineSteps(listOf(1, 2), collapseThinkingSteps = true).hiddenCount)
        assertEquals(0, collapseTimelineSteps(emptyList<Int>(), collapseThinkingSteps = true).hiddenCount)
    }

    @Test
    fun collapse_keepsTailAndCountsHidden() {
        val c = collapseTimelineSteps(listOf("a", "b", "c", "d", "e"), collapseThinkingSteps = true)
        assertEquals(3, c.hiddenCount)
        assertEquals(listOf("d", "e"), c.visible)
    }

    @Test
    fun collapse_respectsFlag() {
        val c = collapseTimelineSteps(listOf("a", "b", "c", "d", "e"), collapseThinkingSteps = false)
        assertEquals(0, c.hiddenCount)
        assertEquals(listOf("a", "b", "c", "d", "e"), c.visible)
    }

    // ---- loading helper ----

    @Test
    fun reasoningLoading_onlyWhileStreamingAndUnfinished() {
        assertFalse(timelineReasoningLoading(finishedAt = 5L, isStreaming = true))
        assertTrue(timelineReasoningLoading(finishedAt = null, isStreaming = true))
        assertFalse(timelineReasoningLoading(finishedAt = null, isStreaming = false))
    }

    // ---- ChatTimelineSettings.fromPrefs (settings_provider.dart defaults) ----

    @Test
    fun settings_absentKeysUseDefaults() {
        val s = ChatTimelineSettings.fromPrefs { null }
        assertEquals(true, s.showThinkingCards)
        assertEquals(true, s.showToolCards)
        assertEquals(false, s.collapseThinkingSteps)
        assertEquals(false, s.showToolResultSummary)
        assertEquals(false, s.hideToolResultImages)
        assertEquals(true, s.enableReasoningMarkdown)
    }

    @Test
    fun settings_parsesStoredOnesAndZeroes() {
        val store = mapOf(
            "display_show_thinking_cards_v1" to "0",
            "display_show_tool_cards_v1" to "1",
            "display_collapse_thinking_steps_v1" to "1",
            "display_show_tool_result_summary_v1" to "1",
            "display_hide_tool_result_images_v1" to "1",
            "display_enable_reasoning_markdown_v1" to "0",
        )
        val s = ChatTimelineSettings.fromPrefs { store[it] }
        assertEquals(false, s.showThinkingCards)
        assertEquals(true, s.showToolCards)
        assertEquals(true, s.collapseThinkingSteps)
        assertEquals(true, s.showToolResultSummary)
        assertEquals(true, s.hideToolResultImages)
        assertEquals(false, s.enableReasoningMarkdown)
    }

    @Test
    fun settings_nonOneValueIsFalse() {
        val s = ChatTimelineSettings.fromPrefs { "true" }
        assertEquals(false, s.showThinkingCards)
    }

    // --- ReasoningSegmentCodec.toggleExpandedAt ---------------------------------

    @Test
    fun toggleFlipsAnExistingCollapsedSegment() {
        val json = ReasoningSegmentCodec.encode(
            listOf(ReasoningSegment(expanded = false)),
        )
        val out = ReasoningSegmentCodec.toggleExpandedAt(json, 0)
        val decoded = ReasoningSegmentCodec.decode(out)
        assertEquals(1, decoded.size)
        assertEquals(true, decoded[0].expanded)
    }

    @Test
    fun toggleFlipsAnExistingExpandedSegment() {
        val json = ReasoningSegmentCodec.encode(
            listOf(ReasoningSegment(expanded = true)),
        )
        val out = ReasoningSegmentCodec.toggleExpandedAt(json, 0)
        assertEquals(false, ReasoningSegmentCodec.decode(out)[0].expanded)
    }

    /**
     * Regression: an older message whose `reasoning_segments_json` is null still
     * renders expanded (projectAssistantBlocks defaults missing -> true), but the
     * toggle used to no-op because there was no stored segment. Now it synthesizes
     * the segment and collapses it.
     */
    @Test
    fun toggleOnMissingSegmentDoesNotNoop() {
        val out = ReasoningSegmentCodec.toggleExpandedAt(null, 0)
        val decoded = ReasoningSegmentCodec.decode(out)
        assertEquals(1, decoded.size)
        assertEquals(false, decoded[0].expanded)
    }

    /** Index beyond the stored list: pad with default (expanded=true) entries, then flip the target. */
    @Test
    fun toggleBeyondStoredListPadsAndFlips() {
        val json = ReasoningSegmentCodec.encode(
            listOf(ReasoningSegment(expanded = false)),
        )
        val out = ReasoningSegmentCodec.toggleExpandedAt(json, 2)
        val decoded = ReasoningSegmentCodec.decode(out)
        assertEquals(3, decoded.size)
        assertEquals(false, decoded[0].expanded) // untouched
        assertEquals(true, decoded[1].expanded) // padded default
        assertEquals(false, decoded[2].expanded) // flipped from default true
    }

    @Test
    fun toggleNegativeIndexIsSafe() {
        val json = ReasoningSegmentCodec.encode(listOf(ReasoningSegment(expanded = false)))
        assertEquals(json, ReasoningSegmentCodec.toggleExpandedAt(json, -1))
    }
}
