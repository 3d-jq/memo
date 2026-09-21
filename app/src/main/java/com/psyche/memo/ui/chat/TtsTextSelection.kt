package com.psyche.memo.ui.chat

/**
 * tts_text_selection.dart 1:1 —— 「朗读取哪部分文本」的五种模式。
 *
 * 上游只有一个消费点：朗读**助手消息**（home_page_controller.dart:1818
 * `_speakAssistantMessage`，手动按钮与自动播放共用）。本地工具朗读、语音服务页的
 * 「听测试」、悬浮条重播都不施加选取，所以接线时别放进 [TtsPlayer] 的通用入口。
 */
enum class TtsTextSelectionMode {
    fullText,
    quotedOnly,
    outsideParentheses,
    italicOnly,
    nonItalic,
}

/** 存储值就是枚举名（`extension TtsTextSelectionModeStorage`）；认不出的一律 fullText。 */
fun ttsTextSelectionModeOf(value: String?): TtsTextSelectionMode =
    TtsTextSelectionMode.values().firstOrNull { it.name == value }
        ?: TtsTextSelectionMode.fullText

private class TextRange(val start: Int, val end: Int)

private class ItalicMatch(val range: TextRange, val text: String)

object TtsTextSelection {

    fun apply(
        input: String,
        mode: TtsTextSelectionMode,
        fallbackToOriginal: Boolean = true,
    ): String {
        val original = input.trim()
        if (original.isEmpty()) return ""
        val source = if (mode == TtsTextSelectionMode.fullText) {
            original
        } else {
            markdownRemoveCode(original).trim()
        }
        if (source.isEmpty()) return ""

        val selected = when (mode) {
            TtsTextSelectionMode.fullText -> source
            TtsTextSelectionMode.quotedOnly -> quotedText(source)
            TtsTextSelectionMode.outsideParentheses -> outsideParentheses(source)
            TtsTextSelectionMode.italicOnly -> italicText(source)
            TtsTextSelectionMode.nonItalic -> nonItalicText(source)
        }
        val normalized = normalizeSelectedText(selected)
        if (normalized.isNotEmpty() || !fallbackToOriginal) return normalized
        return source
    }

    private val pairedQuotes = mapOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
    )

    private fun quotedText(input: String): String {
        val ranges = ArrayList<TextRange>()
        var i = 0
        while (i < input.length) {
            val char = input[i]
            val close = pairedQuotes[char]
            if (close != null) {
                val end = input.indexOf(close, i + 1)
                if (end > i + 1) {
                    ranges.add(TextRange(i + 1, end))
                    i = end + 1
                    continue
                }
            } else if ((char == '"' || char == '\'') && isStraightQuoteOpening(input, i)) {
                val end = findStraightQuoteClose(input, i + 1, char)
                if (end > i + 1) {
                    ranges.add(TextRange(i + 1, end))
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return joinRanges(input, ranges)
    }

    private fun outsideParentheses(input: String): String {
        val buffer = StringBuilder()
        var depth = 0
        for (i in input.indices) {
            val char = input[i]
            if (char == '(' || char == '（') {
                if (buffer.isNotEmpty()) buffer.append(' ')
                depth++
                continue
            }
            if ((char == ')' || char == '）') && depth > 0) {
                depth--
                continue
            }
            if (depth == 0) buffer.append(char)
        }
        return buffer.toString()
    }

    private fun italicText(input: String): String =
        collectItalicMatches(input)
            .map { normalizeInlineWhitespace(it.text) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun nonItalicText(input: String): String =
        removeRanges(input, collectItalicMatches(input).map { it.range })

    private val htmlItalicPattern = Regex("<(em|i)\\b[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)

    private fun collectItalicMatches(input: String): List<ItalicMatch> {
        val matches = ArrayList<ItalicMatch>()
        for (match in htmlItalicPattern.findAll(input)) {
            matches.add(ItalicMatch(TextRange(match.range.first, match.range.last + 1), match.groupValues[2]))
        }
        var i = 0
        while (i < input.length) {
            val marker = input[i]
            if ((marker == '*' || marker == '_') &&
                isSingleMarkdownMarker(input, i, marker) &&
                isMarkdownItalicOpening(input, i, marker)
            ) {
                val end = findMarkdownItalicClose(input, i + 1, marker)
                if (end > i + 1) {
                    matches.add(
                        ItalicMatch(
                            range = TextRange(i, end + 1),
                            text = input.substring(i + 1, end),
                        ),
                    )
                    i = end + 1
                    continue
                }
            }
            i++
        }
        matches.sortBy { it.range.start }
        return withoutOverlaps(matches)
    }

    private fun withoutOverlaps(matches: List<ItalicMatch>): List<ItalicMatch> {
        val result = ArrayList<ItalicMatch>()
        var lastEnd = -1
        for (match in matches) {
            if (match.range.start < lastEnd) continue
            result.add(match)
            lastEnd = match.range.end
        }
        return result
    }

    private fun findMarkdownItalicClose(input: String, start: Int, marker: Char): Int {
        for (i in start until input.length) {
            if (input[i] != marker) continue
            if (!isSingleMarkdownMarker(input, i, marker)) continue
            if (i == start || isWhitespace(input[i - 1])) continue
            if (marker == '_' && i + 1 < input.length && isAsciiLetterOrDigit(input[i + 1])) continue
            return i
        }
        return -1
    }

    private fun isMarkdownItalicOpening(input: String, index: Int, marker: Char): Boolean {
        if (index + 1 >= input.length || isWhitespace(input[index + 1])) return false
        if (marker == '_' && index > 0 && isAsciiLetterOrDigit(input[index - 1])) return false
        return true
    }

    private fun isSingleMarkdownMarker(input: String, index: Int, marker: Char): Boolean {
        val previousSame = index > 0 && input[index - 1] == marker
        val nextSame = index + 1 < input.length && input[index + 1] == marker
        return !previousSame && !nextSame
    }

    private fun isStraightQuoteOpening(input: String, index: Int): Boolean {
        if (index + 1 >= input.length || isWhitespace(input[index + 1])) return false
        if (index == 0) return true
        return !isAsciiLetterOrDigit(input[index - 1])
    }

    private fun findStraightQuoteClose(input: String, start: Int, quote: Char): Int {
        for (i in start until input.length) {
            if (input[i] != quote) continue
            if (i == start || isWhitespace(input[i - 1])) continue
            if (quote == '\'' &&
                i + 1 < input.length &&
                isAsciiLetterOrDigit(input[i - 1]) &&
                isAsciiLetterOrDigit(input[i + 1])
            ) {
                continue
            }
            return i
        }
        return -1
    }

    private fun joinRanges(input: String, ranges: List<TextRange>): String =
        ranges.map { input.substring(it.start, it.end) }
            .map { normalizeInlineWhitespace(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun removeRanges(input: String, ranges: List<TextRange>): String {
        if (ranges.isEmpty()) return input
        val sorted = ranges.sortedBy { it.start }
        val buffer = StringBuilder()
        var cursor = 0
        for (range in sorted) {
            if (range.start < cursor) continue
            buffer.append(input, cursor, range.start)
            cursor = range.end
        }
        buffer.append(input.substring(cursor))
        return buffer.toString()
    }

    private fun normalizeSelectedText(input: String): String =
        input.split(Regex("\n+"))
            .map { normalizeInlineWhitespace(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun normalizeInlineWhitespace(input: String): String =
        input.replace(Regex("\\s+"), " ").trim()

    private fun isWhitespace(char: Char): Boolean = char.isWhitespace()

    private fun isAsciiLetterOrDigit(char: Char): Boolean =
        char in '0'..'9' || char in 'A'..'Z' || char in 'a'..'z'
}

/**
 * markdown_line_lexer.dart `markdownRemoveCode`（L93-121）—— 去掉围栏与行内代码，
 * 保留行结构：整行围栏换成一个空格，配对的行内码整段换成一个空格。
 */
internal fun markdownRemoveCode(text: String): String {
    if (!text.contains('`') && !text.contains("~~~")) return text
    val lexer = FenceLexer()
    val output = StringBuilder()
    var cursor = 0
    while (cursor < text.length) {
        var lineEnd = cursor
        while (lineEnd < text.length && !isLogicalLineBreak(text[lineEnd])) lineEnd++
        val line = text.substring(cursor, lineEnd)
        val prefix = FENCE_CONTAINER_PREFIX.find(line)?.let { it.range.first + it.value.length } ?: 0
        val fenceLine = line.substring(minOf(prefix, line.length))
        if (lexer.consumeFence(fenceLine)) {
            output.append(' ')
        } else {
            output.append(withoutInlineCode(line))
        }
        if (lineEnd >= text.length) break
        val next = if (text[lineEnd] == '\r' && lineEnd + 1 < text.length && text[lineEnd + 1] == '\n') {
            lineEnd + 2
        } else {
            lineEnd + 1
        }
        output.append(text, lineEnd, next)
        cursor = next
    }
    return output.toString()
}

/** `^[ \t]*(?:(?:>[ \t]*)|(?:(?:[*+-]|\d+\.)[ \t]+))*` —— 引用/列表前缀后的真围栏起点。 */
private val FENCE_CONTAINER_PREFIX = Regex("^[ \\t]*(?:(?:>[ \\t]*)|(?:(?:[*+-]|\\d+\\.)[ \\t]+))*")

/**
 * `_LineBackticks`：长度相同的两个反引号串算一对，配对成功就把整段（含两侧标记）
 * 换成一个空格；没配对的保持原样。区间内的其它串一并消费，避免二次配对。
 */
private fun withoutInlineCode(line: String): String {
    val starts = ArrayList<Int>()
    val lengths = ArrayList<Int>()
    var i = 0
    while (i < line.length) {
        if (line[i] != '`') {
            i++
            continue
        }
        val start = i
        i++
        while (i < line.length && line[i] == '`') i++
        starts.add(start)
        lengths.add(i - start)
    }
    if (starts.isEmpty()) return line

    val jump = HashMap<Int, Int>()
    val consumed = BooleanArray(starts.size)
    val lastByLength = HashMap<Int, Int>()
    val nextSame = IntArray(starts.size) { -1 }
    for (r in starts.indices.reversed()) {
        nextSame[r] = lastByLength[lengths[r]] ?: -1
        lastByLength[lengths[r]] = r
    }
    for (r in starts.indices) {
        if (consumed[r]) continue
        val closer = nextSame[r]
        if (closer >= 0) {
            jump[starts[r]] = starts[closer] + lengths[closer]
            for (k in r..closer) consumed[k] = true
        } else {
            jump[starts[r]] = starts[r] + lengths[r]
            consumed[r] = true
        }
    }

    val output = StringBuilder()
    var cursor = 0
    var index = 0
    var removed = false
    while (index < line.length) {
        if (line[index] != '`') {
            index++
            continue
        }
        var runEnd = index + 1
        while (runEnd < line.length && line[runEnd] == '`') runEnd++
        val spanEnd = jump[index] ?: index + 1
        if (spanEnd > runEnd) {
            output.append(line, cursor, index).append(' ')
            cursor = spanEnd
            index = spanEnd
            removed = true
        } else {
            index = runEnd
        }
    }
    if (!removed) return line
    output.append(line.substring(cursor))
    return output.toString()
}

/**
 * 朗读一条**助手消息**前要念的文（`home_page_controller.dart:1818`）。
 * 模式取自 `tts_text_selection_mode_v1`（存的是枚举名），两处调用方都是**现读**：
 * [TtsPlayer.speakAssistantReply] 与自动播放 —— 设置页改完立刻生效，不缓存进组合。
 */
fun assistantReplyForTts(modeValue: String?, content: String): String =
    TtsTextSelection.apply(content, mode = ttsTextSelectionModeOf(modeValue))

/**
 * tts_provider.dart `_stripMarkdown`（L983-995）—— 朗读前把 markdown 压成一行纯文本。
 * 链接与图片的先后顺序照上游（先链接后图片，所以 `![a](u)` 会先被链接规则吃掉一半）。
 */
internal fun stripMarkdownForTts(input: String): String {
    var s = markdownRemoveCode(input)
    s = LINK_PATTERN.replace(s) { it.groupValues[1] }
    s = IMAGE_PATTERN.replace(s, " ")
    s = BLOCK_MARKERS.replace(s, "")
    s = EMPHASIS.run { replace(s, "") }
    s = s.replace("|", " ")
    return WHITESPACE_RUN.replace(s, " ")
}

private val LINK_PATTERN = Regex("""\[([^]]+)]\([^)]*\)""")
private val IMAGE_PATTERN = Regex("""![[^]]*]\([^)]*\)""")
private val BLOCK_MARKERS = Regex("^[#>\\-*+]+\\s*", RegexOption.MULTILINE)
private val EMPHASIS = Regex("[*_~]{1,3}")
private val WHITESPACE_RUN = Regex("\\s+")
