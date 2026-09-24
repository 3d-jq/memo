package com.psyche.memo.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.psyche.memo.ui.ChatStyleSpec

/**
 * 生成中的**呼吸圆点** —— 移植自 Agora（`newo-ether/Agora`，同为 Kotlin/Compose 的安卓 LLM 客户端）
 * 的 `ui/chat/StreamingTailIndicator.kt` + `AssistantMessageContent.kt:768`。
 *
 * 用户 2026-09-23：「他是用一个呼吸的圆点来做这个…我很喜欢」；2026-09-24：「没有走主题色，
 * 硬编码的黑色」「他还有渐显的效果」。参数照它：
 *
 * - 尺寸 **11dp**、`CircleShape`；
 * - 颜色 **`MaterialTheme.colorScheme.primary`**（跟随主题色；`onSurface` 在浅色主题下是黑 ✗）；
 * - 呼吸：scale **0.55f ⇄ 1.30f**，`tween(1000ms, FastOutSlowInEasing)`，`RepeatMode.Reverse`；
 * - **渐显**：alpha 0→1（400ms FastOutSlowInEasing），只在首次组合跑一次；
 * - 只做**绘制态**（`graphicsLayer`），不占额外布局、不影响会话高度。
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
    // **渐显**（照 Agora StreamingTailIndicator 的出现过渡）：alpha 0→1（400ms FastOutSlowIn），
    // 只在首次组合跑一次；配合呼吸 scale，出现即有「浮现」感。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier
            .size(GenerationActivityDotSize)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = appear.value
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(
                // 颜色跟随**主题色**（primary）：随主题切换联动（用户 2026-09-24
                // 「没有走主题色，硬编码的黑色」—— onSurface 在浅色主题下就是黑 ✗）。
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
    )
}
