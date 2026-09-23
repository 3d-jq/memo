package com.psyche.memo.ui.chat

import com.psyche.memo.ui.ChatStyleSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/** 圆点所在的**固定高度槽位**（Agora 的 `StreamingTailAnchorHeight` 同款）。 */
internal val StreamingTailAnchorHeight = 24.dp

/**
 * 生成中的呼吸圆点 —— **带渐显**（移植 Agora `StreamingTailIndicator.kt` 的出现过渡）。
 *
 * 参数照它：alpha 0→1（400ms FastOutSlowInEasing）+ 出现时 scale 0.55→1；
 * 消失直接 `snap()`（不留残影）。槽位高度恒定 24dp 且**只走 graphicsLayer 绘制**，
 * 所以它的出现/消失**不会推动会话布局**，也不会被视口裁切。
 */
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

@Composable
internal fun StreamingTailDot(modifier: Modifier = Modifier) {
    // 渐显：出现时 alpha 0→1（400ms）+ scale 0.55→1；消失按 Agora 直接 snap()（不留残影）。
    val appear = remember {
        androidx.compose.animation.core.Animatable(0f)
    }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(StreamingTailAnchorHeight)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = appear.value
                val s = 0.55f + (1f - 0.55f) * appear.value
                scaleX = s
                scaleY = s
                clip = false
            }
            .padding(start = ChatStyleSpec.ASSISTANT_MESSAGE_HORIZONTAL_DP.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        GenerationActivityDot()
    }
}
