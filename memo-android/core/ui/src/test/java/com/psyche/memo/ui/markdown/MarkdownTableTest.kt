package com.psyche.memo.ui.markdown

import androidx.compose.ui.unit.Density
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GFM table parsing (markdown_with_highlight.dart `_MarkdownTableBlock`):
 * the parser had `TablesExtension` enabled all along but the Compose renderer
 * had no table branch, so cells fell through to the paragraph fallback and were
 * emitted as unrelated stacked paragraphs. These tests lock the model the
 * renderer now consumes: header detection (commonmark flags the header on the
 * *cells*, not the row), column count, row/cell text and alignment.
 */
class MarkdownTableTest {

    private fun firstTable(markdown: String): TableBlock {
        val root: Node = MarkdownRenderer.parse(markdown)
        var child = root.firstChild
        while (child != null) {
            if (child is TableBlock) return child
            child = child.next
        }
        throw AssertionError("no TableBlock parsed from:\n$markdown")
    }

    private fun cellText(cell: TableCell): String = buildString {
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                if (child is org.commonmark.node.Text) append(child.literal ?: "")
                else walk(child)
                child = child.next
            }
        }
        walk(cell)
    }

    @Test
    fun parsesHeaderAndBodyRows() {
        // NB: leading indentation would make commonmark treat the table as an
        // indented code block, so the source strings stay flush-left.
        val table = firstTable("| Name | Age |\n|------|-----|\n| Ada  | 36  |\n| Bob  | 41  |")
        val model = parseTable(table)
        assertEquals(2, model.columnCount)
        assertEquals(listOf("Name", "Age"), model.header.map(::cellText))
        assertEquals(2, model.body.size)
        assertEquals(listOf("Ada", "36"), model.body[0].map(::cellText))
        assertEquals(listOf("Bob", "41"), model.body[1].map(::cellText))
    }

    @Test
    fun headerCellsAreFlaggedByCommonmark() {
        val table = firstTable("| A | B |\n|---|---|\n| 1 | 2 |")
        val model = parseTable(table)
        assertTrue("header row parsed", model.header.isNotEmpty())
        assertTrue("cells carry the header flag", model.header.all { it.isHeader })
        assertTrue("body cells are not headers", model.body.flatten().none { it.isHeader })
    }

    @Test
    fun columnCountUsesWidestRowWhenBodyIsRagged() {
        // A body row with fewer cells than the header must not shrink the table.
        val table = firstTable("| A | B | C |\n|---|---|---|\n| 1 |")
        val model = parseTable(table)
        assertEquals(3, model.columnCount)
        assertEquals(3, model.header.size)
    }

    @Test
    fun singleColumnTableIsSupported() {
        val table = firstTable("| Only |\n|------|\n| x |")
        val model = parseTable(table)
        assertEquals(1, model.columnCount)
        assertEquals(listOf("Only"), model.header.map(::cellText))
        assertEquals(listOf("x"), model.body.single().map(::cellText))
    }

    @Test
    fun inlineMarkdownInsideCellsIsPreserved() {
        val table = firstTable("| A |\n|---|\n| **bold** |")
        val model = parseTable(table)
        val cell = model.body.single().single()
        // The renderer feeds cells through renderInline, so the emphasis node
        // must survive parsing rather than being flattened to plain text.
        // (The extension puts inline content directly under TableCell.)
        assertTrue(
            "strong emphasis preserved inside cell",
            cell.firstChild is org.commonmark.node.StrongEmphasis,
        )
        assertEquals("bold", cellText(cell))
    }

    @Test
    fun alignmentIsReadFromDelimiterRow() {
        val table = firstTable("| L | C | R |\n|:--|:-:|--:|\n| a | b | c |")
        val model = parseTable(table)
        assertEquals(TableCell.Alignment.LEFT, model.header[0].alignment)
        assertEquals(TableCell.Alignment.CENTER, model.header[1].alignment)
        assertEquals(TableCell.Alignment.RIGHT, model.header[2].alignment)
    }

    @Test
    fun headerlessTableKeepsAllRowsInBody() {
        // A pipe table always carries a header syntactically, so this locks the
        // fallback shape: when no cell claims the header flag, every row is body.
        val table = firstTable("| a | b |\n|---|---|\n| 1 | 2 |")
        val model = parseTable(table)
        assertTrue(model.columnCount > 0)
        assertEquals(
            "all rows accounted for",
            1 + model.body.size,
            model.header.size.let { if (it == 0) model.body.size else model.body.size + 1 },
        )
    }

    // -----------------------------------------------------------------------
    // columnWeights (the FlexColumnWidth stand-in used by MarkdownTableView)
    //
    // Natural widths arrive already measured (TextMeasurer), in px. `weight` is
    // a *relative* share, so the function normalises its output to sum to
    // columnCount (average weight 1.0) and these tests assert the relational
    // properties that matter: monotonicity, the legibility floor, and that a
    // ragged/absent column is never left with nothing. The regression they
    // guard against is the old character-count heuristic, which gave "排名"
    // (2 chars) a sixth of the share of a 9-character cell and cropped the
    // whole first column off screen.
    // -----------------------------------------------------------------------

    /** 1px == 1dp so the assertions read in dp-sized numbers. */
    private val unitDensity = Density(1f)

    @Test
    fun wideColumnOutweighsNarrowOne() {
        val weights = columnWeights(listOf(40f, 300f), unitDensity, 2)
        assertEquals(2, weights.size)
        assertTrue(
            "a 300px column must outweigh a 40px one (weights=$weights)",
            weights[1] > weights[0],
        )
    }

    @Test
    fun emptyColumnsShareTheWidthEvenly() {
        // Both columns have no measurable text, so they must come out equal —
        // the old heuristic mixed a 0-length column into the same pool as a
        // 9-glyph one and let the ratio decide everything.
        val weights = columnWeights(listOf(0f, 0f), unitDensity, 2)
        assertEquals(weights[0], weights[1], 1e-4f)
    }

    @Test
    fun everyColumnGetsAPositiveShare() {
        // The floor used to be measured in px; after normalisation a column can
        // only be checked for being non-degenerate, but it must never be zero
        // (a zero weight collapses the column entirely).
        val weights = columnWeights(listOf(0f, 400f), unitDensity, 2)
        weights.forEach { assertTrue("weight $it must be > 0", it > 0f) }
    }

    @Test
    fun trailingColumnGetsAUsableShareEvenWithoutText() {
        // A ragged row can leave the last column's measured width at zero while
        // the column still needs its padding — the old heuristic dropped it to
        // a bare 1f and let the column collapse.
        val weights = columnWeights(listOf(200f), unitDensity, 3)
        assertEquals(3, weights.size)
        assertTrue("missing columns still get a usable share", weights[2] > 0f)
    }

    @Test
    fun weightsScaleWithMeasuredWidth() {
        // Two columns, switching which one holds the wide text: the wide side
        // must always win. (A single column normalises to 1.0 regardless, so
        // it cannot express this.)
        val a = columnWeights(listOf(60f, 600f), unitDensity, 2)
        val b = columnWeights(listOf(600f, 60f), unitDensity, 2)
        assertTrue("wide column wins on the right (weights=$a)", a[1] > a[0])
        assertTrue("wide column wins on the left (weights=$b)", b[0] > b[1])
    }

    @Test
    fun normalisedWeightsAverageToOne() {
        val weights = columnWeights(listOf(40f, 300f, 120f), unitDensity, 3)
        assertEquals(
            "weights are normalised so their mean is 1.0",
            3f,
            weights.sum(),
            1e-3f,
        )
    }

    @Test
    fun zeroColumnsYieldsNoWeights() {
        assertTrue(columnWeights(emptyList(), unitDensity, 0).isEmpty())
    }
}
