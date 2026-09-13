package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translations of chat_input_bar.dart:1602-1612 `_handlePastedText` +
 * settings_provider.dart:1172-1176 / 4879-4893. The threshold comparison must
 * stay *strictly greater* — an off-by-one here turns a 5000-char paste into a
 * file (or fails to).
 */
class LongPasteAsFileTest {

    private val chars: (String) -> Int = { it.length }

    @Test
    fun defaultsAreEnabledWithFiveThousandGraphemes() {
        val s = LongPasteSettings.fromPrefs { null }
        assertTrue(s.enabled)
        assertEquals(5000, s.threshold)
    }

    @Test
    fun readsStoredToggleAndThreshold() {
        val store = mapOf(
            LongPasteSettings.ENABLED_KEY to "0",
            LongPasteSettings.THRESHOLD_KEY to "120",
        )
        val s = LongPasteSettings.fromPrefs { store[it] }
        assertFalse(s.enabled)
        assertEquals(120, s.threshold)
    }

    @Test
    fun thresholdIsClampedAndMalformedValuesFallBack() {
        assertEquals(
            LongPasteSettings.MAX_THRESHOLD,
            LongPasteSettings.fromPrefs { "999999999" }.threshold,
        )
        assertEquals(
            LongPasteSettings.MIN_THRESHOLD,
            LongPasteSettings.fromPrefs { "0" }.threshold,
        )
        assertEquals(
            LongPasteSettings.DEFAULT_THRESHOLD,
            LongPasteSettings.fromPrefs { "abc" }.threshold,
        )
    }

    @Test
    fun lengthMustStrictlyExceedTheThreshold() {
        val s = LongPasteSettings(enabled = true, threshold = 10)
        assertFalse(s.isLongPaste("a".repeat(10), chars))
        assertTrue(s.isLongPaste("a".repeat(11), chars))
    }

    @Test
    fun disabledOrEmptyNeverConverts() {
        assertFalse(LongPasteSettings(enabled = false, threshold = 1).isLongPaste("abcdef", chars))
        assertFalse(LongPasteSettings(enabled = true, threshold = 0).isLongPaste("", chars))
    }

    @Test
    fun countIsGraphemeBasedNotUtf16() {
        // 注入字素计数器：4 个码元但 2 个字素 ⇒ 阈值 3 时不转文件。
        val s = LongPasteSettings(enabled = true, threshold = 3)
        assertFalse(s.isLongPaste("ab\uD83D\uDE00\uD83D\uDE00", graphemeCount = { 2 }))
        assertTrue(s.isLongPaste("ab\uD83D\uDE00\uD83D\uDE00", graphemeCount = { 4 }))
    }
}
