package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reasoning-effort stops behind the redesigned budget sheet
 * (`reasoning_budget_sheet.dart` as of 83ab329): 关闭 → 自动 → Low → Medium →
 * High → (XHigh) → (Max), with the XHigh/Max stops gated by model capability
 * and custom values parking on the numerically closest stop.
 */
class ReasoningEffortStopsTest {

    private fun titles() = ReasoningStopTitles(
        off = "关闭",
        auto = "自动",
        low = "Low",
        medium = "Medium",
        high = "High",
        xhigh = "XHigh",
        max = "Max",
        offSubtitle = "关闭推理功能，直接回答",
        autoSubtitle = "由模型自动决定推理级别",
        lowSubtitle = "使用少量推理来回答问题",
        mediumSubtitle = "使用较多推理来回答问题",
        highSubtitle = "使用大量推理来回答问题，适合复杂问题",
        xhighSubtitle = "使用最大推理深度，适合最复杂的问题",
    )

    private fun values(vararg stops: EffortStop) = stops.map { it.value }

    @Test
    fun `a plain model shows five stops in the original order`() {
        val stops = ReasoningEffortStops.build(titles(), showXhigh = false, showMax = false)
        assertEquals(
            values(*stops.toTypedArray()),
            listOf(0, -1, 1024, 16000, 32000),
        )
    }

    @Test
    fun `xhigh and max appear only for capable models`() {
        val xhighOnly = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = false)
        assertEquals(values(*xhighOnly.toTypedArray()), listOf(0, -1, 1024, 16000, 32000, 64000))

        val both = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = true)
        assertEquals(
            values(*both.toTypedArray()),
            listOf(0, -1, 1024, 16000, 32000, 64000, 128000),
        )

        // Max alone without XHigh is not a state the capability helpers produce,
        // but the gate must still be per-stop.
        val maxOnly = ReasoningEffortStops.build(titles(), showXhigh = false, showMax = true)
        assertEquals(values(*maxOnly.toTypedArray()), listOf(0, -1, 1024, 16000, 32000, 128000))
    }

    @Test
    fun `titles and subtitles follow the upstream mapping`() {
        val stops = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = true)
        assertEquals("关闭", stops[0].title)
        assertEquals("关闭推理功能，直接回答", stops[0].subtitle)
        assertEquals("自动", stops[1].title)
        assertEquals("Low", stops[2].title)
        assertEquals("使用少量推理来回答问题", stops[2].subtitle)
        assertEquals("Medium", stops[3].title)
        assertEquals("High", stops[4].title)
        assertEquals("使用大量推理来回答问题，适合复杂问题", stops[4].subtitle)
        assertEquals("XHigh", stops[5].title)
        // Max reuses the XHigh subtitle (deliberate in the Dart source).
        assertEquals("Max", stops[6].title)
        assertEquals("使用最大推理深度，适合最复杂的问题", stops[6].subtitle)
    }

    @Test
    fun `indexFor hits an exact preset`() {
        val stops = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = true)
        assertEquals(0, ReasoningEffortStops.indexFor(stops, 0))
        assertEquals(1, ReasoningEffortStops.indexFor(stops, -1))
        assertEquals(2, ReasoningEffortStops.indexFor(stops, 1024))
        assertEquals(6, ReasoningEffortStops.indexFor(stops, 128000))
    }

    @Test
    fun `a custom value parks on the numerically closest stop`() {
        val stops = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = true)
        // 8000 sits closer to Low (1024) than to Medium (16000).
        assertEquals(2, ReasoningEffortStops.indexFor(stops, 8000))
        // 12000 sits closer to Medium.
        assertEquals(3, ReasoningEffortStops.indexFor(stops, 12000))
        // Beyond the last stop, the thumb parks at the end.
        assertEquals(6, ReasoningEffortStops.indexFor(stops, 500000))
    }

    @Test
    fun `custom detection matches the preset set`() {
        val stops = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = true)
        assertFalse(ReasoningEffortStops.isCustom(stops, 16000))
        assertFalse(ReasoningEffortStops.isCustom(stops, -1))
        assertTrue(ReasoningEffortStops.isCustom(stops, 4096))
    }

    @Test
    fun `stop values agree with the icon tiers`() {
        // assetForBudget maps each stop's value back to its own tier icon — a
        // mismatched tier would draw the wrong lamp on the pill.
        val stops = ReasoningEffortStops.build(titles(), showXhigh = true, showMax = true)
        assertEquals(ReasoningBudgetIcons.OFF, ReasoningBudgetIcons.assetForBudget(stops[0].value))
        assertEquals(ReasoningBudgetIcons.AUTO, ReasoningBudgetIcons.assetForBudget(stops[1].value))
        assertEquals(ReasoningBudgetIcons.LIGHT, ReasoningBudgetIcons.assetForBudget(stops[2].value))
        assertEquals(ReasoningBudgetIcons.MEDIUM, ReasoningBudgetIcons.assetForBudget(stops[3].value))
        assertEquals(ReasoningBudgetIcons.HEAVY, ReasoningBudgetIcons.assetForBudget(stops[4].value))
        assertEquals(ReasoningBudgetIcons.XHIGH, ReasoningBudgetIcons.assetForBudget(stops[5].value))
        // Max shares the XHigh lamp upstream (maxAsset = xhighAsset).
        assertEquals(ReasoningBudgetIcons.XHIGH, ReasoningBudgetIcons.assetForBudget(stops[6].value))
    }
}
