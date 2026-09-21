package com.psyche.memo

import com.psyche.memo.ui.AssistantEditTab
import com.psyche.memo.ui.DEFAULT_ASSISTANT_EDIT_TAB_ORDER
import com.psyche.memo.ui.applyAssistantTabMove
import com.psyche.memo.ui.orderAssistantEditTabIds
import com.psyche.memo.ui.visibleAssistantEditTabIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * assistant_edit_tab_layout.dart — ordering, visibility and the single reorder
 * crossing, plus the invariant that every id in the default order resolves to a
 * declared tab (a typo there would silently drop a tab from the page).
 */
class AssistantEditTabLayoutTest {

    @Test
    fun `empty saved order falls back to the default order`() {
        assertEquals(DEFAULT_ASSISTANT_EDIT_TAB_ORDER, orderAssistantEditTabIds(emptyList()))
    }

    @Test
    fun `saved ids come first and missing defaults are appended`() {
        assertEquals(
            listOf("regex", "basic", "prompts", "memory", "quickPhrase", "custom", "localTools", "skills", "workspace", "imageGeneration", "videoGeneration", "mcp"),
            orderAssistantEditTabIds(listOf("regex", "basic")),
        )
    }

    @Test
    fun `unknown ids and duplicates are dropped`() {
        assertEquals(
            listOf("basic", "mcp", "prompts", "memory", "quickPhrase", "custom", "regex", "localTools", "skills", "workspace", "imageGeneration", "videoGeneration"),
            orderAssistantEditTabIds(listOf("basic", "bogus", "basic", "mcp")),
        )
    }

    @Test
    fun `hidden ids are removed and the rest keep their order`() {
        assertEquals(
            listOf("basic", "prompts", "localTools", "skills", "workspace", "imageGeneration", "videoGeneration", "mcp"),
            visibleAssistantEditTabIds(
                savedOrder = emptyList(),
                hiddenIds = setOf("memory", "quickPhrase", "custom", "regex"),
            ),
        )
    }

    @Test
    fun `hiding everything falls back to the first ordered tab`() {
        assertEquals(
            listOf("basic"),
            visibleAssistantEditTabIds(
                savedOrder = emptyList(),
                hiddenIds = DEFAULT_ASSISTANT_EDIT_TAB_ORDER.toSet(),
            ),
        )
    }

    @Test
    fun `a reorder crossing moves one id`() {
        val order = DEFAULT_ASSISTANT_EDIT_TAB_ORDER
        assertEquals(
            listOf(
                "prompts", "memory", "quickPhrase", "custom", "regex", "localTools", "skills",
                "basic", "workspace", "imageGeneration", "videoGeneration", "mcp",
            ),
            applyAssistantTabMove(order, from = 0, to = 7),
        )
    }

    @Test
    fun `out of range and no-op moves leave the list untouched`() {
        val order = DEFAULT_ASSISTANT_EDIT_TAB_ORDER
        assertSame(order, applyAssistantTabMove(order, from = 0, to = 0))
        assertSame(order, applyAssistantTabMove(order, from = -1, to = 3))
        assertSame(order, applyAssistantTabMove(order, from = 2, to = order.size))
    }

    @Test
    fun `every default id resolves to a declared tab and the sets match`() {
        val ids = DEFAULT_ASSISTANT_EDIT_TAB_ORDER.map { AssistantEditTab.byId(it) }
        assertEquals(DEFAULT_ASSISTANT_EDIT_TAB_ORDER.size, ids.filterNotNull().size)
        assertEquals(
            AssistantEditTab.entries.map { it.id }.toSet(),
            DEFAULT_ASSISTANT_EDIT_TAB_ORDER.toSet(),
        )
    }
}
