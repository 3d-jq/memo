package com.psyche.memo.data.repo

import com.psyche.memo.data.model.ProviderGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port coverage of lib/utils/provider_grouping_logic.dart (display keys,
 * insert / reorder with the ungrouped pseudo-row / delete index fixups).
 */
class ProviderGroupLogicTest {

    private fun group(id: String) = ProviderGroup(id = id, name = id, createdAt = 0)

    @Test
    fun `display keys insert the ungrouped pseudo entry at the index`() {
        val groups = listOf(group("g1"), group("g2"))
        assertEquals(
            listOf("__ungrouped__", "g1", "g2"),
            ProviderGroupLogic.buildProviderGroupDisplayKeys(groups, 0),
        )
        assertEquals(
            listOf("g1", "__ungrouped__", "g2"),
            ProviderGroupLogic.buildProviderGroupDisplayKeys(groups, 1),
        )
        assertEquals(
            listOf("g1", "g2", "__ungrouped__"),
            ProviderGroupLogic.buildProviderGroupDisplayKeys(groups, 2),
        )
        // Clamped beyond the end.
        assertEquals(
            listOf("g1", "g2", "__ungrouped__"),
            ProviderGroupLogic.buildProviderGroupDisplayKeys(groups, 99),
        )
    }

    @Test
    fun `inserting a group before the ungrouped index shifts it right`() {
        val res = ProviderGroupLogic.insertProviderGroup(
            groups = listOf(group("g1")),
            ungroupedIndex = 1,
            group = group("g0"),
            insertIndex = 0,
        )
        assertEquals(listOf("g0", "g1"), res.groups.map { it.id })
        assertEquals(2, res.ungroupedIndex)
    }

    @Test
    fun `inserting a group after the ungrouped index keeps it`() {
        val res = ProviderGroupLogic.insertProviderGroup(
            groups = listOf(group("g1")),
            ungroupedIndex = 0,
            group = group("g2"),
            insertIndex = 1,
        )
        assertEquals(0, res.ungroupedIndex)
    }

    @Test
    fun `reorder moving a group above ungrouped decrements the index`() {
        // display: g1, ungrouped, g2
        val res = ProviderGroupLogic.reorderProviderGroupDisplayWithUngrouped(
            groups = listOf(group("g1"), group("g2")),
            ungroupedIndex = 1,
            oldIndex = 2,
            newIndex = 0,
        )
        assertEquals(listOf("g2", "g1"), res.groups.map { it.id })
        assertEquals(2, res.ungroupedIndex)
    }

    @Test
    fun `reorder moving ungrouped to the front`() {
        val res = ProviderGroupLogic.reorderProviderGroupDisplayWithUngrouped(
            groups = listOf(group("g1"), group("g2")),
            ungroupedIndex = 2,
            oldIndex = 2,
            newIndex = 0,
        )
        assertEquals(listOf("g1", "g2"), res.groups.map { it.id })
        assertEquals(0, res.ungroupedIndex)
    }

    @Test
    fun `reorder moving ungrouped to the end after earlier groups`() {
        // display: ungrouped, g1, g2 -> move ungrouped to index 3 (end).
        val res = ProviderGroupLogic.reorderProviderGroupDisplayWithUngrouped(
            groups = listOf(group("g1"), group("g2")),
            ungroupedIndex = 0,
            oldIndex = 0,
            newIndex = 3,
        )
        assertEquals(listOf("g1", "g2"), res.groups.map { it.id })
        assertEquals(2, res.ungroupedIndex)
    }

    @Test
    fun `no-op reorder returns the input untouched`() {
        val groups = listOf(group("g1"), group("g2"))
        val res = ProviderGroupLogic.reorderProviderGroupDisplayWithUngrouped(groups, 1, 1, 1)
        assertEquals(groups.map { it.id }, res.groups.map { it.id })
        assertEquals(1, res.ungroupedIndex)
    }

    @Test
    fun `out-of-range old index is a no-op`() {
        val groups = listOf(group("g1"))
        val res = ProviderGroupLogic.reorderProviderGroupDisplayWithUngrouped(groups, 0, 5, 0)
        assertEquals(groups.map { it.id }, res.groups.map { it.id })
    }

    @Test
    fun `deleting a group before the ungrouped index decrements it`() {
        val res = ProviderGroupLogic.deleteProviderGroup(
            groups = listOf(group("g1"), group("g2")),
            ungroupedIndex = 2,
            providerGroupMap = mapOf("OpenAI" to "g2"),
            collapsed = mapOf("g2" to true),
            groupId = "g1",
        )
        assertEquals(listOf("g2"), res.groups.map { it.id })
        assertEquals(1, res.ungroupedIndex)
        // The surviving group's membership and collapse state are untouched.
        assertEquals(mapOf("OpenAI" to "g2"), res.providerGroupMap)
        assertEquals(mapOf("g2" to true), res.collapsed)
    }

    @Test
    fun `deleting a group after the ungrouped index keeps it`() {
        val res = ProviderGroupLogic.deleteProviderGroup(
            groups = listOf(group("g1"), group("g2")),
            ungroupedIndex = 0,
            providerGroupMap = mapOf("OpenAI" to "g1"),
            collapsed = emptyMap(),
            groupId = "g2",
        )
        assertEquals(listOf("g1"), res.groups.map { it.id })
        assertEquals(0, res.ungroupedIndex)
        // The surviving group's membership is untouched.
        assertEquals(mapOf("OpenAI" to "g1"), res.providerGroupMap)
    }
}
