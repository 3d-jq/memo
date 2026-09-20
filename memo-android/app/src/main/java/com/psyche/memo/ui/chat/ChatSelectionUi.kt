package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.rememberMemoSheetState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wrench
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.selectionChipColor
import com.psyche.memo.ui.R as UiR

/**
 * Port of chat_selection_export_bar.dart / chat_selection_delete_bar.dart: the
 * bottom action bars shown while messages are selected.
 */
@Composable
fun ChatSelectionExportBar(
    showThinkingTools: Boolean,
    showThinkingContent: Boolean,
    onExportMarkdown: () -> Unit,
    onExportTxt: () -> Unit,
    onToggleThinkingTools: () -> Unit,
    onToggleThinkingContent: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface.copy(alpha = 0.94f), RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionActionButton(
                icon = Lucide.FileText,
                label = stringResource(UiR.string.chat_selection_export_txt),
                color = cs.tertiary,
                modifier = Modifier.weight(1f),
            ) {
                Haptics.light(view)
                onExportTxt()
            }
            SelectionActionButton(
                icon = Lucide.BookOpenText,
                label = stringResource(UiR.string.chat_selection_export_md),
                color = cs.primary,
                modifier = Modifier.weight(1f),
            ) {
                Haptics.light(view)
                onExportMarkdown()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionToggleCard(
                icon = Lucide.Wrench,
                label = stringResource(UiR.string.chat_selection_thinking_tools),
                selected = showThinkingTools,
                enabled = true,
                modifier = Modifier.weight(1f),
                onTap = onToggleThinkingTools,
            )
            SelectionToggleCard(
                icon = Lucide.Brain,
                label = stringResource(UiR.string.chat_selection_thinking_content),
                selected = showThinkingContent,
                enabled = showThinkingTools,
                modifier = Modifier.weight(1f),
                onTap = onToggleThinkingContent,
            )
        }
    }
}

/** ChatSelectionDeleteBar. */
@Composable
fun ChatSelectionDeleteBar(
    hasMultiVersionSelection: Boolean,
    onDeleteCurrentVersions: () -> Unit,
    onDeleteAllVersions: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface.copy(alpha = 0.94f), RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasMultiVersionSelection) {
            SelectionActionButton(
                icon = Lucide.Trash2,
                label = stringResource(UiR.string.home_page_delete),
                color = cs.error,
                modifier = Modifier.weight(1f),
            ) {
                Haptics.light(view)
                onDeleteCurrentVersions()
            }
        } else {
            SelectionActionButton(
                icon = Lucide.Trash2,
                label = stringResource(UiR.string.home_page_delete_message),
                color = cs.error,
                modifier = Modifier.weight(1f),
            ) {
                Haptics.light(view)
                onDeleteCurrentVersions()
            }
            SelectionActionButton(
                icon = Lucide.Trash,
                label = stringResource(UiR.string.home_page_delete_all_versions),
                color = cs.error,
                modifier = Modifier.weight(1f),
            ) {
                Haptics.light(view)
                onDeleteAllVersions()
            }
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val isDark = LocalSemanticColors.current.isDark
    // 与抽屉那一排共用上游同一条式子（`chat_selection_export_bar.dart:169-172` ≡
    // `sidebar_selection_bars.dart:231-234`）：原来是往白/黑 lerp 的近似，两处不一致。
    val bg = selectionChipColor(
        onSurface = MaterialTheme.colorScheme.onSurface,
        color = color,
        isDark = isDark,
    )
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color),
        )
    }
}

@Composable
private fun SelectionToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    val base = if (selected) {
        cs.primary.copy(alpha = if (isDark) 0.22f else 0.14f)
    } else {
        cs.onSurface.copy(alpha = 0.06f)
    }
    val border = if (selected) {
        cs.primary.copy(alpha = if (isDark) 0.52f else 0.36f)
    } else {
        cs.outlineVariant.copy(alpha = if (isDark) 0.18f else 0.14f)
    }
    val fg = if (selected) cs.primary else cs.onSurface.copy(alpha = if (enabled) 0.9f else 0.35f)
    Row(
        modifier = modifier
            .background(base, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, border, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg),
        )
    }
}

/** message_export_sheet.dart `_BatchExportSheet` — format picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageExportSheet(
    onMarkdown: () -> Unit,
    onTxt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
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
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(UiR.string.message_export_sheet_format_title),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(10.dp))
            ExportOptionTile(
                icon = Lucide.BookOpenText,
                title = stringResource(UiR.string.message_export_sheet_markdown),
                subtitle = stringResource(UiR.string.message_export_sheet_batch_markdown_subtitle),
            ) {
                Haptics.light(view)
                onMarkdown()
            }
            ExportOptionTile(
                icon = Lucide.FileText,
                title = stringResource(UiR.string.message_export_sheet_plain_text),
                subtitle = stringResource(UiR.string.message_export_sheet_batch_txt_subtitle),
            ) {
                Haptics.light(view)
                onTxt()
            }
        }
    }
}

@Composable
private fun ExportOptionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(LocalSemanticColors.current.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.6f)),
            )
        }
    }
}
