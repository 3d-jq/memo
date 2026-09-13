package com.psyche.memo.ui.chat

/**
 * 1:1 port of `lib/features/chat/utils/assistant_paragraph_splitter.dart` —
 * 「一个段落一个气泡」的切分（`assistantBubbleSplitParagraphs` 打开时）。
 *
 * 规则与原版一致：
 * - 空行 = 段落边界，但**围栏代码块内**的空行不算（`MarkdownLineLexer` 的
 *   fence 规则：缩进 `[ \t]`、标记 ``` 或 ~~~、run ≥ 3、closer 同字符且不更短、
 *   收尾只能是空格/制表符；反引号围栏的 info string 不能含反引号）。
 * - 空行后面跟缩进行（4 空格 / 制表符）属于上一块的续行，不切。
 * - 相邻的列表块合并（否则每个气泡都会从 1 重新编号）。
 * - 只有标题的块并入它引出的下一块。
 *
 * **未移植的两条保护**：原版还借 `markdownScanDisplayMath` 保护 `$$…$$` /
 * `\[…\]`，以及 `<details>` 块。本工程的渲染器既没有数学渲染也没有 HTML 块
 * （`MarkdownRenderer` 没有 HtmlBlock 分支），所以这两类内容在 Memo 里本就
 * 不是「块」，无需保护。
 *
 * 切不动时返回只含原串的单元素列表（原版 L51-52）。
 */
fun splitAssistantParagraphs(text: String): List<String> {
    if (text.isBlank()) return listOf(text)

    val lines = text.split('\n')
    val chunks = ArrayList<String>()
    val current = ArrayList<String>()
    val lexer = MarkdownLineLexer()

    fun flush() {
        val chunk = current.joinToString("\n").trim()
        if (chunk.isNotEmpty()) chunks.add(chunk)
        current.clear()
    }

    for (i in lines.indices) {
        val line = lines[i]
        lexer.consume(line)
        val breaksParagraph = line.isBlank() && !lexer.protected && !continuesBlock(lines, i + 1)
        if (breaksParagraph) {
            flush()
            continue
        }
        current.add(line)
    }
    flush()

    if (chunks.size < 2) return listOf(text)
    return mergeRelatedChunks(chunks)
}

private val LIST_ITEM_PATTERN = Regex("^\\s*([-*+]\\s|\\d+[.)]\\s)")
private val HEADING_PATTERN = Regex("^#{1,6}\\s")

/** splitter L60-66 —— 空行后面是缩进行 ⇒ 续行，不切。 */
private fun continuesBlock(lines: List<String>, from: Int): Boolean {
    for (i in from until lines.size) {
        if (lines[i].isBlank()) continue
        return lines[i].startsWith("    ") || lines[i].startsWith("\t")
    }
    return false
}

private fun mergeRelatedChunks(chunks: List<String>): List<String> {
    val merged = ArrayList<String>()
    for (chunk in chunks) {
        val last = merged.lastOrNull()
        if (last != null && shouldMerge(last, chunk)) {
            merged[merged.size - 1] = "$last\n\n$chunk"
            continue
        }
        merged.add(chunk)
    }
    return merged
}

private fun shouldMerge(previous: String, next: String): Boolean {
    if (isHeadingOnly(previous)) return true
    return startsList(previous) && startsList(next)
}

private fun isHeadingOnly(chunk: String): Boolean =
    !chunk.contains('\n') && HEADING_PATTERN.containsMatchIn(chunk)

private fun startsList(chunk: String): Boolean = LIST_ITEM_PATTERN.containsMatchIn(chunk)

/**
 * `markdown_line_lexer.dart` 的 fence 部分：只有 `protected` 被切分器用到的
 * 那点半状态。
 */
internal class MarkdownLineLexer {
    private var fenceMarker: Char? = null
    private var fenceLength = 0

    /** consume 之后为 true ⇒ 本行处于围栏内（含刚闭合的那一行）。 */
    val protected: Boolean get() = fenceMarker != null

    fun consume(line: String) {
        val mark = fenceMarkOf(line) ?: return
        val marker = fenceMarker
        if (marker == null) {
            if (!mark.canOpen) return
            fenceMarker = mark.marker
            fenceLength = mark.length
            return
        }
        if (!mark.canClose) return
        if (mark.marker != marker || mark.length < fenceLength) return
        fenceMarker = null
        fenceLength = 0
    }

    private data class FenceMark(
        val marker: Char,
        val length: Int,
        val canClose: Boolean,
        val canOpen: Boolean,
    )

    /** markdown_line_lexer.dart:799-838 `_fenceMarkOf`。 */
    private fun fenceMarkOf(rawLine: String): FenceMark? {
        var indent = 0
        while (indent < rawLine.length && (rawLine[indent] == ' ' || rawLine[indent] == '\t')) indent++
        if (indent >= rawLine.length) return null
        val marker = rawLine[indent]
        if (marker != '`' && marker != '~') return null
        var n = indent + 1
        while (n < rawLine.length && rawLine[n] == marker) n++
        val length = n - indent
        if (length < 3) return null
        var canClose = true
        var canOpen = true
        for (i in n until rawLine.length) {
            val unit = rawLine[i]
            if (unit != ' ' && unit != '\t') canClose = false
            // CommonMark：反引号围栏的 info string 不能含反引号；波浪号围栏可以。
            if (marker == '`' && unit == '`') canOpen = false
        }
        return FenceMark(marker, length, canClose, canOpen)
    }
}
