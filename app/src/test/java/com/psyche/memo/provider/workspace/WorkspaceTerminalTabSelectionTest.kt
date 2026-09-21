package com.psyche.memo.provider.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 关掉一个终端 tab 之后该选哪个 —— 上游 `WorkspaceTerminalSessionManager.closeTab` 里
 * 那段三元表达式。选错的表现是「关掉第 2 个，界面跳到第 1 个」或者「关完最后一个还停在
 * 一个已经不存在的 tab 上（终端视口空白）」。
 */
class WorkspaceTerminalTabSelectionTest {

    @Test
    fun `closing the selected tab selects the one that slides into its place`() {
        // 关中间那个 → 补上来的是原 index 处的下一个
        assertEquals(3L, selectedTabIdAfterClose(listOf(1L, 2L, 3L), selectedTabId = 2L, closedTabId = 2L))
    }

    @Test
    fun `closing the last tab falls back to the previous one`() {
        assertEquals(2L, selectedTabIdAfterClose(listOf(1L, 2L, 3L), selectedTabId = 3L, closedTabId = 3L))
    }

    @Test
    fun `closing the only tab selects nothing`() {
        assertNull(selectedTabIdAfterClose(listOf(7L), selectedTabId = 7L, closedTabId = 7L))
    }

    @Test
    fun `closing a background tab keeps the current selection`() {
        assertEquals(1L, selectedTabIdAfterClose(listOf(1L, 2L, 3L), selectedTabId = 1L, closedTabId = 2L))
    }

    @Test
    fun `closing an unknown tab is a no-op`() {
        assertEquals(1L, selectedTabIdAfterClose(listOf(1L, 2L), selectedTabId = 1L, closedTabId = 99L))
        assertNull(selectedTabIdAfterClose(emptyList(), selectedTabId = null, closedTabId = 99L))
    }
}
