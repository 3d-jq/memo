package com.psyche.memo.ui.slider

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn

/**
 * Memo 自己的 slider —— 取代 M3 原生 `Slider`（用户 2026-09-16：「很多界面都是用的 m3 那个
 * 原生 slider 不好看 我们直接自定义个好看的吧」）。
 *
 * **外观照「推理强度」那根滑条**：与 `ui/chat/ReasoningBudgetSheet.kt` 的 `EffortSlider` /
 * `drawEffortSlider`（= Flutter 原版 `_EffortSlider` + `_SliderPainter`）逐项同值：
 * - 轨道 **34dp 药丸**，底色 `onSurface@10%`；
 * - 已选段 = **`primary` 50% → 100% 横向渐变**，裁剪在药丸内、画到圆钮中心；
 * - **白色大圆钮**：直径 38dp、柔和投影（blur 10dp / offset y 3dp / 黑 25%），拖动时放大
 *   1.14（180ms `EaseOutBack`）；
 * - 两端各内缩一个圆钮半径（`EffortSlider` 的 `stopInset`），极值处圆钮与轨道齐平。
 *
 * **有意偏离原版：本控件是真正无级的**（用户 2026-09-16「改成不分级 就是真实无极滑动那种
 * 原项目这个有分级这个不好用 改成无极调节」）。原版（以及我们最早的移植）处处带 `stepSize`
 * 并在回调里取整，拖起来一格一格；现在控件**没有 `steps`、不吸附**，值就是手指位置；
 * 档位点也不画了（没有档位就没有档位点）。需要「档位感」的地方请由调用方自己取整 ——
 * 但按用户要求，Memo 里所有 slider 都不取整了（码表/整数类型的值除外，那是数据本身的粒度）。
 *
 * 手势：**单一 `awaitEachGesture` 循环** —— 按下即跟手、逐帧上报（无极滑动）、横向累计越过
 * `touchSlop` 后才 `consume`、纵向累计超 slop 且大于横向则让位给外面的滚动并**回滚**数值、
 * 抬手统一回调 `onValueChangeFinished`。`enabled = false` 时整体 38% 透明且不吃手势；
 * `semantics` 的 `progressBarRangeInfo` / `setProgress` 照给（无障碍与键盘可用）。
 */
@Composable
fun MemoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * 拖动时胶囊里的文案。`null` = 不显示胶囊（行内已有常显数值时可以用来避免重复）。
     */
    valueLabel: ((Float) -> String)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl

    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    val start = valueRange.start
    val end = valueRange.endInclusive
    val coerced = value.coerceIn(start, end)
    val fraction = sliderFractionForValue(coerced, start, end)

    val thumbRadiusPx = with(density) { THUMB_DP.dp.toPx() } / 2f
    val trackCenterYPx = with(density) { (CAPSULE_ZONE_DP + TRACK_ZONE_DP / 2f).dp.toPx() }
    // 拖动时圆钮放大 1.14（180ms EaseOutBack）—— 推理强度滑条同款反馈。
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) THUMB_DRAG_SCALE else 1f,
        animationSpec = tween(durationMillis = THUMB_SCALE_MS, easing = EaseOutBack),
        label = "memoSliderThumbScale",
    )

    val activeColor = cs.primary
    val trackColor = cs.onSurface.copy(alpha = TRACK_ALPHA)

    fun report(x: Float) {
        if (!enabled) return
        onValueChange(
            sliderValueForPosition(
                x = x,
                trackWidthPx = widthPx,
                thumbRadiusPx = thumbRadiusPx,
                start = start,
                end = end,
                rtl = rtl,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SLIDER_HEIGHT_DP.dp)
            .testTag(MEMO_SLIDER_TAG)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = DISABLED_ALPHA })
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(coerced, start..end, 0)
                if (enabled) {
                    setProgress { target ->
                        onValueChange(target.coerceIn(start, end))
                        true
                    }
                } else {
                    disabled()
                }
            }
            // 手势仲裁照 Android 的习惯：**谁先过 slop 谁赢**。
            //   ① 按下只让圆钮放大 / 显示胶囊，**不改值** —— 曾经「按下即改值 + 纵向偏移就回滚」
            //      在真机上会「一按就跳、手指划弧就跳回来」，观感就是拖不动（用户 2026-09-16
            //      连报两次「不能随意左右滑动」）；
            //   ② 横向先过 `touchSlop` → 判定为拖动：先把圆钮落到按下点，再逐帧跟手（无极）；
            //   ③ 纵向先过 `touchSlop` → 让位给外面的滚动容器，**全程一个值都不改**（不回滚，
            //      所以没有跳变）；
            //   ④ 一直没定方向就抬手 = 点按 → 值落到按下的位置（M3 同款）；
            //   ⑤ 抬手统一回调 `onValueChangeFinished`。
            // **key 只放会改变手势语义的量**（enabled / 范围 / 方向）：宽度绝不能进 key ——
            // 拖动时旁边那个「当前值」文本会随值变宽变窄，滑条宽度跟着变，key 一变 Compose 就
            // 重启这段手势（= 取消进行中的拖动）。宽度/半径在 lambda 里现读（`widthPx` 是 state，
            // `onSizeChanged` 写入；`thumbRadiusPx` 只随 density 变）。
            .pointerInput(enabled, start, end, rtl) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val downY = down.position.y
                    var resolved = false
                    var cancelled = false
                    dragging = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) {
                            // 被父级（sheet 拖拽 / 列表滚动）拿走 → 让位；值没改过，无需回滚。
                            cancelled = true
                            break
                        }
                        if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                        val dx = change.position.x - downX
                        val dy = change.position.y - downY
                        if (!resolved) {
                            val horizontalSlop = abs(dx) >= viewConfiguration.touchSlop
                            val verticalSlop = abs(dy) >= viewConfiguration.touchSlop
                            if (horizontalSlop && (!verticalSlop || abs(dx) >= abs(dy))) {
                                resolved = true
                                report(downX)
                            } else if (verticalSlop) {
                                cancelled = true
                                break
                            }
                        }
                        if (resolved) {
                            change.consume()
                            report(change.position.x)
                        }
                    }
                    dragging = false
                    if (!resolved && !cancelled) report(downX)
                    onValueChangeFinished?.invoke()
                }
            },
    ) {
        val travel = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(0f)
        val thumbCenterX = thumbRadiusPx + travel * fraction

        Canvas(modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT_DP.dp)) {
            // 轨道 + 渐变填充 + 圆钮 —— 画法与数值照推理强度滑条（`EffortSlider` /
            // Flutter 原版 `_SliderPainter`）；**不画档位点**（本控件无级，没有档位）。
            drawMemoSlider(
                centerY = trackCenterYPx,
                thumbCenterX = thumbCenterX,
                thumbRadiusPx = thumbRadiusPx,
                thumbScale = thumbScale,
                primary = activeColor,
                trackColor = trackColor,
                // 圆钮固定纯白（原版 `_SliderPainter` 就是 `Color.White`）：暗色主题下
                // `cs.surface` 是深色，会把圆钮糊在轨道里。
                thumbColor = Color.White,
            )
        }

        // 拖动时的数值胶囊：贴在圆钮正上方，左右不出界（`BiasAlignment` 天然把溢出的量
        // 收回来，和标签行用的是同一招）。
        if (dragging && valueLabel != null) {
            Box(
                modifier = Modifier
                    .align(
                        BiasAlignment(
                            horizontalBias = (fraction * 2f - 1f).coerceIn(-1f, 1f),
                            verticalBias = -1f,
                        ),
                    )
                    .height(CAPSULE_HEIGHT_DP.dp)
                    .background(activeColor, RoundedCornerShape(CAPSULE_HEIGHT_DP.dp / 2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = valueLabel(coerced),
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onPrimary,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }

        // 圆钮本身画在 Canvas 里（白色 + 柔和投影，与推理强度滑条同一套），这里只留一个
        // 尺寸/位置的**不可见锚点**：几何断言与无障碍都靠它（`EffortSlider` 同款做法）。
        // `testTag` 放在 `offset` **之后**：语义节点报的是它左边那段的布局结果，挂在
        // offset 前面会报未偏移的位置（`MemoSliderUiTest` 的几何断言就量错过）。
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (thumbCenterX - thumbRadiusPx).roundToInt(),
                        (trackCenterYPx - thumbRadiusPx).roundToInt(),
                    )
                }
                .size(THUMB_DP.dp)
                .testTag(MEMO_SLIDER_THUMB_TAG),
        )
    }
}

/**
 * 轨道 + 渐变填充 + 档位圆点 + 圆钮 —— **照推理强度滑条**（`ui/chat/ReasoningBudgetSheet.kt` 的
 * `EffortSlider`/`drawEffortSlider`，即 Flutter 原版 `_SliderPainter`）的画法与数值：
 * 34dp 药丸轨道、`primary` 50%→100% 的横向渐变填充（裁剪在药丸内、画到圆钮中心）、
 * 每个档位一颗 3.5dp 圆点（已过的转白、未到的 `onSurface@25%`）、
 * 白色大圆钮（直径 38dp、柔和投影 blur 10 / dy 3 / 黑 25%、拖动放大 1.14）。
 */
private fun DrawScope.drawMemoSlider(
    centerY: Float,
    thumbCenterX: Float,
    thumbRadiusPx: Float,
    thumbScale: Float,
    primary: Color,
    trackColor: Color,
    thumbColor: Color,
) {
    val trackHeightPx = TRACK_HEIGHT_DP.dp.toPx()
    val trackRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
    val top = centerY - trackHeightPx / 2f
    val trackRect = Rect(0f, top, size.width, top + trackHeightPx)

    drawRoundRect(
        color = trackColor,
        topLeft = trackRect.topLeft,
        size = trackRect.size,
        cornerRadius = trackRadius,
    )

    val thumbX = thumbCenterX.coerceIn(0f, size.width)
    if (thumbX > 0f) {
        val brush = Brush.horizontalGradient(
            colors = listOf(primary.copy(alpha = 0.5f), primary),
            startX = 0f,
            endX = thumbX,
        )
        // 填充必须贴轨道矩形（从 Canvas 顶部画会被裁成上半截细条 —— `EffortSlider` 踩过）。
        clipPath(Path().apply { addRoundRect(RoundRect(rect = trackRect, cornerRadius = trackRadius)) }) {
            drawRect(
                brush = brush,
                topLeft = trackRect.topLeft,
                size = Size(thumbX, trackHeightPx),
            )
        }
    }

    // 圆钮：白色 + 柔和投影（blur 10dp / offset y 3dp / 黑 25%，与推理强度滑条同一套）。
    val radiusPx = thumbRadiusPx * thumbScale
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb((0.25f * 255).toInt(), 0, 0, 0)
            setMaskFilter(
                android.graphics.BlurMaskFilter(THUMB_SHADOW_BLUR_DP.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL),
            )
        }
        canvas.nativeCanvas.drawCircle(thumbX, centerY + THUMB_SHADOW_DY_DP.dp.toPx(), radiusPx, paint)
    }
    drawCircle(color = thumbColor, radius = radiusPx, center = Offset(thumbX, centerY))
}

// ---------------------------------------------------------------------------
// 几何（纯函数，`MemoSliderTest` 直接钉住）
// ---------------------------------------------------------------------------

/** 值 → 0..1 位置比例；空区间（`start == end`）一律 0。 */
internal fun sliderFractionForValue(value: Float, start: Float, end: Float): Float {
    val span = end - start
    if (span <= 0f) return 0f
    return ((value - start) / span).coerceIn(0f, 1f)
}

/**
 * 手指 x（相对控件左边）→ 值。**不吸附**（本控件无级，用户 2026-09-16「改成不分级 就是真实
 * 无极滑动那种」）。圆钮圆心只在 `[thumbRadius, width - thumbRadius]` 之间移动，所以「行程」
 * 要扣掉两端各一个半径（否则手指到不了两端）。
 */
internal fun sliderValueForPosition(
    x: Float,
    trackWidthPx: Float,
    thumbRadiusPx: Float,
    start: Float,
    end: Float,
    rtl: Boolean = false,
): Float {
    val travel = trackWidthPx - thumbRadiusPx * 2f
    if (travel <= 0f) return start
    val fraction = ((x - thumbRadiusPx) / travel).coerceIn(0f, 1f)
    val directed = if (rtl) 1f - fraction else fraction
    return (start + directed * (end - start)).coerceIn(start, end)
}

/**
 * 几何/配色常量 —— **取值与推理强度滑条（`EffortSlider`）一致**：
 * 控件总高 = 胶囊区 18 + 轨道区 38（38dp 圆钮正好填满轨道区，圆钮顶边落在 18dp）；
 * 轨道高 34、圆钮直径 38、档位点半径 3.5、投影 blur 10 / dy 3、拖动放大 1.14。
 */
private const val SLIDER_HEIGHT_DP = 56
private const val TRACK_ZONE_DP = 38
private const val CAPSULE_ZONE_DP = 18
private const val CAPSULE_HEIGHT_DP = 18
private const val TRACK_HEIGHT_DP = 34
private const val THUMB_DP = 38
private const val DOT_RADIUS_DP = 3.5f
private const val TRACK_ALPHA = 0.10f
private const val THUMB_SHADOW_BLUR_DP = 10
private const val THUMB_SHADOW_DY_DP = 3
private const val THUMB_DRAG_SCALE = 1.14f
private const val THUMB_SCALE_MS = 180
private const val DISABLED_ALPHA = 0.38f

/**
 * 圆钮的测试锚点 —— `MemoSliderUiTest` 用它断言几何（圆钮 38dp、顶边 18dp、控件总高 56dp）。
 * 与 `VOICE_WAVEFORM_TAG` 同一个用途：把「看着对」变成「量得出来」。
 */
const val MEMO_SLIDER_THUMB_TAG = "memo_slider_thumb"

/** 整块滑块的测试锚点（手势测试从轨道任意处按下用）。 */
const val MEMO_SLIDER_TAG = "memo_slider"

/**
 * 与 slider 同一行的「当前值」文本 —— **按最宽文案预留宽度**。
 *
 * 为什么需要它：`Row { Text(当前值) … MemoSlider(Modifier.weight(1f)) }` 这种排法里，数值一变宽
 * （"9" → "100"、"50%" → "100%"），`weight(1f)` 的 slider 就被挤短，于是拖动时**滑条本身跟着变长
 * 变短**、圆钮在手指下的位置也跟着漂（用户 2026-09-16：「左右数字会变 导致这个 slider 也会变
 * 这个不太好 有解决方法吗？」）。
 *
 * 用法：把**可能出现的最大数值文案**作为 [widest] 传进来（例如范围上限的格式化结果），
 * 本控件把它量成最小宽度；文案再变也不会撑动同排的 slider。真的更宽时（[widest] 传小了）
 * 它仍会正常长大，不会裁剪。
 */
@Composable
fun SliderValueLabel(
    text: String,
    widest: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    /** 胶囊那种居中的容器传 `Alignment.Center` + `TextAlign.Center`。 */
    contentAlignment: Alignment = Alignment.CenterEnd,
    textAlign: TextAlign = TextAlign.End,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val widestWidthPx = remember(widest, style, density) {
        measurer.measure(
            text = widest,
            style = style,
            maxLines = 1,
            softWrap = false,
        ).size.width
    }
    Box(
        modifier = modifier.widthIn(min = with(density) { widestWidthPx.toDp() }),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
        )
    }
}
