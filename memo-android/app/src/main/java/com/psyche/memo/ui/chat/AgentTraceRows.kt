package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.snapshotFlow
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * AI 输出时间线 —— **ZCode 桌面端样式**（2026-10-02 用户「完全按照 zcode 的 AI 输出
 * 样式来」+ 返修「人家原本有 已工作 4 分 19 秒 / 工作中 加时间 / 完成会折叠思考和工作」）。
 * 参照实现：`D:\ZCode\packages\ui\src\v4\ConversationTurnGroup.tsx`
 * （AssistantHistoryStatus / ConversationWorkSegmentFlow）、
 * `conversationTurnWorkSegments.ts`（workStatus/duration）、
 * `components/ai-elements/reasoning.tsx`（思考行）与 `ToolCallBlocks` 目录（工具行）。
 * 只借形态自绘 Compose，颜色全部走 Memo 主题。
 *
 * 结构（对应 ZCode 的 turn work segment）：
 * - **一个 [AssistantBlock.Thinking] = 一段工作**（连续的思考 + 工具调用步骤）。
 * - **阶段头部行**（[WorkPhaseHeader]）：运行中＝「工作中 {时长}」**强制展开**、无
 *   chevron、时长每秒跳动；完成＝自动折叠成「已工作 {时长}」一行，chevron 展开看
 *   全部步骤行；中断＝「已停止」；无任何起止时刻（老消息）＝「已处理」。头部下沿
 *   一条 hairline（ZCode `border-b pb-2`）。
 * - **时长口径**（照 `resolveConversationTurnWorkDurationMs`）：完成态 = 首步 start 到
 *   末步 end；运行态 = now − start（每秒喂 now，绝不让历史时长继续增长）。
 *   格式（`formatConversationWorkDuration`）：总秒数 max(1, round)，按 天/时/分/秒
 *   取**前两个非零单位**拼空格 ⇒「4 分 19 秒」「45 秒」「2 时 3 分」。
 * - **阶段内的步骤行**：思考行（Brain + 「思考 · 持续了 N 秒」/ 流式「正在思考」+ 滚动
 *   摘要，展开为纯文本限高滚动）与工具行（图标 + 标题 + 状态词 + 计时，点开行内详情）。
 *   各级展开态一律收 VM（LazyColumn 滑出即销毁组合，本地 remember 会弹回折叠）。
 * - 无卡片、无底板；颜色全主题派生。
 */

// ---------------------------------------------------------------------------
// 纯函数（AgentTraceFormatTest 钉住）
// ---------------------------------------------------------------------------

/** ZCode `resolveReasoningStreamingSummary`：思考文本的最后一个非空行（流式摘要）。 */
fun reasoningLastLine(text: String): String? =
    text.split('\n').lastOrNull { it.isNotBlank() }?.trim()

/** 工具/思考行的短时长秒数：向上取整、不足 1 秒记 1 秒（0 秒观感像没干活）。 */
fun traceDurationSeconds(elapsedMs: Long): Int = ceil(elapsedMs / 1000.0).toInt().coerceAtLeast(1)

/** `_sanitize`（CMW:5036）：去 \r、去首尾空白。 */
fun sanitizeReasoning(text: String): String = text.replace("\r", "").trim()

/**
 * 阶段时长的单位拆分（ZCode `formatConversationWorkDuration`）：总秒数
 * max(1, round(ms/1000))，产出 (单位下标, 数值) 列表——单位 0=天 1=时 2=分 3=秒，
 * 只收非零单位、秒恒兜底（全零也要显示「0 秒」）、**最多取前两个**。
 */
fun workDurationUnits(durationMs: Long): List<Pair<Int, Int>> {
    val totalSeconds = (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    val parts = ArrayList<Pair<Int, Int>>(4)
    if (days > 0) parts.add(0 to days)
    if (hours > 0) parts.add(1 to hours)
    if (minutes > 0) parts.add(2 to minutes)
    if (seconds > 0 || parts.isEmpty()) parts.add(3 to seconds)
    return parts.take(2)
}

/** [workDurationUnits] 的单位文案键表（0=天 1=时 2=分 3=秒）。 */
internal val WORK_DURATION_UNIT_KEYS = intArrayOf(
    UiR.string.agent_trace_duration_day,
    UiR.string.agent_trace_duration_hour,
    UiR.string.agent_trace_duration_minute,
    UiR.string.agent_trace_duration_second,
)

// ---------------------------------------------------------------------------
// 块（一段工作 == 连续的 reasoning/tool 步）
// ---------------------------------------------------------------------------

@Composable
fun AgentTraceBlock(
    steps: List<TimelineStep>,
    settings: ChatTimelineSettings,
    /** 阶段折叠态的键（msg.id + 首步标识），收在 [com.psyche.memo.ChatViewModel.expandedWorkPhases]。 */
    phaseKey: String,
    /** 生成被中断（msg.failed）→ 头部显示「已停止」。 */
    messageFailed: Boolean = false,
    onToggleReasoning: (segmentIndex: Int) -> Unit,
    expandedPhases: Set<String> = emptySet(),
    onTogglePhase: (String) -> Unit = {},
    askUser: AskUserInteractionService? = null,
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)? = null,
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

    // ---- 阶段状态（conversationTurnWorkSegments 口径）----
    // start = 首个带时刻的步骤起点；end = 全部收口时的最大终点。
    var phaseStart = Long.MAX_VALUE
    var phaseEnd = 0L
    var allEnded = true
    for (step in filteredSteps) {
        when (step) {
            is TimelineStep.Reasoning -> {
                step.startAt?.takeIf { it > 0 }?.let { if (it < phaseStart) phaseStart = it }
                val end = step.finishedAt
                if (end != null && end > 0) {
                    if (end > phaseEnd) phaseEnd = end
                } else {
                    allEnded = false
                }
            }
            is TimelineStep.Tool -> {
                step.part.startedAt?.takeIf { it > 0 }?.let { if (it < phaseStart) phaseStart = it }
                val end = step.part.finishedAt
                if (end != null && end > 0) {
                    if (end > phaseEnd) phaseEnd = end
                } else {
                    allEnded = false
                }
            }
        }
    }
    val hasStart = phaseStart != Long.MAX_VALUE
    val running = !allEnded
    // 完成时长：end-start；无时刻数据 → durationMs undefined → 「已处理」。
    val doneDurationMs = if (hasStart && allEnded) max(phaseEnd - phaseStart, 0L) else null

    // 展开态：运行中强制展开（ZCode assistantHistoryDefaultOpen）；完成后默认折叠，
    // 用户点开过的阶段记在 VM。中断（已停止）同样默认折叠、可展开回看。
    val expanded = (running && !messageFailed) || phaseKey in expandedPhases
    val elapsedMs = rememberWorkElapsed(
        startAt = if (hasStart) phaseStart else null,
        running = running,
        doneDurationMs = doneDurationMs,
    ).value

    Column(modifier = Modifier.fillMaxWidth()) {
        WorkPhaseHeader(
            running = running && !messageFailed,
            failed = messageFailed && !running,
            hasStart = hasStart,
            elapsedMs = elapsedMs,
            doneDurationMs = doneDurationMs,
            expanded = expanded,
            toggleHidden = running && !messageFailed,
            onToggle = if (running && !messageFailed) null else ({ onTogglePhase(phaseKey) }),
        )
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                filteredSteps.forEachIndexed { index, step ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
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
    }
}

/**
 * 阶段头部行（AssistantHistoryStatus 等价）：标签（工作中/已工作/已停止/已处理）+
 * chevron；下沿 hairline（border-b pb-2）。运行中 toggle 隐藏（defaultOpen）。
 */
@Composable
private fun WorkPhaseHeader(
    running: Boolean,
    failed: Boolean,
    hasStart: Boolean,
    elapsedMs: Long,
    doneDurationMs: Long?,
    expanded: Boolean,
    toggleHidden: Boolean,
    onToggle: (() -> Unit)?,
) {
    val fg = chatSurfaceFg()
    val label = when {
        failed -> stringResource(UiR.string.agent_trace_stopped)
        running -> stringResource(
            UiR.string.agent_trace_working,
            if (hasStart) workDurationLabel(elapsedMs) else "",
        )
        doneDurationMs != null -> stringResource(
            UiR.string.agent_trace_worked,
            workDurationLabel(doneDurationMs),
        )
        else -> stringResource(UiR.string.agent_trace_worked_plain)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
                .then(
                    if (onToggle != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggle,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            // 运行中头部带活动指示器（ZCode「工作中」也配 loader；用 Memo 的点点点）。
            if (running) {
                LoadingDotsIndicator(
                    color = fg.accent,
                    dotDp = ChatStyleSpec.TOOL_LOADING_DOTS_DOT_DP,
                    gapDp = ChatStyleSpec.TOOL_LOADING_DOTS_GAP_DP,
                    heightDp = ChatStyleSpec.TOOL_LOADING_DOTS_HEIGHT_DP,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (running) fg.accent else fg.muted,
                ),
                modifier = Modifier.weight(1f),
            )
            if (!toggleHidden) {
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(fg.divider.copy(alpha = 0.5f)),
        )
    }
}

/** 单位拼接：「4 分 19 秒」（ZCode parts.join(" ")）。 */
@Composable
private fun workDurationLabel(durationMs: Long): String {
    // joinToString 不是 inline，lambda 里不能调 @Composable 的 stringResource——用循环拼。
    val sb = StringBuilder()
    var index = 0
    for ((unit, value) in workDurationUnits(durationMs)) {
        if (index > 0) sb.append(' ')
        sb.append(stringResource(WORK_DURATION_UNIT_KEYS[unit], value))
        index++
    }
    return sb.toString()
}

/**
 * 阶段计时：运行中每秒重算 now − start（UI 每秒喂 now 的等价物）；完成态**定格**在
 * doneDurationMs——绝不吃当前时钟（否则历史「已工作」会随时间增长，
 * conversationTurnWorkSegments.ts:57-61 的注释原话）。
 */
@Composable
private fun rememberWorkElapsed(
    startAt: Long?,
    running: Boolean,
    doneDurationMs: Long?,
): State<Long> {
    val elapsed = remember(startAt) { mutableLongStateOf(doneDurationMs ?: 0L) }
    LaunchedEffect(running, startAt) {
        if (startAt == null || startAt <= 0) {
            elapsed.value = doneDurationMs ?: 0L
            return@LaunchedEffect
        }
        if (!running) {
            elapsed.value = doneDurationMs ?: 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsed.value = (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
            delay(1000)
        }
    }
    return elapsed
}

// ---------------------------------------------------------------------------
// 阶段内的思考行
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
    val elapsedMs by rememberRowElapsed(step.startAt, step.finishedAt, step.loading)
    // 流式摘要：收起态才显示（ZCode `isStreaming && !isOpen`）。
    val summary = if (step.loading && !expanded) reasoningLastLine(display) else null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
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
    if (expanded) {
        TraceIndentedBody {
            ReasoningTraceBody(text = display, loading = step.loading)
        }
    }
}

/**
 * 行级计时：流式中每秒重算 `now - startAt`；结束冻结在 `finishedAt - startAt`。
 * 无起表时刻（老 payload）恒 0，显示端门控。
 */
@Composable
private fun rememberRowElapsed(
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
// 阶段内的工具行
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
    val elapsedMs by rememberRowElapsed(part.startedAt, part.finishedAt, loading)
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
            .padding(vertical = 4.dp)
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

/**
 * 生成刚起步、还没有任何思考/工具步骤时的「工作中」占位行（点点点 + 标签）——
 * ZCode 会先亮出工作中状态，我们在此刻只有尾部呼吸星，观感缺一块。首步一到
 * 就被真正的阶段头部接管。
 */
@Composable
fun WorkPhaseRunningPlaceholder(modifier: Modifier = Modifier) {
    val fg = chatSurfaceFg()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        LoadingDotsIndicator(
            color = fg.accent,
            dotDp = ChatStyleSpec.TOOL_LOADING_DOTS_DOT_DP,
            gapDp = ChatStyleSpec.TOOL_LOADING_DOTS_GAP_DP,
            heightDp = ChatStyleSpec.TOOL_LOADING_DOTS_HEIGHT_DP,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(UiR.string.agent_trace_working_plain),
            maxLines = 1,
            style = TextStyle(
                fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                fontWeight = FontWeight.Medium,
                color = fg.accent,
            ),
        )
    }
}

/** 展开态正文的最大高度（ZCode `max-h-60` = 240px 的 dp 直取）。 */
internal const val TRACE_BODY_MAX_HEIGHT_DP = 240
