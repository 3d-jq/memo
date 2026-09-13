package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.ReasoningSegmentCodec
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import com.psyche.memo.ui.BuiltInToolCatalog

/**
 * Assistant timeline projection — 1:1 port of
 * `timeline_projection.dart::_projectFromParts` (reasoning/tool steps grouped
 * into thinking blocks) plus `timeline_visibility.dart::isTimelineToolVisible`
 * and `collapseTimelineSteps`.
 *
 * The Dart original carries two projection modes: structured parts, or the
 * legacy `contentSplits` interleave rebuilt from `reasoningSegments` +
 * `liveTools`. Memo always stores structured parts in arrival order —
 * [com.psyche.memo.llm.stream.StreamChunkHandler] keeps them there — so only
 * `_projectFromParts` applies; the split-based modes have no data to run on.
 */

/** chat_message_widget.dart _TimelineStepData。 */
sealed class TimelineStep {

    /**
     * One reasoning segment. [segmentIndex] is the `reasoningOverlayIndex`
     * (chat_message_widget.dart:2639) the toggle callback is bound to, and
     * [hasToggle] — CMW:5213 — only holds when the step maps onto a stored
     * segment, i.e. `segmentIndex >= 0`.
     */
    data class Reasoning(
        val text: String,
        val expanded: Boolean,
        val loading: Boolean,
        val startAt: Long?,
        val finishedAt: Long?,
        val segmentIndex: Int,
    ) : TimelineStep() {
        val hasToggle: Boolean get() = segmentIndex >= 0
    }

    data class Tool(val part: ToolUiPart) : TimelineStep()
}

/** timeline_visibility.dart kBuiltinSearchToolName。 */
const val BUILTIN_SEARCH_TOOL_NAME = "builtin_search"

/**
 * timeline_projection.dart `timelineReasoningLoading` 176-185：段落已闭合就不
 * 再算加载；否则跟随消息的流式状态。
 */
fun timelineReasoningLoading(finishedAt: Long?, isStreaming: Boolean): Boolean =
    finishedAt == null && isStreaming

/**
 * timeline_visibility.dart `isTimelineToolVisible` 263-279：ask-user 常驻
 * （否则生成会卡在等待回答），未执行完的工具卡只在等待审批时保留。
 *
 * 审批服务属工具执行器批次，native 侧 [pendingApproval] 目前恒为 false。
 */
fun isTimelineToolVisible(
    toolName: String,
    loading: Boolean,
    showToolCards: Boolean,
    pendingApproval: Boolean = false,
    filterBuiltinSearch: Boolean = true,
): Boolean {
    if (filterBuiltinSearch && toolName == BUILTIN_SEARCH_TOOL_NAME) return false
    if (showToolCards) return true
    if (toolName == BuiltInToolCatalog.LocalToolNames.ASK_USER) return true
    return loading && pendingApproval
}

/** timeline_visibility.dart `collapseTimelineSteps` 281-309。 */
fun <T> collapseTimelineSteps(
    steps: List<T>,
    collapseThinkingSteps: Boolean,
): TimelineCollapse<T> {
    if (!collapseThinkingSteps || steps.size <= 2) return TimelineCollapse(steps, 0)
    val hiddenCount = steps.size - 2
    return TimelineCollapse(steps.subList(hiddenCount, steps.size), hiddenCount)
}

/** [visible] 是渲染出来的尾部步骤，[hiddenCount] 是被折叠掉的头部步骤数。 */
data class TimelineCollapse<T>(val visible: List<T>, val hiddenCount: Int)

/**
 * 思考卡片相关的显示开关（settings_provider.dart 默认值）。
 *
 * 由 [fromPrefs] 从 `PreferenceRepository.readLocal` 读出，键名与 Dart 侧
 * `display_*_v1` 一致。
 */
data class ChatTimelineSettings(
    val showThinkingCards: Boolean = true,
    val showToolCards: Boolean = true,
    val collapseThinkingSteps: Boolean = false,
    val showToolResultSummary: Boolean = false,
    val hideToolResultImages: Boolean = false,
    val enableReasoningMarkdown: Boolean = true,
    // Markdown 开关（settings_provider.dart:272-277，三个默认都是 true）：
    // 关掉时正文退化成同字号/行高的纯文本（chat_message_widget.dart
    // L2049-2066 用户侧 / L2427-2441 助手侧）。
    val enableUserMarkdown: Boolean = true,
    val enableAssistantMarkdown: Boolean = true,
    /** 代码块：`display_auto_collapse_code_block_v1` + 行数 + 移动端换行。 */
    val autoCollapseCodeBlock: Boolean = false,
    val autoCollapseCodeBlockLines: Int = 2,
    val mobileCodeBlockWrap: Boolean = false,
    // 气泡样式（message_style_settings_page）：样式键
    // `display_chat_message_background_style_v1` + 助手/用户两份覆盖 JSON
    // （settings_provider.dart:312-315），以及「贴合内容」「按段拆分」两个开关。
    val bubbleStyles: ChatBubbleStyles = ChatBubbleStyles(),
    val assistantBubbleFitContent: Boolean = false,
    val assistantBubbleSplitParagraphs: Boolean = false,
    /**
     * `display_chat_font_scale_v1`（settings_provider.dart:5041-5049，默认 1.0）。
     * 原版在 message_list_view.dart:2018-2024 把整条消息包进
     * `MediaQuery(textScaler: systemScale × chatFontScale)`，移植版等价地用
     * 缩放后的 `LocalDensity.fontScale` 包住消息行。
     */
    val chatFontScale: Float = 1f,
    // 用户（user）消息侧 —— settings_provider.dart:1068-1085（三个默认都是 true），
    // 由 chat_message_widget.dart:1719-1735 的用户头消费。
    val showUserAvatar: Boolean = true,
    val showUserName: Boolean = true,
    val showUserTimestamp: Boolean = true,
) {
    companion object {
        fun fromPrefs(read: (key: String) -> String?): ChatTimelineSettings {
            fun bool(key: String, default: Boolean): Boolean =
                read(key)?.let { it == "1" } ?: default
            // 数值键存的是 JSON 数字（settings 页写的是纯数字文本）。
            fun int(key: String, default: Int): Int =
                read(key)?.trim()?.trim('"')?.toIntOrNull() ?: default
            return ChatTimelineSettings(
                showThinkingCards = bool("display_show_thinking_cards_v1", true),
                showToolCards = bool("display_show_tool_cards_v1", true),
                collapseThinkingSteps = bool("display_collapse_thinking_steps_v1", false),
                showToolResultSummary = bool("display_show_tool_result_summary_v1", false),
                hideToolResultImages = bool("display_hide_tool_result_images_v1", false),
                enableReasoningMarkdown = bool("display_enable_reasoning_markdown_v1", true),
                enableUserMarkdown = bool("display_enable_user_markdown_v1", true),
                enableAssistantMarkdown = bool("display_enable_assistant_markdown_v1", true),
                autoCollapseCodeBlock = bool("display_auto_collapse_code_block_v1", false),
                autoCollapseCodeBlockLines = int("display_auto_collapse_code_block_lines_v1", 2),
                mobileCodeBlockWrap = bool("display_mobile_code_block_wrap_v1", false),
                bubbleStyles = ChatBubbleStyles(
                    style = ChatBubbleStyle.fromWire(
                        read("display_chat_message_background_style_v1")
                            ?.trim()?.trim('"')?.takeIf { it.isNotEmpty() },
                    ),
                    assistantOverrides = BubbleOverrides.fromJson(read("chat_bubble_style_overrides_v1")),
                    userOverrides = BubbleOverrides.fromJson(read("chat_bubble_style_overrides_user_v1")),
                ),
                assistantBubbleFitContent = bool("display_assistant_bubble_fit_content_v1", false),
                assistantBubbleSplitParagraphs = bool("display_assistant_bubble_split_paragraphs_v1", false),
                chatFontScale = read("display_chat_font_scale_v1")
                    ?.trim()?.trim('"')?.toFloatOrNull() ?: 1f,
                showUserAvatar = bool("display_show_user_avatar_v1", true),
                showUserName = bool("display_show_user_name_v1", true),
                showUserTimestamp = bool("display_show_user_timestamp_v1", true),
            )
        }
    }
}

/**
 * 助手气泡里的有序块（CMW:2965-3009 的 visibleBlocks 子集）：文本块与思考块按
 * part 到达顺序交替出现，渲染时相邻块之间留 8pt（addVisible）。
 */
sealed class AssistantBlock {

    /** 一个 TextPart 的正文（助手 15.7sp markdown）。 */
    data class Text(val text: String) : AssistantBlock()

    /** 一张 `_ChainOfThoughtCard` 的全部时间线步骤。 */
    data class Thinking(val steps: List<TimelineStep>) : AssistantBlock()
}

/**
 * 把消息 parts 折叠成有序块：连续的 reasoning / tool parts 归入当前思考块，
 * 非空 text / 可见 image part 打断它（timeline_projection.dart 566-651）。
 *
 * 图片块不在这里输出——HomeScreen 已用 [MessageImageAttachments] 把整条消息的
 * 图片 part 作为缩略图组渲染在气泡顶部；图片 part 仍然打断思考块。
 */
fun projectAssistantBlocks(
    parts: List<MessagePart>,
    segmentsJson: String?,
    isStreaming: Boolean,
): List<AssistantBlock> {
    val segments = ReasoningSegmentCodec.decode(segmentsJson)
    val blocks = ArrayList<AssistantBlock>()
    var pending: ArrayList<TimelineStep>? = null
    var reasoningIndex = 0

    fun flush() {
        pending?.let { if (it.isNotEmpty()) blocks.add(AssistantBlock.Thinking(it)) }
        pending = null
    }

    for (part in parts) {
        when (part) {
            is TextPart -> if (part.text.isNotBlank()) {
                flush()
                blocks.add(AssistantBlock.Text(part.text))
            }
            is ImagePart -> if (part.unavailable != true && part.uri.isNotBlank()) flush()
            is ReasoningPart -> {
                if (part.text.isEmpty()) continue
                val segment = segments.getOrNull(reasoningIndex)
                val overlayIndex = reasoningIndex
                reasoningIndex++
                pending = (pending ?: ArrayList()).also {
                    it.add(
                        TimelineStep.Reasoning(
                            text = part.text,
                            expanded = segment?.expanded ?: true,
                            loading = timelineReasoningLoading(
                                finishedAt = segment?.finishedAt,
                                isStreaming = isStreaming,
                            ),
                            startAt = segment?.startAt?.takeIf { at -> at > 0 },
                            finishedAt = segment?.finishedAt,
                            segmentIndex = overlayIndex,
                        ),
                    )
                }
            }
            is ToolCallPart -> {
                val tool = ToolUiPart.fromPayload(part.payloadJson) ?: continue
                if (tool.toolName == BUILTIN_SEARCH_TOOL_NAME) continue
                pending = (pending ?: ArrayList()).also { it.add(TimelineStep.Tool(tool)) }
            }
            else -> Unit
        }
    }
    flush()
    return blocks
}
