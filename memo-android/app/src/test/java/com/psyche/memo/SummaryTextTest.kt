package com.psyche.memo

import com.psyche.memo.common.SummaryText
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTextTest {

    // ---- buildContent: join new user messages, head-truncate ----

    @Test
    fun buildContent_joinsUserMessages() {
        val out = SummaryText.buildContent(listOf("q1", "q2", "q3"))
        assertEquals("q1\n\nq2\n\nq3", out)
    }

    @Test
    fun buildContent_headTruncatesLongInput() {
        val out = SummaryText.buildContent(listOf("a".repeat(5000)), maxChars = 2000)
        assertEquals(2000, out.length)
        assertEquals("a".repeat(2000), out)
    }

    // ---- parseSummary: strip fences + trim ----

    @Test
    fun parseSummary_stripsCodeFences() {
        assertEquals("summary text", SummaryText.parseSummary("```summary text```"))
    }

    @Test
    fun parseSummary_trims() {
        assertEquals("summary text", SummaryText.parseSummary("  summary text  "))
    }

    @Test
    fun parseSummary_passthroughPlain() {
        assertEquals("plain summary", SummaryText.parseSummary("plain summary"))
    }
}
