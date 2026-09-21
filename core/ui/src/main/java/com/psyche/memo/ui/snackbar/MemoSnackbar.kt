package com.psyche.memo.ui.snackbar

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.VisibleForTesting
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Top snackbar stack — 1:1 port of kelivo lib/shared/widgets/snackbar.dart.
 * Max 3 visible toasts stacked 8dp apart with scale/opacity falloff, swipe up
 * to dismiss (top toast only, threshold -40px or fast upward fling), auto
 * dismiss after [AppNotification.durationMs], four types with distinct icons
 * and colors. All later features report user feedback through
 * [SnackbarManager.show].
 */
enum class NotificationType { SUCCESS, ERROR, INFO, WARNING }

class AppNotification(
    val message: String,
    val type: NotificationType = NotificationType.INFO,
    val durationMs: Long = 3000,
    val onTap: (() -> Unit)? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

internal class SnackbarEntry(
    val id: Long,
    val notification: AppNotification,
    /** Set by the manager when the countdown ends; drives the exit fade. */
    @Volatile var expiring: Boolean = false,
    @Volatile var removed: Boolean = false,
)

object SnackbarManager {
    private var nextId = 0L
    internal val entries = mutableStateListOf<SnackbarEntry>()

    /** Mirrors AppSnackBarManager._maxVisible. */
    const val MAX_VISIBLE = 3

    /** Entrance / exit fade durations, matching the original's 300ms controller. */
    internal const val ENTER_MS = 300L
    internal const val EXIT_MS = 300L

    /** How many toasts are queued, visible or not (the original's `activeToasts`). */
    val activeCount: Int get() = entries.size

    /** Queued messages, newest first — the read-only view the original exposes. */
    val activeMessages: List<String> get() = entries.map { it.notification.message }

    /**
     * The queue's clock, deliberately outside the composition.
     *
     * The original schedules its `Timer` in `AppSnackBarManager.show()`
     * (`snackbar.dart` L78), so a toast that arrives while three others are on
     * screen still expires on time. Driving the delay from the item instead
     * meant only the [MAX_VISIBLE] rendered toasts ever ran it — every other
     * entry sat in [entries] indefinitely, and then replayed its entrance
     * animation and full countdown whenever it finally reached the visible
     * window. That is what made a burst of toasts look stuck.
     *
     * Only [delay] runs here: the fades stay in the item, where a
     * MonotonicFrameClock exists (`Animatable`/`animateFloatAsState` throw
     * outside the composition).
     */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun show(notification: AppNotification) {
        val entry = SnackbarEntry(nextId++, notification)
        entries.add(0, entry)
        scope.launch {
            delay(notification.durationMs)
            expire(entry)
        }
    }

    /**
     * Begin the exit: flip [SnackbarEntry.expiring] so a *visible* toast fades
     * out, then drop the entry once the fade has had its time. Works the same
     * for an off-screen toast, which simply disappears — it was never seen.
     */
    internal fun expire(entry: SnackbarEntry) {
        if (entry.removed || entry.expiring) return
        entry.expiring = true
        scope.launch {
            delay(EXIT_MS)
            removeNow(entry)
        }
    }

    /** Drops the entry. Idempotent; used by the swipe gesture and by [expire]. */
    internal fun removeNow(entry: SnackbarEntry) {
        entry.removed = true
        entries.remove(entry)
    }

    /**
     * Empties the queue. Public only for the cross-module Robolectric test that
     * pins the drain behaviour; production code never calls it.
     */
    @VisibleForTesting
    fun resetForTest() {
        entries.forEach { it.removed = true }
        entries.clear()
        nextId = 0L
    }
}

@Composable
fun AppSnackBarOverlay(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Render bottom-most first so the top toast draws last. Each item
            // carries its entry's id as its composition key: without it the
            // slots are matched by position, so a dismissal shifts every toast
            // up one slot and Compose reuses each composable for a different
            // entry (restarting its state) — the stack visibly scrambled.
            val visible = SnackbarManager.entries.take(SnackbarManager.MAX_VISIBLE)
            for (i in visible.indices.reversed()) {
                val entry = visible[i]
                key(entry.id) {
                    ToastItem(
                        entry = entry,
                        isTop = i == 0,
                        visualIndex = i,
                        onDismiss = { SnackbarManager.expire(entry) },
                        // Swiping throws the toast off-screen; fading it out
                        // again afterwards is what made the gesture feel like
                        // it stalled.
                        onSwipedOut = { SnackbarManager.removeNow(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastItem(
    entry: SnackbarEntry,
    isTop: Boolean,
    visualIndex: Int,
    onDismiss: () -> Unit,
    onSwipedOut: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Entrance: compose at 0 and let the first frame flip it to 1, so
    // animateFloatAsState has somewhere to animate *from*. Exit: the manager
    // flips `expiring` when the countdown ends (no timer here — the countdown
    // must run whether or not this toast is inside the visible window).
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val fade by animateFloatAsState(
        targetValue = when {
            entry.expiring -> 0f
            entered -> 1f
            else -> 0f
        },
        animationSpec = tween(
            durationMillis = if (entry.expiring) SnackbarManager.EXIT_MS.toInt() else SnackbarManager.ENTER_MS.toInt(),
            easing = if (entry.expiring) {
                CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
            } else {
                CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
            },
        ),
        label = "toastFade",
    )

    // Swipe-up offset (negative = up); top toast only.
    // snackbar.dart compares in logical pixels (dp): dismiss when dragged past
    // -40dp *or* flicked faster than 300dp/s, and animate out to -150dp. Compose
    // drag deltas and velocities are device pixels, so convert — using the raw
    // px numbers made the threshold 3x too sensitive on a 3x-density screen and
    // the fly-out 3x too short, which is why the toast stopped mid-air and then
    // faded for another 300ms instead of leaving smoothly.
    val density = LocalDensity.current
    val dismissDistancePx = with(density) { 40.dp.toPx() }
    val flyOutDistancePx = with(density) { 150.dp.toPx() }
    val dismissVelocityPxPerSec = with(density) { 300.dp.toPx() }

    val dragOffset = remember { mutableFloatStateOf(0f) }
    val dismissing = remember { mutableStateOf(false) }
    val interactiveScale by animateFloatAsState(
        targetValue = if (dismissing.value) 0.98f else 1f,
        animationSpec = tween(150),
        label = "toastScale",
    )
    val dragState = rememberDraggableState { delta ->
        // Downward swipes are ignored (snackbar.dart: only upward dismisses).
        if (delta < 0f) dragOffset.floatValue = min(0f, dragOffset.floatValue + delta)
    }

    val baseOpacity = 1f - (visualIndex * 0.2f)
    val stackOffset = visualIndex * 8f
    // snackbar.dart `Tween(begin: Offset(0,-1), end: Offset.zero)` —— 从上方(-100)落下
    // 进位，反向退场时滑上去 + 淡出，与上滑手势方向一致（此前写成 (1-fade)*100 方向反了）。
    val slideUp = (fade - 1f) * 100f

    val icon: ImageVector = when (entry.notification.type) {
        NotificationType.SUCCESS -> Icons.Rounded.CheckCircle
        NotificationType.ERROR -> Icons.Rounded.Error
        NotificationType.WARNING -> Icons.Rounded.Warning
        NotificationType.INFO -> Icons.Rounded.Info
    }
    val iconColor: Color = when (entry.notification.type) {
        NotificationType.SUCCESS -> semantic.success
        NotificationType.ERROR -> cs.error
        NotificationType.WARNING -> semantic.warning
        NotificationType.INFO -> cs.primary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .alpha((fade * baseOpacity).coerceIn(0f, 1f))
                .scale((1f - visualIndex * 0.03f) * interactiveScale)
                .graphicsLayer { translationY = stackOffset + slideUp + dragOffset.floatValue }
                .shadow(16.dp, RoundedCornerShape(MemoRadius.CARD_DP.dp))
                .background(cs.surfaceContainerHigh.copy(alpha = 0.98f), RoundedCornerShape(MemoRadius.CARD_DP.dp))
                .clickable {
                    if (!dismissing.value) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        entry.notification.onTap?.invoke()
                        onDismiss()
                    }
                }
                // `draggable` (not detectVerticalDragGestures) because it hands
                // back the fling velocity, which the dismiss rule needs: a short
                // flick faster than 300dp/s closes the toast even when it never
                // travelled 40dp.
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = isTop && !dismissing.value,
                    onDragStopped = { velocity ->
                        if (dismissing.value) return@draggable
                        if (
                            dragOffset.floatValue < -dismissDistancePx ||
                            velocity < -dismissVelocityPxPerSec
                        ) {
                            dismissing.value = true
                            scope.launch {
                                // Carry the finger's velocity into the fly-out so
                                // the toast keeps the speed it was thrown with.
                                val anim = Animatable(dragOffset.floatValue)
                                anim.animateTo(
                                    targetValue = -flyOutDistancePx,
                                    animationSpec = tween(220, easing = EaseOut),
                                    initialVelocity = velocity,
                                ) { dragOffset.floatValue = value }
                                onSwipedOut()
                            }
                        } else {
                            scope.launch {
                                val anim = Animatable(dragOffset.floatValue)
                                anim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.85f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                    initialVelocity = velocity,
                                ) { dragOffset.floatValue = value }
                            }
                        }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(12.dp))
                Text(
                    text = entry.notification.message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.notification.actionLabel != null) {
                    Spacer(Modifier.size(12.dp))
                    TextButton(
                        onClick = {
                            entry.notification.onAction?.invoke()
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = entry.notification.actionLabel!!,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.primary,
                            ),
                        )
                    }
                }
            }
        }
    }
}
