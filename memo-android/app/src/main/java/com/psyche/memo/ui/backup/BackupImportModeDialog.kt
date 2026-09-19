package com.psyche.memo.ui.backup

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.RotateCw
import com.psyche.memo.data.backup.RestoreMode
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.alphaBlend
import com.psyche.memo.ui.theme.withAlpha

/**
 * Port of `_chooseImportModeDialog` + `_ActionCard`
 * (`backup_page.dart` L165-201 / L2385-2462).
 *
 * Two tappable cards — Complete Overwrite and Merge — over a Cancel button.
 * The card is r14 with a hairline border, a 40dp primary-tinted icon tile at
 * radius 10, a title and a 12sp@70% description, plus a trailing chevron.
 * [onDismiss] with no selection means the user backed out.
 */
@Composable
fun BackupImportModeDialog(
    onSelect: (RestoreMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = {
            Text(
                text = stringResource(UiR.string.backup_page_select_import_mode),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ImportModeCard(
                    icon = Lucide.RotateCw,
                    title = stringResource(UiR.string.backup_page_overwrite_mode),
                    subtitle = stringResource(UiR.string.backup_page_overwrite_mode_description),
                    onTap = { onSelect(RestoreMode.OVERWRITE) },
                )
                Spacer(Modifier.height(10.dp))
                ImportModeCard(
                    icon = Lucide.GitFork,
                    title = stringResource(UiR.string.backup_page_merge_mode),
                    subtitle = stringResource(UiR.string.backup_page_merge_mode_description),
                    onTap = { onSelect(RestoreMode.MERGE) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(UiR.string.backup_page_cancel),
                    style = TextStyle(fontSize = 14.sp, color = cs.primary),
                )
            }
        },
    )
}

@Composable
private fun ImportModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // _TactileRow(pressedScale: 0.98) + a 5/6% surface wash on press.
    val scale = if (pressed) 0.98f else 1f
    val cardColor = if (pressed) {
        alphaBlend(cs.surface, if (semantic.isDark) 0.06 else 0.05, semantic.surfaceFill)
    } else {
        semantic.surfaceFill
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .background(cardColor)
            .border(1.dp, withAlpha(cs.outlineVariant, 0.18), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(interactionSource = interaction, indication = null) { onTap() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(withAlpha(cs.primary, 0.10), RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.7)),
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = withAlpha(cs.onSurface, 0.4),
            modifier = Modifier.size(18.dp),
        )
    }
}
