package com.psyche.memo.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
                    RoundedCornerShape(16.dp),
                )
                // 源码 chat_message_widget.dart:1503-1512 —— border 画在
                // 圆角外层:dark onSurface@0.08 / light outlineVariant@0.2。
                .border(
                    width = 1.dp,
                    color = if (isDark) cs.onSurface.copy(alpha = 0.08f)
                    else cs.outlineVariant.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp),
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
                        .background(cs.surfaceContainerHigh, RoundedCornerShape(12.dp))
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
 * 三点波动流式指示器 —— 1:1 移植 chat_message_widget.dart:4105-4196
 * LoadingIndicator/_LoadingDotsPainter：3 个 9dp 圆点、间距 6、画布高 16、
 * 1100ms 线性循环、每点相位差 0.22，wave=(sin(phase)+1)/2，
 * scale=0.85+0.15·wave，alpha=0.45+0.45·wave，颜色 primary。
 * 公式细节在 [ChatStyleSpec.dotState]（可单测），此处只做绘制。
 */
@Composable
fun LoadingDotsIndicator(
    color: Color,
    modifier: Modifier = Modifier,
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
    Canvas(
        modifier = modifier.size(
            ChatStyleSpec.DOTS_WIDTH_DP.dp,
            ChatStyleSpec.DOTS_HEIGHT_DP.dp,
        ),
    ) {
        val dotRadius = ChatStyleSpec.DOTS_DOT_DP.dp.toPx() / 2f
        val gap = ChatStyleSpec.DOTS_GAP_DP.dp.toPx()
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
