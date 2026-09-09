package com.psyche.memo.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of ChatBackdropSpec.isBackgroundActive. */
class ChatBackdropSpecTest {

    @Test
    fun `blank backgrounds are inactive`() {
        assertFalse(isBackgroundActive(""))
        assertFalse(isBackgroundActive("   "))
    }

    @Test
    fun `http urls are active`() {
        assertTrue(isBackgroundActive("https://example.com/a.png"))
        assertTrue(isBackgroundActive("http://example.com/a.png"))
    }

    @Test
    fun `missing local files are inactive`() {
        assertFalse(isBackgroundActive("/definitely/not/here.png"))
    }
}
