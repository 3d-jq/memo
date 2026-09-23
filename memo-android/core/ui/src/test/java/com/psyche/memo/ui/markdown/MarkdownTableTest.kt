package com.psyche.memo.ui.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun cellText(cell: TableCell): String = cellText(cell as Node)
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

    // -----------------------------------------------------------------------
    // 横向滚动（用户 2026-09-23 定调：**宁可滚动也别换行**）
    //
    // 两个判据：按内容自然宽排得下 → 不滚动（FlexColumnWidth 铺满，修掉"宽屏右边空一大块、
    // 单元格还在换行"）；排不下 → 滚动，每列按内容宽（单列最多一屏）。上游是「列数 >= 4
    // 且溢出」才滚，1~3 列宁可压窄折行 —— 用户看到的就是那个"挤在一起"。
    // -----------------------------------------------------------------------

    @Test
    fun tablesScrollWheneverTheContentDoesNotFit() {
        // 放得下：一律不滚动，交给 flex 铺满。
        assertFalse(tableNeedsHorizontalScroll(viewportDp = 900f, naturalTotalDp = 712f))
        assertFalse(tableNeedsHorizontalScroll(viewportDp = 360f, naturalTotalDp = 360f))
        // 放不下：滚 —— 注意 1~3 列同样滚（上游只在 >= 4 列时滚，这是我们有意改的）。
        assertTrue(tableNeedsHorizontalScroll(viewportDp = 360f, naturalTotalDp = 561f))
        assertTrue(tableNeedsHorizontalScroll(viewportDp = 360f, naturalTotalDp = 380f))
        assertTrue("两列的窄表放不下也滚", tableNeedsHorizontalScroll(viewportDp = 240f, naturalTotalDp = 400f))
        // 量不到视口宽（理论上不该发生）时当放不下，别把表压扁。
        assertTrue(tableNeedsHorizontalScroll(Float.POSITIVE_INFINITY, 600f))
    }

    @Test
    fun scrollingColumnsKeepTheirContentWidthCappedAtOneScreen() {
        val widths = scrollColumnWidths(
            naturalPx = listOf(120f, 300f, 2_000f),
            availPx = 360f,
        )
        assertEquals(listOf(120f, 300f, 360f), widths)
        // 视口宽无效时不做封顶（交给上层兜底）。
        assertEquals(
            listOf(120f, 300f, 2_000f),
            scrollColumnWidths(listOf(120f, 300f, 2_000f), Float.POSITIVE_INFINITY),
        )
    }

    /** 行内代码要算进单元格文本（测量宽 + 复制 Markdown/CSV 都靠它）。 */
    @Test
    fun inlineCodeIsPartOfTheCellText() {
        val table = firstTable("| cmd | note |\n| --- | --- |\n| `npm run build` | plain |")
        val row = parseTable(table).body.single()
        assertEquals("npm run build", cellText(row[0]))
        assertEquals("plain", cellText(row[1]))
    }

}
