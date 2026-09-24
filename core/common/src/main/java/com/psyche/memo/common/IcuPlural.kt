package com.psyche.memo.common

/**
 * Flutter 的 intl 复数消息按原文落在 strings.xml 的 `<string>` 里，
 * Android 的 `String.format` 不认 `{n, plural, …}`，
 * 所以在这里按 ICU 子集求值：`=N` 精确匹配优先，其次英文类别 one/other，
 * 最后 other / 首个分支。分支体与命名占位符都可嵌套。
 */
object IcuPlural {
    fun format(pattern: String, values: Map<String, Any?>): String {
        val out = StringBuilder()
        render(pattern, values, out)
        return out.toString()
    }

    private fun render(text: String, values: Map<String, Any?>, out: StringBuilder) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '{') {
                out.append(c)
                i++
                continue
            }
            val end = matchingBrace(text, i)
            if (end < 0) {
                out.append(text, i, text.length)
                return
            }
            renderArg(text.substring(i + 1, end), values, out)
            i = end + 1
        }
    }

    private fun renderArg(inner: String, values: Map<String, Any?>, out: StringBuilder) {
        val firstComma = inner.indexOf(',')
        val name = (if (firstComma < 0) inner else inner.substring(0, firstComma)).trim()
        val secondComma = if (firstComma < 0) -1 else inner.indexOf(',', firstComma + 1)
        if (secondComma < 0) {
            if (name.isEmpty()) out.append("{}") else out.append(values[name]?.toString().orEmpty())
            return
        }
        val type = inner.substring(firstComma + 1, secondComma).trim()
        if (type != "plural") {
            out.append('{').append(inner).append('}')
            return
        }
        val branches = pluralBranches(inner.substring(secondComma + 1))
        val count = (values[name] as? Number)?.toInt()
            ?: values[name]?.toString()?.toDoubleOrNull()?.toInt()
            ?: 0
        val chosen = branches.firstOrNull { it.first == "=$count" }
            ?: branches.firstOrNull { it.first == pluralCategory(count) }
            ?: branches.firstOrNull { it.first == "other" }
            ?: branches.firstOrNull()
        if (chosen != null) render(chosen.second, values, out)
    }

    private fun pluralCategory(count: Int): String = if (count == 1) "one" else "other"

    private fun pluralBranches(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i] == ' ' || body[i] == ',')) i++
            val brace = body.indexOf('{', i)
            if (brace < 0) return out
            val key = body.substring(i, brace).trim()
            val end = matchingBrace(body, brace)
            if (end < 0) return out
            out.add(key to body.substring(brace + 1, end))
            i = end + 1
        }
        return out
    }

    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }
}
