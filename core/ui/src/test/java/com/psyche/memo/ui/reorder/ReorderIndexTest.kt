package com.psyche.memo.ui.reorder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [dataIndexOf], the mapping from the reorder library's lazy-list
 * indices onto data indices.
 *
 * The library reports positions in the whole LazyColumn, so a header item
 * shifts every data index by one. Feeding a shifted index to `onMove` reorders
 * the wrong pair and desyncs the drag from the layout (siblings pile up on the
 * dragged card) — the resolver exists so keys, not raw indices, decide.
 */
class ReorderIndexTest {

    private val items = listOf("a", "b", "c", "d")
    private val keyOf: (String) -> Any = { it }

    @Test
    fun `key resolves to its data index`() {
        assertEquals(0, dataIndexOf(items, keyOf, "a", 0))
        assertEquals(2, dataIndexOf(items, keyOf, "c", 2))
    }

    @Test
    fun `key wins over a header-shifted lazy index`() {
        // With one header item the library reports "c" at index 3, not 2.
        assertEquals(2, dataIndexOf(items, keyOf, "c", 3))
        assertEquals(0, dataIndexOf(items, keyOf, "a", 1))
    }

    @Test
    fun `key wins over a footer-shifted lazy index`() {
        assertEquals(3, dataIndexOf(items, keyOf, "d", 3))
    }

    @Test
    fun `unknown key falls back to the clamped lazy index`() {
        // A header/footer item is the drag target; land on the list boundary.
        assertEquals(0, dataIndexOf(items, keyOf, "__header__", 0))
        assertEquals(3, dataIndexOf(items, keyOf, "__footer__", 99))
    }

    @Test
    fun `null key falls back to the clamped lazy index`() {
        assertEquals(1, dataIndexOf(items, keyOf, null, 1))
        assertEquals(3, dataIndexOf(items, keyOf, null, 42))
    }

    @Test
    fun `empty list never produces a negative index`() {
        assertEquals(0, dataIndexOf(emptyList<String>(), keyOf, "a", 0))
        assertEquals(0, dataIndexOf(emptyList<String>(), keyOf, null, 5))
    }

    @Test
    fun `non-string keys compare by equality`() {
        val rows = listOf(1 to "one", 2 to "two")
        assertEquals(1, dataIndexOf(rows, { it.first }, 2, 1))
        assertEquals(0, dataIndexOf(rows, { it.first }, 1, 1))
    }
}
