package com.psyche.memo.provider.browser

/** 元素在视口里的位置（CSS 像素，来自 getBoundingClientRect）。 */
data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

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
 * 给模型的 find 文本：`[index] tag(role) "文案" -> href` 一行一条，首行带 generation，
 * 溢出时说明还剩几个。**绝不输出 selector**。
 */
fun renderFindBlock(snapshot: BrowserPageSnapshot, limit: Int = FIND_LIMIT): String = buildString {
    appendLine("generation=${snapshot.generation} —— click/type 必须回传它；页面一变就要重新 find。")
    snapshot.elements.take(limit).forEach { e ->
        val label = e.text.ifBlank { e.placeholder.orEmpty() }
        append('[').append(e.index).append("] ").append(e.tag)
        e.role?.let { append('(').append(it).append(')') }
        if (label.isNotBlank()) append(" \"").append(label.take(60)).append('"')
        e.href?.let { append(" -> ").append(it.take(80)) }
        appendLine()
    }
    val hidden = snapshot.elements.size - limit
    if (hidden > 0) appendLine("…还有 $hidden 个未列出：用 scroll 之后重新 find。")
}
