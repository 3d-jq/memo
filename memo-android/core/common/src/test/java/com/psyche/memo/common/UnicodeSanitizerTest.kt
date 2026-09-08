package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of unicode_sanitizer.dart. */
class UnicodeSanitizerTest {

    @Test
    fun `valid text passes through unchanged`() {
        val text = "hello 世界 🚀"
        assertEquals(text, UnicodeSanitizer.sanitize(text))
        assertEquals("", UnicodeSanitizer.sanitize(""))
    }

    @Test
    fun `lone high surrogate becomes the replacement char`() {
        val text = "a" + 0xD800.toChar() + "b"
        assertEquals("a\uFFFDb", UnicodeSanitizer.sanitize(text))
    }

    @Test
    fun `lone low surrogate becomes the replacement char`() {
        val text = "a" + 0xDC00.toChar() + "b"
        assertEquals("a\uFFFDb", UnicodeSanitizer.sanitize(text))
    }

    @Test
    fun `stripped low surrogate is repaired back into the pair`() {
        // 0xDCE1 with the high nibble stripped shows up as U+0CE1.
        val text = "" + 0xD83D.toChar() + 0x0CE1.toChar()
        val sanitized = UnicodeSanitizer.sanitize(text)
        assertEquals("\uD83D\uDCE1", sanitized)
        assertTrue(sanitized.codePointAt(0) == 0x1F4E1)
    }

    @Test
    fun `valid surrogate pairs survive`() {
        val text = "ok " + String(Character.toChars(0x1F680)) + " done"
        assertEquals(text, UnicodeSanitizer.sanitize(text))
    }
}
