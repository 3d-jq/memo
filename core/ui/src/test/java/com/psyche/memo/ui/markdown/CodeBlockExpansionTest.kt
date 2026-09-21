package com.psyche.memo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块折叠判定 + 折叠预览取哪几行。
 *
 * 用户 2026-09-18 实测报障：「大模型输出的时候是固定状态，不能滑动，也没有滚动，
 * 只能看到开始部分，导致用户以为没有输出」；随后纠正：「可以折叠，实在折叠也可以流式」
 * ⇒ 折叠照旧，但**折叠态下的预览要跟着写指针走**（取末尾几行）。
 */
class CodeBlockExpansionTest {

    private val code = (1..10).joinToString("\n") { "line$it" }

    @Test
    fun `manual choice always wins`() {
        assertFalse(codeBlockExpanded(manual = false, autoCollapse = true, exceeds = true))
        assertTrue(codeBlockExpanded(manual = true, autoCollapse = true, exceeds = true))
    }

    @Test
    fun `auto collapse only when the setting is on and the code is long`() {
        assertFalse(codeBlockExpanded(manual = null, autoCollapse = true, exceeds = true))
        assertTrue(codeBlockExpanded(manual = null, autoCollapse = false, exceeds = true))
        assertTrue(codeBlockExpanded(manual = null, autoCollapse = true, exceeds = false))
    }

    @Test
    fun `collapsed preview shows the head once finished`() {
        assertEquals("line1\nline2", collapsedCodePreview(code, 2))
    }

    @Test
    fun `collapsed preview follows the writing pointer while streaming`() {
        // 折叠着也要能看到内容在推进 —— 取末尾几行
        assertEquals("line9\nline10", collapsedCodePreview(code, 2, fromTail = true))
        // 还没超出窗口时，头尾其实是一样的
        assertEquals("line1", collapsedCodePreview("line1", 2, fromTail = true))
        // 空代码不炸
        assertEquals("", collapsedCodePreview("", 2, fromTail = true))
        // 上游只去尾部**换行**，空白行原样保留
        assertEquals("   ", collapsedCodePreview("   \n\n", 2, fromTail = true))
    }

    @Test
    fun `visible lines is clamped to at least one`() {
        assertEquals("line10", collapsedCodePreview(code, 0, fromTail = true))
        assertEquals("line1", collapsedCodePreview(code, -3))
    }

    /**
     * 流式中的折叠预览强制不换行（用户 2026-09-20「代码块在输出时大小会变，界面一直变」）：
     * 换行时「一行源码 = 几视觉行」随内容变，框高就每 tick 抖。其余三种状态都照设置走。
     */
    @Test
    fun `streaming collapsed preview never wraps`() {
        assertEquals(false, codeBlockPreviewWraps(wrap = true, expanded = false, isStreaming = true))
        assertEquals(true, codeBlockPreviewWraps(wrap = true, expanded = true, isStreaming = true))
        assertEquals(true, codeBlockPreviewWraps(wrap = true, expanded = false, isStreaming = false))
        assertEquals(false, codeBlockPreviewWraps(wrap = false, expanded = true, isStreaming = false))
    }
}
