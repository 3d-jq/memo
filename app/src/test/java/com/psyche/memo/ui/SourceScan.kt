package com.psyche.memo.ui

/**
 * 源码扫描类测试的公共工具。
 *
 * `blankComments` 把注释抹成空白（**保留换行**，行号不漂）：守卫测试都会在注释里写
 * 「以前是 `remember { dao.getAll() }`」「以前是 `animateScrollToItem(…, Int.MAX_VALUE)`」
 * 这类反例，不抹掉的话守卫会把自己的说明文档当成违规。
 */
internal fun blankComments(code: String): String {
    val sb = StringBuilder(code.length)
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            c == '"' || c == '\'' -> {
                val next = skipStringLiteral(code, i)
                sb.append(code, i, next)
                i = next
            }
            c == '/' && code.getOrNull(i + 1) == '/' -> {
                while (i < code.length && code[i] != '\n') {
                    sb.append(' ')
                    i++
                }
            }
            c == '/' && code.getOrNull(i + 1) == '*' -> {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) code.length else close + 2
                for (j in i until end) sb.append(if (code[j] == '\n') '\n' else ' ')
                i = end
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/** 返回字符串字面量之后的下标（支持 `"…"`、`"""…"""`、`'…'`）。 */
internal fun skipStringLiteral(source: String, start: Int): Int {
    val quote = source[start]
    if (quote == '"' && source.startsWith("\"\"\"", start)) {
        val end = source.indexOf("\"\"\"", start + 3)
        return if (end < 0) source.length else end + 3
    }
    var i = start + 1
    while (i < source.length) {
        val c = source[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == quote || c == '\n') return i + 1
        i++
    }
    return source.length
}
