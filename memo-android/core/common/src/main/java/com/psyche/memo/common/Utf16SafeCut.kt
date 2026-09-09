package com.psyche.memo.common

/**
 * Surrogate-safe UTF-16 cut helpers — port of lib/utils/utf16_safe_cut.dart.
 *
 * Kotlin strings are UTF-16 as well, so a plain `substring` can split a
 * surrogate pair and leave a lone surrogate behind, which breaks JSON/UTF-8
 * boundaries. Every cross-process truncation uses these helpers instead.
 */
object Utf16SafeCut {

    /** Head end that never lands between a high and its low surrogate. */
    fun headEnd(value: String, end: Int): Int {
        if (end <= 0 || end >= value.length) return end
        val prev = value[end - 1].code
        val cur = value[end].code
        if (!isHighSurrogate(prev) || !isLowSurrogate(cur)) return end
        return end - 1
    }

    /** Tail start that never lands between a high and its low surrogate. */
    fun tailStart(value: String, start: Int): Int {
        if (start <= 0 || start >= value.length) return start
        val prev = value[start - 1].code
        val cur = value[start].code
        if (!isHighSurrogate(prev) || !isLowSurrogate(cur)) return start
        return start + 1
    }

    /** Keeps the head, dropping a pair that straddles the cut whole. */
    fun truncateHead(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        return value.substring(0, headEnd(value, maxLength))
    }

    /** Splits into chunks of at most [maxLength] code units. */
    fun splitChunks(value: String, maxLength: Int): List<String> {
        if (maxLength <= 0) return if (value.isEmpty()) emptyList() else listOf(value)
        if (value.length <= maxLength) return listOf(value)
        val out = ArrayList<String>()
        var start = 0
        while (start < value.length) {
            var end = start + maxLength
            if (end >= value.length) {
                out.add(value.substring(start))
                break
            }
            end = headEnd(value, end)
            if (end <= start) {
                end = if (start + 2 <= value.length) start + 2 else value.length
            }
            out.add(value.substring(start, end))
            start = end
        }
        return out
    }

    /** Splits at the midpoint; null when no non-empty split exists. */
    fun splitHalves(value: String): Pair<String, String>? {
        if (value.length < 2) return null
        val mid = value.length / 2
        var cut = headEnd(value, mid)
        if (cut <= 0 || cut >= value.length) {
            cut = tailStart(value, mid)
        }
        if (cut <= 0 || cut >= value.length) return null
        return value.substring(0, cut) to value.substring(cut)
    }

    private fun isHighSurrogate(codeUnit: Int) = codeUnit in 0xD800..0xDBFF

    private fun isLowSurrogate(codeUnit: Int) = codeUnit in 0xDC00..0xDFFF
}
