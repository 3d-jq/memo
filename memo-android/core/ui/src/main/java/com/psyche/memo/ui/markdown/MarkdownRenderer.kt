package com.psyche.memo.ui.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.ImageDown
import com.psyche.memo.ui.R
import com.psyche.memo.ui.theme.alphaBlend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
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

/**
 * Platform hooks for the table toolbar. `core:ui` owns the toolbar's look and
 * knows how to serialise the table; the host supplies clipboard / file / image
 * behaviour, which needs Android APIs this module deliberately does not hold.
 *
 * Null means "this action is unavailable here" and its button is not drawn —
 * e.g. a preview that has no Activity to launch a document picker from.
 */
data class MarkdownTableActions(
    /** Copy the GFM pipe table. */
    val onCopyMarkdown: ((String) -> Unit)? = null,
    /** Copy the rendered table as an image. */
    val onCopyImage: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)? = null,
    /** Write a CSV file (SAF picker in the host). */
    val onExportCsv: ((String) -> Unit)? = null,
    /** Save the rendered table image to the gallery. */
    val onSaveImage: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)? = null,
)

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseFontSize: Float = 15.7f,
    baseLineHeight: Float = 23.55f,
    onCitationTap: ((String) -> Unit)? = null,
    citationInfoResolver: ((String) -> CitationInfo?)? = null,
    tableActions: MarkdownTableActions? = null,
) {
    if (markdown.isEmpty()) return
    val citation = CitationRenderConfig(onCitationTap, citationInfoResolver)
    // 首帧同步解析（避免空白闪烁），之后的内容变化在 Default 线程解析，并用
    // mapLatest 丢弃过期请求 —— RikkaHub Markdown.kt:240-252 同款做法。流式输出
    // 时内容每个 chunk 都变，主线程不再被 CommonMark 解析（含纯文本预计算）阻塞，
    // 这是滚动掉帧的主要来源；未变化的内容由 distinctUntilChanged + drop(1) 跳过。
    var parsed by remember { mutableStateOf(parseMarkdownSource(markdown, onCitationTap != null)) }
    val latest by rememberUpdatedState(markdown to (onCitationTap != null))
    LaunchedEffect(Unit) {
        snapshotFlow { latest }
            .distinctUntilChanged()
            .drop(1)
            .mapLatest { (md, withCitations) -> parseMarkdownSource(md, withCitations) }
            .flowOn(Dispatchers.Default)
            .collect { parsed = it }
    }
    MarkdownBody(
        node = parsed.root,
        plainTexts = parsed.plainTexts,
        modifier = modifier,
        baseFontSize = baseFontSize,
        baseLineHeight = baseLineHeight,
        citation = citation,
        tableActions = tableActions,
    )
}

/**
 * 一次解析的产物：AST 根 + 每个节点的纯文本（渲染阶段查表，避免在组合里递归）。
 * commonmark 0.26 移除了 `Node.data`，所以只能外挂一张表。
 */
private class ParsedMarkdown(
    val root: Node,
    val plainTexts: Map<Node, String>,
)

/**
 * 解析结果缓存（按源文本）。滚动时每条"见过"的消息都能直接命中 —— 否则每次
 * 滚回一条消息，`MarkdownText` 的首帧同步解析都要在主线程重跑一遍 CommonMark，
 * 这正是长会话滚动掉帧的主因（RikkaHub 靠段落级 AnnotatedString 缓存达到同样
 * 效果）。[android.util.LruCache] 自带同步，解析可能在 Default 线程并发调用。
 *
 * 条数上限：一条长消息的 AST 不算小，32 条覆盖一屏多一点，超出按 LRU 淘汰。
 */
private val parsedCache = android.util.LruCache<String, ParsedMarkdown>(32)

/** 预热：消息列表变化后把这些内容先解析进缓存（调用方放在后台线程）。 */
fun preloadMarkdown(marks: List<Pair<String, Boolean>>) {
    for ((markdown, withCitations) in marks) {
        if (markdown.isEmpty()) continue
        val key = cacheKey(markdown, withCitations)
        if (parsedCache.get(key) == null) {
            runCatching { parsedCache.put(key, parseMarkdown(markdown, withCitations)) }
        }
    }
}

private fun cacheKey(markdown: String, withCitations: Boolean): String =
    (if (withCitations) "c:" else "p:") + markdown

/**
 * One parse pipeline for [MarkdownText]: citation preprocessing only runs when
 * tap handling is wired for this message, then the CommonMark parse.
 *
 * 解析完顺带把每个节点的纯文本算好（[collectPlainText]），这样渲染阶段
 * `nodeText` 只是查表 —— 否则每次重组都要在主线程递归遍历子树拼字符串，流式
 * 输出时每帧都得跑一遍。预计算跟着解析一起走后台线程。
 */
private fun parseMarkdownSource(markdown: String, withCitations: Boolean): ParsedMarkdown {
    val key = cacheKey(markdown, withCitations)
    parsedCache.get(key)?.let { return it }
    val parsed = parseMarkdown(markdown, withCitations)
    parsedCache.put(key, parsed)
    return parsed
}

private fun parseMarkdown(markdown: String, withCitations: Boolean): ParsedMarkdown {
    val source = if (withCitations) preprocessCitations(markdown) else markdown
    val root = MarkdownRenderer.parse(source)
    val plainTexts = HashMap<Node, String>()
    collectPlainText(root, plainTexts)
    return ParsedMarkdown(root, plainTexts)
}

/** 自底向上记录「本节点子树的纯文本」，供 [nodeText] 查表。 */
private fun collectPlainText(node: Node, out: MutableMap<Node, String>): String {
    val sb = StringBuilder()
    var child = node.firstChild
    while (child != null) {
        sb.append(
            if (child is Text) child.literal.orEmpty() else collectPlainText(child, out),
        )
        child = child.next
    }
    val text = sb.toString()
    out[node] = text
    return text
}

@Composable
private fun MarkdownBody(
    node: Node,
    plainTexts: Map<Node, String>,
    modifier: Modifier = Modifier,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
    tableActions: MarkdownTableActions?,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var child = node.firstChild
        while (child != null) {
            MarkdownNode(child, plainTexts, baseFontSize, baseLineHeight, citation, tableActions)
            child = child.next
        }
    }
}

@Composable
private fun MarkdownNode(
    node: Node,
    plainTexts: Map<Node, String>,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
    tableActions: MarkdownTableActions?,
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
                text = nodeText(node, plainTexts),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = size.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        is Paragraph -> {
            val inlineContent = mutableMapOf<String, InlineTextContent>()
            val annotated = renderInline(node, plainTexts, citation, inlineContent)
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
                    plainTexts,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    baseFontSize = baseFontSize,
                    baseLineHeight = baseLineHeight,
                    citation = citation,
                    tableActions = tableActions,
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
                            plainTexts,
                            modifier = Modifier.padding(start = 4.dp),
                            baseFontSize = baseFontSize,
                            baseLineHeight = baseLineHeight,
                            citation = citation,
                            tableActions = tableActions,
                        )
                    }
                    index++
                    item = item.next
                }
            }
        }
        is ThematicBreak -> HorizontalDivider(color = cs.outlineVariant)
        is TableBlock -> MarkdownTableView(node, plainTexts, baseFontSize, baseLineHeight, citation, tableActions)
        is Image -> Text(
            text = "[image ${node.destination}]",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        else -> MarkdownBody(node, plainTexts, Modifier, baseFontSize, baseLineHeight, citation, tableActions)
    }
}

// ---------------------------------------------------------------------------
// GFM table (markdown_with_highlight.dart `_MarkdownTableBlock` L3223-3436)
//
// The Flutter original builds a fully custom table via gpt_markdown's
// `tableBuilder`: 0.5dp inside borders, primary-tinted header row, 10/9 cell
// padding, 13sp semibold header / 13.5sp regular body, wrapped in a rounded
// card. Tables with >= 4 columns keep a fixed minimum column width and scroll
// horizontally instead of squeezing. The Compose port matches those metrics
// and reproduces the toolbar (_MarkdownTableToolbar L3843-3930: a 38dp label
// row with copy / save-image / export-CSV buttons) plus the row pager
// (_buildRowPager L3438: 40 rows up front, 100 more per tap).
//
// The toolbar's *actions* are injected via MarkdownTableActions — this module
// serialises the table but leaves clipboard / SAF / gallery work to the host,
// which owns the Activity. A null action hides its button.
// ---------------------------------------------------------------------------

private const val TABLE_HEADER_SP = 13f
private const val TABLE_BODY_SP = 13.5f
private const val TABLE_LINE_HEIGHT_MULT = 1.42f
internal val TABLE_CELL_PADDING_H = 10.dp
internal val TABLE_CELL_PADDING_V = 9.dp
private val TABLE_CARD_RADIUS = 12.dp
private val TABLE_BORDER_WIDTH = 0.5.dp
private val TABLE_INSET_VERTICAL = 6.dp
private const val TABLE_HEADER_ALPHA_DARK = 0.15
private const val TABLE_HEADER_ALPHA_LIGHT = 0.07
private const val TABLE_BODY_ALPHA_DARK = 0.04
private const val TABLE_BODY_ALPHA_LIGHT = 0.015
// 卡片底的 tint 与正文底色不同（原版 L3344-3346 用 0.045/0.018）。
private const val TABLE_CARD_ALPHA_DARK = 0.045
private const val TABLE_CARD_ALPHA_LIGHT = 0.018
// `kBlockFillAlphaTable`（原版 L54）：屏幕上的表头/正文/卡片底色统一乘这个透明度，
// 好让助手壁纸透出来；只有截图时才用不透明（JPEG 会把透明孔编码成黑，原版 L3267-3274
// 的注释）。导出路径靠 flattenOntoOpaque 合成到 surface，所以这里始终用 0.72 即可。
private const val TABLE_FILL_ALPHA = 0.72f
private const val TABLE_BORDER_ALPHA_DARK = 0.22f
private const val TABLE_BORDER_ALPHA_LIGHT = 0.30f
internal const val TABLE_MIN_COLUMN_DP = 112f
private const val TABLE_MAX_COLUMN_DP = 178f
private const val TABLE_SCROLL_COLUMN_THRESHOLD = 4

/** `_MarkdownTableToolbar` L3843-3930: 38dp label bar above the table body. */
private val TABLE_TOOLBAR_HEIGHT = 38.dp
private val TABLE_TOOLBAR_START_PADDING = 12.dp
private val TABLE_TOOLBAR_END_PADDING = 6.dp
private const val TABLE_TOOLBAR_LABEL_SP = 12f
private const val TABLE_TOOLBAR_LABEL_ALPHA = 0.80f
private const val TABLE_TOOLBAR_ICON_ALPHA = 0.68f
private const val TABLE_TOOLBAR_BORDER_ALPHA_DARK = 0.20f
private const val TABLE_TOOLBAR_BORDER_ALPHA_LIGHT = 0.28f
private val TABLE_TOOLBAR_BORDER_WIDTH = 0.6.dp
private val TABLE_TOOLBAR_ICON_BUTTON_SIZE = 32.dp
private val TABLE_TOOLBAR_ICON_SIZE = 15.dp
private val TABLE_TOOLBAR_ICON_PADDING = 7.dp

/** `_initialRows` / `_rowPageSize` (L3241-3242). */
private const val TABLE_INITIAL_ROWS = 40
private const val TABLE_ROW_PAGE_SIZE = 100

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
    plainTexts: Map<Node, String>,
    baseFontSize: Float,
    baseLineHeight: Float,
    citation: CitationRenderConfig,
    actions: MarkdownTableActions?,
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
    // The original blends the primary tint ONTO `surface` (L3260-3266) instead
    // of drawing a translucent primary: a plain low-alpha wash composites over
    // whatever sits behind the card and turns muddy grey, which is especially
    // wrong in dark mode where `primary` is a light colour.
    // 截图期间用不透明底色（原版 L3269-3274）：0.72 的屏幕透明度会让导出图的
    // 表头/正文比原版浅一档。声明必须早于下面的底色计算。
    var capturing by remember(model) { mutableStateOf(false) }
    val headerBg = alphaBlend(
        fg = cs.primary,
        fgAlpha = if (isDark) TABLE_HEADER_ALPHA_DARK else TABLE_HEADER_ALPHA_LIGHT,
        bg = cs.surface,
    ).copy(alpha = if (capturing) 1f else TABLE_FILL_ALPHA)
    val bodyBg = alphaBlend(
        fg = cs.primary,
        fgAlpha = if (isDark) TABLE_BODY_ALPHA_DARK else TABLE_BODY_ALPHA_LIGHT,
        bg = cs.surface,
    ).copy(alpha = if (capturing) 1f else TABLE_FILL_ALPHA)
    // 卡片自身的底色（原版 L3344-3347）。
    val cardBg = alphaBlend(
        fg = cs.primary,
        fgAlpha = if (isDark) TABLE_CARD_ALPHA_DARK else TABLE_CARD_ALPHA_LIGHT,
        bg = cs.surface,
    ).copy(alpha = if (capturing) 1f else TABLE_FILL_ALPHA)
    val scrollable = model.columnCount >= TABLE_SCROLL_COLUMN_THRESHOLD
    val scrollState = rememberScrollState()

    // Row pager (_initialRows 40, then +100 per tap), like _buildRowPager.
    var visibleRows by remember(model) { mutableStateOf(TABLE_INITIAL_ROWS) }
    val bodyRows = model.body.take(visibleRows)

    // The toolbar images the whole card, so capture the frame that holds the
    // toolbar + table (not just the scrolling viewport) — matching the
    // original's RepaintBoundary placement.
    val imageCaptureNeeded = actions?.onCopyImage != null || actions?.onSaveImage != null
    val boundaryLayer = rememberTableBoundary()

    // 导出用截图：**先展开到全部行再录**。录制层只包含当前渲染的行，而长表格默认
    // 只渲染 TABLE_INITIAL_ROWS 行 —— 直接截就是用户看到的"只有一部分"。展开后等
    // 两帧（一帧应用新状态、一帧把完整高度画进 layer），截完把展开状态还给用户。
    val captureForExport: (suspend () -> ImageBitmap?)? = if (imageCaptureNeeded) {
        {
            val previous = visibleRows
            val needsExpand = previous < model.body.size
            visibleRows = model.body.size
            capturing = true
            withFrameNanos { }
            withFrameNanos { }
            if (!needsExpand && previous == model.body.size) visibleRows = previous
            val bitmap = runCatching { boundaryLayer.toImageBitmap() }.getOrNull()
            android.util.Log.d(
                "TableCapture",
                "rows=${model.body.size} cols=${model.columnCount} scrollable=$scrollable" +
                    " layer=${boundaryLayer.size.width}x${boundaryLayer.size.height}" +
                    " bitmap=${bitmap?.width}x${bitmap?.height}",
            )
            visibleRows = previous
            capturing = false
            bitmap
        }
    } else {
        null
    }
    val tableRows = if (actions != null) {
        // Flatten to plain strings once; the toolbar needs them for both the
        // markdown and CSV serialisations.
        (listOf(model.header) + model.body)
            .map { row -> row.map(::cellText) }
    } else {
        emptyList()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TABLE_INSET_VERTICAL)
            .clip(RoundedCornerShape(TABLE_CARD_RADIUS))
            .background(cardBg)
            .border(0.8.dp, borderColor, RoundedCornerShape(TABLE_CARD_RADIUS)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (actions != null) {
                MarkdownTableToolbar(
                    isDark = isDark,
                    headerBg = headerBg,
                    rows = tableRows,
                    actions = actions,
                    capture = captureForExport,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
            ) {
                BoxWithConstraints {
                    // Non-scrolling tables mirror FlexColumnWidth: every column
                    // gets at least its measured text width, then the leftover
                    // space is shared out proportionally by `weight`. Scrolling
                    // tables instead pin a fixed legible column width so >= 4
                    // columns overflow sideways, like _compactColumnWidth (L3522).
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
                        modifier = Modifier
                            .then(
                                if (scrollable) Modifier.width(columnWidth * model.columnCount)
                                else Modifier.fillMaxWidth(),
                            )
                            // 录制层必须挂在**横向滚动容器内部**的这张表上：它的宽度是
                            // 表格的真实宽度（scrollable 时 = 列数 × 列宽，会超出视口）。
                            // 挂在外层时图层宽度只有视口宽，右侧的列根本不在图层里 ——
                            // 这就是"横向内容保存不下来"的根因。
                            .then(
                                if (imageCaptureNeeded) Modifier.recordTableBoundary(boundaryLayer)
                                else Modifier,
                            ),
                    ) {
                        if (model.header.isNotEmpty()) {
                            TableRowView(
                                cells = model.header,
                                plainTexts = plainTexts,
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
                                isDark = isDark,
                            )
                        }
                        bodyRows.forEachIndexed { index, cells ->
                            TableRowView(
                                cells = cells,
                                plainTexts = plainTexts,
                                header = false,
                                columnCount = model.columnCount,
                                columnWidth = columnWidth,
                                weights = weights,
                                scrollable = scrollable,
                                rowBackground = null,
                                // The original's TableBorder draws only *inside*
                                // rules; the outer frame is the rounded card. A
                                // bottom rule on every row but the last
                                // reproduces that (and avoids the stray vertical
                                // line a `Column.border` produced in the scroll
                                // container).
                                bottomBorder = index < bodyRows.lastIndex,
                                baseFontSize = baseFontSize,
                                baseLineHeight = baseLineHeight,
                                citation = citation,
                                isDark = isDark,
                            )
                        }
                    }
                }
            }
            if (actions != null) {
                MarkdownTableRowPager(
                    totalRows = model.body.size,
                    visibleRows = visibleRows,
                    onShowMore = {
                        visibleRows = minOf(model.body.size, visibleRows + TABLE_ROW_PAGE_SIZE)
                    },
                    onCollapse = { visibleRows = TABLE_INITIAL_ROWS },
                )
            }
        }
    }
}

/**
 * Renders the table card's subtree into an offscreen [GraphicsLayer] so the
 * toolbar can export it as a PNG. This is the Compose equivalent of wrapping
 * the card in a `RepaintBoundary` and calling `toImage` on it, which is what
 * the original does (markdown_with_highlight.dart `_captureTablePngBytes`).
 *
 * [remember] is intentional: the layer must survive recomposition, and the
 * recording modifier is only attached when an image action is wired up.
 */
@Composable
private fun rememberTableBoundary(): GraphicsLayer = rememberGraphicsLayer()

/** Records the subtree into [layer] so it can be exported later. */
private fun Modifier.recordTableBoundary(layer: GraphicsLayer): Modifier = this.drawWithContent {
    // `record` is a DrawScope extension on GraphicsLayer:
    // record(size: IntSize, block: DrawScope.() -> Unit). The block paints the
    // real content into the layer; drawLayer then replays it into the frame.
    layer.record(IntSize(size.width.toInt(), size.height.toInt())) {
        this@drawWithContent.drawContent()
    }
    drawLayer(layer)
}

/**
 * `_MarkdownTableToolbar` (L3843-3930): a 38dp bar showing the "Table" label
 * with copy / save-image / export-CSV buttons. Buttons whose action is null are
 * omitted (see [MarkdownTableActions]).
 */
@Composable
private fun MarkdownTableToolbar(
    isDark: Boolean,
    headerBg: Color,
    rows: List<List<String>>,
    actions: MarkdownTableActions,
    /** 截图（已展开全部行）；null = 未接线/不可用。 */
    capture: (suspend () -> ImageBitmap?)?,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.markdown_table_label)
    val copyLabel = stringResource(R.string.markdown_table_copied_markdown_snackbar)
    val exportLabel = stringResource(R.string.markdown_table_export_csv_tooltip)
    val imageLabel = stringResource(R.string.markdown_table_save_image_tooltip)
    val toolbarRule = cs.outlineVariant.copy(
        alpha = if (isDark) TABLE_TOOLBAR_BORDER_ALPHA_DARK else TABLE_TOOLBAR_BORDER_ALPHA_LIGHT,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TABLE_TOOLBAR_HEIGHT)
            .background(headerBg)
            .drawBehind {
                // Bottom hairline separating the toolbar from the table body.
                val stroke = TABLE_TOOLBAR_BORDER_WIDTH.toPx()
                drawRect(
                    color = toolbarRule,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - stroke),
                    size = androidx.compose.ui.geometry.Size(size.width, stroke),
                )
            }
            .padding(start = TABLE_TOOLBAR_START_PADDING, end = TABLE_TOOLBAR_END_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = TABLE_TOOLBAR_LABEL_SP.sp,
                fontWeight = FontWeight(600),
                color = cs.onSurfaceVariant.copy(alpha = TABLE_TOOLBAR_LABEL_ALPHA),
                lineHeight = TABLE_TOOLBAR_LABEL_SP.sp,
            ),
        )
        // Buttons are icon-only; the label lives in contentDescription so
        // TalkBack reads what a tooltip would show.
        val copyImage = actions.onCopyImage
        if (actions.onCopyMarkdown != null || copyImage != null) {
            // Long-press copies the image, tap copies the markdown — the same
            // split the original wires with onTap/onLongPress.
            MarkdownTableIconButton(
                icon = Lucide.Copy,
                contentDescription = copyLabel,
                onLongClick = if (copyImage == null) {
                    null
                } else {
                    { capture?.let { shot -> scope.launch { shot()?.let { bmp -> copyImage(bmp) } } } }
                },
                onClick = { actions.onCopyMarkdown?.invoke(MarkdownTableText.toMarkdown(rows)) },
            )
        }
        val saveImage = actions.onSaveImage
        if (saveImage != null) {
            MarkdownTableIconButton(
                icon = Lucide.ImageDown,
                contentDescription = imageLabel,
                onClick = { capture?.let { shot -> scope.launch { shot()?.let { bmp -> saveImage(bmp) } } } },
            )
        }
        val exportCsv = actions.onExportCsv
        if (exportCsv != null) {
            MarkdownTableIconButton(
                icon = Lucide.Download,
                contentDescription = exportLabel,
                onClick = { exportCsv(MarkdownTableText.toCsv(rows)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownTableIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    // The original wraps each button in a Flutter Tooltip; M3's plain tooltip
    // is the closest equivalent (hover/long-press on desktop, focus on touch).
    val tipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        modifier = Modifier
            .size(TABLE_TOOLBAR_ICON_BUTTON_SIZE)
            .clickable { scope.launch { tipState.show() } },
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = tipState,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(TABLE_TOOLBAR_ICON_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(TABLE_TOOLBAR_ICON_SIZE),
                tint = cs.onSurfaceVariant.copy(alpha = TABLE_TOOLBAR_ICON_ALPHA),
            )
        }
    }
}

/**
 * `_buildRowPager` (L3438): only shown when the body is longer than the initial
 * page. "Show more" reveals the rest in 100-row chunks; "Collapse" appears once
 * expanded.
 */
@Composable
private fun MarkdownTableRowPager(
    totalRows: Int,
    visibleRows: Int,
    onShowMore: () -> Unit,
    onCollapse: () -> Unit,
) {
    if (totalRows <= TABLE_INITIAL_ROWS) return
    val remaining = totalRows - visibleRows
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visibleRows > TABLE_INITIAL_ROWS) {
            TextButton(onClick = onCollapse) {
                Text(stringResource(R.string.large_content_collapse))
            }
        }
        if (remaining > 0) {
            TextButton(onClick = onShowMore) {
                Text(stringResource(R.string.large_content_show_more, remaining))
            }
        }
    }
}

/** `outlineVariant`, matching the table rules. */
@Composable
private fun TableRowView(
    cells: List<TableCell>,
    plainTexts: Map<Node, String>,
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
    isDark: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val borderColor = cs.outlineVariant.copy(
        alpha = if (isDark) TABLE_BORDER_ALPHA_DARK else TABLE_BORDER_ALPHA_LIGHT,
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
                    plainTexts = plainTexts,
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
    plainTexts: Map<Node, String>,
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
    val annotated = cell?.let { renderInline(it, plainTexts, citation, inlineContent) } ?: AnnotatedString("")
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
    plainTexts: Map<Node, String>,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    val codeBackground = cs.surfaceVariant
    val linkColor = cs.primary
    return buildAnnotatedString {
        var child = node.firstChild
        while (child != null) {
            appendInlineStyled(child, plainTexts, codeBackground, linkColor, citation, inlineContent)
            child = child.next
        }
    }
}

@Composable
private fun AnnotatedString.Builder.appendInlineStyled(
    node: Node,
    plainTexts: Map<Node, String>,
    codeBackground: Color,
    linkColor: Color,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    when (node) {
        is Text -> append(node.literal ?: "")
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight(600))) {
            appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
        }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
        }
        is Code -> withStyle(SpanStyle(background = codeBackground, fontFamily = FontFamily.Monospace)) {
            append(node.literal ?: "")
        }
        is Link -> {
            val label = nodeText(node, plainTexts).trim()
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
                appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
            }
        }
        is Image -> withStyle(SpanStyle(color = linkColor)) {
            append("[image ${node.destination}]")
        }
        is SoftLineBreak -> append(" ")
        else -> appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
    }
}

@Composable
private fun AnnotatedString.Builder.appendInlineChildren(
    node: Node,
    plainTexts: Map<Node, String>,
    codeBackground: Color,
    linkColor: Color,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    var child = node.firstChild
    while (child != null) {
        appendInlineStyled(child, plainTexts, codeBackground, linkColor, citation, inlineContent)
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

/**
 * Plain text of a node: looked up in the map [collectPlainText] built during
 * parsing, with a live walk as the fallback for trees that did not go through
 * [parseMarkdownSource] (e.g. hand-built nodes in a test).
 */
private fun nodeText(node: Node, plainTexts: Map<Node, String>): String =
    plainTexts[node] ?: buildString {
        var child = node.firstChild
        while (child != null) {
            if (child is Text) append(child.literal ?: "")
            else append(nodeText(child, plainTexts))
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
