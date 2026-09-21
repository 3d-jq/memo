package com.psyche.memo

import com.psyche.memo.common.SessionCompaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 压缩设置偏好的解析（opencode 阈值机制的可调项）。 */
class ContextCompactionPrefsTest {

    @Test
    fun `booleans accept raw and quoted forms`() {
        assertTrue(ContextCompactionPrefs.boolOf("true", false))
        assertTrue(ContextCompactionPrefs.boolOf("1", false))
        assertTrue(ContextCompactionPrefs.boolOf("\"true\"", false))
        assertFalse(ContextCompactionPrefs.boolOf("false", true))
        assertFalse(ContextCompactionPrefs.boolOf("0", true))
        // 缺省值只在写坏/缺失时生效。
        assertTrue(ContextCompactionPrefs.boolOf(null, true))
        assertTrue(ContextCompactionPrefs.boolOf("garbage", true))
    }

    @Test
    fun `integers fall back to the default`() {
        assertEquals(8000, ContextCompactionPrefs.intOf("8000", 1))
        assertEquals(1, ContextCompactionPrefs.intOf(null, 1))
        assertEquals(1, ContextCompactionPrefs.intOf("abc", 1))
    }

    @Test
    fun `window and buffer must stay positive while keep tokens may be zero`() {
        assertEquals(128_000, ContextCompactionPrefs.positiveOf("0", 128_000))
        assertEquals(128_000, ContextCompactionPrefs.positiveOf("-5", 128_000))
        assertEquals(64_000, ContextCompactionPrefs.positiveOf("64000", 128_000))
        assertEquals(0, ContextCompactionPrefs.nonNegativeOf("0", 8_000))
        assertEquals(8_000, ContextCompactionPrefs.nonNegativeOf("-1", 8_000))
        assertEquals(2_000, ContextCompactionPrefs.nonNegativeOf("2000", 8_000))
    }

    @Test
    fun `defaults mirror opencode`() {
        val settings = SessionCompaction.Settings()
        assertTrue(settings.auto)
        assertEquals(20_000, settings.buffer)
        assertEquals(8_000, settings.keepTokens)
        assertEquals(128_000, settings.contextWindow)
    }
}
