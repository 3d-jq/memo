package com.psyche.memo.provider.browser

/** 元素在视口里的位置（CSS 像素，来自 getBoundingClientRect）。 */
data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * `<select>` 的一个可选项（spec §12.1 的 `browser_select`）。
 *
 * 只是给模型**看得懂能选什么**：`browser_select` 的 `value` 与这两半里的任意一半匹配即可，
 * 所以这里两个值都要带（很多站点的 `value` 是内部 id、只有 `text` 是人话）。
 */
data class SelectOption(
    val value: String,
    val text: String,
    val selected: Boolean,
)

/**
 * 一页里可交互元素的一条记录。
 *
 * [index] 是**唯一**递给模型的把手；[selector] 只在本机内部用来取回同一个节点。
 */
data class BrowserElement(
    val index: Int,
    val tag: String,
    val role: String?,
    val text: String,
    val placeholder: String?,
    val type: String?,
    val href: String?,
    val selector: String,
    val bounds: Bounds,
    /** `<select>` 的可选项（最多 [SELECT_OPTION_LIST_LIMIT] 个）；其它标签为空。 */
    val options: List<SelectOption> = emptyList(),
    /** 这个 select **实际**有多少个选项（> [options] 的条数时清单里写「…共 N 项」）。 */
    val optionTotal: Int = 0,
)

/** 一次 `find` 的结果。[generation] 由 [BrowserSession] 盖章，不是 JS 算的。 */
data class BrowserPageSnapshot(
    val generation: Int,
    val url: String,
    val title: String,
    val elements: List<BrowserElement>,
) {
    fun find(index: Int): BrowserElement? = elements.firstOrNull { it.index == index }
}

sealed class GenerationCheck {
    object Current : GenerationCheck()
    data class Stale(val seen: Int, val now: Int) : GenerationCheck()
    object UnknownGeneration : GenerationCheck()
}

/**
 * `browser_page_info` 的一页几何信息（spec §12.1，本工程新增）。
 *
 * 只有几何与标题这几项 —— 元素清单归 `find`、正文归 `read`。这里出现任何「页面内容」字段
 * 就等于把这颗工具变成第二个 find。
 */
data class BrowserPageInfo(
    val url: String,
    val title: String,
    val scrollY: Int,
    val scrollHeight: Int,
    val viewportHeight: Int,
    val atTop: Boolean,
    val atBottom: Boolean,
)

/**
 * 代次校验：不匹配一律不执行。`requested == null` 单独成一类 —— 「模型没传」和
 * 「传了旧的」该给的补救话不一样（前者是「先 find」，后者是「页面变了，重新 find」）。
 */
fun checkGeneration(requested: Int?, current: Int): GenerationCheck = when {
    requested == null -> GenerationCheck.UnknownGeneration
    requested == current -> GenerationCheck.Current
    else -> GenerationCheck.Stale(requested, current)
}

/** 一次 `find` 最多列多少个元素。JS 侧的循环上限也插值这个常量，只有一处定义。 */
const val FIND_LIMIT: Int = 20

/**
 * 一个 `<select>` 在 `find` 清单里最多列多少个 option（spec §12.1 的 `browser_select`）。
 *
 * 上限是**刻意的**：模型需要知道「能选什么」，但不需要一整页国家/年份下拉框 —— 几百条
 * option 会把 find 的文本淹没（一份清单 20 个元素 × 无上限 option = 上下文黑洞）。
 * 超出的部分报「…共 N 项」，让模型知道还有多少而不是以为只有这些。
 * JS 侧的循环上限与解析端的兜底（防页面给更多）都插值这一个常量。
 */
const val SELECT_OPTION_LIST_LIMIT: Int = 12

/**
 * 给模型的 find 文本：`[index] tag(role) "文案" -> href` 一行一条，首行带 generation，
 * 溢出时说明还剩几个。**绝不输出 selector**。
 */
fun renderFindBlock(snapshot: BrowserPageSnapshot, limit: Int = FIND_LIMIT): String = buildString {
    appendLine("generation=${snapshot.generation} —— click/type/select/wait 必须回传它；页面一变就要重新 find。")
    snapshot.elements.take(limit).forEach { e ->
        val label = e.text.ifBlank { e.placeholder.orEmpty() }
        append('[').append(e.index).append("] ").append(e.tag)
        e.role?.let { append('(').append(it).append(')') }
        if (label.isNotBlank()) append(" \"").append(label.take(60)).append('"')
        e.href?.let { append(" -> ").append(it.take(80)) }
        appendLine()
        // select 的可选项要带出来（browser_select 的 value 就照着这里选）。
        // 折空白 + 截断在解析端已经做过（BrowserScripts 那两道闸），这里只管排版。
        if (e.options.isNotEmpty()) {
            append("    可选: ")
            e.options.forEachIndexed { i, o ->
                if (i > 0) append(" | ")
                append(o.text.ifBlank { o.value }.take(40))
                if (o.selected) append('*')
            }
            if (e.optionTotal > e.options.size) append(" …共 ${e.optionTotal} 项")
            appendLine()
        }
    }
    val hidden = snapshot.elements.size - limit
    if (hidden > 0) appendLine("…还有 $hidden 个未列出：用 scroll 之后重新 find。")
}
