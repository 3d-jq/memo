package com.psyche.memo.ui

/**
 * 供应商列表的**分组区块**计算 —— 移植 `_buildProviderGroupingRows`
 * （providers_page.dart L507-571）+ `buildProviderGroupDisplayKeys`
 * （utils/provider_grouping_logic.dart）。
 *
 * 规则（与原版逐条对齐）：
 * - 供应商按 `groupMap` 分桶；分组 id 已失效（分组被删）的落回「未分组」；
 * - 显示顺序 = 分组顺序，把「未分组」插到 `ungroupedPosition`（越界夹取）；
 * - **空分组不显示**；
 * - 搜索时：分组名命中就显示该组全部成员，否则只显示名字命中的成员；
 *   搜索期间不折叠（原版 `collapsed = searching ? false : isGroupCollapsed`）。
 *
 * 纯函数，便于单测；UI 侧把区块展平成「key → 该行要不要画组头」。
 */
internal data class ProviderSection(
    val groupKey: String,
    val title: String,
    val keys: List<String>,
    val collapsed: Boolean,
)

internal fun buildProviderSections(
    orderedKeys: List<String>,
    groupMap: Map<String, String>,
    /** (groupId, name)，按分组顺序。 */
    groups: List<Pair<String, String>>,
    ungroupedPosition: Int,
    ungroupedKey: String,
    ungroupedTitle: String,
    collapsedKeys: Set<String>,
    query: String,
    /** 某供应商是否命中搜索（只比显示名，和列表原有过滤一致）。 */
    matchesProvider: (String) -> Boolean,
): List<ProviderSection> {
    val validGroupIds = groups.map { it.first }.toSet()
    val buckets = LinkedHashMap<String, MutableList<String>>()
    for ((id, _) in groups) buckets[id] = mutableListOf()
    buckets[ungroupedKey] = mutableListOf()
    for (key in orderedKeys) {
        val gid = groupMap[key]
        val bucket = if (gid != null && gid in validGroupIds) gid else ungroupedKey
        buckets.getOrPut(bucket) { mutableListOf() }.add(key)
    }

    val displayKeys = ArrayList<String>(groups.size + 1)
    displayKeys.addAll(groups.map { it.first })
    displayKeys.add(ungroupedPosition.coerceIn(0, displayKeys.size), ungroupedKey)

    val q = query.trim()
    val searching = q.isNotEmpty()
    val out = ArrayList<ProviderSection>(displayKeys.size)
    for (groupKey in displayKeys) {
        val title = if (groupKey == ungroupedKey) {
            ungroupedTitle
        } else {
            groups.firstOrNull { it.first == groupKey }?.second ?: continue
        }
        val all = buckets[groupKey].orEmpty()
        val groupMatched = searching && title.contains(q, ignoreCase = true)
        val keys = if (!searching || groupMatched) all else all.filter(matchesProvider)
        if (keys.isEmpty()) continue
        out.add(
            ProviderSection(
                groupKey = groupKey,
                title = title,
                keys = keys,
                collapsed = !searching && groupKey in collapsedKeys,
            ),
        )
    }
    return out
}

/**
 * 展平成 LazyColumn 的项：`(providerKey, 该行要画的组头或 null)`。
 *
 * 组头**挂在区块第一项上**而不是单独占一个 item —— 这样 LazyColumn 的索引
 * 依然等于供应商索引，`shallow` 的拖拽库不会因为多出来的 header item 而错位
 * （这正是之前"拖拽重叠"那个 bug 的成因）。折叠的区块只留组头这一项。
 */
internal fun flattenProviderSections(
    sections: List<ProviderSection>,
): List<Pair<String, ProviderSection?>> {
    val out = ArrayList<Pair<String, ProviderSection?>>()
    for (section in sections) {
        section.keys.forEachIndexed { index, key ->
            if (section.collapsed && index > 0) return@forEachIndexed
            out.add(key to if (index == 0) section else null)
        }
    }
    return out
}
