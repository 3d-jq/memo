package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * token 明细气泡里的两行数字：`<n> tok/s` 与 `<n>s`。
 *
 * 口径 1:1 上游 `token_detail_popup.dart`：速度 = completion tokens ÷ 总耗时（秒）
 * （`:59-72`），耗时 = 毫秒 ÷ 1000（`:76`），都只保留一位小数、条件只有「> 0」——
 * **不要**加「太短就不显示」那类上游没有的门槛。两条护栏：`%` 定点必须用 `.`
 * （中文系统上 `"%.1f".format` 会给出 `12,3`，读起来像千位分隔），以及没有数据时
 * 返回 null（那一行整个不画，见 `TokenPopupRow`）。
 */
class TokenSpeedFormatTest {

    @Test
    fun formatsCompletionTokensOverTotalDuration() {
        assertEquals("50.0", formatTokensPerSecond(completionTokens = 500, durationMs = 10_000))
        assertEquals("12.3", formatTokensPerSecond(completionTokens = 123, durationMs = 10_000))
        assertEquals("1.0", formatTokensPerSecond(completionTokens = 1, durationMs = 1_000))
        // 上游只要求 > 0：短回复也照算（0.499s 也要出数）。
        assertEquals("100.2", formatTokensPerSecond(completionTokens = 50, durationMs = 499))
    }

    @Test
    fun hidesTheNumberWhenTheDataIsNotUsable() {
        assertNull("没有 completion 数就不显示", formatTokensPerSecond(null, 10_000))
        assertNull("没有耗时就不显示", formatTokensPerSecond(500, null))
        assertNull("0 token 不显示", formatTokensPerSecond(0, 10_000))
        assertNull("负数不显示", formatTokensPerSecond(-3, 10_000))
        assertNull("耗时为 0 不显示（上游 `durationMs > 0`）", formatTokensPerSecond(500, 0))
    }

    /**
     * 有「正文实际在流」的窗口时，分母用它，不再算上排队/prefill/工具时间。
     *
     * 但窗口短到不像在流（非流式回合一个 chunk 吐完 ⇒ 窗口 0）就退回总耗时 ——
     * 拿 0.2 秒做分母会显示 600 tok/s（用户 2026-09-25 抓到的就是这个）。
     */
    @Test
    fun dividesByTheMeasuredStreamingWindowAndFallsBackWhenItIsTooShort() {
        assertEquals("100.0", formatTokensPerSecond(500, 10_000, textStreamMs = 5_000))
        // 没记到（旧消息 / 非流式）仍按上游口径。
        assertEquals("50.0", formatTokensPerSecond(500, 10_000, textStreamMs = null))
        assertEquals(
            "窗口 200ms 不算测量（一个 chunk 吐完），退回总耗时而不是 2500 tok/s",
            "50.0", formatTokensPerSecond(500, 10_000, textStreamMs = 200),
        )
        assertEquals(
            "刚好到线就用它",
            "500.0", formatTokensPerSecond(500, 10_000, textStreamMs = 1_000),
        )
        assertEquals(
            "窗口比总耗时还长（时钟跳变）也不能算出负数分母",
            "50.0", formatTokensPerSecond(500, 10_000, textStreamMs = 0),
        )
    }

    @Test
    fun formatsDurationSecondsWithOneDecimal() {
        assertEquals("1.0", formatSeconds(1_000))
        assertEquals("1.5", formatSeconds(1_500))
        assertEquals("0.4", formatSeconds(399))
        assertEquals("123.5", formatSeconds(123_456))
    }

    @Test
    fun usesADotNoMatterTheDeviceLocale() {
        // 中文/德语系统上 String.format 默认会给 "12,3" —— 那个逗号在界面上会被读成千位分隔。
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("12.3", formatTokensPerSecond(123, 10_000))
            assertEquals("1.5", formatSeconds(1_500))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
