package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of chat_suggestion_bubbles.dart: up to three tappable suggestion pills
 * under the last assistant message (primaryContainer 42% in light, onSurface
 * 8% in dark, r16, 13sp medium).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSuggestionBubbles(
    suggestions: List<String>,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val visible = suggestions.map { it.trim() }.filter { it.isNotEmpty() }.take(3)
    if (visible.isEmpty()) return

    val baseColor = if (isDark) {
        cs.onSurface.copy(alpha = 0.08f)
    } else {
        cs.primaryContainer.copy(alpha = 0.42f)
    }
    val textColor = cs.onSurface.copy(alpha = if (isDark) 0.92f else 0.88f)

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visible.forEach { suggestion ->
            Box(
                modifier = Modifier
                    .background(baseColor, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable(role = Role.Button) { onTap(suggestion) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 13.sp,
                        lineHeight = 15.6.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}
