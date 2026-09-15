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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
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
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.ImageDown
import com.psyche.memo.ui.R
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.alphaBlend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
internal data class CitationRenderConfig(
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
    codeBlock: CodeBlockConfig = CodeBlockConfig(),
    codeBlockActions: CodeBlockActions = CodeBlockActions(),
    /** 数学公式：`display_enable_math_rendering_v1` / `display_enable_dollar_latex_v1`。 */
    math: MathConfig = MathConfig(),
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
            // `conflate()` + 长文时的 50ms 等待 = 原版 `_syncRenderText`/
            // `_streamingLongRenderDebounce` 的去抖语义：解析（或去抖窗口）期间到达的
            // 增量被丢掉，只保留**最新**那份再渲染。长回答流式输出时，每个 token 都重建
            // 整篇 AnnotatedString 太贵（内容多的会话尤其明显），这也是原版唯一为长文
            // 加的那道闸。短文本不加延迟（`shouldThrottleStreamingRender` 判据同原版）。
            .conflate()
            .map { (md, withCitations) ->
                val started = android.os.SystemClock.uptimeMillis()
                val result = parseMarkdownSource(md, withCitations)
                if (shouldThrottleStreamingRender(md.length)) {
                    val elapsed = android.os.SystemClock.uptimeMillis() - started
                    val wait = STREAMING_LONG_RENDER_DEBOUNCE_MS - elapsed
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                }
                result
            }
            .flowOn(Dispatchers.Default)
            .collect { parsed = it }
    }
    // 行内公式的尺寸/颜色只在这里算一次，`appendInlineStyled` 通过 CompositionLocal
    // 取用（InlineTextContent 的占位符必须**提前**知道尺寸）。
    val mathDensity = LocalDensity.current
    val mathColorArgb = LocalContentColor.current.toArgb()
    val inlineMath = remember(math, baseFontSize, mathColorArgb, mathDensity) {
        InlineMathScope(
            config = math,
            fontPx = with(mathDensity) { baseFontSize.sp.toPx() },
            colorArgb = mathColorArgb,
            density = mathDensity,
        )
    }
    CompositionLocalProvider(LocalInlineMath provides inlineMath) {
        MarkdownBody(
            node = parsed.root,
            plainTexts = parsed.plainTexts,
            modifier = modifier,
            baseFontSize = baseFontSize,
            baseLineHeight = baseLineHeight,
            citation = citation,
            tableActions = tableActions,
            codeBlock = codeBlock,
            codeBlockActions = codeBlockActions,
            math = math,
        )
    }
}

/**
 * 行内公式渲染上下文（MarkdownText 注入）：[config] 是两开关，[fontPx] 是正文字号
 * （公式按这个尺寸渲染，RikkaHub 同款），[density] 用来把 drawable 的像素尺寸换成
 * 占位符要的 sp。
 */
private data class InlineMathScope(
    val config: MathConfig,
    val fontPx: Float,
    val colorArgb: Int,
    val density: androidx.compose.ui.unit.Density,
)

private val LocalInlineMath = staticCompositionLocalOf<InlineMathScope?> { null }

/**
 * 长文渲染去抖阈值 —— 逐字照原版 `markdown_with_highlight.dart:122`：
 * 只有「流式 + 文本 ≥8000 字」才启用去抖（短文本照旧每个增量都渲染，
 * 不然打字机效果会被拖成 20fps）。
 */
internal const val STREAMING_DEBOUNCE_THRESHOLD_CHARS = 8000

/** 去抖窗口 —— 原版 `markdown_with_highlight.dart:127-129` 的 50ms。 */
internal const val STREAMING_LONG_RENDER_DEBOUNCE_MS = 50L

/** 这次渲染要不要走去抖（原版 `_syncRenderText` 的判据）。 */
internal fun shouldThrottleStreamingRender(textLength: Int): Boolean =
    textLength >= STREAMING_DEBOUNCE_THRESHOLD_CHARS

/**
 * 一次解析的产物：AST 根 + 每个节点纯文本在**一段扁平缓冲**里的区间。
 *
 * 为什么不是 `Map<Node, String>`：老实现给**每个节点**都存一份「子树纯文本」字符串，
 * 于是祖先节点把它所有后代的文本各存了一遍（O(内容 × 深度) 的分配）。内容多的会话
 * 一打开就是主线程上一大批字符串分配 —— 用户 2026-09-15「点击对话历史…内容多的就会
 * 很卡」。原版 `markdown_with_highlight.dart` 用 `ByteLruCache` + `IncrementalMarkdownDocument`
 * 避免这件事；我们这边等价的做法是：**整篇只拼一次扁平缓冲**，每个节点只记 `(start,end)`。
 * 参数同 [ParsedMarkdown]。
 */
private class ParsedMarkdown(
    val root: Node,
    /** 全篇纯文本；每个节点的 `[start, end)` 就是它在里面的切片。 */
    val flatText: String,
    val ranges: Map<Node, IntRange>,
) {
    /**
     * `Map<Node, String>` 的**惰性视图**（渲染阶段仍按 `plainTexts[node]` 取值，签名不变）：
     * 只在真的取某个节点时才切子串 + 记忆化，不再为整棵树预先造字符串。
     * 视图只在渲染线程（主线程）用，所以内部 `memo` 不需要同步。
     */
    val plainTexts: Map<Node, String> by lazy { LazyNodeTextMap(flatText, ranges) }
}

private class LazyNodeTextMap(
    private val flat: String,
    private val ranges: Map<Node, IntRange>,
) : AbstractMap<Node, String>() {

    private val memo = HashMap<Node, String>()

    override fun get(key: Node): String? {
        memo[key]?.let { return it }
        val range = ranges[key] ?: return null
        val text = flat.substring(range.first, range.last + 1)
        memo[key] = text
        return text
    }

    override fun containsKey(key: Node): Boolean = ranges.containsKey(key)

    override val entries: Set<Map.Entry<Node, String>>
        get() = ranges.entries.mapTo(LinkedHashSet()) { (node, range) ->
            object : Map.Entry<Node, String> {
                override val key: Node = node
                override val value: String = flat.substring(range.first, range.last + 1)
            }
        }
}

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

/** [parseForTest] 的返回值（测试专用，见 `MarkdownPlainTextTest`）。 */
internal class MarkdownParseForTest(
    val root: Node,
    /** 渲染侧用的那张惰性表（`plainTexts[node]`）。 */
    val plainTexts: Map<Node, String>,
    /** 扁平缓冲的长度：整篇只拼一遍，不做「每个祖先各存一份子树文本」。 */
    val flatChars: Int,
)

/**
 * 测试用：解析一段 Markdown 并把内部结构暴露出来，用来钉住两件事：
 * ① 惰性表取到的每个节点纯文本与原实现（每个节点各存一份子树文本）**逐字相同**；
 * ② 扁平缓冲**不重复**祖先内容（`flatChars` 只含 Text 字面量，不会随嵌套深度膨胀）。
 */
internal fun parseForTest(markdown: String, withCitations: Boolean = true): MarkdownParseForTest {
    val parsed = parseMarkdown(markdown, withCitations)
    return MarkdownParseForTest(parsed.root, parsed.plainTexts, parsed.flatText.length)
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
    // 冷解析发生在**组合期（主线程）**：首帧同步解析是照 RikkaHub `Markdown.kt:240`
    // 做的（避免空白闪烁），但「打开一条历史会话」时可见的几条消息全是冷的，
    // 累计耗时是那条路径的主要嫌疑。这里把慢的那几次报给可选 sink（app 层在 debug
    // 构建里接到 PerfProbe；release 默认 null，零开销）。
    val startedAt = android.os.SystemClock.uptimeMillis()
    val parsed = parseMarkdown(markdown, withCitations)
    parsedCache.put(key, parsed)
    val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
    if (elapsed >= 4) {
        MarkdownPerf.sink?.invoke("markdown-cold-parse ${markdown.length}ch ${elapsed}ms")
    }
    return parsed
}

/**
 * 冷解析的观测口（app 层 debug 构建里接到 `PerfProbe`；release 保持 null）。
 * 放在 core:ui 里是因为解析发生在这一层，而 app 依赖 core:ui（不能反向依赖）。
 */
object MarkdownPerf {
    @Volatile
    var sink: ((String) -> Unit)? = null
}

private fun parseMarkdown(markdown: String, withCitations: Boolean): ParsedMarkdown {
    val source = if (withCitations) preprocessCitations(markdown) else markdown
    val root = MarkdownRenderer.parse(source)
    val flat = StringBuilder(source.length + 16)
    val ranges = HashMap<Node, IntRange>()
    // 一次自底向上遍历：整篇只拼一遍，每个节点只记区间（见 ParsedMarkdown 的注释）。
    collectPlainTextRanges(root, flat, ranges)
    return ParsedMarkdown(root, flat.toString(), ranges)
}

/** 把整棵树的纯文本拼进 [flat]，同时记下每个节点的 `[start, end)`。 */
private fun collectPlainTextRanges(
    node: Node,
    flat: StringBuilder,
    out: MutableMap<Node, IntRange>,
): Unit {
    val start = flat.length
    var child = node.firstChild
    while (child != null) {
        if (child is Text) {
            flat.append(child.literal.orEmpty())
        } else {
            collectPlainTextRanges(child, flat, out)
        }
        child = child.next
    }
    out[node] = start until flat.length
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
    codeBlock: CodeBlockConfig,
    codeBlockActions: CodeBlockActions,
    math: MathConfig,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var child = node.firstChild
        while (child != null) {
            MarkdownNode(
                child,
                plainTexts,
                baseFontSize,
                baseLineHeight,
                citation,
                tableActions,
                codeBlock,
                codeBlockActions,
                math,
            )
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
    codeBlock: CodeBlockConfig,
    codeBlockActions: CodeBlockActions,
    math: MathConfig,
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
            // 块级公式：整段就是 `$$…$$` / `\[…\]` 时走 MathBlock（居中 + 横向滚动），
            // 否则按行内公式切分后再排版。
            val displayBody = displayMathBody(nodeText(node, plainTexts), math)
            if (displayBody != null) {
                MathBlock(latex = displayBody, fontSize = baseFontSize.sp)
            } else {
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
        }
        is FencedCodeBlock -> CodeBlockView(
            code = node.literal,
            language = node.info,
            config = codeBlock,
            actions = codeBlockActions,
        )
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
                    codeBlock = codeBlock,
                    codeBlockActions = codeBlockActions,
                    math = math,
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
                            codeBlock = codeBlock,
                            codeBlockActions = codeBlockActions,
                            math = math,
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
        else -> MarkdownBody(
            node,
            plainTexts,
            Modifier,
            baseFontSize,
            baseLineHeight,
            citation,
            tableActions,
            codeBlock,
            codeBlockActions,
            math,
        )
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


/**
 * Inline formatting with real styles (gpt_markdown md_widget behavior):
 * strong (w600), emphasis (italic), strikethrough (line-through), inline code
 * (code background + monospace), links (colored, underlined, clickable) and
 * citation capsules (rounded primary pill showing the resolved index).
 * Image nodes remain a placeholder until the image subsystem lands.
 */
@Composable
internal fun renderInline(
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

/**
 * 行内公式（RikkaHub `Markdown.kt:1160-1215` 的 INLINE_MATH 分支）：把一段文本按
 * `$…$` / `\(…\)` 切开，公式登记成 [InlineTextContent]（占位符尺寸必须**提前**算），
 * 过长的公式按顶层运算符拆段并插零宽空格提供换行点；解析失败或尺寸为 0 时回退原文，
 * 保证内容不被吞掉。
 */
@Composable
private fun AnnotatedString.Builder.appendTextWithInlineMath(
    literal: String,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    val scope = LocalInlineMath.current
    if (scope == null || !scope.config.enabled || literal.isEmpty()) {
        append(literal)
        return
    }
    val segments = splitInlineMath(literal, scope.config)
    if (segments.size == 1 && segments[0] is MathSegment.Plain) {
        append(literal)
        return
    }
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is MathSegment.Plain -> append(segment.text)
            is MathSegment.Formula -> {
                val drawables = splitLatex(
                    latex = segment.latex,
                    maxWidthPx = scope.fontPx * 2,
                    fontSizePx = scope.fontPx,
                    color = scope.colorArgb,
                )
                if (drawables.isEmpty()) {
                    val rect = assumeLatexSize(segment.latex, scope.fontPx)
                    if (rect.width() <= 0 || rect.height() <= 0) {
                        // 非法 LaTeX：原样显示，别丢内容。
                        append(segment.latex)
                    } else {
                        val key = "math:$index:${segment.latex.hashCode()}"
                        appendInlineContent(key, ZERO_WIDTH)
                        val width = with(scope.density) { rect.width().toSp() }
                        val height = with(scope.density) { rect.height().toSp() }
                        inlineContent.putIfAbsent(
                            key,
                            InlineTextContent(
                                Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
                            ) { MathInline(latex = segment.latex) },
                        )
                    }
                } else {
                    drawables.forEachIndexed { partIndex, drawable ->
                        // 段间零宽空格 = 可换行点（RikkaHub 同款）。
                        if (partIndex > 0) append(ZERO_WIDTH)
                        val key = "math:$index:$partIndex:${segment.latex.hashCode()}"
                        appendInlineContent(key, ZERO_WIDTH)
                        val width = with(scope.density) { drawable.bounds.width().toSp() }
                        val height = with(scope.density) { drawable.bounds.height().toSp() }
                        inlineContent.putIfAbsent(
                            key,
                            InlineTextContent(
                                Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
                            ) { LatexDrawable(drawable = drawable) },
                        )
                    }
                }
            }
        }
    }
}

/** 行内占位符的替换文本：零宽空格，避免复制正文时混进标记。 */
private const val ZERO_WIDTH = "\u200B"

@Composable
private fun AnnotatedString.Builder.appendInlineStyled(    node: Node,
    plainTexts: Map<Node, String>,
    codeBackground: Color,
    linkColor: Color,
    citation: CitationRenderConfig,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    when (node) {
        is Text -> appendTextWithInlineMath(node.literal ?: "", inlineContent)
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight(600))) {
            appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
        }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlineChildren(node, plainTexts, codeBackground, linkColor, citation, inlineContent)
        }
        is Code -> withStyle(SpanStyle(background = codeBackground, fontFamily = LocalMarkdownCodeFont.current)) {
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
                    // An empty display text marks a citation-shaped marker whose
                    // index could not be resolved: drop the marker entirely
                    // instead of drawing a "?" capsule (2026-09-12, user
                    // decision — a dangling "?" beside the prose reads as a
                    // defect). Leaving the marker unrendered keeps the sentence
                    // clean; the source was never in this message's list, so
                    // there is nothing to open either.
                    if (capsule.text.isEmpty()) return
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
                        val text = info?.index?.toString()
                            ?: ref.indexText.takeIf { it != ref.id }
                            ?: return // unresolvable: render nothing
                        inlineContent.putIfAbsent(
                            "citation:${ref.id}",
                            citationInlineContent(text) { onTap(ref.id) },
                        )
                        appendInlineContent("citation:${ref.id}", " ")
                        return
                    }
                }
                // 指向本条消息来源的普通 Markdown 链接（实测 DeepSeek 写
                // `[链接](https://aihot.news/items/…)`）同样画成序号胶囊 —— 用户
                // 要求来源一律「胶囊 + 数字」，不要"链接"这种链接文字。
                val sourceCapsule = resolveSourceUrlCapsule(node.destination ?: "", citation.resolver)
                if (sourceCapsule != null) {
                    inlineContent.putIfAbsent(
                        "citation:${sourceCapsule.key}",
                        citationInlineContent(sourceCapsule.text) { onTap(sourceCapsule.key) },
                    )
                    appendInlineContent("citation:${sourceCapsule.key}", " ")
                    return
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
 * 普通 Markdown 链接 `[标签](https://…)` → 序号胶囊，前提是这个 URL 能解析成
 * 本条消息的来源（[resolver] 返回了序号）。
 *
 * 模型实测（DeepSeek + MCP 搜索工具）不写 `[cite:id]`，而是把来源写成
 * `[链接](https://aihot.news/items/…)`；那时正文里只剩"链接"两个字，用户要的是
 * 「胶囊 + 数字」。命中来源才转换，未命中的链接照旧按普通链接渲染。
 */
internal fun resolveSourceUrlCapsule(
    url: String,
    resolver: ((String) -> CitationInfo?)?,
): CitationCapsule? {
    val dest = url.trim()
    if (!dest.startsWith("http://", ignoreCase = true) &&
        !dest.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val index = resolver?.invoke(dest)?.index ?: return null
    return CitationCapsule(dest, index.toString())
}

/**
 * Resolve a `[citation,X](Y)` / `[cite,X](Y)` link into a capsule.
 *
 * Models drift from the prompted `[cite:id]` to the comma-metadata spelling
 * (`[citation,domain](id)` / `[cite,domain](id)`); both are accepted here. The
 * capsule key prefers a 6-char id token (Memo search ids are `UUID.take(6)`)
 * taken from the destination first, then the label; a URL destination renders
 * a capsule that opens the link directly. The label metadata is ignored for
 * display — the capsule always shows the numeric index, like the original
 * project.
 *
 * Returns `null` when the label is not a citation at all (the caller then
 * renders an ordinary link), and a capsule with an **empty [CitationCapsule.text]**
 * when the marker *is* a citation but its index cannot be resolved — the caller
 * drops those rather than drawing a "?" (2026-09-12, user decision; the
 * original falls back to "?" here). A numeric label metadata still wins over
 * dropping, since it is a usable index on its own.
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
    val text = info?.index?.toString()
        ?: meta.toIntOrNull()?.toString()
        ?: ""
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