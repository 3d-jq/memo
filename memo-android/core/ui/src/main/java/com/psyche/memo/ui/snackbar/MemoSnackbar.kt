package com.psyche.memo.ui.snackbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.theme.LocalSemanticColors
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
    val anim: Animatable<Float, *> = Animatable(0f),
    @Volatile var removed: Boolean = false,
)

object SnackbarManager {
    private var nextId = 0L
    internal val entries = mutableStateListOf<SnackbarEntry>()

    /** Mirrors AppSnackBarManager._maxVisible. */
    const val MAX_VISIBLE = 3

    fun show(notification: AppNotification) {
        val entry = SnackbarEntry(nextId++, notification)
        entries.add(0, entry)
    }

    internal fun dismiss(entry: SnackbarEntry) {
        if (entry.removed) return
        entry.removed = true
    }
}

@Composable
fun AppSnackBarOverlay(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()

    fun dismiss(entry: SnackbarEntry) {
        scope.launch {
            // Exit: fade/slide back out (reverse of entrance), then remove.
            entry.anim.animateTo(0f, tween(300, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)))
            SnackbarManager.entries.remove(entry)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Render bottom-most first so the top toast draws last.
            val visible = SnackbarManager.entries.take(SnackbarManager.MAX_VISIBLE)
            for (i in visible.indices.reversed()) {
                val entry = visible[i]
                ToastItem(
                    entry = entry,
                    isTop = i == 0,
                    visualIndex = i,
                    onDismiss = { dismiss(entry) },
                )
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
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Entrance + auto-dismiss timer.
    LaunchedEffect(entry.id) {
        entry.anim.animateTo(1f, tween(300, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)))
        delay(entry.notification.durationMs)
        onDismiss()
    }

    // Swipe-up offset (negative = up); top toast only.
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val dismissing = remember { mutableStateOf(false) }
    val interactiveScale by animateFloatAsState(
        targetValue = if (dismissing.value) 0.98f else 1f,
        animationSpec = tween(150),
        label = "toastScale",
    )

    val fade = entry.anim.value
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
                .shadow(16.dp, RoundedCornerShape(14.dp))
                .background(cs.surfaceContainerHigh.copy(alpha = 0.98f), RoundedCornerShape(14.dp))
                .clickable {
                    if (!dismissing.value) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        entry.notification.onTap?.invoke()
                        onDismiss()
                    }
                }
                .pointerInput(isTop) {
                    if (!isTop) return@pointerInput
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            // Only upward swipes (dy <= 0) move the toast.
                            if (dragAmount < 0f) {
                                dragOffset.floatValue = min(0f, dragOffset.floatValue + dragAmount)
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (dismissing.value) return@detectVerticalDragGestures
                            if (dragOffset.floatValue < -40f) {
                                dismissing.value = true
                                scope.launch {
                                    animate(
                                        initialValue = dragOffset.floatValue,
                                        targetValue = -150f,
                                        animationSpec = tween(250, easing = EaseOut),
                                    ) { v, _ -> dragOffset.floatValue = v }
                                    onDismiss()
                                }
                            } else {
                                scope.launch {
                                    animate(
                                        initialValue = dragOffset.floatValue,
                                        targetValue = 0f,
                                        animationSpec = tween(250, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)),
                                    ) { v, _ -> dragOffset.floatValue = v }
                                }
                            }
                        },
                    )
                }
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
