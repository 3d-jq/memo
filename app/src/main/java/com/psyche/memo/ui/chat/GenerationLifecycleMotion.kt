package com.psyche.memo.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 内容块入场时长（ms）—— 照 Agora `SEGMENT_ENTER_DURATION_MS`。 */
internal const val SEGMENT_ENTER_DURATION_MS = 420

/** 内容块入场的初始 scale —— 照 Agora `SEGMENT_ENTER_INITIAL_SCALE`。 */
internal const val SEGMENT_ENTER_INITIAL_SCALE = 0.90f

/**
 * 内容块的**渐显**入场（移植 Agora `GenerationLifecycleMotion.kt` 的
 * `generationLifecycleAppearanceModifier`）。
 *
 * Agora 注释原话：「Content occupies its final measured size from the first frame. The animation
 * therefore cannot resize a LazyColumn item, invalidate Markdown layout, or compete with scroll
 * positioning.」—— 动画**只拥有一层绘制**（alpha + scale），内容从第一帧起就占最终测量尺寸，
 * 所以它不会改变 LazyColumn 项的高度、不会让 Markdown 重排、也不会和贴底跟随抢位置。
 *
 * [animationKey] 是**块的稳定键**：同一个块无论内容怎么增长，key 不变 ⇒ 渐显只在
 * **第一次出现**时播一次，之后不再重播（这正是丝滑的关键）。
 */
@Composable
internal fun generationAppearanceModifier(
    animationKey: String,
    animate: Boolean,
): Modifier {
    val progress = remember(animationKey) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animationKey) {
        if (animate) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = SEGMENT_ENTER_DURATION_MS, easing = LinearEasing),
            )
        }
    }
    return Modifier.graphicsLayer {
        val value = progress.value.coerceIn(0f, 1f)
        alpha = value
        val scaleProgress = LinearOutSlowInEasing.transform(value)
        val scale = SEGMENT_ENTER_INITIAL_SCALE + (1f - SEGMENT_ENTER_INITIAL_SCALE) * scaleProgress
        scaleX = scale
        scaleY = scale
    }
}
