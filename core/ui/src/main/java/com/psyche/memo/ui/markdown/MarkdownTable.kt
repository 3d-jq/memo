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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.ImageDown
import com.psyche.memo.ui.R
import com.psyche.memo.ui.theme.alphaBlend
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.Text

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

/** 不可断片段：ASCII 字母数字串（含常见连接符）算一个整体，CJK 可逐字断行。 */
private val TOKEN_RE = Regex("[A-Za-z0-9_.-]+")

/**
 * 每列「最窄也要放得下」的宽度：该列所有单元格里最长的不可断片段。
 *
 * 这是 Flutter `Table` 的 `minIntrinsicWidth`：它保证 "2026年"、"DeepSeek-V4-Pro"
 * 这类内容至少能放下最长的一截，而不是被旁边的长文本列压到每行两三个字。
 */
private fun measureColumnMinWidths(
    measurer: TextMeasurer,
    model: TableModel,
    cellStyle: TextStyle,
): List<Float> {
    val cjkUnit = runCatching { measurer.measure("中", cellStyle).size.width.toFloat() }
        .getOrDefault(0f)
    return (0 until model.columnCount).map { col ->
        val texts = buildList {
            model.header.getOrNull(col)?.let { add(cellText(it)) }
            model.body.forEach { row -> row.getOrNull(col)?.let { add(cellText(it)) } }
        }
        var widest = cjkUnit
        for (text in texts) {
            for (token in TOKEN_RE.findAll(text)) {
                val w = measurer.measure(token.value, cellStyle).size.width.toFloat()
                if (w > widest) widest = w
            }
        }
        widest
    }
}

/**
 * 列宽 = 每列的最小可读宽 + 剩余空间按「自然宽 - 最小宽」的比例分配 —— Flutter
 * `Table` 的 FlexColumnWidth 语义。此前的做法只按自然宽比例分配权重，没有下限，
 * 长文本列会把短列压到每行两三个字（用户实测："时间"列被挤成 202 / 6年 / 4月）。
 *
 * 返回 px；`sum(min) >= avail` 时直接给最小宽（放不下，由外层决定是否横向滚动）。
 */
internal fun columnWidths(
    naturals: List<Float>,
    mins: List<Float>,
    availPx: Float,
    padPx: Float,
    slack: Float,
): List<Float> {
    val n = naturals.size
    if (n == 0) return emptyList()
    val minPx = List(n) { i -> mins.getOrElse(i) { 0f } + padPx }
    val natPx = List(n) { i ->
        (naturals.getOrElse(i) { 0f } * slack + padPx).coerceAtLeast(minPx[i])
    }
    val sumMin = minPx.sum()
    if (sumMin >= availPx || availPx <= 0f) return minPx
    val extra = availPx - sumMin
    val flexSum = List(n) { i -> (natPx[i] - minPx[i]).coerceAtLeast(0f) }.sum()
    if (flexSum <= 0f) return List(n) { minPx[it] + extra / n }
    return List(n) { i ->
        val flex = (natPx[i] - minPx[i]).coerceAtLeast(0f)
        minPx[i] + extra * flex / flexSum
    }
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
internal fun MarkdownTableView(
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
                    // 列宽（Flutter Table 的语义）：
                    // - 能横向滚动时（列多）：固定列宽 _compactColumnWidth（L3522），
                    //   让 >= 4 列的表格往右溢出；
                    // - 否则：每列先拿到「最小可读宽」（最长不可断片段，等价
                    //   minIntrinsicWidth），剩余空间再按自然宽比例分配 —— 这正是
                    //   Flutter 用 FlexColumnWidth 时不会把"时间"列压成每行两三个字
                    //   的原因。
                    val cellPadPx = with(density) { (TABLE_CELL_PADDING_H * 2).toPx() }
                    val widths: List<androidx.compose.ui.unit.Dp> = if (scrollable) {
                        val available = maxWidth - TABLE_INSET_H_TOTAL
                        val compact = (available / 2.45f)
                            .coerceIn(TABLE_MIN_COLUMN_DP.dp, TABLE_MAX_COLUMN_DP.dp)
                        List(model.columnCount) { compact }
                    } else {
                        columnWidths(
                            naturals = measureColumnWidths(measurer, model, measureStyle),
                            mins = measureColumnMinWidths(measurer, model, measureStyle),
                            availPx = with(density) { maxWidth.toPx() },
                            padPx = cellPadPx,
                            slack = TABLE_COLUMN_SLACK,
                        ).map { px -> with(density) { px.toDp() } }
                    }
                    Column(
                        modifier = Modifier
                            .width(widths.fold(0.dp) { acc, w -> acc + w })
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
                                widths = widths,
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
                                widths = widths,
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
    widths: List<androidx.compose.ui.unit.Dp>,
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
                .fillMaxWidth()
                // 竖线画在**整行**上，而不是每个单元格各自画：同一行里单元格高度
                // 可能不同（多行文字 vs 单行），各画各的会让短的那条只到半截 ——
                // 用户看到的"竖线没接满"。Flutter 的 TableBorder(verticalInside)
                // 是整表统一画的，这里等价还原。
                .drawBehind {
                    if (columnCount <= 1) return@drawBehind
                    val stroke = TABLE_BORDER_WIDTH.toPx()
                    var x = 0f
                    for (i in 0 until columnCount - 1) {
                        x += widths.getOrElse(i) { 0.dp }.toPx()
                        drawLine(
                            color = borderColor,
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = stroke,
                        )
                    }
                },
        ) {
            for (i in 0 until columnCount) {
                TableCellView(
                    cell = cells.getOrNull(i),
                    plainTexts = plainTexts,
                    header = header,
                    width = widths.getOrElse(i) { 0.dp },
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
    width: androidx.compose.ui.unit.Dp,
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
            // 竖线由所在行统一绘制（见 TableRowView），这里不逐单元格画。
            .width(width)
            .padding(horizontal = TABLE_CELL_PADDING_H, vertical = TABLE_CELL_PADDING_V),
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

/**
 * 代码块外观/行为（原版 `_CollapsibleCodeBlock` 读的三个设置项）。
 *
 * [autoCollapse] = `display_auto_collapse_code_block_v1`：代码行数超过
 * [autoCollapseLines]（`display_auto_collapse_code_block_lines_v1`，默认 2）时
 * 默认折叠；[wrap] = `display_mobile_code_block_wrap_v1`：软换行而不是横向滚动。
 */