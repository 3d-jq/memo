package com.psyche.memo.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size

/**
 * 生成中的**呼吸圆点** —— 移植自 Agora（`newo-ether/Agora`，同为 Kotlin/Compose 的安卓 LLM 客户端）
 * 的 `ui/chat/StreamingTailIndicator.kt`。
 *
 * 用户 2026-09-23：「他是用一个呼吸的圆点来做这个…我很喜欢」「用它这个吧，颜色跟着主题走，
 * 他这个又好看又没有任何问题」。所以按它原样的参数来：
 *
 * - 尺寸 **11dp**、`CircleShape`；
 * - 颜色 **`MaterialTheme.colorScheme.onSurface`**（前景色 ⇒ 跟着主题：暗色下是浅点、浅色下是深点）；
 * - 呼吸：scale **0.55f ⇄ 1.30f**，`tween(1000ms, FastOutSlowInEasing)`，`RepeatMode.Reverse`；
 * - 只做**绘制态**（`graphicsLayer` 的 scale），不占额外布局、不影响会话高度。
 */
internal val GenerationActivityDotSize = 11.dp

@Composable
internal fun rememberGenerationActivityDotBreathingScale(): Float {
    val breathing = rememberInfiniteTransition(label = "GenerationActivityBreathing")
    val scale by breathing.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "GenerationActivityBreathingScale",
    )
    return scale
}

@Composable
internal fun GenerationActivityDot(modifier: Modifier = Modifier) {
    val scale = rememberGenerationActivityDotBreathingScale()
    Box(
        modifier = modifier
            .size(GenerationActivityDotSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            ),
    )
}
