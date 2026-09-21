package com.psyche.memo

import com.psyche.memo.common.TitleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTextTest {

    // ---- buildContent: role/content filtering + tail window ----

    @Test
    fun buildContent_filtersEmptyAndKeepsRecent() {
        val msgs = listOf(
            "user" to "first question",
            "assistant" to "first answer",
            "system" to "ignored",
            "user" to "",
            "user" to "second question",
            "assistant" to "second answer",
        )
        val out = TitleText.buildContent(msgs, maxMessages = 12, maxChars = 3000)
        assertEquals(
            "User: first question\n\nAssistant: first answer\n\nUser: second question\n\nAssistant: second answer",
            out,
        )
    }

    @Test
    fun buildContent_keepsOnlyLastNMessages() {
        val msgs = (1..20).map { "user" to "msg$it" }
        val out = TitleText.buildContent(msgs, maxMessages = 3, maxChars = 3000)
        assertEquals("User: msg18\n\nUser: msg19\n\nUser: msg20", out)
    }

    @Test
    fun buildContent_truncatesToTailChars() {
        val msgs = listOf("user" to "a".repeat(5000))
        val out = TitleText.buildContent(msgs, maxMessages = 12, maxChars = 3000)
        assertEquals(3000, out.length)
        assertEquals("a".repeat(3000), out)
    }

    // ---- parseTitle: fences + wrapping quotes + whitespace ----

    @Test
    fun parseTitle_stripsDoubleQuotes() {
        assertEquals("Hello World", TitleText.parseTitle("\"Hello World\""))
    }

    @Test
    fun parseTitle_stripsSingleQuotes() {
        assertEquals("Hi", TitleText.parseTitle("'Hi'"))
    }

    @Test
    fun parseTitle_stripsCurlyQuotes() {
        assertEquals("标题", TitleText.parseTitle("“标题”"))
    }

    @Test
    fun parseTitle_stripsCornerBrackets() {
        assertEquals("會話標題", TitleText.parseTitle("「會話標題」"))
    }

    @Test
    fun parseTitle_stripsCodeFences() {
        assertEquals("My Title", TitleText.parseTitle("```My Title```"))
    }

    @Test
    fun parseTitle_stripsNestedQuotes() {
        assertEquals("nested", TitleText.parseTitle("\"\"nested\"\""))
    }

    @Test
    fun parseTitle_collapsesInternalWhitespace() {
        assertEquals("spaced title", TitleText.parseTitle("  spaced\n  title  "))
    }

    @Test
    fun parseTitle_passthroughPlain() {
        assertEquals("plain title", TitleText.parseTitle("plain title"))
    }

    // ---- shouldRefreshCurrent: only the displayed conversation refreshes ----

    @Test
    fun shouldRefreshCurrent_trueWhenSameId() {
        assertTrue(TitleText.shouldRefreshCurrent("c1", "c1"))
    }

    @Test
    fun shouldRefreshCurrent_falseWhenDifferentId() {
        assertFalse(TitleText.shouldRefreshCurrent("c1", "c2"))
    }

    @Test
    fun shouldRefreshCurrent_falseWhenChangedIdNull() {
        assertFalse(TitleText.shouldRefreshCurrent(null, "c1"))
    }

    @Test
    fun shouldRefreshCurrent_falseWhenNothingSelected() {
        assertFalse(TitleText.shouldRefreshCurrent("c1", null))
        assertFalse(TitleText.shouldRefreshCurrent(null, null))
    }
}
