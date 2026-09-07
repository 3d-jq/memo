package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ICU-plural evaluator tests. ARB plural messages are emitted verbatim into
 * strings.xml (arb_to_android.py) and Android's String.format cannot evaluate
 * `{n, plural, …}`, so IcuPlural does. These assert the exact rendering the
 * tool-card question count needs.
 */
class IcuPluralTest {

    @Test
    fun englishExactMatchTakesPrecedence() {
        val pattern = "{count, plural, =1{Ask 1 question} other{Ask {count} questions}}"
        assertEquals("Ask 1 question", IcuPlural.format(pattern, mapOf("count" to 1)))
    }

    @Test
    fun englishOtherBranchSubstitutes() {
        val pattern = "{count, plural, =1{Ask 1 question} other{Ask {count} questions}}"
        assertEquals("Ask 3 questions", IcuPlural.format(pattern, mapOf("count" to 3)))
    }

    @Test
    fun chineseUsesOnlyOtherBranch() {
        val pattern = "{count, plural, other{询问 {count} 个问题}}"
        assertEquals("询问 1 个问题", IcuPlural.format(pattern, mapOf("count" to 1)))
        assertEquals("询问 5 个问题", IcuPlural.format(pattern, mapOf("count" to 5)))
    }

    @Test
    fun plainNamedPlaceholderResolves() {
        assertEquals("hello Memo", IcuPlural.format("hello {name}", mapOf("name" to "Memo")))
    }

    @Test
    fun missingValueBecomesEmpty() {
        assertEquals("hi ", IcuPlural.format("hi {name}", emptyMap()))
    }

    @Test
    fun unmatchedBraceIsKeptLiteral() {
        assertEquals("{oops", IcuPlural.format("{oops", emptyMap()))
    }

    @Test
    fun nonPluralPlaceholderKeptLiteral() {
        assertEquals("{x, number, integer}", IcuPlural.format("{x, number, integer}", mapOf("x" to 1)))
    }
}
