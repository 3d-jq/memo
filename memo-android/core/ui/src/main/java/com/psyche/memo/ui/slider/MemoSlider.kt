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

/**
 * Memo 自己的 slider —— 取代 M3 原生 `Slider`（用户 2026-09-16：「很多界面都是用的 m3 那个
 * 原生 slider 不好看 我们直接自定义个好看的吧」）。
 *
 * **外观照「推理强度」那根滑条**（用户 2026-09-16：「因为这个效果是分级的对吧 要不改成推理
 * 强度那种 slider 吧」）：与 `ui/chat/ReasoningBudgetSheet.kt` 的 `EffortSlider` /
 * `drawEffortSlider`（= Flutter 原版 `_EffortSlider` + `_SliderPainter`）逐项同值：
 * - 轨道 **34dp 药丸**，底色 `onSurface@10%`；
 * - 已选段 = **`primary` 50% → 100% 横向渐变**，裁剪在药丸内、画到圆钮中心；
 * - **档位圆点**：`steps + 1` 颗（`steps = 0` 时没有），半径 3.5dp，已过的转白（90%）、
 *   未到的 `onSurface@25%` —— 「分级」因此看得见；
 * - **白色大圆钮**：直径 38dp、柔和投影（blur 10dp / offset y 3dp / 黑 25%），拖动时放大
 *   1.14（180ms `EaseOutBack`）；
 * - 两端档位相对轨道各内缩一个圆钮半径（`EffortSlider` 的 `stopInset`），极值处圆钮与轨道齐平。
 *
 * 用户先前选过「简洁款、不画刻度」；现在要的是**看得见档位**，于是档位以圆点呈现（而不是原版
 * SfSlider 的刻度线 + 刻度数字）。取值语义仍与原版一致：`steps` = M3 `Slider.steps`，
 * 落在 `steps + 1` 等分点上（等价于原版各处 `stepSize`）。
 *
 * 手势：**单一 `awaitEachGesture` 循环** —— 按下即跟手（SfSlider 同款）、逐帧上报（无级滑动）、
 * 横向累计越过 `touchSlop` 后才 `consume`、纵向累计超 slop 且大于横向则让位给外面的滚动并
 * **回滚**数值、抬手统一回调 `onValueChangeFinished`。`enabled = false` 时整体 38% 透明且不吃
 * 手势；`semantics` 的 `progressBarRangeInfo` / `setProgress` 照给（无障碍与键盘可用）。
 */
@Composable
fun MemoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * 拖动时胶囊里的文案（按**吸附后**的值生成）。`null` = 不显示胶囊（行内已有常显数值时
     * 可以用来避免重复）。
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
    // 手势循环里要读**按下那一刻**的值（取消时回滚用），用 updatedState 免得把值塞进
    // pointerInput 的 key（那会每帧重启手势、拖动直接断掉）。
    val currentValue by rememberUpdatedState(coerced)
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
    val dotFractions = remember(steps) { sliderDotFractions(steps) }

    fun report(x: Float) {
        if (!enabled) return
        onValueChange(
            sliderValueForPosition(
                x = x,
                trackWidthPx = widthPx,
                thumbRadiusPx = thumbRadiusPx,
                start = start,
                end = end,
                steps = steps,
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
                progressBarRangeInfo = ProgressBarRangeInfo(coerced, start..end, steps.coerceAtLeast(0))
                if (enabled) {
                    setProgress { target ->
                        onValueChange(sliderSnapValue(target, start, end, steps))
                        true
                    }
                } else {
                    disabled()
                }
            }
            // 单一手势循环同时管「点」与「拖」——之前拆成 `detectTapGestures` +
            // `detectHorizontalDragGestures` 两个 pointerInput，两者会互相吃事件；后来一版又把
            // slop 判据写成**每帧位移**，真机每帧只走几个像素、永远超不过 `touchSlop`，于是
            // 只有按下那一下会改值（用户 2026-09-16「怎么是靠点击来的呀 是可以无级滑动那种呀」）。
            // 现在：
            //   ① 按下即把圆钮挪到手指处（SfSlider 同款），之后**每一帧都跟手**（无级滑动），
            //      台阶只来自调用方本来就有的 stepSize/取整（与原版一致，不是手势造成的）；
            //   ② slop 用**累计位移**判：纵向累计超过 `touchSlop` 且大于横向 → 让位给外面的
            //      滚动容器，并把值**回滚**到按下前的值；
            //   ③ 横向累计越过 `touchSlop` 后才 `consume`，纵向滚动不受影响；
            //   ④ 抬手统一回调 `onValueChangeFinished`（点按也算一次）。
            .pointerInput(enabled, start, end, steps, widthPx, thumbRadiusPx, rtl) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val valueAtDown = currentValue
                    val startX = down.position.x
                    val startY = down.position.y
                    dragging = true
                    report(startX)
                    var horizontal = false
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) {
                            // 被父级（sheet 拖拽 / 列表滚动）拿走 → 让位并回滚。
                            cancelled = true
                            break
                        }
                        if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                        // 累计位移，不是每帧位移（每帧位移判 slop 是上面那个 bug 的根因）。
                        val totalX = change.position.x - startX
                        val totalY = change.position.y - startY
                        if (!horizontal) {
                            if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) {
                                // 纵向意图：让给外面的滚动容器，值回滚。
                                cancelled = true
                                break
                            }
                            if (abs(totalX) > viewConfiguration.touchSlop) horizontal = true
                        }
                        if (horizontal) change.consume()
                        report(change.position.x)
                    }
                    dragging = false
                    if (cancelled) onValueChange(valueAtDown)
                    onValueChangeFinished?.invoke()
                }
            },
    ) {
        val travel = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(0f)
        val thumbCenterX = thumbRadiusPx + travel * fraction

        Canvas(modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT_DP.dp)) {
            // 轨道 + 渐变填充 + 档位圆点 + 圆钮 —— 全部照推理强度滑条（`EffortSlider` /
            // Flutter 原版 `_SliderPainter`）的画法，逐项取同一组数值。
            drawMemoSlider(
                centerY = trackCenterYPx,
                thumbCenterX = thumbCenterX,
                thumbRadiusPx = thumbRadiusPx,
                thumbScale = thumbScale,
                thumbFraction = fraction,
                dotFractions = dotFractions,
                primary = activeColor,
                trackColor = trackColor,
                upcomingDotColor = cs.onSurface.copy(alpha = UPCOMING_DOT_ALPHA),
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
    thumbFraction: Float,
    dotFractions: List<Float>,
    primary: Color,
    trackColor: Color,
    upcomingDotColor: Color,
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

    if (dotFractions.isNotEmpty()) {
        val dotRadiusPx = DOT_RADIUS_DP.dp.toPx()
        // 档位点相对圆钮行程：两端各内缩一个圆钮半径（`EffortSlider` 的 stopInset）。
        val span = size.width - 2f * thumbRadiusPx
        val passedColor = Color.White.copy(alpha = 0.9f)
        for (dotFraction in dotFractions) {
            val x = thumbRadiusPx + span * dotFraction
            drawCircle(
                color = if (dotFraction <= thumbFraction + 0.0001f) passedColor else upcomingDotColor,
                radius = dotRadiusPx,
                center = Offset(x, centerY),
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
// 几何与吸附（纯函数，`MemoSliderTest` 直接钉住）
// ---------------------------------------------------------------------------

/**
 * 档位圆点的位置（0..1 比例）。
 *
 * - `steps = 0`（无档位，如 TTS 语速）→ 不画点；
 * - 档位少 → **一段一点**，就是「推理强度」那种一眼看出几档的样子（6 档 = 7 颗点）；
 * - 档位多 → 按原版 `_SliderTileNew` 的 `interval` 策略**抽稀到 6–9 颗**（原版是
 *   `total/4`、`total/5`、`total/8` 三档，这里用等价的 `divisions/8` 步长），
 *   免得 32/41 档画成一条虚线；末点离 1 太近时直接并到 1，避免两颗点挤在一起。
 */
internal fun sliderDotFractions(steps: Int): List<Float> {
    if (steps <= 0) return emptyList()
    val divisions = steps + 1
    val stride = maxOf(1, (divisions / 8f).roundToInt())
    val fractions = ArrayList<Float>()
    var i = 0
    while (i <= divisions) {
        fractions += i.toFloat() / divisions
        i += stride
    }
    if (fractions.last() < 1f) {
        val lastGap = 1f - fractions.last()
        if (lastGap < stride / divisions.toFloat() / 2f) {
            fractions[fractions.size - 1] = 1f
        } else {
            fractions += 1f
        }
    }
    return fractions
}

/** 值 → 0..1 位置比例；空区间（`start == end`）一律 0。 */
internal fun sliderFractionForValue(value: Float, start: Float, end: Float): Float {
    val span = end - start
    if (span <= 0f) return 0f
    return ((value - start) / span).coerceIn(0f, 1f)
}

/**
 * 值吸附 —— M3 `Slider.steps` 语义：`steps = 0` 连续；`steps > 0` 落在 range 的
 * `steps + 1` 等分点上（与原版 `_SliderTileNew` 传给 SfSlider 的
 * `stepSize = (max - min) / divisions` 等价）。
 */
internal fun sliderSnapValue(raw: Float, start: Float, end: Float, steps: Int): Float {
    val span = end - start
    if (span <= 0f) return start
    val clamped = raw.coerceIn(start, end)
    if (steps <= 0) return clamped
    val divisions = steps + 1
    val position = ((clamped - start) / span) * divisions
    val snapped = start + position.roundToInt().toFloat() / divisions * span
    return snapped.coerceIn(start, end)
}

/**
 * 手指 x（相对控件左边）→ 吸附后的值。圆钮圆心只在 `[thumbRadius, width - thumbRadius]`
 * 之间移动，所以「行程」要扣掉两端各一个半径（M3 同款）。
 */
internal fun sliderValueForPosition(
    x: Float,
    trackWidthPx: Float,
    thumbRadiusPx: Float,
    start: Float,
    end: Float,
    steps: Int,
    rtl: Boolean = false,
): Float {
    val travel = trackWidthPx - thumbRadiusPx * 2f
    if (travel <= 0f) return sliderSnapValue(start, start, end, steps)
    val fraction = ((x - thumbRadiusPx) / travel).coerceIn(0f, 1f)
    val directed = if (rtl) 1f - fraction else fraction
    return sliderSnapValue(start + directed * (end - start), start, end, steps)
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
private const val UPCOMING_DOT_ALPHA = 0.25f
private const val THUMB_SHADOW_BLUR_DP = 10
private const val THUMB_SHADOW_DY_DP = 3
private const val THUMB_DRAG_SCALE = 1.14f
private const val THUMB_SCALE_MS = 180
private const val DISABLED_ALPHA = 0.38f

/**
 * 圆钮的测试锚点 —— `MemoSliderUiTest` 用它断言几何（圆钮 20dp、圆心落在轨道中心线 35dp、
 * 控件总高 50dp）。与 `VOICE_WAVEFORM_TAG` 同一个用途：把「看着对」变成「量得出来」。
 */
const val MEMO_SLIDER_THUMB_TAG = "memo_slider_thumb"

/** 整块滑块的测试锚点（手势测试从轨道任意处按下用）。 */
const val MEMO_SLIDER_TAG = "memo_slider"
