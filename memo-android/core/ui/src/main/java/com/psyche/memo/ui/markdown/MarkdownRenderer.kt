package com.psyche.memo.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
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
        is Image -> Text(
            text = "[image ${node.destination}]",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        else -> MarkdownBody(node, Modifier, baseFontSize, baseLineHeight, citation)
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
                // [citation](id) by preprocessCitations — resolve the domain
                // against this message's search results.
                if (label.equals("citation", ignoreCase = true)) {
                    val ref = parseCitationRef(node.destination ?: "")
                    if (ref != null) {
                        val info = citation.resolver?.invoke(ref.id)
                        val text = when {
                            !info?.domain.isNullOrEmpty() -> info!!.domain
                            ref.indexText != ref.id -> ref.indexText
                            info?.index != null -> info.index.toString()
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
 * Inline citation capsule — RikkaHub MarkdownNew.kt shape (circular pill,
 * centered 10sp monospace Thin label, width 7sp per char, height 1em, tap
 * opens the source) with the ORIGINAL project's colors (primary 20% bg +
 * primary label, markdown_with_highlight.dart linkBuilder). `coerceAtLeast(20)`
 * only guards the short legacy index fallback; real domains (≥3 chars) match
 * RikkaHub's `(domain.length * 7).sp` exactly. FontFamily.Monospace substitutes
 * RikkaHub's bundled JetBrains Mono (no such asset in Memo).
 */
@Composable
private fun citationInlineContent(text: String, onClick: () -> Unit): InlineTextContent {
    val cs = MaterialTheme.colorScheme
    return InlineTextContent(
        Placeholder(
            width = (text.length * 7).coerceAtLeast(20).sp,
            height = 1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxSize()
                .clip(CircleShape)
                // Capsule colors follow the ORIGINAL project (primary 20% bg +
                // primary label); only the RikkaHub shape/layout is ported.
                .background(cs.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                modifier = Modifier.wrapContentSize(),
                style = TextStyle(
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = cs.primary,
                    fontWeight = FontWeight.Thin,
                ),
            )
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
 * Models drift from the prompted `[citation,domain](id)` to the shorter
 * `[cite,domain](id)` spelling, and sometimes echo the id into the label
 * (`[cite,e2dce5](e2dce5)`); both spellings are accepted here. The capsule
 * key prefers a 6-char id token (RikkaHub's gate — Memo search ids are
 * `UUID.take(6)`) taken from the destination first, then the label; a URL
 * destination renders a capsule that opens the link directly. Display text:
 * the model-supplied domain when the label carries one, otherwise the domain
 * resolved from this message's search results, else the raw label metadata.
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
    val text = when {
        meta.contains('.') -> meta
        else -> resolver?.invoke(key)?.domain?.takeIf { it.isNotEmpty() } ?: meta
    }.ifEmpty { "?" }
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
