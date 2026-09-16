package com.psyche.memo.ui.slider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MemoSlider` 的几何纯逻辑。**本控件是无级的**（用户 2026-09-16「改成不分级 就是真实无极滑动
 * 那种 原项目这个有分级这个不好用 改成无极调节」），所以这里钉的是「值 = 手指位置，不吸附」。
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

    /** 手指位置：圆钮圆心从 `radius` 走到 `width - radius`，两端正好是 range 的端点。 */
    @Test
    fun positionMapsThumbTravelToRange() {
        val start = 2f
        val end = 64f
        val radius = 10f
        val width = 210f // 行程 = 190
        assertEquals(start, sliderValueForPosition(radius, width, radius, start, end), eps)
        assertEquals(end, sliderValueForPosition(width - radius, width, radius, start, end), eps)
        assertEquals(
            (start + end) / 2f,
            sliderValueForPosition(width / 2f, width, radius, start, end),
            eps,
        )
        // 控件还没量出宽度（第一帧）时回落到 range 起点，不崩、不跳。
        assertEquals(start, sliderValueForPosition(50f, 0f, radius, start, end), eps)
    }

    /**
     * **无极**：手指停在哪儿就取哪儿，不做任何量化 —— 0.4947 这种「不在任何档位上」的值必须
     * 原样拿到（换成带 stepSize 的旧实现会被吸到 0.5）。
     */
    @Test
    fun valuesAreNeverSnapped() {
        val width = 210f
        val radius = 10f
        val value = sliderValueForPosition(104f, width, radius, 0f, 1f)
        assertEquals(94f / 190f, value, eps)
        assertTrue("不能落在 0.1 的档位上", kotlin.math.abs(value - 0.5f) > 1e-3f)

        // 温度那种 0..2 的区间同样连续。
        val temperature = sliderValueForPosition(123f, 260f, radius, 0f, 2f)
        assertEquals((123f - radius) / (260f - 2f * radius) * 2f, temperature, eps)
    }

    /** RTL 下方向镜像（值随手指向左移动而变大）。 */
    @Test
    fun positionRespectsRtl() {
        val radius = 10f
        val width = 210f
        assertEquals(0f, sliderValueForPosition(radius, width, radius, 0f, 100f, rtl = false), eps)
        assertEquals(100f, sliderValueForPosition(radius, width, radius, 0f, 100f, rtl = true), eps)
    }
}
