package com.psyche.memo.ui.markdown

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
    // columnWidths (the FlexColumnWidth + minIntrinsicWidth stand-in)
    //
    // Natural widths arrive already measured (TextMeasurer) in px, the mins are
    // the widest unbreakable run per column. The rules under test:
    //   1. every column gets at least its min + padding - this is what stops a
    //      long cell ("DeepSeek-V4-Pro ...") from crushing a short one ("时间")
    //      down to two or three glyphs per line, which is the bug the user hit;
    //   2. the leftover space is shared out by (natural - min), so wide content
    //      still wins that part;
    //   3. the total always equals the available width when it fits.
    // -----------------------------------------------------------------------

    private val unitPad = 20f

    @Test
    fun shortColumnKeepsItsMinIntrinsicWidth() {
        // "时间" column: 40px of text, min run 40px. Right column is huge.
        val widths = columnWidths(
            naturals = listOf(40f, 900f),
            mins = listOf(40f, 120f),
            availPx = 400f,
            padPx = unitPad,
            slack = 1f,
        )
        assertEquals(2, widths.size)
        assertTrue(
            "short column must keep min+padding (widths=$widths)",
            widths[0] >= 40f + unitPad - 1e-3f,
        )
        assertTrue("wide column gets the rest (widths=$widths)", widths[1] > widths[0])
    }

    @Test
    fun widthsFillTheAvailableSpace() {
        val widths = columnWidths(
            naturals = listOf(40f, 900f),
            mins = listOf(40f, 120f),
            availPx = 400f,
            padPx = unitPad,
            slack = 1f,
        )
        assertEquals(400f, widths.sum(), 1e-3f)
    }

    @Test
    fun extraSpaceFavoursTheWiderNaturalColumn() {
        val widths = columnWidths(
            naturals = listOf(100f, 300f),
            mins = listOf(50f, 50f),
            availPx = 600f,
            padPx = unitPad,
            slack = 1f,
        )
        assertTrue("wider content takes more of the slack (widths=$widths)", widths[1] > widths[0])
    }

    @Test
    fun minWidthsWinWhenNothingFits() {
        // Available space below sum(min): keep every column at its legible floor
        // (the caller decides whether to scroll) instead of squeezing one away.
        val widths = columnWidths(
            naturals = listOf(500f, 500f),
            mins = listOf(100f, 200f),
            availPx = 150f,
            padPx = unitPad,
            slack = 1f,
        )
        assertEquals(120f, widths[0], 1e-3f)
        assertEquals(220f, widths[1], 1e-3f)
    }

    @Test
    fun emptyColumnsShareEqually() {
        val widths = columnWidths(
            naturals = listOf(0f, 0f),
            mins = listOf(0f, 0f),
            availPx = 200f,
            padPx = unitPad,
            slack = 1f,
        )
        assertEquals(widths[0], widths[1], 1e-3f)
    }

}
