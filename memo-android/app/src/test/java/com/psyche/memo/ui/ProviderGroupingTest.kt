package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商列表分组区块（`_buildProviderGroupingRows` providers_page.dart L507-571 +
 * `buildProviderGroupDisplayKeys`）：分桶、未分组插入位置、空组隐藏、搜索时
 * 组名命中显示整组、折叠时只留组头、以及「组头跟着卡片」的展平方式。
 */
class ProviderGroupingTest {

    private val ungrouped = "__ungrouped__"
    private val groups = listOf("g1" to "工作", "g2" to "娱乐")

    private fun sections(
        orderedKeys: List<String>,
        groupMap: Map<String, String>,
        ungroupedPosition: Int = 0,
        collapsedKeys: Set<String> = emptySet(),
        query: String = "",
        matched: (String) -> Boolean = { true },
        groupList: List<Pair<String, String>> = groups,
    ) = buildProviderSections(
        orderedKeys = orderedKeys,
        groupMap = groupMap,
        groups = groupList,
        ungroupedPosition = ungroupedPosition,
        ungroupedKey = ungrouped,
        ungroupedTitle = "其他",
        collapsedKeys = collapsedKeys,
        query = query,
        matchesProvider = matched,
    )

    @Test
    fun providersAreBucketedByGroupAndUngroupedSitsAtItsPosition() {
        val out = sections(
            orderedKeys = listOf("a", "b", "c"),
            groupMap = mapOf("a" to "g1", "b" to "g2"),
            ungroupedPosition = 1,
        )
        // 显示顺序 = 分组顺序 + 未分组插到 index 1。
        assertEquals(listOf("工作", "其他", "娱乐"), out.map { it.title })
        assertEquals(listOf("a"), out[0].keys)
        assertEquals(listOf("c"), out[1].keys)
        assertEquals(listOf("b"), out[2].keys)
    }

    @Test
    fun unknownGroupIdFallsBackToUngroupedAndEmptyGroupsAreHidden() {
        val out = sections(
            orderedKeys = listOf("a", "b"),
            // g9 不存在（分组被删了）→ a 落回「其他」；g1 里没人 → 整组不显示。
            groupMap = mapOf("a" to "g9", "b" to "g2"),
            ungroupedPosition = 0,
        )
        assertEquals(listOf("其他", "娱乐"), out.map { it.title })
        assertEquals(listOf("a"), out[0].keys)
        assertEquals(listOf("b"), out[1].keys)
    }

    @Test
    fun searchShowsTheWholeGroupWhenItsNameMatches() {
        val out = sections(
            orderedKeys = listOf("a", "b"),
            groupMap = mapOf("a" to "g1", "b" to "g1"),
            query = "工作",
            matched = { false }, // 两个供应商名字都不命中
        )
        assertEquals(1, out.size)
        assertEquals(listOf("a", "b"), out[0].keys)
    }

    @Test
    fun searchFiltersMembersWhenOnlyTheProviderMatches() {
        val out = sections(
            orderedKeys = listOf("alpha", "beta"),
            groupMap = mapOf("alpha" to "g1", "beta" to "g1"),
            query = "alp",
            matched = { it.startsWith("alp") },
        )
        assertEquals(listOf("alpha"), out[0].keys)
        assertTrue(out.none { it.title == "其他" })
    }

    @Test
    fun searchingNeverCollapses() {
        val out = sections(
            orderedKeys = listOf("a"),
            groupMap = mapOf("a" to "g1"),
            collapsedKeys = setOf("g1"),
            query = "工作",
        )
        assertEquals(false, out[0].collapsed)
    }

    @Test
    fun collapseKeepsTheHeaderOnly() {
        val out = sections(
            orderedKeys = listOf("a", "b"),
            groupMap = mapOf("a" to "g1", "b" to "g1"),
            collapsedKeys = setOf("g1"),
        )
        assertEquals(true, out[0].collapsed)
        val flat = flattenProviderSections(out)
        // 折叠：只留一项，带组头、不画卡片。
        assertEquals(1, flat.size)
        assertEquals("a", flat[0].first)
        assertEquals(true, flat[0].second?.collapsed)
    }

    @Test
    fun headerRidesOnTheFirstCardSoLazyIndicesStayAligned() {
        val out = sections(
            orderedKeys = listOf("a", "b", "c"),
            groupMap = mapOf("a" to "g1", "b" to "g1", "c" to "g2"),
            ungroupedPosition = 2,
        )
        val flat = flattenProviderSections(out)
        // 每行仍然是「一个供应商一项」（索引 == 供应商索引），组头只挂第一张卡。
        assertEquals(listOf("a", "b", "c"), flat.map { it.first })
        assertEquals("工作", flat[0].second?.title)
        assertNull(flat[1].second)
        assertEquals("娱乐", flat[2].second?.title)
    }
}
