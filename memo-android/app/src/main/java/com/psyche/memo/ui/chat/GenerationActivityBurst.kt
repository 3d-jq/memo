package com.psyche.memo.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 生成中的**呼吸指示器** —— 形状是应用图标本体那 8 向放射线（用户 2026-09-28
 * 「这个是原图表的显示，现在改成我们现在图标这样式，动画改成这个放射性线条一缩一放
 * 那种，像绽放的效果一样」），取代此前那颗品牌四角星。
 *
 * 形态照图标量出来的比例（`ic_launcher_foreground` 的母版，外径 325px）：
 *
 * - **8 条射线**，每 45° 一条，圆头；
 * - **外半径 11/24 视口**、**内半径 2.1/24**（中心留白，正是图标中间那个洞）；
 * - **线宽 2.5/24**，与图标 76/325 的粗细比一致。
 *
 * 动画是**绽放**而不是整体缩放：中心留白固定不动，射线从短到长向外舒展再收回
 * （外半径 ×0.52→1.0，线宽 ×0.80→1.0），外扩 700ms 快、回收 1100ms 慢，于是节奏
 * 像花瓣打开又合上。尺寸从旧星形的 12dp 提到 14dp：放射线是空心细线条，
 * 同尺寸下比实心四角星轻一档，14dp 才和原来的视觉重量相当。
 *
 * 颜色仍是 `MaterialTheme.colorScheme.primary`（跟随主题，不贴位图）。
 *
 * 相位读在 `Canvas` 的绘制 lambda 里（`State.value` 在 draw 阶段读取），
 * 所以逐帧只重画这个小画布，**不会**让所在的消息行重组 —— 消息行的 skippable
 * 是有守卫测试的，不许为了动画破掉。
 */
internal val GenerationActivityBurstSize = 14.dp

private const val RAY_COUNT = 8

/** 视口边长；下面所有几何常量都写在这个 24×24 坐标系里。 */
private const val VIEWPORT = 24f

private const val RAY_OUTER_RADIUS = 11f
private const val RAY_INNER_RADIUS = 2.1f
private const val RAY_WIDTH = 2.5f

/** 绽放两端的比例：收拢时最短的射线 / 最细的线宽。 */
private const val LENGTH_MIN = 0.52f
private const val THICKNESS_MIN = 0.80f

private const val EXPAND_MILLIS = 700
private const val RETRACT_MILLIS = 1_100

/**
 * 绽放相位 0f→1f→0f。外扩快、回收慢，两段时长不同 —— 等时长的正反运动会像
 * 弹簧来回抖，而不对称时长才读成「打开 / 合上」。
 *
 * 用 `keyframes` 而不是两个 `tween`：这套 Compose（1.8.3）的
 * `InfiniteRepeatableSpec` 只有单个 `animation` + `repeatMode`，没有双时长参数，
 * 想要不对称节奏只能在一个 spec 里分段。
 */
@Composable
internal fun rememberGenerationActivityBurstPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "GenerationActivityBurst")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = EXPAND_MILLIS + RETRACT_MILLIS
                0f at 0 using LinearOutSlowInEasing
                1f at EXPAND_MILLIS using FastOutSlowInEasing
                0f at (EXPAND_MILLIS + RETRACT_MILLIS) using LinearOutSlowInEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "GenerationActivityBurstPhase",
    )
}

@Composable
internal fun GenerationActivityBurst(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val phase = rememberGenerationActivityBurstPhase()
    // 渐显照旧（400ms，只在首次组合跑一次）：指示器出现时有「浮现」感。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
    }
    Canvas(
        modifier = modifier
            .size(GenerationActivityBurstSize)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = appear.value
                clip = false
            },
    ) {
        val t = phase.value // 绘制阶段读相位：只重画这里，不重组消息行
        val ratio = minOf(size.width, size.height) / VIEWPORT
        val cx = size.width / 2f
        val cy = size.height / 2f
        val inner = RAY_INNER_RADIUS * ratio
        val outer = RAY_OUTER_RADIUS * ratio * lerp(LENGTH_MIN, 1f, t)
        val stroke = RAY_WIDTH * ratio * lerp(THICKNESS_MIN, 1f, t)
        for (i in 0 until RAY_COUNT) {
            val angle = (i * (2 * PI / RAY_COUNT)).toFloat()
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = color,
                start = Offset(cx + dx * inner, cy + dy * inner),
                end = Offset(cx + dx * outer, cy + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
