package com.psyche.memo.provider.workspace

/**
 * `workspace_edit_file` 的三级文本替换器 —— 1:1 移植 RikkaHub `data/ai/tools/TextReplacers.kt`。
 *
 * 逐级尝试，**前一级找不到任何匹配时**才降级到下一级更宽松的策略：
 *
 *  1. `exact` —— 精确匹配；
 *  2. `line_trimmed` —— 逐行 trim 后比较，容忍缩进/行尾空白/CRLF 差异；
 *  3. `block_anchor` —— old_text ≥3 行时，只用**首尾行**做锚点，容忍中间行的细微差异。
 *
 * 两个容易漏的细节（上游都有，照抄）：
 *  - 命中后要按**匹配处的真实缩进**重排 `new_text`（[reindent]）—— 否则模型给的相对缩进会
 *    把代码改乱；
 *  - `"foo\n"` 语义上是一行：行尾换行产生的空尾行要丢掉，`new_text` 同步处理（对称）。
 */
interface TextReplacer {
    val name: String

    fun findMatches(content: String, oldText: String, newText: String): List<Match>

    data class Match(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
    )
}

data class ReplaceTextResult(
    val updated: String,
    val replacements: Int,
    /** 命中的**总处数**（`replace_all=false` 且命中多处时会让调用方报错）。 */
    val occurrences: Int,
    val strategy: String,
)

val WorkspaceEditReplacers: List<TextReplacer> = listOf(
    ExactReplacer,
    LineTrimmedReplacer,
    BlockAnchorReplacer,
)

/**
 * 逐级尝试，用第一个产生匹配的替换器。
 * 命中多处且 `replaceAll` 为 false 时**抛错**，而不是猜要改哪一处。
 */
fun replaceWorkspaceText(
    content: String,
    oldText: String,
    newText: String,
    replaceAll: Boolean,
    replacers: List<TextReplacer> = WorkspaceEditReplacers,
): ReplaceTextResult {
    require(oldText.isNotEmpty()) { "old_text must not be empty" }
    for (replacer in replacers) {
        val matches = replacer.findMatches(content, oldText, newText)
        if (matches.isEmpty()) continue
        if (!replaceAll) {
            require(matches.size == 1) {
                "old_text matches ${matches.size} locations (strategy: ${replacer.name}); " +
                    "add more surrounding context to make it unique, or set replace_all=true"
            }
        }
        val applied = if (replaceAll) matches.sortedBy { it.start } else listOf(matches.minBy { it.start })
        val builder = StringBuilder(content.length)
        var cursor = 0
        for (match in applied) {
            builder.append(content, cursor, match.start)
            builder.append(match.replacement)
            cursor = match.endExclusive
        }
        builder.append(content, cursor, content.length)
        return ReplaceTextResult(
            updated = builder.toString(),
            replacements = applied.size,
            occurrences = matches.size,
            strategy = replacer.name,
        )
    }
    throw IllegalArgumentException(
        "old_text was not found, even with whitespace-tolerant matching; " +
            "read the file again and copy old_text exactly from its current content",
    )
}

/** 第一级：精确匹配，非重叠计数，与 `String.replace` 语义一致。 */
object ExactReplacer : TextReplacer {
    override val name: String = STRATEGY_EXACT

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val matches = mutableListOf<TextReplacer.Match>()
        var index = content.indexOf(oldText)
        while (index >= 0) {
            matches += TextReplacer.Match(index, index + oldText.length, newText)
            index = content.indexOf(oldText, index + oldText.length)
        }
        return matches
    }
}

/** 行级窗口匹配的公共骨架：拆行滑窗比较，命中后按匹配处首行的真实缩进重排 `new_text`。 */
abstract class LineWindowReplacer : TextReplacer {

    protected abstract fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean

    /** old_text 全是空白行时禁用宽松匹配，避免命中任意空白区域。 */
    protected open fun isApplicable(oldTrimmed: List<String>): Boolean = oldTrimmed.any { it.isNotEmpty() }

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val rawOldLines = oldText.lines()
        // "foo\n" 语义上是一行，去掉行尾换行产生的空尾行；new_text 同步处理保持对称。
        val dropTrailingEmpty = rawOldLines.size > 1 && rawOldLines.last().isEmpty()
        val oldLines = if (dropTrailingEmpty) rawOldLines.dropLast(1) else rawOldLines
        val oldTrimmed = oldLines.map { it.trim() }
        if (!isApplicable(oldTrimmed)) return emptyList()
        val adjustedNewText = if (dropTrailingEmpty) newText.removeOneTrailingNewline() else newText

        val contentLines = splitLinesWithOffsets(content)
        val matches = mutableListOf<TextReplacer.Match>()
        var index = 0
        while (index + oldLines.size <= contentLines.size) {
            val window = contentLines.subList(index, index + oldLines.size)
            if (windowMatches(window.map { it.text.trim() }, oldTrimmed)) {
                val replacement = reindent(
                    text = adjustedNewText,
                    oldIndent = indentOf(oldLines.first()),
                    newIndent = indentOf(window.first().text),
                )
                matches += TextReplacer.Match(window.first().start, window.last().endExclusive, replacement)
                index += oldLines.size
            } else {
                index++
            }
        }
        return matches
    }
}

/** 第二级：逐行 trim 后比较。 */
object LineTrimmedReplacer : LineWindowReplacer() {
    override val name: String = STRATEGY_LINE_TRIMMED

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed == oldTrimmed
}

/** 第三级：old_text ≥3 行时只用首尾行做锚点。 */
object BlockAnchorReplacer : LineWindowReplacer() {
    override val name: String = STRATEGY_BLOCK_ANCHOR

    override fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.size >= 3 && oldTrimmed.first().isNotEmpty() && oldTrimmed.last().isNotEmpty()

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed.first() == oldTrimmed.first() && windowTrimmed.last() == oldTrimmed.last()
}

internal const val STRATEGY_EXACT = "exact"
internal const val STRATEGY_LINE_TRIMMED = "line_trimmed"
internal const val STRATEGY_BLOCK_ANCHOR = "block_anchor"

private class LineWithOffset(val start: Int, val endExclusive: Int, val text: String)

/** 按 `\n` 切行并记住偏移；`\r\n` 的行尾 `\r` 不计入行内容（替换区间也不含它）。 */
private fun splitLinesWithOffsets(content: String): List<LineWithOffset> {
    val lines = mutableListOf<LineWithOffset>()
    var start = 0
    for (index in content.indices) {
        if (content[index] == '\n') {
            val end = if (index > start && content[index - 1] == '\r') index - 1 else index
            lines += LineWithOffset(start, end, content.substring(start, end))
            start = index + 1
        }
    }
    lines += LineWithOffset(start, content.length, content.substring(start))
    return lines
}

private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

private fun reindent(text: String, oldIndent: String, newIndent: String): String {
    if (oldIndent == newIndent) return text
    return text.lines().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.startsWith(oldIndent) -> newIndent + line.removePrefix(oldIndent)
            else -> line
        }
    }
}

private fun String.removeOneTrailingNewline(): String = when {
    endsWith("\r\n") -> dropLast(2)
    endsWith("\n") -> dropLast(1)
    else -> this
}
