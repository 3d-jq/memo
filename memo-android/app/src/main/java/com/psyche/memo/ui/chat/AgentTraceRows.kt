package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.AppFontWeights
import kotlin.math.ceil
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow

/**
 * AI 输出时间线 —— **ZCode 桌面端样式**（2026-09-26 用户「完全按照 zcode 的 AI 输出
 * 样式来」）。参照实现：`D:\ZCode\packages\ui\src\components\ai-elements\reasoning.tsx`
 * 与 `ToolCallBlocks` 目录（思考块衍生自 vercel/ai-elements，Apache-2.0；本工程只借形态、
 * 自绘 Compose，颜色全部走 Memo 主题）。
 *
 * 与旧 `ChainOfThoughtCard`（已删）的根本差异：
 * - **无卡片**：思考与工具调用都是与正文同列的**紧凑文字行**——图标 + 标签 + 摘要/
 *   时长 + chevron；展开的正文用左侧导线缩进，不再有 primaryContainer 底板。
 * - **思考行**：流式＝「正在思考」（accent + 扫光，[thinkingSheen]，ZCode
 *   `animated-gradient-text` 等价）+ `·` + **滚动摘要**（思考正文最后一个非空行，
 *   两端渐隐）；完成＝折叠成「思考 · 持续了 N 秒」（`chat.reasoning.durationSeconds`
 *   文案口径）。正文**纯文本**（ZCode 刻意不重跑 markdown 解析，长思考流式追加时
 *   每 chunk 重解析是真机卡顿源），限高 240dp 内部滚动 + 吸底跟随 + 上下渐隐。
 * - **工具行**：状态词（执行中/已执行/执行失败）+ **计时**（ZCode 工具行没有计时，
 *   这是用户点名「工作中和时间、完成后的已工作加时间」的超参照加法——数据层
 *   `ToolCallPart` payload 新增 `startedAt/finishedAt`）；点开行内展开
 *   摘要/图片条/参数/结果（原「详情弹层」内容全部收进展开态，弹层删除）。
 * - 展开态：思考沿用落库的 `ReasoningSegment.expanded`（[onToggleReasoning]）；
 *   工具行收在 `ChatViewModel.expandedToolRows`（LazyColumn 会销毁滑出视口的组合，
 *   本地 remember 会自己弹回折叠——CollapsibleUserBubble 同坑）。
 */

// ---------------------------------------------------------------------------
// 纯函数（AgentTraceFormatTest 钉住）
// ---------------------------------------------------------------------------

/** ZCode `resolveReasoningStreamingSummary`：思考文本的最后一个非空行（流式摘要）。 */
fun reasoningLastLine(text: String): String? =
    text.split('\n').lastOrNull { it.isNotBlank() }?.trim()

/**
 * 时长显示的秒数：向上取整、不足 1 秒记 1 秒（ZCode `Math.ceil` 口径；显示 0 秒
 * 观感像没干活）。
 */
fun traceDurationSeconds(elapsedMs: Long): Int = ceil(elapsedMs / 1000.0).toInt().coerceAtLeast(1)

/** `_sanitize`（CMW:5036）：去 \r、去首尾空白。 */
fun sanitizeReasoning(text: String): String = text.replace("\r", "").trim()

// ---------------------------------------------------------------------------
// 块（一个 Thinking block == 连续的 reasoning/tool 步）
// ---------------------------------------------------------------------------

@Composable
fun AgentTraceBlock(
    steps: List<TimelineStep>,
    settings: ChatTimelineSettings,
    conversationId: String? = null,
    askUser: AskUserInteractionService? = null,
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)? = null,
    onToggleReasoning: (segmentIndex: Int) -> Unit,
    expandedToolRows: Set<String> = emptySet(),
    onToggleToolRow: (String) -> Unit = {},
) {
    // _visibleChatTimelineSteps 4406-4443（审批态那一支已随审批体系删除）。
    val filteredSteps = remember(steps, settings.showThinkingCards, settings.showToolCards) {
        steps.filter { step ->
            when (step) {
                is TimelineStep.Reasoning -> settings.showThinkingCards
                is TimelineStep.Tool -> isTimelineToolVisible(
                    toolName = step.part.toolName,
                    showToolCards = settings.showToolCards,
                )
            }
        }
    }
    if (filteredSteps.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        filteredSteps.forEachIndexed { index, step ->
            if (index > 0) Spacer(Modifier.height(2.dp))
            when (step) {
                is TimelineStep.Reasoning -> ReasoningTraceRow(
                    step = step,
                    onToggle = if (step.hasToggle) {
                        { onToggleReasoning(step.segmentIndex) }
                    } else {
                        null
                    },
                )
                is TimelineStep.Tool -> ToolTraceRow(
                    part = step.part,
                    expanded = step.part.id in expandedToolRows,
                    onToggleExpanded = { onToggleToolRow(step.part.id) },
                    showToolResultSummary = settings.showToolResultSummary,
                    hideToolResultImages = settings.hideToolResultImages,
                    askUser = askUser,
                    onSubmitAskUser = onRecoveredAnswer?.let { cb ->
                        { result -> cb(step.part, result) }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 思考行
// ---------------------------------------------------------------------------

@Composable
private fun ReasoningTraceRow(
    step: TimelineStep.Reasoning,
    onToggle: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()
    val display = sanitizeReasoning(step.text)
    val expanded = step.expanded
    // 计时（reasoning.tsx）：流式中按 startAt 实时；结束冻结在 finishedAt-startAt。
    val elapsedMs by rememberTraceElapsed(step.startAt, step.finishedAt, step.loading)
    // 流式摘要：收起态才显示（ZCode `isStreaming && !isOpen`）。
    val summary = if (step.loading && !expanded) reasoningLastLine(display) else null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(
                if (onToggle != null) {
                    Modifier.clickable(
                        interactionSource = remember(step.segmentIndex) { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(
            imageVector = Lucide.Brain,
            contentDescription = null,
            tint = if (step.loading) fg.accent else fg.muted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        // 标签：流式＝「正在思考」accent+扫光；完成＝「思考」。有起止时刻才带时长
        // （老 payload 无键省略）。整组靠左，不摊满（ZCode 触发行同构）。
        Text(
            text = stringResource(
                if (step.loading) UiR.string.agent_trace_thinking else UiR.string.agent_trace_thought,
            ),
            maxLines = 1,
            style = TextStyle(
                fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                fontWeight = FontWeight.Medium,
                color = if (step.loading) fg.accent else fg.muted,
            ),
            modifier = if (step.loading) {
                Modifier.thinkingSheen(fg.accent, isDark)
            } else {
                Modifier
            },
        )
        if (!step.loading && step.startAt != null && step.startAt > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "·",
                style = TextStyle(fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp, color = fg.muted),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(
                    UiR.string.agent_trace_duration_seconds,
                    traceDurationSeconds(elapsedMs),
                ),
                maxLines = 1,
                style = TextStyle(fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp, color = fg.muted),
            )
        }
        if (summary != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "·",
                style = TextStyle(fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp, color = fg.muted),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = summary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                style = TextStyle(fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp, color = fg.muted),
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
                    .fadeLeftRight(16.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onToggle != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = fg.muted,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            )
        }
    }
    // 正文：展开才挂载；纯文本 + 限高滚动 + 吸底跟随 + 上下渐隐 + 左侧导线。
    if (expanded) {
        TraceIndentedBody {
            ReasoningTraceBody(text = display, loading = step.loading)
        }
    }
}

/**
 * reasoning.tsx 计时移植：流式中每秒重算 `now - startAt`（「执行中 · Ns」在收起态
 * 也要跳）；结束冻结在 `finishedAt - startAt`。无起表时刻（老 payload）恒 0，
 * 显示端以 `startAt != null` 门控。
 */
@Composable
private fun rememberTraceElapsed(
    startAt: Long?,
    finishedAt: Long?,
    running: Boolean,
): State<Long> {
    val elapsed = remember(startAt) { mutableLongStateOf(0L) }
    LaunchedEffect(running, startAt) {
        if (startAt == null || startAt <= 0) {
            elapsed.value = 0L
            return@LaunchedEffect
        }
        if (!running) {
            elapsed.value = ((finishedAt ?: startAt) - startAt).coerceAtLeast(0L)
            return@LaunchedEffect
        }
        while (true) {
            elapsed.value = (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
            delay(1000)
        }
    }
    return elapsed
}

/**
 * 展开态思考正文（reasoning.tsx `ReasoningContent` 等价）：**纯文本**
 * whitespace-pre-wrap，限高 240dp 内部滚动；流式中吸底跟随、用户上滚即暂停
 * （`autoFollowBottom`，滚回底部自动恢复）；上下各 16dp 渐隐。
 */
@Composable
private fun ReasoningTraceBody(
    text: String,
    loading: Boolean,
) {
    val fg = chatSurfaceFg()
    val scroll = rememberScrollState()
    // 贴底判据：距底 ≤4px 或还没有可滚空间。用户拖动（isScrollInProgress）离底 →
    // 暂停跟随；程序化 scrollTo 不置 isScrollInProgress，不会自我打断。
    val atBottom by remember(scroll) {
        derivedStateOf { scroll.maxValue - scroll.value <= 4 || scroll.maxValue == 0 }
    }
    var autoFollowBottom by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }
            .collect { dragging: Boolean ->
                if (dragging) autoFollowBottom = atBottom
            }
    }
    LaunchedEffect(text.length, loading) {
        if (loading && autoFollowBottom) scroll.scrollTo(scroll.maxValue)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = TRACE_BODY_MAX_HEIGHT_DP.dp)
            .clipToBounds()
            .fadeTopBottom(16.dp, 16.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
        ) {
            SelectionContainer {
                Text(
                    text = text.ifEmpty { "…" },
                    style = TextStyle(
                        fontSize = ChatStyleSpec.TIMELINE_BODY_SP.sp,
                        lineHeight = ChatStyleSpec.TIMELINE_BODY_LINE_HEIGHT_SP.sp,
                        color = fg.muted,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工具行
// ---------------------------------------------------------------------------

@Composable
internal fun ToolTraceRow(
    part: ToolUiPart,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    showToolResultSummary: Boolean,
    hideToolResultImages: Boolean = false,
    askUser: AskUserInteractionService? = null,
    onSubmitAskUser: ((AskUserResult) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()
    val isAskUser = part.toolName == com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.ASK_USER
    val loading = part.loading
    val elapsedMs by rememberTraceElapsed(part.startedAt, part.finishedAt, loading)
    var viewerState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    // ask-user 保持旧行为：默认展开、可折叠（问询交互不可丢）。
    var askUserExpanded by rememberSaveable { mutableStateOf(true) }
    val answered = part.content?.trim()?.isNotEmpty() == true && !loading
    var wasAnswered by remember(part.id) { mutableStateOf(answered) }
    LaunchedEffect(answered) {
        if (isAskUser && !wasAnswered && answered) askUserExpanded = true
        wasAnswered = answered
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(
                interactionSource = remember(part.id) { MutableInteractionSource() },
                indication = null,
            ) {
                if (isAskUser) askUserExpanded = !askUserExpanded else onToggleExpanded()
            },
    ) {
        if (loading && !isAskUser) {
            LoadingDotsIndicator(
                color = fg.muted,
                dotDp = ChatStyleSpec.TOOL_LOADING_DOTS_DOT_DP,
                gapDp = ChatStyleSpec.TOOL_LOADING_DOTS_GAP_DP,
                heightDp = ChatStyleSpec.TOOL_LOADING_DOTS_HEIGHT_DP,
            )
        } else {
            Icon(
                imageVector = toolIconFor(part.toolName, part.arguments),
                contentDescription = null,
                tint = fg.muted,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = toolTitleFor(part.toolName, part.arguments, isResult = !loading),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                fontWeight = FontWeight.Medium,
                color = fg.strong,
            ),
            modifier = Modifier
                .weight(1f)
                .then(
                    if (loading && !isAskUser) {
                        Modifier.thinkingSheen(fg.strong, isDark)
                    } else {
                        Modifier
                    },
                ),
        )
        if (!isAskUser) {
            Spacer(Modifier.width(8.dp))
            // 状态词 + 计时（用户点名：工作中带时间、完成后带「已执行 · Ns」）。
            val statusColor = when {
                part.isError -> cs.error
                loading -> fg.accent
                else -> fg.muted
            }
            Text(
                text = stringResource(
                    when {
                        part.isError -> UiR.string.agent_trace_tool_failed
                        loading -> UiR.string.agent_trace_tool_running
                        else -> UiR.string.agent_trace_tool_done
                    },
                ),
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = statusColor),
            )
            if (part.startedAt != null && part.startedAt > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "· ${traceDurationSeconds(elapsedMs)}s",
                    maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, color = fg.muted),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            tint = fg.muted,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    rotationZ = when {
                        isAskUser -> if (askUserExpanded) 90f else 0f
                        else -> if (expanded) 90f else 0f
                    }
                },
        )
    }

    // 正文：ask-user 的问询卡（默认展开、可折叠，答完仍可回看）；普通工具展开态
    // 承载摘要/图片/详情。
    if (isAskUser) {
        if (askUserExpanded) {
            TraceIndentedBody {
                AskUserInlineBody(part = part, onSubmit = onSubmitAskUser, askUser = askUser)
            }
        }
    } else if (expanded) {
        TraceIndentedBody {
            ToolTraceBody(
                part = part,
                showToolResultSummary = showToolResultSummary,
                hideToolResultImages = hideToolResultImages,
                onOpenViewer = { paths, index -> viewerState = listOf(paths[index]) to 0 },
            )
        }
    }
    viewerState?.let { (paths, index) ->
        ImageViewerOverlay(
            images = paths,
            initialIndex = index,
            onClose = { viewerState = null },
        )
    }
}

/**
 * 展开态正文：摘要优先级链（TTS 回放/屏幕时间/天气/文本摘要）→ 工具结果图片
 * 横滚条 → 参数与结果（原「详情弹层」的两个 section 收进来）。
 */
@Composable
private fun ToolTraceBody(
    part: ToolUiPart,
    showToolResultSummary: Boolean,
    hideToolResultImages: Boolean,
    onOpenViewer: (List<String>, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fg = chatSurfaceFg()
    Column(modifier = Modifier.fillMaxWidth()) {
        val summary = toolStepSummary(
            part = part,
            fg = fg,
            errorColor = cs.error,
            isAskUser = false,
            showToolResultSummary = showToolResultSummary,
        )
        if (summary != null) summary()
        if (!hideToolResultImages && part.allImagePaths.isNotEmpty()) {
            if (summary != null) Spacer(Modifier.height(8.dp))
            ToolResultImageStrip(
                paths = part.allImagePaths,
                height = ChatStyleSpec.TOOL_IMAGE_TIMELINE_HEIGHT_DP.dp,
                maxWidth = ChatStyleSpec.TOOL_IMAGE_TIMELINE_MAX_WIDTH_DP.dp,
                onOpenViewer = onOpenViewer,
            )
        }
        Spacer(Modifier.height(6.dp))
        TraceToolDetailBody(part = part)
    }
}

/**
 * 左侧导线缩进容器（ZCode `ml-2 border-l pl-3.5` 的 Compose 等价）：7dp 空隙 +
 * 1dp 导线 + 10dp 内容缩进。导线高度随内容（IntrinsicSize.Min）。
 */
@Composable
private fun TraceIndentedBody(content: @Composable () -> Unit) {
    val fg = chatSurfaceFg()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min),
    ) {
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(fg.divider),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * 工具详情正文（原 ToolDetailSheet 的两个 section 的**行内版**）：参数（美化 JSON）
 * 与结果（结果文本美化、大文本按 40 行分块）各装进限高 240dp 的滚动容器；
 * screen_time 有 apps 时整块换专属 body。
 */
@Composable
private fun TraceToolDetailBody(part: ToolUiPart) {
    val fg = chatSurfaceFg()
    val argumentsLabel = stringResource(UiR.string.chat_message_widget_arguments)
    val resultLabel = stringResource(UiR.string.chat_message_widget_result)
    val cleanText = part.cleanText
    val screenTime = if (part.toolName == com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.SCREEN_TIME) {
        ScreenTimeResult.tryParse(cleanText)
    } else {
        null
    }
    Column(Modifier.fillMaxWidth()) {
        if (screenTime != null && screenTime.hasApps) {
            ScreenTimeToolDetailBody(result = screenTime)
        } else {
            TraceDetailSection(
                label = argumentsLabel,
                text = prettyArgsJson(part.arguments),
                color = fg.muted,
            )
            Spacer(Modifier.height(8.dp))
            TraceDetailSection(
                label = resultLabel,
                text = if (cleanText.isNotEmpty()) {
                    prettyToolJson(cleanText)
                } else {
                    stringResource(UiR.string.chat_message_widget_no_result_yet)
                },
                color = fg.muted,
            )
        }
    }
}

@Composable
private fun TraceDetailSection(label: String, text: String, color: Color) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = AppFontWeights.semibold,
                color = color,
            ),
        )
        Spacer(Modifier.height(4.dp))
        val chunks = remember(text) { if (shouldChunkText(text)) chunkText(text) else listOf(text) }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = TRACE_BODY_MAX_HEIGHT_DP.dp)
                .clipToBounds()
                .fadeTopBottom(12.dp, 12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                chunks.forEach { chunk ->
                    SelectionContainer {
                        Text(
                            text = chunk,
                            style = TextStyle(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = color,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 展开态正文的最大高度（ZCode `max-h-60` = 240px 的 dp 直取）。 */
internal const val TRACE_BODY_MAX_HEIGHT_DP = 240
