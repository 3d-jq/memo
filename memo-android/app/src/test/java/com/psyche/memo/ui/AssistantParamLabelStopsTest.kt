package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数 sheet 下方「预设值标签行」的去重叠逻辑（用户 2026-09-16：「助手里面那个上下文消息
 * 这个下面那个数字显示有重叠」）。
 *
 * 原版 `_SliderTileNew` 用 `Align(-1 + t*2)` 按**值**线性摆位，而上下文消息那组档位是
 * 1/64/128/256/512/1024/2048/4096 —— 前四个全在左侧 6.3% 内、512 在 12.5%，11sp 标签
 * 叠成一团。
 */
class AssistantParamLabelStopsTest {

    /** 上下文消息那组真实档位（`ContextMessageLabelStops`）。 */
    private val contextStops = listOf(1.0, 64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0, 4096.0)

    @Test
    fun contextMessageStopsLoseTheirCollidingLabels() {
        val kept = spreadLabelStops(contextStops, 1f, 4096f)
        assertEquals(listOf(1.0, 512.0, 1024.0, 2048.0, 4096.0), kept)
        // 被去掉的正是挤在左侧的那几个。
        assertTrue(64.0 !in kept && 128.0 !in kept && 256.0 !in kept)
    }

    /** 相邻标签的位置差必须达到阈值（12% 行宽），否则会叠。 */
    @Test
    fun keptLabelsAreFarEnoughApart() {
        val kept = spreadLabelStops(contextStops, 1f, 4096f)
        val span = 4096f - 1f
        val fractions = kept.map { (it.toFloat() - 1f) / span }
        assertTrue(
            "任意两个保留标签的位置差都要 ≥ 12%（实际 $fractions）",
            fractions.zipWithNext().all { (a, b) -> b - a >= 0.12f - 1e-6f },
        )
    }

    /** 本来就均匀的档位（如 0/25/50/75/100）一个都不该丢。 */
    @Test
    fun evenlySpacedStopsAreKept() {
        val even = listOf(0.0, 25.0, 50.0, 75.0, 100.0)
        assertEquals(even, spreadLabelStops(even, 0f, 100f))
    }

    /** 首末永远保留；末项离前一项太近时**顶替**它（最大档位必须看得见）。 */
    @Test
    fun firstAndLastAlwaysSurvive() {
        // 中间的 1.0 挤在最左 → 去掉，首末保留。
        assertEquals(listOf(0.0, 100.0), spreadLabelStops(listOf(0.0, 1.0, 100.0), 0f, 100f))
        // 末项 100 与前一项 99 只差 1% → 用 100 顶替 99。
        assertEquals(listOf(0.0, 100.0), spreadLabelStops(listOf(0.0, 99.0, 100.0), 0f, 100f))
    }

    /** 边界：空表 / 单档 / 零宽度区间一律原样返回，不抛。 */
    @Test
    fun degenerateInputsPassThrough() {
        assertEquals(emptyList<Double>(), spreadLabelStops(emptyList(), 0f, 100f))
        assertEquals(listOf(5.0), spreadLabelStops(listOf(5.0), 0f, 100f))
        assertEquals(listOf(1.0, 2.0), spreadLabelStops(listOf(1.0, 2.0), 7f, 7f))
    }
}
