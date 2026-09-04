package com.psyche.memo.ui.markdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide

/**
 * Chain-of-thought card matching memo _ChainOfThoughtCard:
 * a shared rounded surface (16) with a collapsible header (Brain in the
 * timeline icon column + "Thinking…" 13px semibold + ChevronDown/Up 16px);
 * the thinking text is revealed on tap.
 */
@Composable
fun ThinkingCard(
    thinking: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val fgStrong = cs.onSurface.copy(alpha = 0.72f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Collapsible header row (icon column + gap + label + chevron).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Lucide.Brain,
                    contentDescription = null,
                    tint = fgStrong,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "Thinking" else "Thinking",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgStrong,
                ),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = fgStrong,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = thinking,
                modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    color = cs.onSurface.copy(alpha = 0.72f),
                ),
            )
        }
    }
}
