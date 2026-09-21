package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.AssistantTag
import com.psyche.memo.data.repo.TagRepository
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * Port of tags_manager_page.dart: create / rename / delete / reorder group
 * tags; tapping a tag assigns it to the assistant and pops.
 */
@Composable
fun TagsManagerScreen(
    container: AppContainerImpl,
    assistantId: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val repo = remember(container) {
        TagRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var reload by remember { mutableIntStateOf(0) }
    val tags = remember(reload) { repo.tags() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AssistantTag?>(null) }
    var deleting by remember { mutableStateOf<AssistantTag?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(UiR.string.assistant_tags_manage_title),
            onBack = onBack,
        ) {
            TopBarAction(
                icon = Lucide.Plus,
                label = stringResource(UiR.string.assistant_tags_create_button),
                onClick = {
                    Haptics.light(view)
                    creating = true
                },
            )
        }
        ReorderableColumn(
            items = tags,
            keyOf = { it.id },
            onMove = { from, to ->
                val newIndex = if (to > from) to + 1 else to
                repo.reorder(from, newIndex)
                reload++
            },
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 16.dp),
        ) { tag, _ ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalSemanticColors.current.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .border(
                        1.dp,
                        cs.outlineVariant.copy(alpha = if (LocalSemanticColors.current.isDark) 0.12f else 0.10f),
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .clickable {
                        Haptics.light(view)
                        repo.assignAssistant(assistantId, tag.id)
                        onBack()
                    }
                    .padding(start = 12.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tag.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TagIconButton(Lucide.Pencil, UiR.string.assistant_tags_rename_button) {
                    Haptics.light(view)
                    renaming = tag
                }
                Spacer(Modifier.width(4.dp))
                TagIconButton(Lucide.Trash2, UiR.string.assistant_tags_delete_button, danger = true) {
                    Haptics.light(view)
                    deleting = tag
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }

    if (creating || renaming != null) {
        TagNameDialog(
            title = stringResource(
                if (renaming == null) UiR.string.assistant_tags_create_dialog_title
                else UiR.string.assistant_tags_rename_dialog_title,
            ),
            initial = renaming?.name ?: "",
            hint = stringResource(UiR.string.assistant_tags_name_hint),
            okLabel = stringResource(
                if (renaming == null) UiR.string.assistant_tags_create_dialog_ok
                else UiR.string.assistant_tags_rename_dialog_ok,
            ),
            onDismiss = {
                creating = false
                renaming = null
            },
            onConfirm = { name ->
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return@TagNameDialog
                val target = renaming
                if (target == null) {
                    if (tags.none { it.name == trimmed }) {
                        repo.create(trimmed)
                    }
                } else {
                    if (tags.none { it.name == trimmed && it.id != target.id }) {
                        repo.rename(target.id, trimmed)
                    }
                }
                creating = false
                renaming = null
                reload++
            },
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleting = null },
            title = { Text(stringResource(UiR.string.assistant_tags_delete_confirm_title)) },
            text = { Text(stringResource(UiR.string.assistant_tags_delete_confirm_content)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    repo.delete(tag.id)
                    reload++
                }) { Text(stringResource(UiR.string.assistant_tags_delete_confirm_ok), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(UiR.string.assistant_tags_delete_confirm_cancel))
                }
            },
        )
    }
}

/** _MobileTagCard iconBtn — 18dp icon, r10 press, onSurface / error. */
@Composable
private fun TagIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (danger) cs.error else cs.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun TagNameDialog(
    title: String,
    initial: String,
    hint: String,
    okLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(hint) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(okLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.assistant_tags_create_dialog_cancel))
            }
        },
    )
}
