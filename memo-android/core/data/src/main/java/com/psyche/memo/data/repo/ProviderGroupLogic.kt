package com.psyche.memo.data.repo

import com.psyche.memo.data.model.ProviderGroup

/**
 * Pure group-display logic — port of lib/utils/provider_grouping_logic.dart
 * (the subset the provider group manager page uses). UI-independent so the
 * reorder/delete math can be unit-tested without a database.
 */
object ProviderGroupLogic {

    const val UNGROUPED_KEY = "__ungrouped__"

    /** Group ids with the ungrouped pseudo-entry inserted at [ungroupedIndex]. */
    fun buildProviderGroupDisplayKeys(groups: List<ProviderGroup>, ungroupedIndex: Int): List<String> {
        val keys = groups.map { it.id }.toMutableList()
        val insertIndex = ungroupedIndex.coerceIn(0, keys.size)
        keys.add(insertIndex, UNGROUPED_KEY)
        return keys
    }

    data class GroupsAndUngrouped(val groups: List<ProviderGroup>, val ungroupedIndex: Int)

    fun insertProviderGroup(
        groups: List<ProviderGroup>,
        ungroupedIndex: Int,
        group: ProviderGroup,
        insertIndex: Int? = null,
    ): GroupsAndUngrouped {
        val normalizedInsertIndex = (insertIndex ?: groups.size).coerceIn(0, groups.size)
        val nextGroups = groups.toMutableList().apply { add(normalizedInsertIndex, group) }
        val clampedUngrouped = ungroupedIndex.coerceIn(0, groups.size)
        val nextUngroupedIndex = if (clampedUngrouped >= normalizedInsertIndex) clampedUngrouped + 1 else clampedUngrouped
        return GroupsAndUngrouped(nextGroups, nextUngroupedIndex.coerceIn(0, nextGroups.size))
    }

    /**
     * Port of reorderProviderGroupDisplayWithUngrouped: reorder within the
     * display list (groups + ungrouped pseudo-row) and re-derive both the
     * group order and the ungrouped display index.
     */
    fun reorderProviderGroupDisplayWithUngrouped(
        groups: List<ProviderGroup>,
        ungroupedIndex: Int,
        oldIndex: Int,
        newIndex: Int,
    ): GroupsAndUngrouped {
        val displayKeys = buildProviderGroupDisplayKeys(groups, ungroupedIndex)
        if (displayKeys.isEmpty()) {
            return GroupsAndUngrouped(groups.toList(), 0)
        }
        if (oldIndex < 0 || oldIndex >= displayKeys.size) {
            return GroupsAndUngrouped(groups.toList(), ungroupedIndex.coerceIn(0, groups.size))
        }
        val normalizedNewIndex = newIndex.coerceIn(0, displayKeys.size)
        if (oldIndex == normalizedNewIndex) {
            return GroupsAndUngrouped(groups.toList(), ungroupedIndex.coerceIn(0, groups.size))
        }

        val mutKeys = displayKeys.toMutableList()
        val item = mutKeys.removeAt(oldIndex)
        val insertIndex = if (normalizedNewIndex > oldIndex) normalizedNewIndex - 1 else normalizedNewIndex
        mutKeys.add(insertIndex.coerceIn(0, mutKeys.size), item)

        val groupById = groups.associateBy { it.id }
        val nextGroups = ArrayList<ProviderGroup>()
        var nextUngroupedIndex = mutKeys.size
        for (i in mutKeys.indices) {
            val key = mutKeys[i]
            if (key == UNGROUPED_KEY) {
                nextUngroupedIndex = i
                continue
            }
            groupById[key]?.let { nextGroups.add(it) }
        }
        return GroupsAndUngrouped(nextGroups, nextUngroupedIndex.coerceIn(0, nextGroups.size))
    }

    data class DeleteGroupResult(
        val groups: List<ProviderGroup>,
        val ungroupedIndex: Int,
        val providerGroupMap: Map<String, String>,
        val collapsed: Map<String, Boolean>,
    )

    fun deleteProviderGroup(
        groups: List<ProviderGroup>,
        ungroupedIndex: Int,
        providerGroupMap: Map<String, String>,
        collapsed: Map<String, Boolean>,
        groupId: String,
    ): DeleteGroupResult {
        val removedGroupIndex = groups.indexOfFirst { it.id == groupId }
        val nextGroups = groups.filter { it.id != groupId }
        val normalizedUngroupedIndex = ungroupedIndex.coerceIn(0, groups.size)
        val nextUngroupedIndex = if (removedGroupIndex >= 0 && removedGroupIndex < normalizedUngroupedIndex) {
            normalizedUngroupedIndex - 1
        } else {
            normalizedUngroupedIndex
        }
        val nextMap = providerGroupMap.filterValues { it != groupId }
        val nextCollapsed = collapsed.filterKeys { it != groupId }
        return DeleteGroupResult(nextGroups, nextUngroupedIndex.coerceIn(0, nextGroups.size), nextMap, nextCollapsed)
    }
}
