package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ProviderGroup
import com.psyche.memo.data.repo.ProviderGroupLogic
import com.psyche.memo.data.repo.ProviderRepository
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of provider_groups_page.dart (#12): draggable group cards (the
 * ungrouped pseudo-row included — moving it changes its display position),
 * per-group member counts, create / rename / delete dialogs and the deleted
 * toast. Reorder + delete math lives in ProviderGroupLogic (tested).
 */
@Composable
fun ProviderGroupsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val context = LocalContext.current
    val repo = remember(container) {
        ProviderRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var reload by remember { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProviderGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<ProviderGroup?>(null) }

    // ---- snapshot of the grouping state (re-read after each mutation) ----
    val groups = remember(reload) { repo.groups() }
    val ungroupedIndex = remember(reload) { repo.ungroupedPosition() }
    val groupMap = remember(reload) { repo.groupMap() }
    val mergedOrder = remember(reload) {
        val known = repo.builtinKeys + repo.getConfigs().keys
        repo.applyOrder(known)
    }
    val counts = remember(reload) {
        val out = HashMap<String, Int>()
        var ungroupedCount = 0
        for (key in mergedOrder) {
            val gid = groupMap[key]
            if (gid == null) ungroupedCount++ else out[gid] = (out[gid] ?: 0) + 1
        }
        out to ungroupedCount
    }

    data class Row(val key: String, val title: String, val count: Int, val isUngrouped: Boolean)
    val otherLabel = stringResource(R.string.provider_groups_other)
    val createFailedToast = stringResource(R.string.provider_groups_create_failed_toast)
    val deletedToast = stringResource(R.string.provider_groups_deleted_toast)
    val displayKeys = ProviderGroupLogic.buildProviderGroupDisplayKeys(groups, ungroupedIndex)
    val rows = displayKeys.map { key ->
        if (key == ProviderGroupLogic.UNGROUPED_KEY) {
            Row(key, otherLabel, counts.second, true)
        } else {
            Row(key, groups.firstOrNull { it.id == key }?.name ?: "", counts.first[key] ?: 0, false)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- AppBar: ChevronLeft 44 + title + Plus ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconActionButton(Lucide.ChevronLeft, cs.onSurface, "Back") { onBack() }
            Text(
                text = stringResource(R.string.provider_groups_manage_title),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                modifier = Modifier.weight(1f),
            )
            IconActionButton(Lucide.Plus, cs.onSurface, stringResource(R.string.provider_groups_create_new_group_action)) {
                showCreate = true
            }
            Spacer(Modifier.width(12.dp))
        }

        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.provider_groups_empty_state),
                    style = TextStyle(color = cs.onSurface.copy(alpha = 0.7f)),
                )
            }
        } else {
            ReorderableColumn(
                items = rows,
                keyOf = { it.key },
                onMove = { from, to ->
                    // sh.calvin.reorderable reports direct-move indices;
                    // upstream expects Flutter's unadjusted onReorder index.
                    val newIndex = if (to > from) to + 1 else to
                    repo.reorderGroupsWithUngrouped(from, newIndex)
                    reload++
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
                itemContent = { row, _ ->
                    val edit: (() -> Unit)? =
                        if (row.isUngrouped) null
                        else ({ renameTarget = groups.firstOrNull { it.id == row.key } })
                    val del: (() -> Unit)? =
                        if (row.isUngrouped) null
                        else ({ deleteTarget = groups.firstOrNull { it.id == row.key } })
                    GroupCard(
                        title = row.title,
                        count = row.count,
                        onEdit = edit,
                        onDelete = del,
                    )
                },
            )
        }
    }

    // ---- Create dialog ----
    if (showCreate) {
        GroupNameDialog(
            title = stringResource(R.string.provider_groups_create_dialog_title),
            hint = stringResource(R.string.provider_groups_name_hint),
            cancelLabel = stringResource(R.string.provider_groups_create_dialog_cancel),
            okLabel = stringResource(R.string.provider_groups_create_dialog_ok),
            initial = "",
            onDismiss = { showCreate = false },
            onOk = { name ->
                showCreate = false
                val id = repo.createGroup(name)
                if (id.isEmpty()) {
                    SnackbarManager.show(
                        AppNotification(
                            message = createFailedToast,
                            type = NotificationType.ERROR,
                        ),
                    )
                }
                reload++
            },
        )
    }

    // ---- Rename dialog ----
    renameTarget?.let { target ->
        GroupNameDialog(
            title = stringResource(R.string.provider_detail_page_edit_tooltip),
            hint = stringResource(R.string.provider_groups_name_hint),
            cancelLabel = stringResource(R.string.provider_groups_create_dialog_cancel),
            okLabel = stringResource(R.string.side_drawer_save),
            initial = target.name,
            onDismiss = { renameTarget = null },
            onOk = { name ->
                renameTarget = null
                repo.renameGroup(target.id, name)
                reload++
            },
        )
    }

    // ---- Delete confirm dialog ----
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.provider_groups_delete_confirm_title)) },
            text = { Text(stringResource(R.string.provider_groups_delete_confirm_content)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    repo.deleteGroupFully(target.id)
                    SnackbarManager.show(
                        AppNotification(
                            message = deletedToast,
                            type = NotificationType.SUCCESS,
                        ),
                    )
                    reload++
                }) {
                    Text(stringResource(R.string.provider_groups_delete_confirm_ok), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.provider_groups_delete_confirm_cancel))
                }
            },
        )
    }
}

/** Group card: surfaceFill r14 + border, title + count pill + edit/delete. */
@Composable
private fun GroupCard(
    title: String,
    count: Int,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(semantic.surfaceFill, RoundedCornerShape(14.dp))
                .border(
                    1.dp,
                    cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.12f else 0.10f),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = TextStyle(fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold),
                )
            }
            if (onEdit != null) {
                Spacer(Modifier.width(10.dp))
                CardIconButton(Lucide.Pencil, cs.onSurface) { onEdit() }
            }
            if (onDelete != null) {
                Spacer(Modifier.width(4.dp))
                CardIconButton(Lucide.Trash2, cs.error) { onDelete() }
            }
        }
    }
}

@Composable
private fun CardIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    hint: String,
    cancelLabel: String,
    okLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(hint) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onOk(name) }) { Text(okLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}
