package com.psyche.memo.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Compose markdown renderer matching the subset of gpt_markdown's md_widget
 * used by the message timeline: headings, paragraphs, lists, block quotes,
 * fenced/inline code, links, images, emphasis, strong, thematic breaks.
 * Styles follow the theme like the Flutter package's theme.dart.
 *
 * The renderer is stateless; parse once per message content.
 *
 * Citation support (RikkaHub MarkdownNew.kt shape + original-project colors):
 * `[citation,domain](id)` markdown links are rendered inline as circular
 * primary capsules showing the domain metadata (10sp monospace, Thin),
 * clickable to open the matching source. Historical `[cite:id]` /
 * `[citation:ref]` markers are normalized into `[citation](id)` links and
 * resolved against the message's search results for domain/index fallback.
 */
object MarkdownRenderer {
    val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
            ),
        )
        .build()

    fun parse(markdown: String): Node = parser.parse(markdown)
}

/** Citation metadata resolved from the message's search results. */
data class CitationInfo(val domain: String? = null, val index: Int? = null)

/**
 * Per-render citation configuration. Only active when [onTap] is non-null,
 * which also gates the `[cite:...]` → `[citation](...)` normalization so that
 * unrelated messages are left untouched.
 */
private data class CitationRenderConfig(
    val onTap: ((String) -> Unit)? = null,
    val resolver: ((String) -> CitationInfo?)? = null,
)

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseFontSize: Float = 15.7f,
    baseLineHeight: Float = 23.55f,
    onCitationTap: ((String) -> Unit)? = null,
    citationInfoResolver: ((String) -> CitationInfo?)? = null,
) {
    if (markdown.isEmpty()) return
    val citation = CitationRenderConfig(onCitationTap, citationInfoResolver)
    // Only normalize when citation handling is wired for this message.
    val source = if (onCitationTap != null) preprocessCitations(markdown) else markdown
    val root = MarkdownRenderer.parse(source)
    MarkdownBody(
        node = root,
        modifier = modifier,
        baseFontSize = baseFontSize,
        baseLineHeight = baseLineHeight,
        citation = citation,
    )
}

@Composable
private fun MarkdownBody(
    node: Node,
    modifier: Modifier = Modifier,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var child = node.firstChild
        while (child != null) {
            MarkdownNode(child, baseFontSize, baseLineHeight, citation)
            child = child.next
        }
    }
}

@Composable
private fun MarkdownNode(
    node: Node,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
) {
    val cs = MaterialTheme.colorScheme
    when (node) {
        is Heading -> {
            val size = when (node.level) {
                1 -> 24f
                2 -> 21f
                3 -> 18f
                4 -> 16f
                else -> baseFontSize
            }
            Text(
                text = nodeText(node),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = size.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        is Paragraph -> {
            val inlineContent = mutableMapOf<String, InlineTextContent>()
            val annotated = renderInline(node, citation, inlineContent)
            Text(
                text = annotated,
                inlineContent = inlineContent,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = baseFontSize.sp,
                    lineHeight = baseLineHeight.sp,
                ),
            )
        }
        is FencedCodeBlock -> CodeBlockView(node.literal)
        is BlockQuote -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp)
                    .border(3.dp, cs.outlineVariant, RoundedCornerShape(4.dp)),
            ) {
                MarkdownBody(
                    node,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    baseFontSize = baseFontSize,
                    baseLineHeight = baseLineHeight,
                    citation = citation,
                )
            }
        }
        is ListBlock -> {
            val ordered = node is OrderedList
            Column {
                var index = 1
                var item = node.firstChild
                while (item != null) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (ordered) "$index. " else "• ",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = baseFontSize.sp),
                        )
                        MarkdownBody(
                            item,
                            modifier = Modifier.padding(start = 4.dp),
                            baseFontSize = baseFontSize,
                            baseLineHeight = baseLineHeight,
                            citation = citation,
                        )
                    }
                    index++
                    item = item.next
                }
            }
        }
        is ThematicBreak -> HorizontalDivider(color = cs.outlineVariant)
        is TableBlock -> MarkdownTableView(node, baseFontSize, baseLineHeight, citation)
        is Image -> Text(
            text = "[image ${node.destination}]",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        else -> MarkdownBody(node, Modifier, baseFontSize, baseLineHeight, citation)
    }
}

// ---------------------------------------------------------------------------
// GFM table (markdown_with_highlight.dart `_MarkdownTableBlock` L3223-3436)
//
// The Flutter original builds a fully custom table via gpt_markdown's
// `tableBuilder`: 0.5dp inside borders, primary-tinted header row, 10/9 cell
// padding, 13sp semibold header / 13.5sp regular body, wrapped in a rounded
// card. Tables with >= 4 columns keep a fixed minimum column width and scroll
// horizontally instead of squeezing. The Compose port matches those metrics;
// the original's toolbar (copy / export CSV / save image) and row pager are
// deliberately omitted — they are desktop/first-class features backed by
// platform IO that has no Compose counterpart yet.
// ---------------------------------------------------------------------------

private const val TABLE_HEADER_SP = 13f
private const val TABLE_BODY_SP = 13.5f
private const val TABLE_LINE_HEIGHT_MULT = 1.42f
internal val TABLE_CELL_PADDING_H = 10.dp
internal val TABLE_CELL_PADDING_V = 9.dp
private val TABLE_CARD_RADIUS = 12.dp
private val TABLE_BORDER_WIDTH = 0.5.dp
private val TABLE_INSET_VERTICAL = 6.dp
private const val TABLE_HEADER_ALPHA_DARK = 0.15f
private const val TABLE_HEADER_ALPHA_LIGHT = 0.07f
private const val TABLE_BORDER_ALPHA_DARK = 0.22f
private const val TABLE_BORDER_ALPHA_LIGHT = 0.30f
internal const val TABLE_MIN_COLUMN_DP = 112f
private const val TABLE_MAX_COLUMN_DP = 178f
private const val TABLE_SCROLL_COLUMN_THRESHOLD = 4

/**
 * Safety multiplier applied to each column's measured ink width before it
 * becomes a `Row` weight. 1.12 covers the gap between `TextMeasurer`'s ink
 * width and the width the text actually occupies once the cell's line box is
 * laid out (letter-spacing rounding, italic overhang), so a column is never
 * assigned exactly its text width and forced to wrap.
 */
private const val TABLE_COLUMN_SLACK = 1.12f

/** The original reserves 16dp of horizontal slack when sizing columns. */
private val TABLE_INSET_H_TOTAL = 16.dp

/**
 * A parsed GFM table: the header row plus the body rows.
 *
 * The extension nests rows under section wrappers
 * (`TableBlock → TableHead/TableBody → TableRow → TableCell`), so rows are
 * collected by walking the whole subtree rather than only the direct children.
 * commonmark flags the header on the *cells* (`TableCell.isHeader()`), not on
 * the `TableRow`.
 */
internal data class TableModel(
    val header: List<TableCell>,
    val body: List<List<TableCell>>,
) {
    val columnCount: Int
        get() = maxOf(
            header.size,
            body.maxOfOrNull { it.size } ?: 0,
        )
}

internal fun parseTable(table: TableBlock): TableModel {
    val rows = mutableListOf<List<TableCell>>()
    fun collectRows(node: Node) {
        var child = node.firstChild
        while (child != null) {
            if (child is TableRow) {
                val cells = mutableListOf<TableCell>()
                var cell = child.firstChild
                while (cell != null) {
                    if (cell is TableCell) cells.add(cell)
                    cell = cell.next
                }
                rows.add(cells)
            } else {
                collectRows(child)
            }
            child = child.next
        }
    }
    collectRows(table)
    // The extension always emits the header row first and flags its cells.
    val header = rows.firstOrNull()?.takeIf { row -> row.any { it.isHeader } }.orEmpty()
    val body = if (header.isEmpty()) rows else rows.drop(1)
    return TableModel(header, body)
}

/**
 * Per-column natural widths, measured on the real text instead of guessed from
 * character counts. The Flutter original uses `FlexColumnWidth`, which lays
 * columns out from their intrinsic text widths; a character-count heuristic
 * mis-sizes CJK badly (a 2-glyph header like "排名" measured as 2 units against
 * a 9-character body cell, crushing the column). Measuring with the same
 * [TextMeasurer] and text style the cells will use reproduces that behaviour.
 */
private fun measureColumnWidths(
    measurer: TextMeasurer,
    model: TableModel,
    cellStyle: TextStyle,
): List<Float> {
    return (0 until model.columnCount).map { col ->
        val headerWidth = model.header.getOrNull(col)
            ?.let { measurer.measure(cellText(it), cellStyle).size.width }
            ?: 0
        val bodyWidth = model.body.maxOfOrNull { row ->
            row.getOrNull(col)?.let { measurer.measure(cellText(it), cellStyle).size.width } ?: 0
        } ?: 0
        maxOf(headerWidth, bodyWidth).toFloat()
    }
}

/**
 * Turns natural text widths into `Row` weights.
 *
 * `Modifier.weight` splits the row proportionally, so the values only need to
 * be *relative*. Each column contributes its measured text width plus the cell
 * padding, and a floor keeps a narrow "#"/"排名" column legible instead of
 * collapsing. The result is intentionally returned **un-normalised** — Compose
 * normalises by the sum — but every entry is a pixel-scale number so the floor
 * means the same thing in every table.
 */
internal fun columnWeights(
    naturals: List<Float>,
    density: Density,
    columnCount: Int,
): List<Float> {
    if (columnCount == 0) return emptyList()
    val padPx = with(density) { (TABLE_CELL_PADDING_H * 2).toPx() }
    val minPx = with(density) { TABLE_MIN_COLUMN_DP.dp.toPx() }
    val weights = FloatArray(columnCount)
    for (col in 0 until columnCount) {
        val natural = naturals.getOrElse(col) { 0f }
        weights[col] = (natural * TABLE_COLUMN_SLACK + padPx).coerceAtLeast(minPx)
    }
    // Normalise to sum == columnCount so the weights stay in a sane range and
    // a single huge cell cannot push every other column below the floor.
    val sum = weights.sum().takeIf { it > 0f } ?: return List(columnCount) { 1f }
    val scale = columnCount / sum
    return weights.map { it * scale }
}

/** Plain text of a cell, flattened through nested inline nodes. */
private fun cellText(cell: Node): String = buildString {
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

@Composable
private fun MarkdownTableView(
    table: TableBlock,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()
    val model = parseTable(table)
    if (model.columnCount == 0) return

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val measureStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = TABLE_BODY_SP.sp,
        lineHeight = TABLE_BODY_SP.sp * TABLE_LINE_HEIGHT_MULT,
    )

    val borderColor = cs.outlineVariant.copy(
        alpha = if (isDark) TABLE_BORDER_ALPHA_DARK else TABLE_BORDER_ALPHA_LIGHT,
    )
    val headerBg = cs.primary.copy(
        alpha = if (isDark) TABLE_HEADER_ALPHA_DARK else TABLE_HEADER_ALPHA_LIGHT,
    )
    val scrollable = model.columnCount >= TABLE_SCROLL_COLUMN_THRESHOLD
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TABLE_INSET_VERTICAL)
            .clip(RoundedCornerShape(TABLE_CARD_RADIUS))
            .background(cs.primary.copy(alpha = if (isDark) 0.045f else 0.018f))
            .border(0.8.dp, borderColor, RoundedCornerShape(TABLE_CARD_RADIUS))
            .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
    ) {
        BoxWithConstraints {
            // Non-scrolling tables mirror FlexColumnWidth: every column gets at
            // least its measured text width, then the leftover space is shared
            // out proportionally by `weight`. Scrolling tables instead pin a
            // fixed legible column width so >= 4 columns overflow sideways,
            // exactly like _compactColumnWidth (L3522).
            val weights = if (scrollable) {
                List(model.columnCount) { 1f }
            } else {
                columnWeights(
                    naturals = measureColumnWidths(measurer, model, measureStyle),
                    density = density,
                    columnCount = model.columnCount,
                )
            }
            val columnWidth = if (scrollable) {
                // _compactColumnWidth (markdown_with_highlight.dart L3522):
                // >= 4 columns show ~2.45 at a time, clamped to a legible min.
                val available = maxWidth - TABLE_INSET_H_TOTAL
                (available / 2.45f)
                    .coerceIn(TABLE_MIN_COLUMN_DP.dp, TABLE_MAX_COLUMN_DP.dp)
            } else {
                maxWidth / model.columnCount
            }
            Column(
                modifier = Modifier.then(
                    if (scrollable) Modifier.width(columnWidth * model.columnCount)
                    else Modifier.fillMaxWidth(),
                ),
            ) {
                if (model.header.isNotEmpty()) {
                    TableRowView(
                        cells = model.header,
                        header = true,
                        columnCount = model.columnCount,
                        columnWidth = columnWidth,
                        weights = weights,
                        scrollable = scrollable,
                        rowBackground = headerBg,
                        bottomBorder = true,
                        baseFontSize = baseFontSize,
                        baseLineHeight = baseLineHeight,
                        citation = citation,
                    )
                }
                model.body.forEachIndexed { index, cells ->
                    TableRowView(
                        cells = cells,
                        header = false,
                        columnCount = model.columnCount,
                        columnWidth = columnWidth,
                        weights = weights,
                        scrollable = scrollable,
                        rowBackground = null,
                        // The original's TableBorder draws only *inside* rules;
                        // the outer frame is the rounded card behind this
                        // Column. Drawing a bottom rule on every row but the
                        // last reproduces that (and avoids the stray vertical
                        // line a `Column.border` produced inside the scroll
                        // container).
                        bottomBorder = index < model.body.lastIndex,
                        baseFontSize = baseFontSize,
                        baseLineHeight = baseLineHeight,
                        citation = citation,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<TableCell>,
    header: Boolean,
    columnCount: Int,
    columnWidth: androidx.compose.ui.unit.Dp,
    weights: List<Float>,
    scrollable: Boolean,
    rowBackground: Color?,
    bottomBorder: Boolean,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
) {
    val cs = MaterialTheme.colorScheme
    val borderColor = cs.outlineVariant.copy(
        alpha = if (isSystemInDarkTheme()) TABLE_BORDER_ALPHA_DARK else TABLE_BORDER_ALPHA_LIGHT,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(if (rowBackground != null) Modifier.background(rowBackground) else Modifier)
                .fillMaxWidth(),
        ) {
            for (i in 0 until columnCount) {
                TableCellView(
                    cell = cells.getOrNull(i),
                    header = header,
                    columnWidth = columnWidth,
                    weight = weights.getOrElse(i) { 1f },
                    scrollable = scrollable,
                    rightBorder = i < columnCount - 1,
                    borderColor = borderColor,
                    baseFontSize = baseFontSize,
                    baseLineHeight = baseLineHeight,
                    citation = citation,
                )
            }
        }
        // Inside-only horizontal rule, like TableBorder(horizontalInside).
        // Applied to the row's *bottom* edge so consecutive rows produce one
        // rule between them rather than two stacked 1px lines.
        if (bottomBorder) {
            HorizontalDivider(
                thickness = TABLE_BORDER_WIDTH,
                color = borderColor,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCellView(
    cell: TableCell?,
    header: Boolean,
    columnWidth: androidx.compose.ui.unit.Dp,
    weight: Float,
    scrollable: Boolean,
    rightBorder: Boolean,
    borderColor: Color,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
) {
    val cs = MaterialTheme.colorScheme
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val annotated = cell?.let { renderInline(it, citation, inlineContent) } ?: AnnotatedString("")
    val cellColor = if (header) cs.onSurface else cs.onSurface.copy(alpha = 0.90f)
    Box(
        modifier = Modifier
            // Scrolling tables need a fixed column width to be wider than the
            // viewport; non-scrolling ones share the available width by weight.
            .then(if (scrollable) Modifier.width(columnWidth) else Modifier.weight(weight))
            .padding(horizontal = TABLE_CELL_PADDING_H, vertical = TABLE_CELL_PADDING_V)
            // Interior vertical rule on the trailing edge only, matching
            // TableBorder(verticalInside) — the outer frame belongs to the
            // rounded card, so the last column draws no line and no cell draws
            // one on its leading edge (that would double up with its neighbour).
            .then(
                if (rightBorder) {
                    Modifier.drawBehind {
                        val stroke = TABLE_BORDER_WIDTH.toPx()
                        drawRect(
                            color = borderColor,
                            topLeft = androidx.compose.ui.geometry.Offset(size.width - stroke, 0f),
                            size = androidx.compose.ui.geometry.Size(stroke, size.height),
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text = annotated,
            inlineContent = inlineContent,
            // The Box has already resolved this cell's width (weight or fixed
            // columnWidth), so `fillMaxWidth` wraps the text inside it. Without
            // it an unbreakable run — a latin token glued to a full-width comma
            // such as "RikkaHub、" — lays out wider than the column and paints
            // past the table's right edge. Alignment is preserved because the
            // Box still positions the (now full-width) Text by `contentAlignment`
            // via the inner `Text`'s own textAlign below.
            modifier = Modifier.fillMaxWidth(),
            textAlign = when (cell?.alignment) {
                TableCell.Alignment.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                TableCell.Alignment.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (if (header) TABLE_HEADER_SP else TABLE_BODY_SP).sp,
                lineHeight = (if (header) TABLE_HEADER_SP else TABLE_BODY_SP).sp * TABLE_LINE_HEIGHT_MULT,
                fontWeight = if (header) FontWeight(600) else FontWeight.Normal,
                color = cellColor,
                // Emoji glyphs carry a taller fallback font, which inflates
                // rows that contain them (medals in the first column, etc.).
                // Trimming the line box to the first/last baseline keeps every
                // row at the intended 1.42 height, like the original's fixed
                // `height: 1.42` text style.
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

@Composable
private fun CodeBlockView(code: String?) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = code ?: "",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Inline formatting with real styles (gpt_markdown md_widget behavior):
 * strong (w600), emphasis (italic), strikethrough (line-through), inline code
 * (code background + monospace), links (colored, underlined, clickable) and
 * citation capsules (rounded primary pill showing the resolved index).
 * Image nodes remain a placeholder until the image subsystem lands.
 */
@Composable
private fun renderInline(
    node: Node,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    val codeBackground = cs.surfaceVariant
    val linkColor = cs.primary
    return buildAnnotatedString {
        var child = node.firstChild
        while (child != null) {
            appendInlineStyled(child, codeBackground, linkColor, citation, inlineContent)
            child = child.next
        }
    }
}

@Composable
private fun AnnotatedString.Builder.appendInlineStyled(
    node: Node,
    codeBackground: Color,
    linkColor: Color,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    when (node) {
        is Text -> append(node.literal ?: "")
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight(600))) {
            appendInlineChildren(node, codeBackground, linkColor, citation, inlineContent)
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlineChildren(node, codeBackground, linkColor, citation, inlineContent)
        }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlineChildren(node, codeBackground, linkColor, citation, inlineContent)
        }
        is Code -> withStyle(SpanStyle(background = codeBackground, fontFamily = FontFamily.Monospace)) {
            append(node.literal ?: "")
        }
        is Link -> {
            val label = nodeText(node).trim()
            val onTap = citation.onTap
            if (onTap != null) {
                // RikkaHub MarkdownNew.kt: [citation,domain](id) → circular
                // capsule. Models drift to the shorter `[cite,domain](id)`
                // spelling (device-observed with glm), so both prefixes are
                // accepted; an id-echo label (`[cite,e2dce5](e2dce5)`) has its
                // display text resolved from the message's search results.
                val capsule =
                    resolveCitationCapsule(label, node.destination ?: "", citation.resolver)
                if (capsule != null) {
                    inlineContent.putIfAbsent(
                        "citation:${capsule.key}",
                        citationInlineContent(capsule.text) { onTap(capsule.key) },
                    )
                    appendInlineContent("citation:${capsule.key}", " ")
                    return
                }
                // Historical [cite:id] / [citation:ref] markers, normalized to
                // [citation](id) by preprocessCitations — resolve the numeric
                // index against this message's search results.
                if (label.equals("citation", ignoreCase = true)) {
                    val ref = parseCitationRef(node.destination ?: "")
                    if (ref != null) {
                        val info = citation.resolver?.invoke(ref.id)
                        val text = when {
                            info?.index != null -> info.index.toString()
                            ref.indexText != ref.id -> ref.indexText
                            else -> "?"
                        }
                        inlineContent.putIfAbsent(
                            "citation:${ref.id}",
                            citationInlineContent(text) { onTap(ref.id) },
                        )
                        appendInlineContent("citation:${ref.id}", " ")
                        return
                    }
                }
            }
            val link = LinkAnnotation.Url(
                node.destination ?: "",
                TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            )
            withLink(link) {
                appendInlineChildren(node, codeBackground, linkColor, citation, inlineContent)
            }
        }
        is Image -> withStyle(SpanStyle(color = linkColor)) {
            append("[image ${node.destination}]")
        }
        is SoftLineBreak -> append(" ")
        else -> appendInlineChildren(node, codeBackground, linkColor, citation, inlineContent)
    }
}

@Composable
private fun AnnotatedString.Builder.appendInlineChildren(
    node: Node,
    codeBackground: Color,
    linkColor: Color,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    var child = node.firstChild
    while (child != null) {
        appendInlineStyled(child, codeBackground, linkColor, citation, inlineContent)
        child = child.next
    }
}

/**
 * Inline citation capsule — a small numbered badge derived from the original
 * project (`markdown_with_highlight.dart` linkBuilder L441-472). The original's
 * 20dp pill sits as tall as the line box and reads as a heavy blob
 * mid-sentence, so the badge is deliberately shrunk to a subtle marker:
 * 16dp tall, 8dp radius (= height / 2, fully round), 16dp minimum width,
 * 10sp label, 16%-tinted primary background.
 *
 * Vertical placement: `PlaceholderVerticalAlign.TextCenter` centers the badge
 * on the line box, which already sits slightly above the CJK glyph baseline —
 * the original's extra -2dp lift therefore pushed the badge visibly above the
 * text. A small downward nudge instead lands it on the visual center of the
 * glyphs, which is what the eye reads as "aligned".
 *
 * The label is always the resolved numeric index — never the domain.
 */
private val CITATION_BADGE_HEIGHT = 16.dp
private val CITATION_BADGE_MIN_WIDTH = 16.dp
private val CITATION_BADGE_H_PADDING = 3.dp
private const val CITATION_BADGE_LABEL_SP = 10f
private const val CITATION_BADGE_GLYPH_DP = 6f
private const val CITATION_BADGE_ALPHA = 0.16f
private val CITATION_BADGE_BASELINE_NUDGE = 1.dp
private val CITATION_BADGE_SIDE_GAP = 2.dp

@Composable
private fun citationInlineContent(text: String, onClick: () -> Unit): InlineTextContent {
    val cs = MaterialTheme.colorScheme
    // InlineTextContent sizes placeholders in TextUnits, so convert the fixed
    // dp metrics to sp with the current font scale to keep the badge stable
    // when the user scales text.
    val density = LocalDensity.current
    val pillHeight = with(density) { CITATION_BADGE_HEIGHT.toSp() }
    // A placeholder must be sized up front: estimate the glyph run, add the
    // side padding, and coerce to the minimum round-badge width.
    // A placeholder must be sized up front: estimate the glyph run, add the
    // side padding, then reserve a little breathing room on each side so the
    // badge does not collide with the preceding glyph run (the original pads
    // the capsule by 1.5dp horizontally for the same reason).
    val slotWidth = with(density) {
        ((text.length * CITATION_BADGE_GLYPH_DP).dp + CITATION_BADGE_H_PADDING * 2)
            .coerceAtLeast(CITATION_BADGE_MIN_WIDTH)
            .plus(CITATION_BADGE_SIDE_GAP * 2)
            .toSp()
    }
    val pillRadius = with(density) { (CITATION_BADGE_HEIGHT / 2).toPx() }
    val nudgeY = with(density) { CITATION_BADGE_BASELINE_NUDGE.toPx() }
    return InlineTextContent(
        Placeholder(
            width = slotWidth,
            height = pillHeight,
            // TextCenter centers on the line box, which floats above the CJK
            // glyphs; AboveBaseline seats the badge on the text baseline, which
            // is what reads as horizontally aligned with the surrounding text.
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = nudgeY }
                .padding(horizontal = CITATION_BADGE_SIDE_GAP),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick)
                    .clip(RoundedCornerShape(pillRadius))
                    .background(cs.primary.copy(alpha = CITATION_BADGE_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.wrapContentSize(),
                    style = TextStyle(
                        fontSize = CITATION_BADGE_LABEL_SP.sp,
                        lineHeight = CITATION_BADGE_LABEL_SP.sp,
                        color = cs.primary,
                    ),
                )
            }
        }
    }
}

/** Plain text of a node: concatenation of descendant Text literals. */
private fun nodeText(node: Node): String = buildString {
    var child = node.firstChild
    while (child != null) {
        if (child is Text) append(child.literal ?: "")
        else append(nodeText(child))
        child = child.next
    }
}

/**
 * Normalize Cherry-style `[cite:id]` (optionally comma-separated,
 * e.g. `[cite:a1b2c3, d4e5f6]`) and legacy `[citation:ref]` markers into
 * `[citation](id)` markdown links so they render as numbered capsules.
 * Ports markdown_with_highlight.dart `_normalizeCiteMarkers` +
 * `_normalizeRawCitationMetadata`.
 */
internal fun preprocessCitations(input: String): String =
    normalizeRawCitationMetadata(normalizeCiteMarkers(input))

private fun normalizeCiteMarkers(input: String): String {
    val citeMarker = Regex(
        """\[cite:\s*([A-Za-z0-9_-]+(?:\s*,\s*[A-Za-z0-9_-]+)*)\s*\]""",
        RegexOption.IGNORE_CASE,
    )
    return citeMarker.replace(input) { m ->
        m.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "[citation]($it)" }
    }
}

private fun normalizeRawCitationMetadata(input: String): String {
    val rawCitation = Regex("""\[citation:([^\]\r\n]+)\]""", RegexOption.IGNORE_CASE)
    return rawCitation.replace(input) { m ->
        val refs = parseCitationRefList(m.groupValues[1])
        if (refs.isEmpty()) m.value else refs.joinToString(" ") { "[citation](${it.markdownTarget})" }
    }
}

/** Parsed `[citation](index:id)` target. */
internal data class CitationRef(val indexText: String, val id: String) {
    val markdownTarget: String
        get() = if (indexText == id) indexText else "$indexText:$id"
}

private fun String.isCitationIndex(): Boolean = Regex("""^[A-Za-z0-9_-]+$""").matches(this)

internal fun parseCitationRef(raw: String): CitationRef? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val sep = trimmed.indexOf(':')
    val hasSep = sep != -1
    val indexText = if (sep == -1) trimmed else trimmed.substring(0, sep).trim()
    val id = if (sep == -1) indexText else trimmed.substring(sep + 1).trim()
    if (!indexText.isCitationIndex() ||
        (hasSep && !Regex("""\d""").containsMatchIn(indexText)) ||
        id.isEmpty()
    ) {
        return null
    }
    if (id.contains(')') || id.contains(']') || Regex("""\s""").containsMatchIn(id)) return null
    return CitationRef(indexText = indexText, id = id)
}

/** A resolved inline citation capsule: lookup key + display text. */
internal data class CitationCapsule(val key: String, val text: String)

/**
 * Resolve a `[citation,X](Y)` / `[cite,X](Y)` link into a capsule.
 *
 * Models drift from the prompted `[cite:id]` to the comma-metadata spelling
 * (`[citation,domain](id)` / `[cite,domain](id)`); both are accepted here. The
 * capsule key prefers a 6-char id token (Memo search ids are `UUID.take(6)`)
 * taken from the destination first, then the label; a URL destination renders
 * a capsule that opens the link directly. The label metadata is ignored for
 * display — the capsule always shows the numeric index, like the original
 * project; it falls back to "?" when no index can be resolved.
 */
internal fun resolveCitationCapsule(
    label: String,
    destination: String,
    resolver: ((String) -> CitationInfo?)?,
): CitationCapsule? {
    val lower = label.lowercase()
    val meta = when {
        lower.startsWith("citation,") -> label.substring("citation,".length).trim()
        lower.startsWith("cite,") -> label.substring("cite,".length).trim()
        else -> return null
    }
    if (meta.isEmpty()) return null
    val dest = destination.trim()
    val key = listOf(dest, meta).firstOrNull { it.length == 6 && it.isCitationIndex() }
        ?: dest.takeIf { it.contains('.') || it.contains('/') }
        ?: return null
    val info = resolver?.invoke(key)
    val text = when {
        info?.index != null -> info.index.toString()
        // Unresolvable id: fall back to a bare numeric label when the metadata
        // already is one, otherwise "?" (same terminal case as the original).
        meta.toIntOrNull() != null -> meta
        else -> "?"
    }
    return CitationCapsule(key, text)
}

private fun parseCitationRefList(raw: String): List<CitationRef> {
    val refs = mutableListOf<CitationRef>()
    for (part0 in raw.split(',')) {
        var part = part0.trim()
        if (part.isEmpty()) return emptyList()
        if (part.startsWith("citation:", ignoreCase = true)) part = part.substring("citation:".length).trim()
        val ref = parseCitationRef(part) ?: return emptyList()
        refs.add(ref)
    }
    return refs
}
