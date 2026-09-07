package com.psyche.memo.ui

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure logic behind the avatar actions in assistant_settings_edit_basic_tab.dart. */
class AvatarLogicTest {

    @Test
    fun `random qq numbers always satisfy the dialog's own validity pattern`() {
        val random = Random(20260907)
        repeat(2000) {
            val qq = randomQqNumber(random)
            assertTrue("$qq rejected by the entry pattern", qq.matches(QqEntryPattern))
        }
    }

    @Test
    fun `random qq length stays inside the weighted table`() {
        val random = Random(7)
        repeat(2000) {
            val len = randomQqNumber(random).length
            assertTrue("unexpected length $len", len in 5..11)
        }
    }

    @Test
    fun `leading digit favours the 1-2 group over the single 9`() {
        val random = Random(99)
        val counts = IntArray(10)
        repeat(4000) { counts[randomQqNumber(random).first().digitToInt()]++ }
        val low = counts[1] + counts[2]
        assertTrue("expected 1/2 to dominate, got ${counts.toList()}", low > counts[9] * 20)
        assertEquals("QQ numbers never start with 0", 0, counts[0])
    }

    @Test
    fun `the same seed reproduces the same number`() {
        assertEquals(randomQqNumber(Random(5)), randomQqNumber(Random(5)))
    }

    @Test
    fun `avatar url uses the qq headimg endpoint`() {
        assertEquals(
            "https://q2.qlogo.cn/headimg_dl?dst_uin=12345&spec=100",
            qqAvatarUrl("12345"),
        )
    }

    @Test
    fun `only a single grapheme validates in the emoji picker`() {
        assertTrue(isSingleGrapheme("😀"))
        assertTrue(isSingleGrapheme("a"))
        assertFalse(isSingleGrapheme(""))
        assertFalse(isSingleGrapheme("   "))
        assertFalse(isSingleGrapheme("😀😀"))
        assertFalse(isSingleGrapheme("ab"))
        // validGrapheme takes the first cluster of the raw text, so a leading
        // space trims to nothing instead of skipping ahead to the emoji.
        assertFalse(isSingleGrapheme("  😀  "))
        assertFalse(isSingleGrapheme(" 😀"))
    }

    @Test
    fun `first grapheme keeps a joined sequence together`() {
        val joined = "👨‍👩‍👧"
        assertEquals(joined, firstGrapheme(joined))
        assertEquals("👍", firstGrapheme("👍👎"))
    }

    @Test
    fun `every quick emoji is a single grapheme`() {
        QuickEmojis.forEach { emoji ->
            assertTrue("$emoji is not one grapheme", isSingleGrapheme(emoji))
        }
    }

    private companion object {
        val QqEntryPattern = Regex("^[0-9]{5,12}$")
    }
}
