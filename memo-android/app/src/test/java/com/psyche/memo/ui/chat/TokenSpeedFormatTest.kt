package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * token 行的生成速度（`TokenDisplay` 里那个 `<n> tok/s`）。
 *
 * 口径是**completion tokens ÷ 总耗时**（PORTING §5.45②）。两条护栏：数据不足时**不显示**
 * （返回 null）—— 缓存命中的回复 completion 是 0、极短回复的耗时会除出几十上百的假数字，
 * 那种数字比不显示更糟；正常值保留一位小数、定点用 `.`（`Locale.US`，中文/欧洲系统上
 * `String.format` 默认会用逗号）。
 */
class TokenSpeedFormatTest {

    @Test
    fun formatsCompletionTokensOverTotalDuration() {
        assertEquals("50.0", formatTokensPerSecond(completionTokens = 500, durationMs = 10_000))
        assertEquals("12.3", formatTokensPerSecond(completionTokens = 123, durationMs = 10_000))
        assertEquals("1.0", formatTokensPerSecond(completionTokens = 1, durationMs = 1_000))
    }

    @Test
    fun hidesTheNumberWhenTheDataIsNotUsable() {
        assertNull("没有 completion 数就不显示", formatTokensPerSecond(null, 10_000))
        assertNull("没有耗时就不显示", formatTokensPerSecond(500, null))
        assertNull("缓存命中（0 token）不显示", formatTokensPerSecond(0, 10_000))
        assertNull("负数不显示", formatTokensPerSecond(-3, 10_000))
        assertNull("瞬时回复（<0.5s）不显示", formatTokensPerSecond(50, 499))
        assertEquals("0.5s 就够显示了", "100.0", formatTokensPerSecond(50, 500))
    }

    @Test
    fun usesADotNoMatterTheDeviceLocale() {
        // 中文/德语系统上 String.format 默认会给 "12,3" —— 那个逗号在界面上会被读成千位分隔。
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("12.3", formatTokensPerSecond(123, 10_000))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
