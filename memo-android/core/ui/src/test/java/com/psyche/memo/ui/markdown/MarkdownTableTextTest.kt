package com.psyche.memo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [MarkdownTableText] to the Flutter original's behaviour
 * (`_rowsToCsv` / `_rowsToMarkdown` / `_markdownTableCell` / `_csvCell`,
 * markdown_with_highlight.dart L4014-4075) so a copy from either client pastes
 * identically.
 */
class MarkdownTableTextTest {

    // ── CSV ──────────────────────────────────────────────────────────────

    @Test
    fun csvJoinsCellsWithCommasAndRowsWithCrLf() {
        val rows = listOf(listOf("a", "b"), listOf("c", "d"))
        assertEquals("a,b\r\nc,d", MarkdownTableText.toCsv(rows))
    }

    @Test
    fun csvLeavesPlainCellsUntouched() {
        assertEquals("plain", MarkdownTableText.csvCell("plain"))
        assertEquals("", MarkdownTableText.csvCell(""))
    }

    @Test
    fun csvQuotesOnlyWhenTheCellNeedsIt() {
        assertEquals("\"a,b\"", MarkdownTableText.csvCell("a,b"))
        assertEquals("\"a\"\"b\"", MarkdownTableText.csvCell("a\"b"))
        assertEquals("\"a\nb\"", MarkdownTableText.csvCell("a\nb"))
        assertEquals("\"a\rb\"", MarkdownTableText.csvCell("a\rb"))
    }

    @Test
    fun csvDoesNotTrimCells() {
        // The original only trims for the markdown flavour; CSV keeps padding.
        assertEquals(" padded ", MarkdownTableText.csvCell(" padded "))
    }

    // ── Markdown ─────────────────────────────────────────────────────────

    @Test
    fun markdownEmitsAHeaderAndSeparatorRow() {
        val rows = listOf(listOf("h1", "h2"), listOf("a", "b"))
        assertEquals(
            "| h1 | h2 |\n| --- | --- |\n| a | b |",
            MarkdownTableText.toMarkdown(rows),
        )
    }

    @Test
    fun markdownPadsRaggedRowsToTheWidestOne() {
        val rows = listOf(listOf("h1", "h2"), listOf("only"))
        assertEquals(
            "| h1 | h2 |\n| --- | --- |\n| only |  |",
            MarkdownTableText.toMarkdown(rows),
        )
    }

    @Test
    fun markdownEscapesPipesBackslashesAndNewlines() {
        val rows = listOf(listOf("a|b", "c\\d"), listOf("e\nf", "g"))
        assertEquals(
            "| a\\|b | c\\\\d |\n| --- | --- |\n| e<br>f | g |",
            MarkdownTableText.toMarkdown(rows),
        )
    }

    @Test
    fun markdownTrimsCellWhitespace() {
        val rows = listOf(listOf("  h1  "), listOf("  a  "))
        assertEquals("| h1 |\n| --- |\n| a |", MarkdownTableText.toMarkdown(rows))
    }

    @Test
    fun markdownNormalisesCrLfToASingleBreak() {
        assertEquals(
            "| a<br>b |",
            MarkdownTableText.toMarkdown(listOf(listOf("a\r\nb"))).lines().first(),
        )
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun emptyInputYieldsEmptyStrings() {
        assertEquals("", MarkdownTableText.toCsv(emptyList()))
        assertEquals("", MarkdownTableText.toMarkdown(emptyList()))
    }

    @Test
    fun zeroColumnInputYieldsEmptyMarkdown() {
        assertEquals("", MarkdownTableText.toMarkdown(listOf(emptyList(), emptyList())))
    }

    @Test
    fun singleRowTableStillEmitsASeparatorRow() {
        val text = MarkdownTableText.toMarkdown(listOf(listOf("only")))
        assertTrue(text.endsWith("| only |\n| --- |"))
    }

    @Test
    fun markdownOutputRoundTripsThroughItsOwnCellEscaping() {
        // A cell containing a literal pipe must not split into two columns when
        // the emitted markdown is read back.
        val rows = listOf(listOf("a|b"))
        val line = MarkdownTableText.toMarkdown(rows).lines().first()
        assertEquals("| a\\|b |", line)
    }
}
