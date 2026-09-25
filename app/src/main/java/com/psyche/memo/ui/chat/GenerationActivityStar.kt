package com.psyche.memo.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 生成中的**呼吸指示器** —— 形状是品牌图标右上角那颗四角星（用户 2026-09-25
 * 「我们这个呼吸圆点改成品牌图标里面那个的星吗」），动画与几何沿用 Agora
 * （`ui/chat/StreamingTailIndicator.kt` + `AssistantMessageContent.kt:768`）那颗圆点：
 *
 * - 尺寸 **12dp**（星形视觉比同直径的圆点小一档，所以比原来的 11dp 给大一点）；
 * - 颜色 **`MaterialTheme.colorScheme.primary`**（跟随主题色；`onSurface` 在浅色主题下是黑 ✗）；
 * - 呼吸：scale **0.55f ⇄ 1.30f**，`tween(1000ms, FastOutSlowInEasing)`，`RepeatMode.Reverse`；
 * - **渐显**：alpha 0→1（400ms FastOutSlowInEasing），只在首次组合跑一次；
 * - 只做**绘制态**（`graphicsLayer`），不占额外布局、不影响会话高度。
 *
 * 形状用 [Path] 手工描而不是贴 `docs/icon.png`：位图要么自带品牌蓝（不跟主题），
 * 要么当模板染成单色（边缘有抗锯齿灰边、缩放发糊），而这条路径能跟着 `primary` 走、
 * 能在任意缩放下保持尖角干净。
 */
internal val GenerationActivityStarSize = 12.dp

/**
 * 24×24 视口里的四角星：四个尖角朝上下左右，四条边被贝塞尔吸向中心。
 * [ratio] 是「视口 → 画布像素」的缩放，尖角留 1/24 的边距。
 *
 * 坐标在这里手工乘出来而不用 `DrawScope.scale`：那条变换在这份 Compose 版本里
 * 与外层同名局部量打架（编译不过），而这条路径只在绘制阶段跑一次（呼吸缩放走
 * `graphicsLayer`，不会重画），不值得为它引入变换 API。
 */
private fun brandStarPath(ratio: Float): Path = Path().apply {
    fun x(v: Float) = v * ratio
    fun y(v: Float) = v * ratio
    moveTo(x(12f), y(1f))
    cubicTo(x(13.3f), y(6.9f), x(17.1f), y(10.7f), x(23f), y(12f))
    cubicTo(x(17.1f), y(13.3f), x(13.3f), y(17.1f), x(12f), y(23f))
    cubicTo(x(10.7f), y(17.1f), x(6.9f), y(13.3f), x(1f), y(12f))
    cubicTo(x(6.9f), y(10.7f), x(10.7f), y(6.9f), x(12f), y(1f))
    close()
}

@Composable
internal fun rememberGenerationActivityStarBreathingScale(): Float {
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
internal fun GenerationActivityStar(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val breathScale = rememberGenerationActivityStarBreathingScale()
    // **渐显**（照 Agora 指示器的出现过渡）：alpha 0→1（400ms FastOutSlowIn），
    // 只在首次组合跑一次；配合呼吸 scale，出现即有「浮现」感。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
    }
    Canvas(
        modifier = modifier
            .size(GenerationActivityStarSize)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = appear.value
                scaleX = breathScale
                scaleY = breathScale
                clip = false
            },
    ) {
        // 视口 24 → 画布边长；坐标从原点算，(12,12) 正好落在画布中心。
        drawPath(brandStarPath(minOf(size.width, size.height) / 24f), color = color)
    }
}
