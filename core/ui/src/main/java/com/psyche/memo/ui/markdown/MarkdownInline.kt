package com.psyche.memo.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eye
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

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
internal fun AnnotatedString.Builder.appendTextWithInlineMath(
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
internal fun AnnotatedString.Builder.appendInlineStyled(    node: Node,
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
internal fun AnnotatedString.Builder.appendInlineChildren(
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
internal fun citationInlineContent(text: String, onClick: () -> Unit): InlineTextContent {
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