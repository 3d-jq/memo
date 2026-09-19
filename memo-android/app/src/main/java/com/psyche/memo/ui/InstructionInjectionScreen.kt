package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.InstructionInjection
import com.psyche.memo.data.repo.InstructionInjectionRepository
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of instruction_injection_page.dart: group-collapsible CRUD list with
 * drag reorder inside a group, swipe delete and the title/group/prompt sheet.
 */
@Composable
fun InstructionInjectionScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val repo = remember(container) {
        InstructionInjectionRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var reload by remember { mutableIntStateOf(0) }
    val items = remember(reload) { repo.items() }
    var editing by remember { mutableStateOf<InstructionInjection?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InstructionInjection?>(null) }

    val grouped = remember(items) {
        items.groupBy { it.group.trim() }
            .toList()
            .sortedBy { it.first }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
                MemoTopBar(
                    title = stringResource(R.string.instruction_injection_title),
                    onBack = onBack,
                ) {
                    IconActionButton(Lucide.Plus, cs.onSurface, stringResource(R.string.instruction_injection_add_title)) {
                        Haptics.light(view)
                        adding = true
                    }
                    Spacer(Modifier.width(12.dp))
                }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            for ((group, groupItems) in grouped) {
                item(key = "header-$group") {
                    val collapsed = remember(reload, group) { repo.isCollapsed(group) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Haptics.light(view)
                                repo.toggleCollapsed(group)
                                reload++
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Lucide.ChevronRight,
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (collapsed) 0f else 90f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = group.ifEmpty { stringResource(R.string.instruction_injection_ungrouped_group) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                    }
                }
                if (!repo.isCollapsed(group)) {
                    items(groupItems.size, key = { groupItems[it].id }) { index ->
                        val item = groupItems[index]
                        Spacer(Modifier.height(if (index == groupItems.lastIndex) 12.dp else 8.dp))
                        InstructionCard(
                            item = item,
                            onClick = {
                                Haptics.light(view)
                                editing = item
                            },
                            onDelete = {
                                Haptics.light(view)
                                deleting = item
                            },
                        )
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        InstructionEditSheet(
            item = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { title, prompt, group ->
                val existing = editing
                if (existing == null) {
                    repo.add(
                        InstructionInjection(
                            id = java.util.UUID.randomUUID().toString(),
                            title = title,
                            prompt = prompt,
                            group = group,
                        ),
                    )
                } else {
                    repo.update(existing.copy(title = title, prompt = prompt, group = group))
                }
                adding = false
                editing = null
                reload++
            },
        )
    }

    deleting?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.provider_groups_delete_confirm_title)) },
            text = { Text(stringResource(R.string.provider_groups_delete_confirm_content)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleting = null
                    repo.delete(item.id)
                    reload++
                }) { Text(stringResource(R.string.quick_phrase_delete_button), color = cs.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.quick_phrase_cancel_button), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }
}

@Composable
private fun InstructionCard(
    item: InstructionInjection,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val displayTitle = item.title.trim().ifEmpty { stringResource(R.string.instruction_injection_default_title) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(
                0.6.dp,
                cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.1f else 0.08f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Layers, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = displayTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.prompt,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.7f)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstructionEditSheet(
    item: InstructionInjection?,
    onDismiss: () -> Unit,
    onSave: (title: String, prompt: String, group: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var title by remember { mutableStateOf(item?.title ?: "") }
    var group by remember { mutableStateOf(item?.group ?: "") }
    var prompt by remember { mutableStateOf(item?.prompt ?: "") }

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
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (item == null) R.string.instruction_injection_add_title
                        else R.string.instruction_injection_edit_title,
                    ),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(16.dp))
            InstructionField(
                value = title,
                onValueChange = { title = it },
                label = stringResource(R.string.instruction_injection_name_label),
            )
            Spacer(Modifier.height(12.dp))
            InstructionField(
                value = group,
                onValueChange = { group = it },
                label = stringResource(R.string.instruction_injection_group_label),
            )
            Spacer(Modifier.height(12.dp))
            InstructionField(
                value = prompt,
                onValueChange = { prompt = it },
                label = stringResource(R.string.instruction_injection_prompt_label),
                minLines = 5,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                IosSheetButton(
                    label = stringResource(R.string.quick_phrase_cancel_button),
                    onTap = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                IosSheetButton(
                    label = stringResource(R.string.quick_phrase_save_button),
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onTap = {
                        if (title.isNotBlank() && prompt.isNotBlank()) {
                            onSave(title.trim(), prompt.trim(), group.trim())
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InstructionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = minLines == 1,
        minLines = minLines,
        maxLines = if (minLines == 1) 1 else 8,
        label = { Text(label) },
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceFill,
            unfocusedContainerColor = semantic.surfaceFill,
            focusedBorderColor = cs.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Port of instruction_injection_sheet.dart: the bottom-tools variant — grouped
 * list where tapping a row toggles it for the assistant and long-press edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionInjectionSheet(
    container: AppContainerImpl,
    assistantId: String?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val repo = remember(container) {
        InstructionInjectionRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var reload by remember { mutableIntStateOf(0) }
    val items = remember(reload) { repo.items() }
    var editing by remember { mutableStateOf<InstructionInjection?>(null) }
    val activeIds = remember(reload, assistantId) { repo.activeIds(assistantId).toSet() }
    val grouped = remember(items) {
        items.groupBy { it.group.trim() }.toList().sortedBy { it.first }
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.instruction_injection_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            ) {
                if (items.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.instruction_injection_empty_message),
                                style = TextStyle(color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                    }
                }
                for ((group, groupItems) in grouped) {
                    item(key = "sheet-header-$group") {
                        val collapsed = remember(reload, group) { repo.isCollapsed(group) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repo.toggleCollapsed(group)
                                    reload++
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Lucide.ChevronRight,
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp).rotate(if (collapsed) 0f else 90f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = group.ifEmpty { stringResource(R.string.instruction_injection_ungrouped_group) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                            )
                        }
                    }
                    if (!repo.isCollapsed(group)) {
                        items(groupItems.size, key = { "sheet-item-${groupItems[it].id}" }) { index ->
                            val item = groupItems[index]
                            val selected = item.id in activeIds
                            Spacer(Modifier.height(if (index == groupItems.lastIndex) 12.dp else 8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                    .combinedClickable(
                                        onClick = {
                                            repo.toggleActive(item.id, assistantId)
                                            reload++
                                        },
                                        onLongClick = { editing = item },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.title.trim()
                                        .ifEmpty { stringResource(R.string.instruction_injection_default_title) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (selected) cs.primary else cs.onSurface,
                                    ),
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                                } else {
                                    Spacer(Modifier.width(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { item ->
        InstructionEditSheet(
            item = item,
            onDismiss = { editing = null },
            onSave = { title, prompt, group ->
                repo.update(item.copy(title = title, prompt = prompt, group = group))
                editing = null
                reload++
            },
        )
    }
}
