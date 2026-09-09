package com.psyche.memo.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.R as UiR

/**
 * Port of bounded_large_text_view.dart: text up to 40 lines / 12k chars renders
 * directly; larger text shows a bounded preview with a "show N more" toggle that
 * expands into a virtualized list of independently selectable chunks.
 */
@Composable
fun BoundedLargeTextView(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    maxExpandedHeight: androidx.compose.ui.unit.Dp = 320.dp,
) {
    val projection = remember(text) { LargeTextProjection.fromText(text) }
    var expanded by remember(text) { mutableStateOf(false) }

    if (!projection.isLarge) {
        SelectionContainer(modifier = modifier) { Text(text = text, style = style) }
        return
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (expanded) {
            SelectionContainer {
                LazyColumn(modifier = Modifier.height(maxExpandedHeight)) {
                    itemsIndexed(projection.chunks) { _, chunk ->
                        Text(text = chunk, style = style)
                    }
                }
            }
        } else {
            SelectionContainer { Text(text = projection.preview, style = style) }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) {
                        stringResource(UiR.string.large_content_collapse)
                    } else {
                        stringResource(UiR.string.large_content_show_more, projection.hiddenLines.toString())
                    },
                )
            }
        }
    }
}

/** _LargeTextProjection — pure port of the collapse/chunk algorithm. */
internal data class LargeTextProjection(
    val preview: String,
    val chunks: List<String>,
    val hiddenLines: Int,
    val isLarge: Boolean,
) {
    companion object {
        const val PREVIEW_LINES = 40
        const val PREVIEW_CHARS = 12000
        const val CHUNK_LINES = 40
        const val CHUNK_CHARS = 16000

        fun fromText(text: String): LargeTextProjection {
            val lines = text.split(Regex("\\r\\n|\\r|\\n"))
            val isLarge = lines.size > PREVIEW_LINES || text.length > PREVIEW_CHARS
            if (!isLarge) {
                return LargeTextProjection(text, listOf(text), 0, false)
            }
            val preview = takeBounded(lines, PREVIEW_LINES, PREVIEW_CHARS)
            val chunks = ArrayList<String>()
            var current = ArrayList<String>()
            var chars = 0
            for (line in lines) {
                val nextChars = chars + line.length + if (current.isEmpty()) 0 else 1
                if (current.isNotEmpty() && (current.size >= CHUNK_LINES || nextChars > CHUNK_CHARS)) {
                    chunks.add(current.joinToString("\n"))
                    current = ArrayList()
                    chars = 0
                }
                if (line.length > CHUNK_CHARS) {
                    if (current.isNotEmpty()) {
                        chunks.add(current.joinToString("\n"))
                        current = ArrayList()
                        chars = 0
                    }
                    var start = 0
                    while (start < line.length) {
                        chunks.add(line.substring(start, (start + CHUNK_CHARS).coerceAtMost(line.length)))
                        start += CHUNK_CHARS
                    }
                    continue
                }
                current.add(line)
                chars += line.length + if (current.size == 1) 0 else 1
            }
            if (current.isNotEmpty()) chunks.add(current.joinToString("\n"))
            return LargeTextProjection(
                preview = "$preview\n…",
                chunks = chunks.toList(),
                hiddenLines = (lines.size - PREVIEW_LINES).coerceIn(1, lines.size),
                isLarge = true,
            )
        }

        private fun takeBounded(lines: List<String>, maxLines: Int, maxChars: Int): String {
            val buffer = StringBuilder()
            for (line in lines.take(maxLines)) {
                val remaining = maxChars - buffer.length
                if (remaining <= 0) break
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(if (line.length <= remaining) line else line.substring(0, remaining))
            }
            return buffer.toString()
        }
    }
}
