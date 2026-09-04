package com.psyche.memo.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseFontSize: Float = 15.7f,
    baseLineHeight: Float = 23.55f,
) {
    if (markdown.isEmpty()) return
    val root = MarkdownRenderer.parse(markdown)
    MarkdownBody(
        node = root,
        modifier = modifier,
        baseFontSize = baseFontSize,
        baseLineHeight = baseLineHeight,
    )
}

@Composable
private fun MarkdownBody(
    node: Node,
    modifier: Modifier = Modifier,
    baseFontSize: Float,
    baseLineHeight: Float,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var child = node.firstChild
        while (child != null) {
            MarkdownNode(child, baseFontSize, baseLineHeight)
            child = child.next
        }
    }
}

@Composable
private fun MarkdownNode(
    node: Node,
    baseFontSize: Float,
    baseLineHeight: Float,
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
            Text(
                text = renderInline(node),
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
                        verticalAlignment = androidx.compose.ui.Alignment.Top,
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
        else -> MarkdownBody(node, Modifier, baseFontSize, baseLineHeight)
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

/** Inline formatting: emphasis/strong/code/link/image text (P1 subset). */
private fun renderInline(node: Node): AnnotatedString = buildAnnotatedString {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> append(child.literal ?: "")
            is StrongEmphasis -> append(child.firstChild?.let { (it as? Text)?.literal } ?: "")
            is Emphasis -> append(child.firstChild?.let { (it as? Text)?.literal } ?: "")
            is Code -> append(child.literal ?: "")
            is Link -> append(child.firstChild?.let { (it as? Text)?.literal } ?: "")
            is SoftLineBreak -> append(" ")
        }
        child = child.next
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
