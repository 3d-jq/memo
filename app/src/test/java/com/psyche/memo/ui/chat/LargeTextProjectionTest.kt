package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of bounded_large_text_view.dart's projection algorithm. */
class LargeTextProjectionTest {

    @Test
    fun `small text stays untouched`() {
        val p = LargeTextProjection.fromText("hello\nworld")
        assertFalse(p.isLarge)
        assertEquals(listOf("hello\nworld"), p.chunks)
        assertEquals(0, p.hiddenLines)
        assertEquals("hello\nworld", p.preview)
    }

    @Test
    fun `more than 40 lines collapses into a preview`() {
        val text = (1..45).joinToString("\n") { "line $it" }
        val p = LargeTextProjection.fromText(text)
        assertTrue(p.isLarge)
        assertTrue(p.preview.endsWith("\n…"))
        assertEquals(5, p.hiddenLines)
        // chunks keep every line
        assertEquals(45, p.chunks.joinToString("\n").lines().size)
    }

    @Test
    fun `a single long line is split into 16k chunks`() {
        val text = "x".repeat(40000)
        val p = LargeTextProjection.fromText(text)
        assertTrue(p.isLarge)
        assertEquals(listOf(16000, 16000, 8000), p.chunks.map { it.length })
    }

    @Test
    fun `chunks break on the 40 line boundary`() {
        val text = (1..100).joinToString("\n") { "l$it" }
        val p = LargeTextProjection.fromText(text)
        assertTrue(p.isLarge)
        assertEquals(3, p.chunks.size)
        assertEquals(40, p.chunks[0].lines().size)
        assertEquals(40, p.chunks[1].lines().size)
        assertEquals(20, p.chunks[2].lines().size)
    }
}
