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
    fun imagePartBreaksAThinkingBlockAndBecomesItsOwnBlock() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                ReasoningPart("a"),
                ImagePart(uri = "file:///x.png"),
                ReasoningPart("b"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as AssistantBlock.Thinking).steps.size)
        // 图片**自己成块**（2026-09-17 起）：按 part 顺序夹在两块思考之间，
        // 而不是被整条消息的「图组」提到气泡上方去。
        assertEquals(
            listOf("file:///x.png"),
            (blocks[1] as AssistantBlock.Media).parts.map { (it as ImagePart).uri },
        )
        assertEquals(1, (blocks[2] as AssistantBlock.Thinking).steps.size)
    }

    /**
     * 生成类工具的产物必须**紧跟工具卡**（用户 2026-09-17「在一轮了 但是位置不对呀
     * 怎么在上面了」），连续多张合并成一块，且视频（FilePart）走同一条路。
     */
    @Test
    fun mediaFollowsItsToolAndConsecutiveMediaMerges() {
        val blocks = projectAssistantBlocks(
            parts = listOf(
                TextPart("画好了："),
                tool(id = "t1", name = "generate_image"),
                ImagePart(uri = "file:///a.png"),
                ImagePart(uri = "file:///b.png"),
                TextPart("还要一张吗？"),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(4, blocks.size)
        assertEquals("画好了：", (blocks[0] as AssistantBlock.Text).text)
        assertEquals(1, (blocks[1] as AssistantBlock.Thinking).steps.size)
        val media = blocks[2] as AssistantBlock.Media
        assertEquals(2, media.parts.size)
        assertEquals("还要一张吗？", (blocks[3] as AssistantBlock.Text).text)

        // 视频：文件 part 同样成块（工具卡之后）。
        val video = projectAssistantBlocks(
            parts = listOf(
                tool(id = "t2", name = "generate_video"),
                com.psyche.memo.data.model.FilePart(
                    uri = "/v/out.mp4",
                    name = "out.mp4",
                    mime = "video/mp4",
                ),
            ),
            segmentsJson = null,
            isStreaming = false,
        )
        assertEquals(2, video.size)
        assertEquals(
            listOf("/v/out.mp4"),
            (video[1] as AssistantBlock.Media).parts
                .filterIsInstance<com.psyche.memo.data.model.FilePart>()
                .map { it.uri },
        )
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
        assertTrue(isTimelineToolVisible("get_weather", showToolCards = true))
        assertFalse(isTimelineToolVisible("get_weather", showToolCards = false))
    }

    @Test
    fun toolVisibility_askUserAlwaysShown() {
        assertTrue(
            isTimelineToolVisible(
                toolName = BuiltInToolCatalog.LocalToolNames.ASK_USER,
                showToolCards = false,
            ),
        )
        assertTrue(
            isTimelineToolVisible(
                toolName = BuiltInToolCatalog.LocalToolNames.ASK_USER,
                showToolCards = false,
            ),
        )
    }

    @Test
    fun toolVisibility_noApprovalExceptionLeft() {
        // 上游那条「loading 且在等审批 ⇒ 隐藏工具卡时也保留」的例外随审批体系一起删除
        //（用户 2026-09-25），所以关掉工具卡后未执行完的普通工具也不再冒出来。
        assertFalse(
            isTimelineToolVisible(toolName = "get_weather", showToolCards = false),
        )
    }

    @Test
    fun toolVisibility_filtersBuiltinSearchUnlessDisabled() {
        assertFalse(isTimelineToolVisible("builtin_search", showToolCards = true))
        assertTrue(
            isTimelineToolVisible(
                "builtin_search",
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
    fun settings_bareBooleanValueIsTrue() {
        // 布尔偏好统一走 DisplayPrefs.decodeBool："1"/"0" 与裸 true/false 都认。
        // 旧实现只认 "1"，早期写入的裸 true 会读成「关」（侧边栏日期分组不显示的根因）。
        val s = ChatTimelineSettings.fromPrefs { "true" }
        assertEquals(true, s.showThinkingCards)
        val zero = ChatTimelineSettings.fromPrefs { "0" }
        assertEquals(false, zero.showThinkingCards)
    }

    // --- 助手消息头 / 操作行的键（settings_provider.dart:1070-1119） ---
    //
    // 用户 2026-09-15「聊天项显示那 6 个开关（做了吗）」—— 这 6 个键此前只看不
    // 消费。默认值逐条核对过 settings_provider.dart：只有「聊天标题栏显示助手头像」
    // （页面级，见 HomeScreen）与「模型名称后显示供应商」是 false。

    @Test
    fun settings_assistantRowsUseSourceDefaults() {
        val s = ChatTimelineSettings.fromPrefs { null }
        assertEquals(true, s.showUserMessageActions) // :1085-1086
        assertEquals(true, s.showModelName) // :1081-1082
        assertEquals(true, s.showModelTimestamp) // :1083-1084
        assertEquals(true, s.showTokenStats) // :1072
        assertEquals(false, s.showProviderInChatMessage) // :1118-1119
    }

    @Test
    fun settings_assistantRowsReadStoredValues() {
        val store = mapOf(
            "display_show_user_message_actions_v1" to "0",
            "display_show_model_name_v1" to "0",
            "display_show_model_timestamp_v1" to "0",
            "display_show_token_stats_v1" to "0",
            "display_show_provider_in_chat_message_v1" to "1",
        )
        val s = ChatTimelineSettings.fromPrefs { store[it] }
        assertEquals(false, s.showUserMessageActions)
        assertEquals(false, s.showModelName)
        assertEquals(false, s.showModelTimestamp)
        assertEquals(false, s.showTokenStats)
        assertEquals(true, s.showProviderInChatMessage)
    }

    // --- 消息头模型名（_resolveModelDisplayName，CMW:1355-1407） ---

    @Test
    fun modelLabel_fallsBackToTheRawModelId() {
        assertEquals(
            "glm-5.3-flash",
            messageModelDisplayName("glm-5.3-flash", null, showProvider = false),
        )
    }

    @Test
    fun modelLabel_prefersTheOverrideNameThenTheApiId() {
        val source = MessageModelLabelSource(
            providerName = "Zhipu AI",
            overrides = mapOf("glm-5.3-flash" to "GLM 5.3", "glm-4.6" to "glm-4.6-air"),
        )
        assertEquals("GLM 5.3", messageModelDisplayName("glm-5.3-flash", source, showProvider = false))
        assertEquals("glm-4.6-air", messageModelDisplayName("glm-4.6", source, showProvider = false))
        // 没被覆盖过的模型仍然用 id 本身。
        assertEquals("glm-5", messageModelDisplayName("glm-5", source, showProvider = false))
    }

    @Test
    fun modelLabel_appendsTheProviderOnlyWhenTheSwitchIsOn() {
        val source = MessageModelLabelSource(providerName = "Zhipu AI")
        assertEquals(
            "glm-5.3-flash",
            messageModelDisplayName("glm-5.3-flash", source, showProvider = false),
        )
        assertEquals(
            "glm-5.3-flash | Zhipu AI",
            messageModelDisplayName("glm-5.3-flash", source, showProvider = true),
        )
        // 供应商名取不到时不拼一个空后缀（CMW:1401-1405 同样要求非空）。
        assertEquals(
            "glm-5.3-flash",
            messageModelDisplayName("glm-5.3-flash", MessageModelLabelSource(null), showProvider = true),
        )
        assertEquals(
            "glm-5.3-flash",
            messageModelDisplayName("glm-5.3-flash", MessageModelLabelSource("  "), showProvider = true),
        )
    }

    @Test
    fun modelLabel_isEmptyWhenTheMessageHasNoModel() {
        // 预设消息/老消息没有 modelId —— 调用方回落到助手名，不能显示 " | 供应商"。
        assertEquals("", messageModelDisplayName("", MessageModelLabelSource("P"), showProvider = true))
        assertEquals("", messageModelDisplayName("   ", null, showProvider = true))
    }

    // --- 气泡样式 / 贴合内容 / 按段拆分（message_style_settings_page） ---

    @Test
    fun settings_bubbleStyleDefaultsToDefaultWithNoOverrides() {
        val s = ChatTimelineSettings.fromPrefs { null }
        assertEquals(ChatBubbleStyle.DEFAULT, s.bubbleStyles.style)
        assertEquals(BubbleOverrides.NONE, s.bubbleStyles.assistantOverrides)
        assertEquals(BubbleOverrides.NONE, s.bubbleStyles.userOverrides)
        assertEquals(false, s.assistantBubbleFitContent)
        assertEquals(false, s.assistantBubbleSplitParagraphs)
    }

    @Test
    fun settings_readsBubbleStyleOverridesAndToggles() {
        val store = mapOf(
            "display_chat_message_background_style_v1" to "frosted",
            "chat_bubble_style_overrides_v1" to """{"cornerRadius":20.0,"solidOpacity":0.5}""",
            "chat_bubble_style_overrides_user_v1" to """{"textArgbDark":255}""",
            "display_assistant_bubble_fit_content_v1" to "1",
            "display_assistant_bubble_split_paragraphs_v1" to "1",
        )
        val s = ChatTimelineSettings.fromPrefs { store[it] }
        assertEquals(ChatBubbleStyle.FROSTED, s.bubbleStyles.style)
        assertEquals(20.0, s.bubbleStyles.assistantOverrides.cornerRadius!!, 0.0)
        assertEquals(0.5, s.bubbleStyles.assistantOverrides.solidOpacity!!, 0.0)
        assertEquals(255, s.bubbleStyles.userOverrides.textArgbDark)
        assertEquals(true, s.assistantBubbleFitContent)
        assertEquals(true, s.assistantBubbleSplitParagraphs)
    }

    @Test
    fun settings_bubbleStyleToleratesAJsonQuotedName() {
        // 上游（Flutter 备份）写入的是 jsonEncode 过的字符串。
        val s = ChatTimelineSettings.fromPrefs { "\"solid\"" }
        assertEquals(ChatBubbleStyle.SOLID, s.bubbleStyles.style)
    }

    // --- 聊天字号缩放（display_settings_page 的「聊天字体大小」滑杆） ---

    @Test
    fun settings_chatFontScaleDefaultsToOne() {
        assertEquals(1f, ChatTimelineSettings.fromPrefs { null }.chatFontScale)
        // 写坏的值回退 1.0，不当成 0（否则聊天正文会消失）。
        assertEquals(1f, ChatTimelineSettings.fromPrefs { "不是数字" }.chatFontScale)
    }

    @Test
    fun settings_chatFontScaleReadsStoredScale() {
        val s = ChatTimelineSettings.fromPrefs { "1.25" }
        assertEquals(1.25f, s.chatFontScale)
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
