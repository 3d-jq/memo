package com.psyche.memo.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.LocalHapticsSettings
import com.psyche.memo.ui.markdown.MarkdownText
import com.psyche.memo.ui.theme.AppFontWeights
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Chain-of-thought card — 1:1 port of chat_message_widget.dart
 * `_ChainOfThoughtCard` (4525-4803) + `_TimelineStepShell` /
 * `_TimelineIconColumn` / `_TimelineLinePainter` (4805-4998) +
 * `_ChainOfThoughtReasoningStep` (5000-5240).
 *
 * One card == one thinking block (`timeline_projection.dart`
 * `_projectFromParts`); the blocks come from
 * [projectAssistantBlocks]. Steps alternate between reasoning text
 * ([ChainOfThoughtReasoningStep]) and tool calls
 * ([ChainOfThoughtToolStep], ToolCallCard.kt).
 */

// ---------------------------------------------------------------------------
// Card (CMW:4525-4803)
// ---------------------------------------------------------------------------

@Composable
fun ChainOfThoughtCard(
    steps: List<TimelineStep>,
    settings: ChatTimelineSettings,
    conversationId: String? = null,
    askUser: AskUserInteractionService? = null,
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)? = null,
    onToggleReasoning: (segmentIndex: Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()

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

    // CMW:4646-4663 —— 纯思考且全部已闭合时卡片按内容自适应宽度。
    val enableAdaptiveWidth = filteredSteps.all { it is TimelineStep.Reasoning } &&
        filteredSteps.none { (it as? TimelineStep.Reasoning)?.loading == true }
    var showAllSteps by rememberSaveable { mutableStateOf(false) }
    val canCollapse = settings.collapseThinkingSteps && filteredSteps.size > 2
    val hiddenCount = if (canCollapse && !showAllSteps) filteredSteps.size - 2 else 0
    val visibleSteps = if (hiddenCount > 0) filteredSteps.subList(hiddenCount, filteredSteps.size)
    else filteredSteps
    val fillWidth = !enableAdaptiveWidth || visibleSteps.any {
        it is TimelineStep.Reasoning && (it.expanded || it.loading)
    }
    val cardBg = cs.primaryContainer.copy(
        alpha = if (isDark) ChatStyleSpec.TIMELINE_CARD_ALPHA_DARK else ChatStyleSpec.TIMELINE_CARD_ALPHA_LIGHT,
    )

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        // _buildSharedChatSurface(borderRadius 16, padding h8/v4) → AnimatedSize(300ms)
        Column(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .background(cardBg, RoundedCornerShape(ChatStyleSpec.TIMELINE_CARD_CORNER_DP.dp))
                .padding(
                    horizontal = ChatStyleSpec.TIMELINE_CARD_PADDING_H_DP.dp,
                    vertical = ChatStyleSpec.TIMELINE_CARD_PADDING_V_DP.dp,
                )
                .animateContentSizeCard(),
            horizontalAlignment = Alignment.Start,
        ) {
            if (canCollapse) {
                // CMW:4687-4729 展开/收起行。计数用未过滤的 steps.size —— 源码如此。
                CardPress(
                    onTap = { showAllSteps = !showAllSteps },
                    isDark = isDark,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier.width(ChatStyleSpec.TIMELINE_ICON_COLUMN_WIDTH_DP.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (showAllSteps) Lucide.ChevronUp else Lucide.ChevronDown,
                                contentDescription = null,
                                tint = fg.strong,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(ChatStyleSpec.TIMELINE_GAP_DP.dp))
                        Text(
                            text = if (showAllSteps) {
                                stringResource(UiR.string.chain_of_thought_collapse)
                            } else {
                                stringResource(
                                    UiR.string.chain_of_thought_expand_steps,
                                    (steps.size - visibleSteps.size).toString(),
                                )
                            },
                            style = TextStyle(
                                fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                                fontWeight = AppFontWeights.semibold,
                                color = fg.strong,
                            ),
                        )
                    }
                }
            }
            visibleSteps.forEachIndexed { i, step ->
                val isFirst = i == 0
                val isLast = i == visibleSteps.lastIndex
                when (step) {
                    is TimelineStep.Reasoning -> ChainOfThoughtReasoningStep(
                        step = step,
                        isFirst = isFirst,
                        isLast = isLast,
                        enableReasoningMarkdown = settings.enableReasoningMarkdown,
                        onToggleReasoning = onToggleReasoning,
                        math = com.psyche.memo.ui.markdown.MathConfig(
                            enabled = settings.mathRendering,
                            dollarLatex = settings.dollarLatex,
                        ),
                    )
                    is TimelineStep.Tool -> ChainOfThoughtToolStep(
                        part = step.part,
                        isFirst = isFirst,
                        isLast = isLast,
                        showToolResultSummary = settings.showToolResultSummary,
                        hideToolResultImages = settings.hideToolResultImages,
                        conversationId = conversationId,
                        askUser = askUser,
                        onSubmitAskUser = onRecoveredAnswer?.let { cb -> { result -> cb(step.part, result) } },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step shell (CMW:4805-4927) + icon rail (4929-4998)
// ---------------------------------------------------------------------------

/**
 * `_TimelineStepShell`：头行（24dp 轨道 + 间距 8 + 标题 + extra + 指示器）叠加
 * 一条穿过图标的连接线；正文区在 !isLast 时继续画同一条线，正文宽度按
 * AnimatedSize(300ms, Cubic(0.2,0.8,0.2,1)) 展开，缩进 32 / 上 4 / 下 8。
 */
@Composable
fun TimelineStepShell(
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    fg: ChatSurfaceFg,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    indicator: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
    contentVisible: Boolean = false,
    expectContent: Boolean = false,
) {
    val hasBody = content != null || expectContent
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Box(Modifier.fillMaxWidth()) {
            CardPress(
                onTap = onTap,
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        vertical = ChatStyleSpec.TIMELINE_STEP_PADDING_V_DP.dp,
                    ),
                ) {
                    Spacer(
                        Modifier
                            .width(ChatStyleSpec.TIMELINE_ICON_COLUMN_WIDTH_DP.dp)
                            .height(ChatStyleSpec.TIMELINE_ICON_DP.dp),
                    )
                    Spacer(Modifier.width(ChatStyleSpec.TIMELINE_GAP_DP.dp))
                    Box(Modifier.weight(1f)) { label() }
                    if (extra != null) {
                        Spacer(Modifier.width(8.dp))
                        extra()
                    }
                    if (indicator != null) {
                        Spacer(Modifier.width(6.dp))
                        indicator()
                    }
                }
            }
            // Positioned(left 0, top 0, bottom 0, width 24) 的图标列：画在线之上。
            TimelineIconColumn(
                icon = icon,
                isFirst = isFirst,
                isLast = isLast,
                lineColor = fg.divider,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (hasBody) {
            Box(Modifier.fillMaxWidth()) {
                if (!isLast) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxHeight()
                            .offset(x = ChatStyleSpec.TIMELINE_LINE_X_DP.dp)
                            .width(ChatStyleSpec.TIMELINE_LINE_WIDTH_DP.dp)
                            .background(fg.divider),
                    )
                }
                Column(
                    modifier = Modifier,
                    horizontalAlignment = Alignment.Start,
                ) {
                    if (contentVisible && content != null) {
                        Box(
                            Modifier.padding(
                                start = (ChatStyleSpec.TIMELINE_ICON_COLUMN_WIDTH_DP +
                                    ChatStyleSpec.TIMELINE_GAP_DP).dp,
                                top = 4.dp,
                                bottom = 8.dp,
                            ),
                        ) { content() }
                    }
                }
            }
        }
    }
}

/**
 * `_TimelineIconColumn` + `_TimelineLinePainter` 4929-4998：轨道列宽 24，图标
 * 18 居中，连接线宽 1 走 x=12（= 24/2），在图标上下各留 3px 缺口；首步不画
 * 上段、末步不画下段。
 */
@Composable
private fun TimelineIconColumn(
    icon: @Composable () -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val iconPx = with(density) { ChatStyleSpec.TIMELINE_ICON_DP.dp.toPx() }
    val gapPx = with(density) { ChatStyleSpec.TIMELINE_LINE_GAP_DP.dp.toPx() }
    val strokePx = with(density) { ChatStyleSpec.TIMELINE_LINE_WIDTH_DP.dp.toPx() }
    // modifier = matchParentSize() 让这一层拿到与头行同高的定界，里面再放一条
    // 24dp 宽的竖向轨道（fillMaxHeight 此时才有界可用），水平靠左。
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(ChatStyleSpec.TIMELINE_ICON_COLUMN_WIDTH_DP.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2f
                    val iconTop = (size.height - iconPx) / 2f
                    val iconBottom = iconTop + iconPx
                    if (!isFirst) {
                        drawLine(
                            color = lineColor,
                            start = Offset(x, 0f),
                            end = Offset(x, max(0f, iconTop - gapPx)),
                            strokeWidth = strokePx,
                        )
                    }
                    if (!isLast) {
                        drawLine(
                            color = lineColor,
                            start = Offset(x, min(size.height, iconBottom + gapPx)),
                            end = Offset(x, size.height),
                            strokeWidth = strokePx,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

/**
 * `IosCardPress(baseColor: transparent, pressedScale: 1, padding: 0)`
 * （ios_tactile.dart 297-368）：无缩放，按下时把 onSurface 以
 * α0.14(dark)/0.12(light) 洗一层底色，[durationMs] easeOutCubic；点击带
 * `hapticsOnCardTap` 门控的 soft 触感。
 */
@Composable
fun CardPress(
    onTap: (() -> Unit)?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    durationMs: Int = 200,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalHapticsSettings.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val strength = if (isDark) 0.14f else 0.12f
    val progress by animateFloatAsState(
        targetValue = if (onTap != null && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = durationMs, easing = EaseOutCubic),
        label = "cardPressWash",
    )
    Box(
        modifier = modifier
            .background(
                cs.onSurface.copy(alpha = strength * progress),
                RoundedCornerShape(radius),
            )
            .then(
                if (onTap == null) Modifier else Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (haptics.onCardTap) Haptics.soft(view)
                        onTap()
                    },
                ),
            ),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Reasoning step (CMW:5000-5240)
// ---------------------------------------------------------------------------

/** _ReasoningStepState：加载中未展开是预览（限高渐隐），完成后未展开是全折叠。 */
internal enum class ReasoningStepState { Collapsed, Preview, Expanded }

internal fun reasoningStepState(expanded: Boolean, loading: Boolean): ReasoningStepState =
    if (loading) {
        if (expanded) ReasoningStepState.Expanded else ReasoningStepState.Preview
    } else {
        if (expanded) ReasoningStepState.Expanded else ReasoningStepState.Collapsed
    }

@Composable
fun ChainOfThoughtReasoningStep(
    step: TimelineStep.Reasoning,
    isFirst: Boolean,
    isLast: Boolean,
    enableReasoningMarkdown: Boolean,
    onToggleReasoning: (segmentIndex: Int) -> Unit,
    /** 数学公式两开关（渲染页），思考正文里的公式同样要渲染。 */
    math: com.psyche.memo.ui.markdown.MathConfig = com.psyche.memo.ui.markdown.MathConfig(),
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg()
    val state = reasoningStepState(step.expanded, step.loading)
    val display = sanitizeReasoning(step.text)
    // RikkaHub ChatMessageReasoning.kt::rememberReasoningState 的计时逻辑移植：
    // 加载中每 50ms 用 now - startAt 重算 elapsed（实时），闭合（loading 变 false）
    // 时冻结在 finishedAt - startAt。不依赖外部 tick 状态，避免重组竞态。
    val elapsedMs by rememberReasoningElapsed(step.startAt, step.finishedAt, step.loading)

    val label = @Composable {
        Row(
            modifier = Modifier.thinkingSheen(fg.strong, isDark, enabled = step.loading),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(UiR.string.chat_message_widget_deep_thinking),
                style = TextStyle(
                    fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                    fontWeight = AppFontWeights.semibold,
                    color = fg.strong,
                ),
            )
            // CMW:5126-5135 —— 有起始时间就实时显示 (X.Xs)。
            if (step.startAt != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "(${String.format(Locale.US, "%.1f", elapsedMs / 1000.0)}s)",
                    style = TextStyle(
                        fontSize = ChatStyleSpec.TIMELINE_LABEL_SP.sp,
                        color = fg.medium,
                    ),
                )
            }
        }
    }
    val icon = @Composable {
        ThinkingCardIcon(
            size = ChatStyleSpec.TIMELINE_ICON_DP.dp,
            color = fg.strong,
        )
    }

    val onToggleStep: () -> Unit = { onToggleReasoning(step.segmentIndex) }
    val toggle: (() -> Unit)? = if (step.hasToggle) onToggleStep else null
    val indicator: (@Composable () -> Unit)? = if (step.hasToggle) {
        @Composable {
            Icon(
                imageVector = if (state == ReasoningStepState.Expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = fg.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        null
    }
    val body: (@Composable () -> Unit)? = if (state == ReasoningStepState.Collapsed) {
        null
    } else {
        @Composable {
            ReasoningPreviewBody(
                text = display,
                loading = step.loading,
                preview = state == ReasoningStepState.Preview,
                enableMarkdown = enableReasoningMarkdown,
                math = math,
            )
        }
    }

    TimelineStepShell(
        icon = icon,
        label = label,
        isFirst = isFirst,
        isLast = isLast,
        fg = fg,
        isDark = isDark,
        onTap = toggle,
        indicator = indicator,
        content = body,
        contentVisible = state != ReasoningStepState.Collapsed,
        expectContent = true,
    )
}

/** CMW:5036-5038 `_sanitize`。 */
fun sanitizeReasoning(text: String): String = text.replace("\r", "").trim()

/**
 * RikkaHub `ChatMessageReasoning.kt::rememberReasoningState` 的计时逻辑移植：
 * 加载中每 50ms 用 `now - startAt` 重算 elapsed（实时），闭合（`loading` 变
 * false）时冻结在 `finishedAt - startAt`。不依赖外部 tick 状态，避免重组竞态。
 */
@Composable
private fun rememberReasoningElapsed(
    startAt: Long?,
    finishedAt: Long?,
    loading: Boolean,
): State<Long> {
    val elapsed = remember(startAt) { mutableLongStateOf(0L) }
    LaunchedEffect(loading, startAt) {
        if (startAt == null) {
            elapsed.value = 0L
            return@LaunchedEffect
        }
        if (!loading) {
            elapsed.value = ((finishedAt ?: startAt) - startAt).coerceAtLeast(0L)
            return@LaunchedEffect
        }
        while (isActive) {
            elapsed.value = (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
            delay(50)
        }
    }
    return elapsed
}

/**
 * CMW:5148-5211 —— 预览态限高 100dp、上下按 12/28dp 渐隐（ShaderMask dstIn）并
 * 自动滚到底；展开态不限高。文本走 SelectionContainer（Dart SelectionArea）。
 */
@Composable
private fun ReasoningPreviewBody(
    text: String,
    loading: Boolean,
    preview: Boolean,
    enableMarkdown: Boolean,
    math: com.psyche.memo.ui.markdown.MathConfig,
) {
    val content = @Composable {
        ReasoningContent(text, enableMarkdown, loading, math)
    }
    if (!preview) {
        SelectionContainer { content() }
        return
    }
    val density = LocalDensity.current
    val maxPx = with(density) { ChatStyleSpec.TIMELINE_PREVIEW_MAX_HEIGHT_DP.dp.roundToPx() }
    val scroll = rememberScrollState()
    var naturalPx by remember { mutableFloatStateOf(0f) }
    val hasOverflow = naturalPx - maxPx > 0.5f
    // CMW:5065-5083 postFrame jumpTo(maxScrollExtent)
    LaunchedEffect(loading, text.length, hasOverflow) {
        if (loading && hasOverflow) scroll.scrollTo(scroll.maxValue)
    }
    Box(
        Modifier
            .heightIn(max = ChatStyleSpec.TIMELINE_PREVIEW_MAX_HEIGHT_DP.dp)
            .clipToBounds()
            .then(
                if (hasOverflow) Modifier.fadeTopBottom(
                    ChatStyleSpec.TIMELINE_PREVIEW_FADE_TOP_DP.dp,
                    ChatStyleSpec.TIMELINE_PREVIEW_FADE_BOTTOM_DP.dp,
                ) else Modifier,
            ),
    ) {
        Column(
            Modifier
                .verticalScroll(scroll, enabled = hasOverflow)
                .onSizeChanged { naturalPx = it.height.toFloat() },
        ) {
            SelectionContainer { content() }
        }
    }
}

/** CMW:5148-5162 —— markdown 开关决定渲染器，字号 12.5 / 行高 1.32，空文本用 '…'。 */
@Composable
private fun ReasoningContent(
    text: String,
    enableMarkdown: Boolean,
    loading: Boolean,
    math: com.psyche.memo.ui.markdown.MathConfig,
) {
    val shown = text.ifEmpty { "…" }
    if (enableMarkdown) {
        MarkdownText(
            markdown = shown,
            baseFontSize = ChatStyleSpec.TIMELINE_BODY_SP,
            baseLineHeight = ChatStyleSpec.TIMELINE_BODY_LINE_HEIGHT_SP,
            math = math,
        )
    } else {
        Text(
            text = shown,
            style = TextStyle(
                fontSize = ChatStyleSpec.TIMELINE_BODY_SP.sp,
                lineHeight = ChatStyleSpec.TIMELINE_BODY_LINE_HEIGHT_SP.sp,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Icons / assets
// ---------------------------------------------------------------------------

/**
 * lib/icons/reasoning_icons.dart —— `thinkingCardIcon` 用
 * `idea-01-stroke-rounded.svg`（mediumAsset），srcIn 染成 fg.strong；Coil 的
 * SvgDecoder 承担同样的 tint 语义。
 */
@Composable
fun ThinkingCardIcon(size: Dp, color: Color) {
    AsyncImage(
        model = "file:///android_asset/icons/idea-01-stroke-rounded.svg",
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = Modifier.size(size),
    )
}

// ---------------------------------------------------------------------------
// ThinkingSheen (thinking_sheen.dart 呼吸高光 — srcIn 渐变扫过)
// ---------------------------------------------------------------------------

/**
 * 呼吸高光修饰符：base→peak 的斜向渐变随 progress 从左向右扫过内容
 * （thinking_sheen.dart ShaderMask srcIn + _SlideGradientTransform）。
 * speed 1.05 / spread 0.52 / intensity 0.68 与 thinkingSheenDefaults 一致。
 */
@Composable
fun Modifier.thinkingSheen(color: Color, isDark: Boolean, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "thinking-sheen")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2286, easing = LinearEasing), // 2400/1.05
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheen-progress",
    )
    return this.then(
        Modifier.graphicsLayerOffscreen().drawWithContent {
            val base = color.copy(alpha = 1f)
            val highlight = lerp(base, Color.White, if (isDark) 0.82f else 0.78f)
            val peak = lerp(base, highlight, 0.42f + 0.68f * 0.58f)
            val mid = lerp(base, peak, 0.5f)
            val outer = (0.52f.coerceIn(0.2f, 0.9f)) / 2 // 0.26
            val inner = outer * 0.34f
            val stops = floatArrayOf(
                0f,
                (0.5f - outer).coerceIn(0.02f, 0.42f),
                (0.5f - inner).coerceIn(0.16f, 0.48f),
                0.5f,
                (0.5f + inner).coerceIn(0.52f, 0.84f),
                (0.5f + outer).coerceIn(0.58f, 0.98f),
                1f,
            )
            val colors = listOf(base, base, mid, peak, mid, base, base)
            // 源码 _SlideGradientTransform：x 平移 width*(t*2-1)。
            val tx = size.width * (progress * 2f - 1f)
            val brush = Brush.linearGradient(
                *stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(),
                start = Offset(tx, -0.18f * size.height),
                end = Offset(tx + size.width, 0.18f * size.height),
            )
            drawContent()
            drawRect(brush, blendMode = BlendMode.SrcIn)
        },
    )
}

/**
 * Flutter `ShaderMask` + 纵向 alpha 渐变（dstIn）：上 [top]、下 [bottom] 高度内
 * 渐隐出画。Compose 需要离屏合成层才能让 DstIn 只作用于本节点内容。
 */
fun Modifier.fadeTopBottom(top: Dp, bottom: Dp): Modifier =
    this
        .graphicsLayerOffscreen()
        .drawWithContent {
            val topStop = (top.toPx() / size.height).coerceIn(0f, 1f)
            val bottomStop = (1f - bottom.toPx() / size.height).coerceIn(0f, 1f)
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    topStop to Color.White,
                    bottomStop to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

private fun Modifier.graphicsLayerOffscreen(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/** AnimatedSize(duration 300ms, curve easeInOutCubicEmphasized, alignment topLeft) — CMW:4672。 */
fun Modifier.animateContentSizeCard(): Modifier = animateContentSize(
    animationSpec = tween(durationMillis = 300, easing = EaseInOutCubicEmphasized),
)

/**
 * Flutter `Curves.easeInOutCubicEmphasized`（curves.dart:1772 `ThreePointCubic`）。
 * 两段三次贝塞尔共用中点 (0.166666, 0.4)，各自归一化到所属区间后拼接。
 */
private val EmphasizedIn = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val EmphasizedOut = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

val EaseInOutCubicEmphasized: Easing = Easing { t ->
    val midX = 0.166666f
    val midY = 0.4f
    if (t < midX) {
        EmphasizedIn.transform(t / midX) * midY
    } else {
        EmphasizedOut.transform((t - midX) / (1f - midX)) * (1f - midY) + midY
    }
}
