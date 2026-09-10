package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reorder regression cover for the providers list.
 *
 * The original bug had two halves, both reproduced here as behaviour the code
 * must now satisfy:
 *
 * 1. A move must produce exactly the list the user dropped
 *    (`add(to, removeAt(from))`, identical to `providers_page.onReorder`).
 * 2. Out-of-range indices must be *dropped*, not clamped. `sh.calvin.reorderable`
 *    emits stale layout indices when the list recomposes mid-drag; clamping
 *    those would silently reorder unrelated rows, which is what users saw as
 *    "the list jumped somewhere random".
 */
class ProviderReorderTest {

    @Test
    fun `moving down shifts the dragged key to the target slot`() {
        val moved = applyProviderMove(listOf("a", "b", "c", "d"), from = 0, to = 2)
        assertEquals(listOf("b", "c", "a", "d"), moved)
    }

    @Test
    fun `moving up shifts the dragged key to the target slot`() {
        val moved = applyProviderMove(listOf("a", "b", "c", "d"), from = 3, to = 1)
        assertEquals(listOf("a", "d", "b", "c"), moved)
    }

    @Test
    fun `move to the tail lands last`() {
        val moved = applyProviderMove(listOf("a", "b", "c"), from = 0, to = 2)
        assertEquals(listOf("b", "c", "a"), moved)
    }

    @Test
    fun `move to the head lands first`() {
        val moved = applyProviderMove(listOf("a", "b", "c"), from = 2, to = 0)
        assertEquals(listOf("c", "a", "b"), moved)
    }

    @Test
    fun `adjacent swap is a single step`() {
        val moved = applyProviderMove(listOf("a", "b"), from = 0, to = 1)
        assertEquals(listOf("b", "a"), moved)
    }

    @Test
    fun `stale out-of-range indices are dropped rather than clamped`() {
        val keys = listOf("a", "b", "c")
        assertNull("from past the end must be ignored", applyProviderMove(keys, from = 3, to = 1))
        assertNull("to past the end must be ignored", applyProviderMove(keys, from = 0, to = 3))
        assertNull("negative from must be ignored", applyProviderMove(keys, from = -1, to = 1))
        assertNull("negative to must be ignored", applyProviderMove(keys, from = 0, to = -1))
        assertNull("empty list rejects everything", applyProviderMove(emptyList(), from = 0, to = 0))
    }

    @Test
    fun `no-op move is rejected`() {
        assertNull(applyProviderMove(listOf("a", "b", "c"), from = 1, to = 1))
    }

    @Test
    fun `a full drag sequence replay ends at the dropped order`() {
        // The library fires one move per neighbour crossed. Replaying them all
        // in order must leave the list exactly where the finger left it — the
        // regression was that each step was persisted and re-derived, so the
        // cumulative result drifted.
        var order = listOf("builtin1", "builtin2", "db1", "db2", "db3")
        // Drag the last row up to the top, crossing four neighbours.
        listOf(4 to 3, 3 to 2, 2 to 1, 1 to 0).forEach { (from, to) ->
            order = applyProviderMove(order, from, to) ?: order
        }
        assertEquals(listOf("db3", "builtin1", "builtin2", "db1", "db2"), order)
    }

    @Test
    fun `back and forth returns to the original order`() {
        val start = listOf("a", "b", "c", "d")
        val down = applyProviderMove(start, from = 1, to = 3)!!
        val back = applyProviderMove(down, from = 3, to = 1)!!
        assertEquals(start, back)
    }

    @Test
    fun `move preserves every key exactly once`() {
        val keys = (1..40).map { "k$it" }
        val moved = applyProviderMove(keys, from = 7, to = 29)!!
        assertEquals(keys.size, moved.size)
        assertEquals(keys.toSet(), moved.toSet())
    }
}

/**
 * Cover for the item-list build (`providers_page.build` merge semantics).
 *
 * The regression: a hand-typed key (`"zhipu ai"`) coexisted with the seeded
 * built-in (`"Zhipu AI"`). The old `filterNot { it in BUILTIN_KEYS }` compared
 * strings case-sensitively, so both survived — the provider rendered twice and
 * both rows carried the same LazyList key, which corrupts item reuse (cards
 * collapsed and overlapped).
 */
class ProviderListItemsTest {

    private val identityOrder: (List<String>) -> List<String> = { it }

    private fun cfg(key: String, name: String = key, enabled: Boolean = false, models: Int = 0) =
        com.psyche.memo.data.model.ProviderConfig(
            id = key,
            name = name,
            enabled = enabled,
            models = (1..models).map { "$key-model-$it" },
        )

    @Test
    fun `hand-typed builtin key does not duplicate the built-in row`() {
        val providers = listOf("zhipu ai" to cfg("zhipu ai", name = "Zhipu AI", models = 1))
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)

        assertEquals(1, items.count { it.key == "Zhipu AI" })
        assertEquals(0, items.count { it.key == "zhipu ai" })
        // 13 built-ins plus nothing else: the hand-typed row folded into one.
        assertEquals(com.psyche.memo.data.repo.ProviderRepository.BUILTIN_KEYS.size, items.size)
    }

    @Test
    fun `only canonical keys survive the fold`() {
        val providers = listOf("zhipu ai" to cfg("zhipu ai", models = 1))
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)

        assertEquals(
            "every key in the list must be its own canonical form",
            items.map { it.key },
            items.map { it.key }.distinct(),
        )
        assertNull("a lower-case zhipu row must not surface", items.firstOrNull { it.key == "zhipu ai" })
    }

    @Test
    fun `the non-canonical row still contributes its data`() {
        val providers = listOf("zhipu ai" to cfg("zhipu ai", name = "Zhipu AI", models = 3))
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)
        val zhipu = items.first { it.key == "Zhipu AI" }

        // The old code dropped the row entirely, so the provider appeared with
        // no name / no models attached.
        assertEquals("Zhipu AI", zhipu.name)
        assertEquals(3, zhipu.modelCount)
    }

    @Test
    fun `canonical row wins when both spellings exist`() {
        val providers = listOf(
            "Zhipu AI" to cfg("Zhipu AI", name = "Zhipu AI", enabled = true, models = 2),
            "zhipu ai" to cfg("zhipu ai", name = "Zhipu AI"),
        )
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)
        val zhipu = items.first { it.key == "Zhipu AI" }

        assertEquals(2, zhipu.modelCount)
    }

    @Test
    fun `user-added providers are kept verbatim`() {
        val providers = listOf(
            "随想ai中转站" to cfg("随想ai中转站", name = "随想AI中转站"),
            "marucode" to cfg("marucode", name = "MaruCode"),
        )
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)
        val keys = items.map { it.key }

        assertEquals(1, keys.count { it == "随想ai中转站" })
        assertEquals(1, keys.count { it == "marucode" })
    }

    @Test
    fun `every built-in appears exactly once when no rows exist`() {
        val items = buildProviderItems(emptyList(), searchQuery = "", applyOrder = identityOrder)
        val builtins = com.psyche.memo.data.repo.ProviderRepository.BUILTIN_KEYS

        assertEquals(builtins.size, items.size)
        assertEquals(builtins.toSet(), items.map { it.key }.toSet())
    }

    @Test
    fun `dragOrder short-circuits the persisted order`() {
        val providers = listOf("marucode" to cfg("marucode", name = "MaruCode"))
        val dragged = listOf("Grok", "OpenAI", "MaruCode")
        val items = buildProviderItems(
            providers = providers,
            searchQuery = "",
            dragOrder = dragged,
            applyOrder = { error("applyOrder must not run while dragging") },
        )

        assertEquals(dragged, items.map { it.key })
    }

    @Test
    fun `search filters on display name and key case-insensitively`() {
        val providers = listOf("marucode" to cfg("marucode", name = "MaruCode"))
        val byName = buildProviderItems(providers, searchQuery = "maruco", applyOrder = identityOrder)
        val byKey = buildProviderItems(providers, searchQuery = "MARUCODE", applyOrder = identityOrder)

        assertEquals(listOf("marucode"), byName.map { it.key })
        assertEquals(listOf("marucode"), byKey.map { it.key })
    }

    @Test
    fun `a name-less row falls back to its key`() {
        val providers = listOf("marucode" to cfg("marucode", name = ""))
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)

        assertEquals("marucode", items.first { it.key == "marucode" }.name)
    }

    @Test
    fun `the render key list has no duplicates`() {
        val providers = listOf(
            "zhipu ai" to cfg("zhipu ai", models = 1),
            "openai" to cfg("openai"),
            "随想ai中转站" to cfg("随想ai中转站"),
        )
        val items = buildProviderItems(providers, searchQuery = "", applyOrder = identityOrder)
        val keys = items.map { it.key }

        // Duplicate keys are what made ReorderableItem reuse collapse the list.
        assertEquals("render keys must be unique", keys.size, keys.toSet().size)
    }
}
