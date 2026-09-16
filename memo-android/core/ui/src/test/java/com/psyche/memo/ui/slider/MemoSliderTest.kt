package com.psyche.memo.ui.slider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MemoSlider` 的两条纯逻辑：**吸附**（M3 `steps` 语义）与**手指位置 → 值**（圆钮行程要扣掉
 * 两端半径）。用户 2026-09-16 拍板用自定义 slider 取代 M3 原生控件，这里钉住「换皮肤不改
 * 取值行为」——10 处调用点的吸附点必须与原来的 M3 `Slider` 完全一致。
 */
class MemoSliderTest {

    private val eps = 0.0001f

    @Test
    fun fractionIsClampedAndSafeForEmptyRange() {
        assertEquals(0f, sliderFractionForValue(0f, 10f, 10f), eps)
        assertEquals(0f, sliderFractionForValue(-5f, 0f, 100f), eps)
        assertEquals(0.5f, sliderFractionForValue(50f, 0f, 100f), eps)
        assertEquals(1f, sliderFractionForValue(500f, 0f, 100f), eps)
    }

    /** `steps = 0` 连续：任何值原样（只夹到 range 内）。 */
    @Test
    fun zeroStepsKeepsContinuousValues() {
        assertEquals(37.5f, sliderSnapValue(37.5f, 0f, 100f, 0), eps)
        assertEquals(0f, sliderSnapValue(-3f, 0f, 100f, 0), eps)
        assertEquals(100f, sliderSnapValue(103f, 0f, 100f, 0), eps)
    }

    /**
     * `steps > 0`：落在 `steps + 1` 等分点上 —— 这正是 `SliderTile` 传
     * `steps = divisions - 1` 时 M3 的行为（divisions 段）。
     */
    @Test
    fun stepsSnapToEvenDivisions() {
        // 0..1，divisions = 10（steps = 9）→ 0.1 的倍数，与温度 sheet 一致。
        assertEquals(0.3f, sliderSnapValue(0.34f, 0f, 1f, 9), eps)
        assertEquals(0.4f, sliderSnapValue(0.36f, 0f, 1f, 9), eps)
        // 0..64，steps = 31（divisions = 32）→ 2 的倍数。
        assertEquals(8f, sliderSnapValue(8.9f, 0f, 64f, 31), eps)
        // 端点必须可达（吸到端点上不能越界）。
        assertEquals(100f, sliderSnapValue(99.9f, 0f, 100f, 3), eps)
        assertEquals(0f, sliderSnapValue(0.1f, 0f, 100f, 3), eps)
    }

    /** 手指位置：圆钮圆心从 `radius` 走到 `width - radius`，两端正好是 range 的端点。 */
    @Test
    fun positionMapsThumbTravelToRange() {
        val start = 2f
        val end = 64f
        val radius = 10f
        val width = 210f // 行程 = 190
        assertEquals(start, sliderValueForPosition(radius, width, radius, start, end, 0), eps)
        assertEquals(end, sliderValueForPosition(width - radius, width, radius, start, end, 0), eps)
        assertEquals(
            (start + end) / 2f,
            sliderValueForPosition(width / 2f, width, radius, start, end, 0),
            eps,
        )
        // 控件还没量出宽度（第一帧）时回落到 range 起点，不崩、不跳。
        assertEquals(start, sliderValueForPosition(50f, 0f, radius, start, end, 0), eps)
    }

    /** RTL 下方向镜像（值随手指向左移动而变大）。 */
    @Test
    fun positionRespectsRtl() {
        val radius = 10f
        val width = 210f
        val ltr = sliderValueForPosition(radius, width, radius, 0f, 100f, 0, rtl = false)
        val rtl = sliderValueForPosition(radius, width, radius, 0f, 100f, 0, rtl = true)
        assertEquals(0f, ltr, eps)
        assertEquals(100f, rtl, eps)
    }

    /** 位置 → 值这条链上吸附也要生效（拖动到位后会落在刻度点上）。 */
    @Test
    fun positionSnapsWhenStepsAreGiven() {
        val value = sliderValueForPosition(
            x = 105f,
            trackWidthPx = 210f,
            thumbRadiusPx = 10f,
            start = 0f,
            end = 1f,
            steps = 9,
        )
        assertEquals(0.5f, value, eps)
        assertTrue(value in 0f..1f)
    }
}
