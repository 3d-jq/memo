package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronsDown
import com.composables.icons.lucide.ChevronsUp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.R as UiR
import kotlin.math.roundToInt

/**
 * Long-press floating menu on the user bubble — 1:1 port of
 * chat_message_widget.dart:1422-1588 _showUserContextMenu: 220dp wide
 * rounded-16 glass panel, right edge aligned to the bubble's right edge,
 * placed above the bubble when it fits (10dp gap), else below, clamped into
 * the safe area. Items: Copy / Edit / Delete (danger).
 *
 * Placement/blur deviation: Compose has no backdrop filter, so the panel
 * uses a near-solid surfaceContainerHigh instead of blur+α0.66, and the 8%
 * dialog scrim is dropped (the popup is focusable and dismisses on outside
 * tap / back press).
 */
@Composable
fun UserContextMenu(
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val marginPx = with(density) { 12.dp.toPx().roundToInt() }
    val gapPx = with(density) { 10.dp.toPx().roundToInt() }
    val menuProvider = remember(marginPx, gapPx) {
        object : PopupPositionProvider {
            // 源码 chat_message_widget.dart:1442-1444 —— menuWidth 220,
            // estMenuHeight 140, gap 10;四边 safe inset 12。
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val margin = marginPx
                val gap = gapPx
                var x = anchorBounds.right - popupContentSize.width
                val minX = margin
                val maxX = windowSize.width - margin - popupContentSize.width
                if (x < minX) x = minX
                if (x > maxX) x = maxX
                val availableAbove = anchorBounds.top - gap - margin
                val availableBelow =
                    (windowSize.height - margin) - (anchorBounds.bottom + gap)
                val placeAbove = when {
                    availableAbove >= popupContentSize.height -> true
                    availableBelow >= popupContentSize.height -> false
                    else -> availableAbove > availableBelow
                }
                var y = if (placeAbove) {
                    anchorBounds.top - popupContentSize.height - gap
                } else {
                    anchorBounds.bottom + gap
                }
                y = y.coerceIn(margin, (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin))
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = menuProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .background(
                    cs.surfaceContainerHigh.copy(alpha = 0.97f),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                // 源码 chat_message_widget.dart:1503-1512 —— border 画在
                // 圆角外层:dark onSurface@0.08 / light outlineVariant@0.2。
                .border(
                    width = 1.dp,
                    color = if (isDark) cs.onSurface.copy(alpha = 0.08f)
                    else cs.outlineVariant.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                ),
        ) {
            UserMenuItem(Lucide.Copy, UiR.string.share_provider_sheet_copy_button) {
                onDismiss()
                onCopy()
            }
            UserMenuItem(Lucide.Pencil, UiR.string.message_more_sheet_edit) {
                onDismiss()
                onEdit()
            }
            UserMenuItem(Lucide.Trash2, UiR.string.message_more_sheet_delete, danger = true) {
                onDismiss()
                onDelete()
            }
        }
    }
}

@Composable
private fun UserMenuItem(
    icon: ImageVector,
    labelRes: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fg = if (danger) cs.error else cs.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
            ),
        )
    }
}

/**
 * Compact token display — 1:1 port of token_display_widget.dart (mobile
 * path): "123 tokens" 11sp α0.5 label; tap toggles a detail popup anchored
 * above/below the label showing prompt (with cached), completion and
 * duration. Desktop hover behavior is not ported (mobile-only surface).
 */
@Composable
fun TokenDisplay(
    totalTokens: Int,
    promptTokens: Int?,
    completionTokens: Int?,
    cachedTokens: Int?,
    durationMs: Long?,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = androidx.compose.ui.res.stringResource(
                UiR.string.token_detail_total_tokens,
                formatTokenCount(totalTokens),
            ),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                color = cs.onSurface.copy(alpha = 0.5f),
            ),
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .background(cs.surfaceContainerHigh, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TokenPopupRow(
                        text = if (cachedTokens != null && cachedTokens > 0 && promptTokens != null) {
                            androidx.compose.ui.res.stringResource(
                                UiR.string.token_detail_prompt_tokens_with_cache,
                                formatTokenCount(promptTokens),
                                formatTokenCount(cachedTokens),
                            )
                        } else if (promptTokens != null) {
                            androidx.compose.ui.res.stringResource(
                                UiR.string.token_detail_prompt_tokens,
                                formatTokenCount(promptTokens),
                            )
                        } else null,
                    )
                    TokenPopupRow(
                        text = completionTokens?.let {
                            androidx.compose.ui.res.stringResource(
                                UiR.string.token_detail_completion_tokens,
                                formatTokenCount(it),
                            )
                        },
                    )
                    TokenPopupRow(
                        text = durationMs?.takeIf { it > 0 }?.let {
                            androidx.compose.ui.res.stringResource(
                                UiR.string.token_detail_duration,
                                "%.1f".format(it / 1000.0),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenPopupRow(text: String?) {
    if (text.isNullOrEmpty()) return
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp,
            color = cs.onSurface.copy(alpha = 0.8f),
        ),
    )
}

private fun formatTokenCount(value: Int): String = java.text.DecimalFormat.getIntegerInstance().format(value)

/**
 * Version branch selector — 1:1 port of chat_message_widget.dart:3990-4058
 * _BranchSelector: 28dp chevron buttons around a "1/3" 12sp counter.
 */
@Composable
fun BranchSelector(
    index: Int,
    total: Int,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val canPrev = onPrev != null && index > 0
    val canNext = onNext != null && index < total - 1
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(enabled = canPrev) { onPrev?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.ChevronLeft,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = if (canPrev) 1f else 0.3f),
                modifier = Modifier.size(16.dp),
            )
        }
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "${index + 1}/$total",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface.copy(alpha = 0.8f),
                ),
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(enabled = canNext) { onNext?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = if (canNext) 1f else 0.3f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Glassy scroll navigation panel — 1:1 port of scroll_nav_buttons.dart:
 * 4 vertical 28dp circle buttons (top / previous message / next message /
 * bottom), slide-in from right with fade when visible.
 */
@Composable
fun ScrollNavButtonsPanel(
    visible: Boolean,
    onScrollToTop: () -> Unit,
    onPreviousMessage: () -> Unit,
    onNextMessage: () -> Unit,
    onScrollToBottom: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val iconColor = cs.onSurface.copy(alpha = if (isDark) 1.0f else 0.87f)
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavGlassButton(Lucide.ChevronsUp, iconColor, isDark, onScrollToTop)
            NavGlassButton(Lucide.ChevronUp, iconColor, isDark, onPreviousMessage)
            NavGlassButton(Lucide.ChevronDown, iconColor, isDark, onNextMessage)
            NavGlassButton(Lucide.ChevronsDown, iconColor, isDark, onScrollToBottom)
        }
    }
}

@Composable
private fun NavGlassButton(
    icon: ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                cs.surface.copy(alpha = if (isDark) 0.4f else 0.85f),
                CircleShape,
            )
            // 源码 scroll_nav_buttons.dart:159-164 —— dark onSurface@0.12 /
            // light outline@0.20。
            .border(
                width = 1.dp,
                color = if (isDark) cs.onSurface.copy(alpha = 0.12f)
                else cs.outline.copy(alpha = 0.20f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
    }
}

/**
 * 三点波动流式指示器 —— 1:1 移植 chat_message_widget.dart:4105-4196 /
 * 5400-5412 LoadingIndicator/_LoadingDotsPainter：3 个圆点、每点相位差 0.22，
 * wave=(sin(phase)+1)/2，scale=0.85+0.15·wave，alpha=0.45+0.45·wave，颜色
 * 由调用方传入。参数化以支持复用：默认 9/6/16 对应消息流式空态
 * （CMW:4105-4196），工具卡加载用 3/2/12（CMW:5400-5412）。
 * 公式细节在 [ChatStyleSpec.dotState]（可单测），此处只做绘制。
 */
@Composable
fun LoadingDotsIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    dotDp: Float = ChatStyleSpec.DOTS_DOT_DP,
    gapDp: Float = ChatStyleSpec.DOTS_GAP_DP,
    heightDp: Float = ChatStyleSpec.DOTS_HEIGHT_DP,
) {
    val transition = rememberInfiniteTransition(label = "loadingDots")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ChatStyleSpec.DOTS_DURATION_MS, easing = LinearEasing),
        ),
        label = "loadingDotsFraction",
    )
    val widthDp = dotDp * ChatStyleSpec.DOTS_COUNT + gapDp * (ChatStyleSpec.DOTS_COUNT - 1)
    Canvas(
        modifier = modifier.size(widthDp.dp, heightDp.dp),
    ) {
        val dotRadius = dotDp.dp.toPx() / 2f
        val gap = gapDp.dp.toPx()
        for (i in 0 until ChatStyleSpec.DOTS_COUNT) {
            val state = ChatStyleSpec.dotState(fraction, i)
            val cx = i * (dotRadius * 2f + gap) + dotRadius
            drawCircle(
                color = color.copy(alpha = state.alpha),
                radius = dotRadius * state.scale,
                center = Offset(cx, size.height / 2f),
            )
        }
    }
}

/**
 * 流式等待的扫光文字（**用户 2026-09-12 点名改造**，替代原版三点）：一句轮换的
 * 中文短语（思考中／嘻嘻中／深挖中…，可随时加词）+ 从左到右扫过的高光。
 *
 * 原版 `LoadingIndicator`（chat_message_widget.dart L4104-4196）是三点波浪脉动，
 * 我们已 1:1 照抄；用户看过 RikkaHub（兔子图标动画 / M3 ContainedLoadingIndicator）
 * 后决定换成这个 —— 属**有意偏离**，见 PORTING §5.11，勿按原版「修回」三点。
 */
internal object ThinkingPhrases {
    /** 轮换短语（用户给的三个 + 我按同一语气扩的）。改词直接改这里。 */
    val ALL: List<String> = listOf(
        "思考中", "嘻嘻中", "深挖中", "搓手中", "酝酿中", "翻书中",
        "算盘中", "推敲中", "脑暴中", "琢磨中", "灵感中", "搬砖中",
    )
}

@Composable
fun ThinkingShimmerText(
    modifier: Modifier = Modifier,
    phrases: List<String> = ThinkingPhrases.ALL,
    intervalMs: Long = 2200,
    sweepMs: Int = 1500,
    // 用户 2026-09-12「这个文字可以大一点」：13sp → 15sp（贴助手正文 15.7sp）。
    // 2026-09-13 起这三个都可由设置页自定义（见 ThinkingIndicatorSettings）。
    fontSize: TextUnit = 15.sp,
    /** null = 跟随主题色（原行为）。 */
    colorArgb: Int? = null,
) {
    val cs = MaterialTheme.colorScheme
    // 用户 2026-09-12「颜色也改成主题色吧 现在是黑色的」：底色/高光都用主题色，
    // 扫光靠透明度差（0.5 → 1.0）表现 —— 明暗主题下都成立；设置里选了自定义色就
    // 用那个色（同样靠透明度差做扫光）。
    val accent = colorArgb?.let { Color(it) } ?: cs.primary
    val base = accent.copy(alpha = 0.5f)
    val highlight = accent

    // 轮换短语：定时切片，切换用 Crossfade（不打断正在扫的高光）。
    var index by remember(phrases) { mutableIntStateOf(0) }
    LaunchedEffect(phrases, intervalMs) {
        if (phrases.size <= 1) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            index = (index + 1) % phrases.size
        }
    }

    val transition = rememberInfiniteTransition(label = "thinkingShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepMs, easing = LinearEasing),
        ),
        label = "thinkingShimmerProgress",
    )
    var textWidth by remember { mutableIntStateOf(0) }
    val minBand = with(LocalDensity.current) { 48.dp.toPx() }
    // 高光带宽度跟文字走；两端各留一个带宽，保证「跳回开头」发生在文字之外。
    val band = (textWidth * 0.75f).coerceAtLeast(minBand)
    val sweepStart = -band + progress * (textWidth + band * 2f)

    Crossfade(targetState = index, animationSpec = tween(durationMillis = 220), label = "thinkingPhrase") { i ->
        Text(
            text = phrases.getOrElse(i) { phrases.firstOrNull().orEmpty() },
            maxLines = 1,
            style = TextStyle(
                fontSize = fontSize,
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(sweepStart, 0f),
                    end = Offset(sweepStart + band, 0f),
                ),
            ),
            modifier = Modifier.onSizeChanged { textWidth = it.width },
        )
    }
}

/** VoiceWaveform 的布局断言锚点（波形槽位高度回归测试用）。 */
const val VOICE_WAVEFORM_TAG = "voice_waveform"

/**
 * 气泡内自动重试倒计时（1:1 移植 `_RetryCountdownHint`，chat_message_widget.dart
 * L4071-4102）：「N 秒后重试 (attempt/maxRetries)」，12sp、onSurface@55%。剩余
 * 秒数用 Animatable 从起始秒线性降到 0（一次性动画；retryAtMs 变化 → remember key
 * 变 → 动画重启），文案走 core:ui 既有 `auto_retry_countdown`（zh「%1$s 秒后重试
 * (%2$s/%3$s)」）。到 0 显示 0，下一次尝试开始由上层清 retryStatus 切回扫光。
 */
/**
 * 是否显示重试倒计时 —— 必须有状态**且**消息还在流式。
 *
 * 终止路径（正常结束 / 用户停止 / 真失败）都会把 `retryStatus` 清掉，这里再要一道
 * `isStreaming`：即使哪天漏清，也绝不会把一个「N 秒后重试」停在已经结束的消息上
 * （用户 2026-09-16 报的是裸 http 文案，同类问题的另一面就是倒计时残留）。
 */
internal fun shouldShowRetryCountdown(status: Any?, isStreaming: Boolean): Boolean =
    status != null && isStreaming

@Composable
fun RetryCountdownHint(
    status: com.psyche.memo.ChatViewModel.UiMessage.RetryStatus,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // retryAtMs 是退避结束的绝对时刻（Dart RetryStatus.retryAt 等价物）；
    // remember 锚定一个起点避免重组反复取 now。
    val nowMs = remember(status.retryAtMs) { System.currentTimeMillis() }
    val remainingMs = (status.retryAtMs - nowMs).coerceAtLeast(0L)
    val startSeconds = remainingMs / 1000f
    val style = TextStyle(
        fontSize = 12.sp,
        color = cs.onSurface.copy(alpha = 0.55f),
    )
    val countdown = remember(status.retryAtMs) {
        androidx.compose.animation.core.Animatable(startSeconds)
    }
    LaunchedEffect(status.retryAtMs) {
        if (startSeconds > 0f) {
            countdown.animateTo(
                0f,
                androidx.compose.animation.core.tween(
                    durationMillis = remainingMs.toInt(),
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
        }
    }
    Text(
        text = androidx.compose.ui.res.stringResource(
            UiR.string.auto_retry_countdown,
            (if (countdown.value <= 0f) 0 else kotlin.math.ceil(countdown.value)).toInt().toString(),
            status.attempt.toString(),
            status.maxRetries.toString(),
        ),
        style = style,
        modifier = modifier,
    )
}

/**
 * 语音录音波形 —— 1:1 移植 chat_input_bar.dart `_VoiceWaveformPainter`
 * (CIB:3400-3459)：条宽 3、条距 3.5，最新样本靠右、旧样本向左滚动；
 * 振幅 levels[i]∈[0,1] → 高度 max(2, maxH·level·envelope)，maxH = 高·0.92；
 * envelope 为真胶囊轮廓（到最近边的条中心距 < r=maxH/2 时按圆弧截面收缩）；
 * 圆角 = 条宽/2（胶囊端）。
 */
@Composable
fun VoiceWaveform(
    levels: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // 高度兜底（上游 StackFit.expand 语义）：Canvas 本体是 Spacer，高度约束
    // 宽松时测量为 0 → maxH=0 → 所有条贴 2px，「波形不动」。兜底后组件在
    // 只有 fillMaxWidth 的容器里也有真实槽高；调用方显式 .height() 时以其为准。
    val resolved = modifier
        .testTag(VOICE_WAVEFORM_TAG)
        .height(com.psyche.memo.ui.ChatStyleSpec.WAVE_SLOT_HEIGHT_DP.dp)
    Canvas(modifier = resolved) {
        val barW = ChatStyleSpec.WAVE_BAR_WIDTH_DP.dp.toPx()
        val barGap = ChatStyleSpec.WAVE_BAR_GAP_DP.dp.toPx()
        val step = barW + barGap
        val count = ((size.width + barGap) / step).toInt()
        if (count <= 0) return@Canvas
        val centerY = size.height / 2f
        val maxH = size.height * ChatStyleSpec.WAVE_MAX_HEIGHT_RATIO
        // 居中条行，两端 inset 对称（CIB:3418-3420）。
        val leftInset = (size.width - (count * step - barGap)) / 2f
        // 样本右对齐：最新在右缘，旧样本左滚（CIB:3421-3424）。
        val visible = kotlin.math.min(count, levels.size)
        val first = levels.size - visible
        for (i in 0 until visible) {
            val level = levels[first + i].coerceIn(0f, 1f)
            val slot = count - visible + i
            val x = leftInset + slot * step
            // 条中心到最近边的距离（CIB:3433-3435）；胶囊 envelope + 高度
            // 公式抽到 ChatStyleSpec 供单测。
            val dCenter = kotlin.math.min(x, size.width - (x + barW)) + barW / 2f
            val h = ChatStyleSpec.voiceWaveformBarHeight(
                level = level,
                dCenter = dCenter,
                maxBarHeightPx = maxH,
                minBarHeightPx = ChatStyleSpec.WAVE_MIN_BAR_HEIGHT_DP.dp.toPx(),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - h / 2f),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * 转写中指示器 —— 1:1 移植 `_VoiceTranscribingIndicator`（CIB:3344-3398）：
 * Lucide.Loader(15) 900ms 匀速旋转 + 7 间距 + 12sp 文案。
 */
@Composable
fun VoiceTranscribingIndicator(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "voiceTranscribing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "voiceTranscribingAngle",
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Lucide.Loader,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(15.dp)
                .rotate(angle),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = color))
    }
}
