package com.psyche.memo.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.LocalHapticsSettings

/**
 * 聊天时间线的**共用绘制件**（2026-09-26 从 ChainOfThoughtCard.kt 摘出——
 * 思考/工具卡改版为 ZCode 式紧凑行后，旧卡片本体删除，这些修饰符与按压件
 * 仍被 AskUserCard / ReasoningBudgetSheet / AgentTraceRows 使用）。
 */

/**
 * `IosCardPress(baseColor: transparent, pressedScale: 1, padding: 0)`
 * （ios_tactile.dart 297-368）：无缩放，按下时把 onSurface 以
 * α0.14(dark)/0.12(light) 洗一层底色，[durationMs] easeOutCubic；点击带
 * `hapticsOnCardTap` 门控的 soft 触感。
 */
@Composable
fun CardPress(
    onTap: (() -> Unit)?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    durationMs: Int = 200,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalHapticsSettings.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val strength = if (isDark) 0.14f else 0.12f
    val progress by animateFloatAsState(
        targetValue = if (onTap != null && pressed) 1f else 0f,
        animationSpec = tween(durationMillis = durationMs, easing = EaseOutCubic),
        label = "cardPressWash",
    )
    Box(
        modifier = modifier
            .background(
                cs.onSurface.copy(alpha = strength * progress),
                RoundedCornerShape(radius),
            )
            .then(
                if (onTap == null) Modifier else Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (haptics.onCardTap) Haptics.soft(view)
                        onTap()
                    },
                ),
            ),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// ThinkingSheen (thinking_sheen.dart 呼吸高光 — srcIn 渐变扫过)
// ---------------------------------------------------------------------------

/**
 * 呼吸高光修饰符：base→peak 的斜向渐变随 progress 从左向右扫过内容
 * （thinking_sheen.dart ShaderMask srcIn + _SlideGradientTransform）。
 * speed 1.05 / spread 0.52 / intensity 0.68 与 thinkingSheenDefaults 一致。
 *
 * 改版后它就是 ZCode「正在思考」`animated-gradient-text` 的等价物：流式标签
 * 的高光动画。
 */
@Composable
fun Modifier.thinkingSheen(color: Color, isDark: Boolean, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "thinking-sheen")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2286, easing = LinearEasing), // 2400/1.05
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheen-progress",
    )
    return this.then(
        Modifier.graphicsLayerOffscreen().drawWithContent {
            val base = color.copy(alpha = 1f)
            val highlight = lerp(base, Color.White, if (isDark) 0.82f else 0.78f)
            val peak = lerp(base, highlight, 0.42f + 0.68f * 0.58f)
            val mid = lerp(base, peak, 0.5f)
            val outer = (0.52f.coerceIn(0.2f, 0.9f)) / 2 // 0.26
            val inner = outer * 0.34f
            val stops = floatArrayOf(
                0f,
                (0.5f - outer).coerceIn(0.02f, 0.42f),
                (0.5f - inner).coerceIn(0.16f, 0.48f),
                0.5f,
                (0.5f + inner).coerceIn(0.52f, 0.84f),
                (0.5f + outer).coerceIn(0.58f, 0.98f),
                1f,
            )
            val colors = listOf(base, base, mid, peak, mid, base, base)
            // 源码 _SlideGradientTransform：x 平移 width*(t*2-1)。
            val tx = size.width * (progress * 2f - 1f)
            val brush = Brush.linearGradient(
                *stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(),
                start = Offset(tx, -0.18f * size.height),
                end = Offset(tx + size.width, 0.18f * size.height),
            )
            drawContent()
            drawRect(brush, blendMode = BlendMode.SrcIn)
        },
    )
}

/**
 * Flutter `ShaderMask` + 纵向 alpha 渐变（dstIn）：上 [top]、下 [bottom] 高度内
 * 渐隐出画。Compose 需要离屏合成层才能让 DstIn 只作用于本节点内容。
 */
fun Modifier.fadeTopBottom(top: Dp, bottom: Dp): Modifier =
    this
        .graphicsLayerOffscreen()
        .drawWithContent {
            val topStop = (top.toPx() / size.height).coerceIn(0f, 1f)
            val bottomStop = (1f - bottom.toPx() / size.height).coerceIn(0f, 1f)
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    topStop to Color.White,
                    bottomStop to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** 水平两端渐隐（ZCode 思考摘要行的边缘 mask 等价物，[width] 每侧渐隐带）。 */
fun Modifier.fadeLeftRight(width: Dp): Modifier =
    this
        .graphicsLayerOffscreen()
        .drawWithContent {
            val stop = (width.toPx() / size.width).coerceIn(0f, 0.5f)
            drawContent()
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    stop to Color.White,
                    (1f - stop) to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

private fun Modifier.graphicsLayerOffscreen(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/** AnimatedSize(duration 300ms, curve easeInOutCubicEmphasized, alignment topLeft) — CMW:4672。 */
fun Modifier.animateContentSizeCard(): Modifier = animateContentSize(
    animationSpec = tween(durationMillis = 300, easing = EaseInOutCubicEmphasized),
)

/**
 * Flutter `Curves.easeInOutCubicEmphasized`（curves.dart:1772 `ThreePointCubic`）。
 * 两段三次贝塞尔共用中点 (0.166666, 0.4)，各自归一化到所属区间后拼接。
 */
private val EmphasizedIn = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val EmphasizedOut = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

val EaseInOutCubicEmphasized: Easing = Easing { t ->
    val midX = 0.166666f
    val midY = 0.4f
    if (t < midX) {
        EmphasizedIn.transform(t / midX) * midY
    } else {
        EmphasizedOut.transform((t - midX) / (1f - midX)) * (1f - midY) + midY
    }
}
