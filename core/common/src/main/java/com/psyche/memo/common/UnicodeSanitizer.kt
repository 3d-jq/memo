package com.psyche.memo.common

/**
 * Port of lib/utils/unicode_sanitizer.dart — repairs/replaces broken UTF-16
 * surrogate pairs that some PDF text extractors emit (a low surrogate with
 * its high nibble stripped shows up as U+0C00..U+0FFF).
 */
object UnicodeSanitizer {

    private const val REPLACEMENT_CHAR = 0xFFFD

    fun sanitize(input: String): String {
        if (input.isEmpty()) return input
        var out: StringBuilder? = null
        val len = input.length
        var i = 0
        while (i < len) {
            val cu = input[i].code
            if (isHighSurrogate(cu)) {
                if (i + 1 < len) {
                    val next = input[i + 1].code
                    if (isLowSurrogate(next)) {
                        out?.appendCodePoint(codePointFromSurrogates(cu, next))
                        i += 2
                        continue
                    }
                    // Common corruption pattern observed in some PDF text
                    // extraction: the low surrogate may have its high nibble
                    // stripped, turning e.g. 0xDCE1 into U+0CE1.
                    if (looksLikeStrippedLowSurrogate(next)) {
                        val repairedLow = 0xD000 or next
                        if (isLowSurrogate(repairedLow)) {
                            if (out == null) out = StringBuilder(input.substring(0, i))
                            out.appendCodePoint(codePointFromSurrogates(cu, repairedLow))
                            i += 2
                            continue
                        }
                    }
                }
                if (out == null) out = StringBuilder(input.substring(0, i))
                out.append(REPLACEMENT_CHAR.toChar())
                i += 1
                continue
            }
            if (isLowSurrogate(cu)) {
                if (out == null) out = StringBuilder(input.substring(0, i))
                out.append(REPLACEMENT_CHAR.toChar())
                i += 1
                continue
            }
            out?.append(input[i])
            i += 1
        }
        return out?.toString() ?: input
    }

    private fun isHighSurrogate(codeUnit: Int) = codeUnit in 0xD800..0xDBFF
    private fun isLowSurrogate(codeUnit: Int) = codeUnit in 0xDC00..0xDFFF
    private fun looksLikeStrippedLowSurrogate(codeUnit: Int) = codeUnit in 0x0C00..0x0FFF

    private fun codePointFromSurrogates(high: Int, low: Int): Int =
        0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
}
