package com.psyche.memo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.psyche.memo.ui.chat.mcpButtonActive
import com.psyche.memo.ui.chat.quickPhraseButtonVisible

/**
 * 输入栏左排按钮的可用/选中态（用户 2026-09-14：「选择了对应颜色要变吧」
 * 「快捷短语没设置一个不出现吧，现在怎么会一直显示」）——
 * 判定照 chat_input_section.dart:177-199 / 295-308。
 */
class InputBarButtonStateTest {

    @Test
    fun `the quick phrase button hides when nothing is configured`() {
        assertFalse(quickPhraseButtonVisible(globalCount = 0, assistantCount = 0))
        assertTrue(quickPhraseButtonVisible(globalCount = 1, assistantCount = 0))
        assertTrue(quickPhraseButtonVisible(globalCount = 0, assistantCount = 2))
    }

    @Test
    fun `the mcp button only highlights for a selected AND connected server`() {
        assertFalse(mcpButtonActive(selectedIds = emptyList(), connectedIds = setOf("a")))
        assertFalse(mcpButtonActive(selectedIds = listOf("a"), connectedIds = emptySet()))
        assertFalse(mcpButtonActive(selectedIds = listOf("a"), connectedIds = setOf("b")))
        assertTrue(mcpButtonActive(selectedIds = listOf("a", "b"), connectedIds = setOf("b")))
    }

    @Test
    fun `reasoning highlight follows the enabled switch not the tier`() {
        // 上个档位（High）也算「开」；只有 关闭(0) 才是灰的。
        assertTrue(com.psyche.memo.llm.client.ReasoningBudget.isReasoningEnabled(32000))
        assertTrue(com.psyche.memo.llm.client.ReasoningBudget.isReasoningEnabled(-1))
        assertFalse(com.psyche.memo.llm.client.ReasoningBudget.isReasoningEnabled(0))
    }
}
