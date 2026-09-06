package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.key
import com.psyche.memo.AppContainerImpl

/**
 * memory_entries_page.dart 1:1 (mobile branches) — global memory list with
 * search, filters, batch delete, orphan cleanup (§14.4).
 */

private enum class ScopeFilter { ALL, GLOBAL, ASSISTANT }
private enum class StatusFilter { ALL, ACTIVE, ARCHIVED }

@Composable
fun MemoryEntriesScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val provider = remember { MemoryProviderV2(container.preferenceRepository) }
    val assistants = remember { loadAssistantsSync(container) }

    var rev by remember { mutableStateOf(0) }
    fun bump() { rev++ }
    androidx.compose.runtime.LaunchedEffect(Unit) { provider.initialize(loadAll = true) }

    var search by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(ScopeFilter.ALL) }
    var type by remember { mutableStateOf<MemoryType?>(null) }
    var status by remember { mutableStateOf(StatusFilter.ALL) }
    var assistantFilterId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(HashSet<String>()) }
    var selecting by remember { mutableStateOf(false) }

    // _runSearch (L79-98): token search through the provider.
    val searchResults = remember(search, type, rev) {
        val q = search.trim()
        if (q.isEmpty()) null
        else {
            val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) null else provider.search(tokens = tokens, includeArchived = true, type = type)
        }
    }

    // _filtered (L100-131).
    val source = searchResults ?: provider.entries
    val filtered = remember(source, scope, type, status, assistantFilterId, rev) {
        source.filter { e ->
            if (type != null && e.type != type) return@filter false
            when (scope) {
                ScopeFilter.ALL -> {}
                ScopeFilter.GLOBAL -> if (e.scope != MemoryScope.global) return@filter false
                ScopeFilter.ASSISTANT -> {
                    if (e.scope != MemoryScope.assistant) return@filter false
                    if (assistantFilterId != null && e.assistantId != assistantFilterId) return@filter false
                }
            }
            when (status) {
                StatusFilter.ALL -> {}
                StatusFilter.ACTIVE -> if (e.status != MemoryStatus.active) return@filter false
                StatusFilter.ARCHIVED -> if (e.status != MemoryStatus.archived) return@filter false
            }
            true
        }
    }
    val active = filtered.filter { it.status == MemoryStatus.active }
    val archived = filtered.filter { it.status == MemoryStatus.archived }

    var editor by remember { mutableStateOf<MemoryEntry?>(null) }
    var confirm by remember { mutableStateOf<MemoryConfirmRequest?>(null) }
    var scopeSheet by remember { mutableStateOf(false) }
    var typeSheet by remember { mutableStateOf(false) }
    var statusSheet by remember { mutableStateOf(false) }
    var assistantSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(UiR.string.settings_page_back_button), tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(UiR.string.memory_entries_page_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
        }

        // Search field (L410-421).
        Box(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)) {
            MemorySearchField(
                value = search,
                onValueChange = { search = it },
                hintText = stringResource(UiR.string.memory_search_hint),
            )
        }

        // _mobileToolbar (L257-389) — fading horizontal chip row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChipRow(
                label = stringResource(
                    when (scope) {
                        ScopeFilter.ALL -> UiR.string.memory_filter_scope_all
                        ScopeFilter.GLOBAL -> UiR.string.memory_filter_scope_global
                        ScopeFilter.ASSISTANT -> UiR.string.memory_filter_scope_assistant
                    },
                ),
                onTap = { scopeSheet = true },
            )
            if (scope == ScopeFilter.ASSISTANT) {
                Spacer(Modifier.width(8.dp))
                FilterChipRow(
                    label = assistantFilterId?.let { id -> assistants.firstOrNull { it.id == id }?.name }
                        ?: stringResource(UiR.string.memory_ui_assistant_all),
                    onTap = { assistantSheet = true },
                )
            }
            Spacer(Modifier.width(8.dp))
            FilterChipRow(
                label = type?.let { memoryTypeLabel(it) } ?: stringResource(UiR.string.memory_filter_type_all),
                onTap = { typeSheet = true },
            )
            Spacer(Modifier.width(8.dp))
            FilterChipRow(
                label = stringResource(
                    when (status) {
                        StatusFilter.ALL -> UiR.string.memory_filter_status_all
                        StatusFilter.ACTIVE -> UiR.string.memory_filter_status_active
                        StatusFilter.ARCHIVED -> UiR.string.memory_filter_status_archived
                    },
                ),
                onTap = { statusSheet = true },
            )
            Spacer(Modifier.width(8.dp))
            FilterChipRow(
                label = stringResource(
                    if (selecting) UiR.string.memory_entry_action_batch_delete else UiR.string.providers_page_multi_select_tooltip,
                ),
                emphasized = selecting,
                onTap = {
                    // _toggleBatchDelete (L141-164).
                    if (!selecting) {
                        selecting = true
                    } else if (selected.isEmpty()) {
                        selecting = false
                        selected = HashSet()
                    } else {
                        confirm = MemoryConfirmRequest.BatchHardDelete(selected.size)
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            FilterChipRow(
                label = stringResource(UiR.string.memory_entry_action_add),
                emphasized = true,
                icon = Lucide.Plus,
                onTap = { editor = null },
            )
        }

        key(rev) {
            MemoryOrphanBanner(
                count = provider.orphanCount(assistants.map { it.id }.toSet()),
                onCleanup = {
                    val count = provider.orphanCount(assistants.map { it.id }.toSet())
                    if (count > 0) confirm = MemoryConfirmRequest.OrphanCleanup(count)
                },
            )
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchResults != null) stringResource(UiR.string.memory_search_empty) else stringResource(UiR.string.memory_entry_empty),
                    style = TextStyle(color = withAlpha(cs.onSurface, 0.55)),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(active, key = { it.id }) { e ->
                    key(rev) {
                        MemoryEntryCard(
                            entry = e,
                            assistantName = e.assistantId?.let { id -> assistants.firstOrNull { it.id == id }?.name },
                            selectable = selecting,
                            selected = selected.contains(e.id),
                            onSelectedChanged = { v ->
                                val next = HashSet(selected)
                                if (v) next.add(e.id) else next.remove(e.id)
                                selected = next
                            },
                            onEdit = { editor = e },
                            onArchive = { provider.archive(e.id); bump() },
                            onRestore = { provider.restore(e.id); bump() },
                            onHardDelete = { confirm = MemoryConfirmRequest.HardDelete(e.id) },
                            scopeToggleAssistantId = currentAssistantId(container),
                            onToggleScope = {
                                val toGlobal = e.scope == MemoryScope.assistant
                                confirm = MemoryConfirmRequest.ScopeSwitch(toGlobal)
                            },
                        )
                    }
                }
                if (archived.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(UiR.string.memory_entry_archived_section),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                    items(archived, key = { "arch_" + it.id }) { e ->
                        key(rev) {
                            MemoryEntryCard(
                                entry = e,
                                assistantName = e.assistantId?.let { id -> assistants.firstOrNull { it.id == id }?.name },
                                selectable = selecting,
                                selected = selected.contains(e.id),
                                onSelectedChanged = { v ->
                                    val next = HashSet(selected)
                                    if (v) next.add(e.id) else next.remove(e.id)
                                    selected = next
                                },
                                onEdit = { editor = e },
                                onArchive = { provider.archive(e.id); bump() },
                                onRestore = { provider.restore(e.id); bump() },
                                onHardDelete = { confirm = MemoryConfirmRequest.HardDelete(e.id) },
                                scopeToggleAssistantId = currentAssistantId(container),
                                onToggleScope = {
                                    pendingScopeEntry = e
                                    confirm = MemoryConfirmRequest.ScopeSwitch(e.scope == MemoryScope.assistant)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // _FilterChip (L516-536) = MemorySelectChip + ChevronDown.
    // Sheets (scope / type / status / assistant).
    if (scopeSheet) {
        MemoryOptionPickerSheet(
            title = stringResource(UiR.string.memory_entry_scope_label),
            selected = scope,
            options = ScopeFilter.values().map {
                MemoryPickerOption(it, stringResource(when (it) {
                    ScopeFilter.ALL -> UiR.string.memory_filter_scope_all
                    ScopeFilter.GLOBAL -> UiR.string.memory_filter_scope_global
                    ScopeFilter.ASSISTANT -> UiR.string.memory_filter_scope_assistant
                }))
            },
            onDismiss = { scopeSheet = false },
            onSelected = { scope = it },
        )
    }
    if (typeSheet) {
        MemoryOptionPickerSheet(
            title = stringResource(UiR.string.memory_entry_type_label),
            selected = type,
            options = listOf<MemoryPickerOption<MemoryType?>>(
                MemoryPickerOption(null, stringResource(UiR.string.memory_filter_type_all)),
            ) + MemoryType.values().map { MemoryPickerOption<MemoryType?>(it, memoryTypeLabel(it)) },
            onDismiss = { typeSheet = false },
            onSelected = { type = it },
        )
    }
    if (statusSheet) {
        MemoryOptionPickerSheet(
            title = stringResource(UiR.string.memory_ui_status_label),
            selected = status,
            options = StatusFilter.values().map {
                MemoryPickerOption(it, stringResource(when (it) {
                    StatusFilter.ALL -> UiR.string.memory_filter_status_all
                    StatusFilter.ACTIVE -> UiR.string.memory_filter_status_active
                    StatusFilter.ARCHIVED -> UiR.string.memory_filter_status_archived
                }))
            },
            onDismiss = { statusSheet = false },
            onSelected = { status = it },
        )
    }
    if (assistantSheet) {
        MemoryOptionPickerSheet(
            title = stringResource(UiR.string.memory_ui_assistant_label),
            selected = assistantFilterId,
            options = listOf<MemoryPickerOption<String?>>(
                MemoryPickerOption(null, stringResource(UiR.string.memory_ui_assistant_all)),
            ) + assistants.map { MemoryPickerOption<String?>(it.id, it.name) },
            onDismiss = { assistantSheet = false },
            onSelected = { assistantFilterId = it },
        )
    }

    // Entry editor sheet (showMemoryEntryEditor L1059-1113).
    editor.let { existing ->
        MemoryEntryEditSheet(
            container = container,
            provider = provider,
            assistants = assistants,
            existing = existing,
            defaultAssistantId = currentAssistantId(container),
            onDismiss = { editor = null },
            onSaved = { bump(); editor = null },
        )
    }

    // Confirm host mapping back to actions.
    MemoryConfirmHost(confirm) { request, ok ->
        when (request) {
            is MemoryConfirmRequest.HardDelete -> if (ok) { provider.hardDelete(request.entryId); bump() }
            is MemoryConfirmRequest.BatchHardDelete -> if (ok) { provider.hardDeleteMany(selected.toList()); selected = HashSet(); selecting = false; bump() }
            is MemoryConfirmRequest.OrphanCleanup -> if (ok) { provider.deleteOrphanAssistantMemories(assistants.map { it.id }.toSet()); bump() }
            is MemoryConfirmRequest.ScopeSwitch -> {
                val target = pendingScopeEntry
                if (ok && target != null) {
                    if (request.toGlobal) {
                        provider.updateScope(target.id, scope = MemoryScope.global)
                    } else {
                        provider.updateScope(target.id, scope = MemoryScope.assistant, assistantId = currentAssistantId(container))
                    }
                    bump()
                }
                pendingScopeEntry = null
            }
        }
        confirm = null
    }
}

// The entry awaiting a scope-switch confirmation (_toggleScope L1515-1531).
private var pendingScopeEntry: MemoryEntry? = null

@Composable
private fun FilterChipRow(label: String, onTap: () -> Unit, emphasized: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    MemorySelectChip(
        label = label,
        emphasized = emphasized,
        icon = icon,
        trailingIcon = Lucide.ChevronDown,
        onTap = onTap,
    )
}

/**
 * memory_ui.dart L1115-1433 — MemoryEntryEditForm as a modal bottom sheet
 * (mobile branch L1379-1432).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryEntryEditSheet(
    container: AppContainerImpl,
    provider: MemoryProviderV2,
    assistants: List<com.psyche.memo.data.model.Assistant>,
    existing: MemoryEntry?,
    defaultAssistantId: String?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val title = stringResource(if (existing == null) UiR.string.memory_entry_create_title else UiR.string.memory_entry_edit_title)

    var content by remember { mutableStateOf(existing?.content ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: MemoryType.identity) }
    var scope by remember { mutableStateOf(existing?.scope ?: MemoryScope.global) }
    var assistantId by remember { mutableStateOf(existing?.assistantId ?: defaultAssistantId) }
    var saving by remember { mutableStateOf(false) }
    var scopeConfirm by remember { mutableStateOf<Boolean?>(null) }

    fun resolvedAssistantId(): String? {
        if (scope != MemoryScope.assistant) return null
        if (!assistantId.isNullOrEmpty()) return assistantId
        return defaultAssistantId ?: assistants.firstOrNull()?.id
    }

    fun save() {
        if (saving) return
        val text = content.trim()
        if (text.isEmpty()) return
        val assistant = resolvedAssistantId()
        if (scope == MemoryScope.assistant && assistant.isNullOrEmpty()) return
        saving = true
        val existingEntry = existing
        if (existingEntry == null) {
            provider.create(scope = scope, assistantId = assistant, type = type, content = text, source = MemorySource.manual)
            onSaved()
        } else {
            val scopeKindChanged = scope != existingEntry.scope
            val assistantRetargeted = scope == MemoryScope.assistant && existingEntry.assistantId != assistant
            if (scopeKindChanged) {
                // Defer the actual write until confirmScopeSwitch resolves.
                pendingScopeEntry = existingEntry
                scopeConfirm = scope == MemoryScope.global
                saving = false
                return
            }
            if (text != existingEntry.content) provider.updateContent(existingEntry.id, text)
            if (type != existingEntry.type) provider.updateType(existingEntry.id, type)
            if (assistantRetargeted) provider.updateScope(existingEntry.id, scope = scope, assistantId = assistant)
            onSaved()
        }
        saving = false
    }

    ModalBottomSheet(
        dragHandle = null, // 原版自绘 40x4 拖柄，禁用 Material 默认 handle
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            // Drag handle + title (L1391-1415).
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(withAlpha(cs.onSurface, 0.2), RoundedCornerShape(999.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Content field (_formFields L1230-1248, IosFormTextField).
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(app.surfaceCard, RoundedCornerShape(14.dp))
                        .padding(1.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(app.surfaceCard, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                        )
                        if (content.isEmpty()) {
                            Text(
                                stringResource(UiR.string.memory_entry_content_hint),
                                style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.4)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                MemorySectionLabel(stringResource(UiR.string.memory_entry_type_label))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MemoryType.values().forEach { t ->
                        MemorySelectChip(
                            label = memoryTypeLabel(t),
                            selected = type == t,
                            onTap = { type = t },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                MemorySectionLabel(stringResource(UiR.string.memory_entry_scope_label))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MemorySelectChip(
                        label = stringResource(UiR.string.memory_entry_scope_global),
                        selected = scope == MemoryScope.global,
                        onTap = { scope = MemoryScope.global },
                    )
                    Spacer(Modifier.width(8.dp))
                    MemorySelectChip(
                        label = stringResource(UiR.string.memory_entry_scope_assistant),
                        selected = scope == MemoryScope.assistant,
                        onTap = { scope = MemoryScope.assistant },
                    )
                }
                Spacer(Modifier.height(16.dp))
                MemorySectionLabel(stringResource(UiR.string.memory_ui_assistant_label))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    assistants.forEach { a ->
                        MemorySelectChip(
                            label = a.name,
                            selected = resolvedAssistantId() == a.id,
                            onTap = { assistantId = a.id },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MemorySheetActions(
                onCancel = onDismiss,
                onConfirm = { save() },
                confirmLabel = stringResource(UiR.string.user_profile_save),
                confirmEnabled = content.trim().isNotEmpty() && !saving,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // Scope-switch confirmation (L1201-1208).
    scopeConfirm?.let { toGlobal ->
        ConfirmScopeSwitchDialog(toGlobal = toGlobal) { ok ->
            val target = pendingScopeEntry
            if (ok && target != null) {
                if (toGlobal) {
                    provider.updateScope(target.id, scope = MemoryScope.global)
                } else {
                    provider.updateScope(target.id, scope = MemoryScope.assistant, assistantId = resolvedAssistantId())
                }
                scopeConfirm = null
                pendingScopeEntry = null
                onSaved()
            } else {
                scopeConfirm = null
                pendingScopeEntry = null
            }
        }
    }
}
