package com.psyche.memo.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * timeline_visibility.dart `parseToolResultImages`（49-124）的纯逻辑移植。
 *
 * 只有占满整行的 `![alt](url)` 才视为附件：图片标记从正文剥离成图片路径列表
 * （首见去重），正文保留非图片行。JSON 字符串、代码围栏或正文段落里的图片
 * 不会误判。
 *
 * 源码的 mcpResult metadata / legacy envelope / PUA 结构化行三个分支被整体
 * 略去：Memo 与上游数据不兼容（memo.db / dropAll 升级），旧 kelivo 持久化
 * 格式永远不会出现在本应用写入的内容里，按 agents.md「移除过时路径」处理。
 * 审批卡里显示的参数摘要 `_argsSummary`（chat_message_widget.dart 5660-5670）
 * 一并落在这里。
 */

/**
 * (cleanText, imagePaths) —— Dart 返回的 record 两个字段。
 */
internal fun parseToolResultImages(content: String?): Pair<String, List<String>> {
    if (content.isNullOrEmpty()) return "" to emptyList()

    val images = ArrayList<String>()
    val seen = HashSet<String>()
    val kept = ArrayList<String>()
    val lexer = FenceLexer()

    for (line in toolResultLogicalLines(content)) {
        if (isIndentedCodeLine(line)) {
            kept.add(line)
            continue
        }
        if (lexer.consumeFence(line)) {
            kept.add(line)
            continue
        }
        when (val classified = classifyStandaloneMarkdownImageLine(line.trim())) {
            is ClassifiedImageLine.Image -> {
                if (seen.add(classified.path)) images.add(classified.path)
                continue
            }
            ClassifiedImageLine.Placeholder -> continue
            ClassifiedImageLine.NotImage -> kept.add(line)
        }
    }
    return kept.joinToString("\n").trim() to images
}

// ---------------------------------------------------------------------------
// Logical lines / fence lexer (timeline_visibility.dart + markdown_line_lexer.dart)
// ---------------------------------------------------------------------------

/** 逻辑行切分：\r、\n、U+2028、U+2029 都算断行，\r\n 按一个断行处理。 */
internal fun toolResultLogicalLines(content: String): List<String> {
    val lines = ArrayList<String>()
    var i = 0
    val end = content.length
    while (i < end) {
        var lineEnd = i
        while (lineEnd < end && !isLogicalLineBreak(content[lineEnd])) lineEnd++
        lines.add(content.substring(i, lineEnd))
        if (lineEnd >= end) break
        i = if (content[lineEnd] == '\r' && lineEnd + 1 < end && content[lineEnd + 1] == '\n') {
            lineEnd + 2
        } else {
            lineEnd + 1
        }
    }
    return lines
}

internal fun isLogicalLineBreak(c: Char): Boolean =
    c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

private fun isIndentedCodeLine(line: String): Boolean = leadingIndentColumns(line) >= 4

private fun leadingIndentColumns(line: String): Int {
    var columns = 0
    for (c in line) {
        when (c) {
            ' ' -> columns += 1
            '\t' -> columns += 4 - (columns % 4)
            else -> break
        }
    }
    return columns
}

/** markdown_line_lexer.dart consumeFence —— 只跟踪围栏，不关心 details。 */
internal class FenceLexer {
    private var marker: Char? = null
    private var length = 0

    /** True when this line is inside a fence — including the line that just closed one. */
    fun consumeFence(line: String): Boolean {
        val wasFenced = marker != null
        updateFence(line)
        return marker != null || wasFenced
    }

    private fun updateFence(line: String) {
        val mark = fenceMarkOf(line) ?: return
        if (marker == null) {
            if (!mark.canOpen) return
            marker = mark.marker
            length = mark.length
            return
        }
        if (!mark.canClose) return
        if (mark.marker != marker || mark.length < length) return
        marker = null
        length = 0
    }
}

private class FenceMark(
    val marker: Char,
    val length: Int,
    val canClose: Boolean,
    val canOpen: Boolean,
)

/** CommonMark 风格围栏：同类标记、长度 ≥3、关闭行可长不可短。 */
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
        // CommonMark：反引号围栏的 info string 不能含反引号。
        if (marker == '`' && unit == '`') canOpen = false
    }
    return FenceMark(marker, length, canClose, canOpen)
}

// ---------------------------------------------------------------------------
// Standalone markdown image line (timeline_visibility.dart 184-251)
// ---------------------------------------------------------------------------

private sealed class ClassifiedImageLine {
    data class Image(val path: String) : ClassifiedImageLine()
    data object Placeholder : ClassifiedImageLine()
    data object NotImage : ClassifiedImageLine()
}

private fun classifyStandaloneMarkdownImageLine(trimmedLine: String): ClassifiedImageLine {
    if (!trimmedLine.startsWith("![")) return ClassifiedImageLine.NotImage
    val altClose = trimmedLine.indexOf("](")
    if (altClose == -1) return ClassifiedImageLine.NotImage
    val destStart = altClose + 2
    val parsed = readMarkdownImageDestination(trimmedLine, destStart) ?: return ClassifiedImageLine.NotImage
    val raw = parsed.trim()
    if (raw.isEmpty() || raw == "generated") return ClassifiedImageLine.Placeholder
    return ClassifiedImageLine.Image(decodeMarkdownImageDestination(raw))
}

/** 目的地占满整行剩余部分并以最后一个 `)` 收尾时返回原始目的地（含 `<>`）。 */
private fun readMarkdownImageDestination(line: String, destStart: Int): String? {
    if (destStart >= line.length) return null
    if (line[destStart] == '<') {
        var j = destStart + 1
        while (j < line.length) {
            val ch = line[j]
            if (ch == '\\' && j + 1 < line.length) {
                j += 2
                continue
            }
            if (ch == '>') {
                if (j + 1 == line.length - 1 && line[j + 1] == ')') {
                    return line.substring(destStart, j + 1)
                }
                return null
            }
            j += 1
        }
        return null
    }
    var depth = 1
    var j = destStart
    while (j < line.length && depth > 0) {
        val ch = line[j]
        if (ch == '(' && !isEscapedByOddBackslashes(line, j)) {
            depth += 1
        } else if (ch == ')' && !isEscapedByOddBackslashes(line, j)) {
            depth -= 1
            if (depth == 0) break
        }
        j += 1
    }
    if (depth != 0 || j != line.length - 1) return null
    return line.substring(destStart, j)
}

private fun isEscapedByOddBackslashes(text: String, index: Int): Boolean {
    var slashes = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
        slashes += 1
        i -= 1
    }
    return slashes % 2 == 1
}

// ---------------------------------------------------------------------------
// Destination decode (mcp_structured_image.dart 244-338)
// ---------------------------------------------------------------------------

internal fun decodeMarkdownImageDestination(raw: String): String {
    val dest = raw.trim()
    if (dest.length >= 2 && dest.startsWith('<') && dest.endsWith('>')) {
        return unescapeAngleBracketDestination(dest.substring(1, dest.length - 1))
    }
    if (isWindowsImageDestination(dest)) return dest
    return unescapeMarkdownDestination(dest)
}

private fun isWindowsImageDestination(dest: String): Boolean {
    if (dest.startsWith("\\\\")) return true
    return dest.length >= 3 &&
        dest[1] == ':' &&
        (dest[2] == '\\' || dest[2] == '/') &&
        isDriveLetter(dest[0])
}

/**
 * `encodeMarkdownImageDestination`（mcp_structured_image.dart:238-242）—— MCP 工具结果
 * 里的图片要就地写成一行 `![](...)`，路径含空格/括号/`#`/`%`/`<>`/反斜杠或非 ASCII 时
 * 必须包进 `<>` 并转义，否则解析方（上面的 decode）会把目的地截断。
 */
internal fun encodeMarkdownImageDestination(uri: String): String {
    if (uri.isEmpty()) return uri
    if (!destinationNeedsAngleBrackets(uri)) return uri
    return "<" + escapeAngleBracketDestination(uri) + ">"
}

private fun destinationNeedsAngleBrackets(uri: String): Boolean {
    if (isWindowsImageDestination(uri)) return true
    for (ch in uri) {
        val code = ch.code
        if (code <= 0x20) return true
        if (code == 0x28 || code == 0x29 || code == 0x5C || code == 0x23 ||
            code == 0x25 || code == 0x3C || code == 0x3E
        ) {
            return true
        }
        if (code > 0x7E) return true
    }
    return false
}

private fun escapeAngleBracketDestination(uri: String): String {
    val buf = StringBuilder()
    for (ch in uri) {
        if (ch == '\\' || ch == '>') buf.append('\\')
        buf.append(ch)
    }
    return buf.toString()
}

/** 只反转义 Markdown 标点；Windows 路径反斜杠保留。 */
private fun unescapeMarkdownDestination(raw: String): String {
    val buf = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\\' && i + 1 < raw.length) {
            val next = raw[i + 1]
            if (isMarkdownAsciiPunctuation(next)) {
                buf.append(next)
                i += 2
                continue
            }
        }
        buf.append(ch)
        i += 1
    }
    return buf.toString()
}

private fun unescapeAngleBracketDestination(raw: String): String {
    val buf = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val unit = raw[i]
        if (unit == '\\' && i + 1 < raw.length) {
            val next = raw[i + 1]
            if (next == '\\' || next == '>') {
                buf.append(next)
                i += 2
                continue
            }
        }
        buf.append(unit)
        i += 1
    }
    return buf.toString()
}

private fun isDriveLetter(unit: Char): Boolean =
    unit in 'A'..'Z' || unit in 'a'..'z'

private fun isMarkdownAsciiPunctuation(unit: Char): Boolean {
    val code = unit.code
    return (code in 0x21..0x2F) || (code in 0x3A..0x40) || (code in 0x5B..0x60) || (code in 0x7B..0x7E)
}

// ---------------------------------------------------------------------------
// Approval argument summary (chat_message_widget.dart 5660-5670)
// ---------------------------------------------------------------------------

/**
 * 审批卡上的参数摘要：前 1-2 个 key=value，值超 40 字符截断；多于 2 个键加
 * ` ...` 后缀。
 */
internal fun argsSummary(args: JsonObject): String {
    if (args.isEmpty()) return ""
    val entries = args.entries.take(2).map { (key, value) ->
        val text = value.displayText()
        val truncated = if (text.length > 40) "${text.take(40)}..." else text
        "$key: $truncated"
    }
    val suffix = if (args.size > 2) " ..." else ""
    return entries.joinToString(", ") + suffix
}

/** Dart `value?.toString()` 的近似：字符串不带引号，对象/数组输出紧凑 JSON。 */
private fun JsonElement.displayText(): String = when (this) {
    // JsonNull 是 JsonPrimitive 的子类，必须先于 JsonPrimitive 匹配。
    JsonNull -> ""
    is JsonPrimitive -> content
    is JsonObject, is JsonArray -> toString()
}
