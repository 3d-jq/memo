package com.psyche.memo.ui.slider

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.roundToInt

/**
 * Memo 自己的 slider —— 取代 M3 原生 `Slider`（用户 2026-09-16：「很多界面都是用的 m3 那个
 * 原生 slider 不好看 我们直接自定义个好看的吧」）。
 *
 * **取值来自 Flutter 原版的 `SfSliderThemeData`**（原版整套设置页用的都是 Syncfusion
 * `SfSlider`：显示设置 4 处、图片处理、消息样式、助手参数 sheet、渲染页，与我们这 10 处
 * 调用点一一对应）：
 * - 轨道高 **8dp**（原版 `activeTrackHeight`/`inactiveTrackHeight` = 8），全圆角；
 *   已选段 `primary`，未选段 `onSurface` 暗色 25% / 亮色 20%（原版同值）；
 * - 圆钮 **20dp** 实心 `primary`，**亮色**主题带一层柔和投影（原版 `thumbIcon` 的
 *   `BoxShadow`：shadow 8%、blur 8、偏移 (0,2)）；暗色不带投影（原版同样是空列表）；
 * - 按下时的光晕半径 14（原版 `overlayRadius`）。
 *
 * **与用户当面确认过的一处简化**：**不画刻度与刻度数字**（原版 `showTicks: true` /
 * `showLabels: true`），拖动时用一枚跟随圆钮的胶囊显示当前值（原版这里是
 * `SfPaddleTooltipShape` 水滴气泡 —— 同一信息、更简洁的形态）。所以本控件不是「原版 1:1」，
 * 而是**按原版取值、按用户选择收形**（记在 PORTING §5.11）。
 *
 * 行为与 M3 `Slider` 对齐（drop-in）：点轨道即跳到该处、横向拖动、`steps` 吸附
 * （`steps = 0` 连续；`steps > 0` 落在 `steps + 1` 等分点上，M3 同义）、抬手时
 * `onValueChangeFinished`、`enabled = false` 时整体 38% 透明且不吃手势。纵向手势不受影响
 * （拖动只认横向 slop）。
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
    val isDark = LocalSemanticColors.current.isDark
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
    val haloRadiusPx = with(density) { HALO_RADIUS_DP.dp.toPx() }
    val trackCenterYPx = with(density) { (CAPSULE_ZONE_DP + TRACK_ZONE_DP / 2f).dp.toPx() }

    val activeColor = cs.primary
    val inactiveColor = cs.onSurface.copy(alpha = if (isDark) 0.25f else 0.20f)

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
            // 拖动只认横向 slop：纵向手势（列表/抽屉）不受影响。
            .pointerInput(enabled, start, end, steps, widthPx, thumbRadiusPx, rtl) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        report(offset.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        report(change.position.x)
                    },
                    onDragEnd = {
                        dragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        dragging = false
                        onValueChangeFinished?.invoke()
                    },
                )
            }
            // 点轨道直接跳过去（M3 Slider 同款；与拖动分开两个 pointerInput 是 Compose 官方做法）。
            .pointerInput(enabled, start, end, steps, widthPx, thumbRadiusPx, rtl) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    report(offset.x)
                    onValueChangeFinished?.invoke()
                }
            },
    ) {
        val travel = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(0f)
        val thumbCenterX = thumbRadiusPx + travel * fraction

        Canvas(modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT_DP.dp)) {
            drawMemoTrack(
                centerY = trackCenterYPx,
                thumbCenterX = thumbCenterX,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
            )
            if (dragging) {
                drawCircle(
                    color = activeColor.copy(alpha = PRESS_HALO_ALPHA),
                    radius = haloRadiusPx,
                    center = Offset(thumbCenterX, trackCenterYPx),
                )
            }
        }

        // 拖动时的数值胶囊（原版水滴 tooltip 的简洁形态）：贴在圆钮正上方，左右不出界
        // （`BiasAlignment` 天然把溢出的量收回来，和标签行用的是同一招）。
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onPrimary,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }

        // 圆钮：亮色主题带一层柔和投影（原版 thumbIcon 的 BoxShadow）。
        // `testTag` 放在 `offset` **之后**：语义节点报的是它左边那段的布局结果，挂在
        // offset 前面会报未偏移的位置（`MemoSliderUiTest` 的几何断言就量错了）。
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (thumbCenterX - thumbRadiusPx).roundToInt(),
                        (trackCenterYPx - thumbRadiusPx).roundToInt(),
                    )
                }
                .size(THUMB_DP.dp)
                .testTag(MEMO_SLIDER_THUMB_TAG)
                .then(
                    if (isDark) {
                        Modifier
                    } else {
                        // 原版圆钮的 `BoxShadow(color: cs.shadow @8%, blur 8, offset (0,2))`。
                        // 本工程这版 Compose 的 `ColorScheme` **没有 `shadow`**（同
                        // `ChatAssistantBackground.kt:35` 的记载：用近黑等价，原版 shadow 即近黑）。
                        Modifier.shadow(
                            elevation = 8.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = MEMO_THUMB_SHADOW,
                            spotColor = MEMO_THUMB_SHADOW,
                        )
                    },
                )
                .background(activeColor, CircleShape),
        )
    }
}

/** 轨道 + 已选段：8dp 全圆角，画在轨道区的垂直中心。 */
private fun DrawScope.drawMemoTrack(
    centerY: Float,
    thumbCenterX: Float,
    activeColor: Color,
    inactiveColor: Color,
) {
    val trackHeightPx = TRACK_HEIGHT_DP.dp.toPx()
    val corner = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)
    val top = centerY - trackHeightPx / 2f
    drawRoundRect(
        color = inactiveColor,
        topLeft = Offset(0f, top),
        size = Size(size.width, trackHeightPx),
        cornerRadius = corner,
    )
    val activeWidth = thumbCenterX.coerceIn(0f, size.width)
    if (activeWidth > 0f) {
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(0f, top),
            size = Size(activeWidth, trackHeightPx),
            cornerRadius = corner,
        )
    }
}

// ---------------------------------------------------------------------------
// 几何与吸附（纯函数，`MemoSliderTest` 直接钉住）
// ---------------------------------------------------------------------------

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

/** 控件总高 = 胶囊区 20 + 轨道区 30（20dp 圆钮、28dp 光晕都装得下）。 */
private const val SLIDER_HEIGHT_DP = 50
private const val TRACK_ZONE_DP = 30
private const val CAPSULE_ZONE_DP = 20
private const val CAPSULE_HEIGHT_DP = 20
private const val TRACK_HEIGHT_DP = 8
private const val THUMB_DP = 20
private const val HALO_RADIUS_DP = 14
private const val PRESS_HALO_ALPHA = 0.12f
private const val DISABLED_ALPHA = 0.38f

/** 圆钮投影色（近黑 8%，见 `MemoSlider` 里的说明）。 */
private val MEMO_THUMB_SHADOW = Color.Black.copy(alpha = 0.08f)

/**
 * 圆钮的测试锚点 —— `MemoSliderUiTest` 用它断言几何（圆钮 20dp、圆心落在轨道中心线 35dp、
 * 控件总高 50dp）。与 `VOICE_WAVEFORM_TAG` 同一个用途：把「看着对」变成「量得出来」。
 */
const val MEMO_SLIDER_THUMB_TAG = "memo_slider_thumb"
