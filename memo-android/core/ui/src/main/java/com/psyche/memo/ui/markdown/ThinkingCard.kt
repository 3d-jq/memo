package com.psyche.memo.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Chain-of-thought (reasoning) card — 1:1 port of the Flutter
 * _ChainOfThoughtReasoningStep / _TimelineStepShell timeline row
 * (chat_message_widget.dart:5000-5240 + 4445-4452 + 4665-4700).
 *
 * This is the single-step Android form: one [ThinkingCard] == one Flutter
 * `ReasoningSegment` step (the original renders a *list* of steps; on Android a
 * message carries a single reasoning part). Differences forced by the
 * single-step structure are called out inline.
 *
 * fg palette mirrors CMW:3926-3931 (alpha per brightness, base onSurface):
 * strong 0.88/0.78, medium 0.76/0.66, muted 0.56/0.46, body 0.72/0.60.
 *
 * NOTE on sheen: the breathing `thinkingSheen` lives in the `app` module
 * (ToolCallCard.kt) and `core/ui` must NOT depend on `app`, so this card does
 * NOT apply the sheen. Callers that want the streaming shimmer should wrap the
 * card with `Modifier.thinkingSheen(...)` on the `app` side (not wired here).
 */
@Composable
fun ThinkingCard(
    thinking: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val base = cs.onSurface
    val fgStrong = base.copy(alpha = if (isDark) 0.88f else 0.78f)
    val fgMedium = base.copy(alpha = if (isDark) 0.76f else 0.66f)
    val fgMuted = base.copy(alpha = if (isDark) 0.56f else 0.46f)
    val fgBody = base.copy(alpha = if (isDark) 0.72f else 0.60f)
    val cardBg = cs.primaryContainer.copy(alpha = if (isDark) 0.25f else 0.30f)

    val label = androidx.compose.ui.res.stringResource(R.string.chat_message_widget_deep_thinking)

    var expanded by remember { mutableStateOf(false) }
    val hasOverflow = remember { mutableStateOf(false) }

    // Live elapsed timer — only while streaming (CMW:5020-5059: a 100ms
    // periodic tick drives a ValueNotifier; we mirror it with a coroutine that
    // counts from first composition). Frozen on the last value when streaming
    // stops (CMW _elapsed uses finishedAt ?? now, so it stops advancing).
    val startNanos = remember { System.nanoTime() }
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        while (isActive) {
            elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            delay(100)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // AnimatedSize(duration 300ms, easeInOutCubicEmphasized) — CMW:4672.
            .background(cardBg, RoundedCornerShape(16.dp))
            .animateContentSize(tween(300))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon column — _timelineIconColumnWidth = 24, icon 18 (CMW:4449 + 5140-5146).
            Box(
                modifier = Modifier.size(width = 24.dp, height = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Brain,
                    contentDescription = null,
                    tint = fgStrong,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // Header label row (CMW:5112-5138).
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fgStrong,
                    ),
                )
                // Elapsed " (X.Xs)" — only during streaming (CMW:5126-5135).
                if (isStreaming) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(${"%.1f".format(Locale.US, elapsedMs / 1000.0)}s)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            color = fgMedium,
                        ),
                    )
                }
            }
            // Expand affordance — only when the body overflows (CMW:5226-5234
            // shows ChevronUp/Down when a toggle exists; here we synthesize one
            // for the single-step tap-to-expand).
            if (hasOverflow.value) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    tint = fgMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Body (CMW:5148-5211). In the source a finished-and-collapsed step
        // renders nothing; with a single step we always show the text and use
        // the preview (max 100dp + bottom fade) when collapsed, full when
        // tapped — see ReasoningBody for the truncation logic.
        ReasoningBody(
            text = thinking,
            textColor = fgBody,
            expanded = expanded,
            hasOverflow = hasOverflow,
            cardBg = cardBg,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp)
                .clickable { expanded = !expanded },
        )
    }
}

/**
 * Reasoning body with the Flutter preview behaviour
 * (CMW:5165-5208): when not expanded, content is capped at 100dp with the
 * bottom 28dp faded out (ShaderMask dstIn, top 12 / bottom 28) and the row
 * becomes tappable to expand to full height. We approximate the ShaderMask
 * with a card-coloured bottom gradient scrim — same visual result: the text
 * melts into the card background. Overflow is detected by measuring the text at
 * unbounded height via [Layout] and comparing to 100dp.
 */
@Composable
private fun ReasoningBody(
    text: String,
    textColor: Color,
    expanded: Boolean,
    hasOverflow: MutableState<Boolean>,
    cardBg: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val maxPx = with(density) { 100.dp.roundToPx() }
    Box(modifier = modifier) {
        Layout(
            modifier = Modifier.clipToBounds(),
            content = {
                Text(
                    text = if (text.isNotEmpty()) text else "…",
                    color = textColor,
                    fontSize = 12.5.sp,
                    lineHeight = 16.5.sp,
                )
            },
        ) { measurables, constraints ->
            val natural = measurables[0].measure(
                constraints.copy(maxHeight = Constraints.Infinity),
            )
            val limited = !expanded && natural.height > maxPx
            if (hasOverflow.value != limited) hasOverflow.value = limited
            val h = if (limited) maxPx else natural.height
            layout(natural.width, h) {
                natural.place(0, 0)
            }
        }
        if (hasOverflow.value && !expanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            0.0f to cardBg.copy(alpha = 0f),
                            1.0f to cardBg,
                        ),
                    ),
            )
        }
    }
}
