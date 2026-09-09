package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package2
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * Port of context_management_sheet.dart: "compress context" (summarize and
 * start a new chat) and "clear context" (truncateIndex toggle) rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextManagementSheet(
    clearLabel: String,
    onCompress: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))
            OptionRow(
                icon = Lucide.Package2,
                label = stringResource(UiR.string.compress_context),
                description = stringResource(UiR.string.compress_context_desc),
                onTap = {
                    Haptics.light(view)
                    onCompress()
                },
            )
            Spacer(Modifier.height(8.dp))
            OptionRow(
                icon = Lucide.Eraser,
                label = clearLabel,
                description = stringResource(UiR.string.clear_context_desc),
                onTap = {
                    Haptics.light(view)
                    onClear()
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** _OptionRow L85-142 — sheet tile with icon + label + description. */
@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCardFill, RoundedCornerShape(14.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = cs.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.55f)),
            )
        }
    }
}
