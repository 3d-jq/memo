package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import com.psyche.memo.ui.theme.AppFontWeights

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
    // The container-scoped provider is the instance the chat's memory tool
    // writes through. Reading it (and its version) keeps this list from going
    // stale behind a memory the assistant saved while the screen was open —
    // every mutation refreshes the cache and bumps the version.
    val provider = container.memoryProviderV2
    val assistants = remember { loadAssistantsSync(container) }

    val rev = provider.version
    androidx.compose.runtime.LaunchedEffect(Unit) { provider.loadAll() }

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

    // The editor sheet distinguishes "closed" from "new entry" (existing == null),
    // so the open flag is separate from the entry being edited — `editor == null`
    // alone would keep the sheet composed and visible at all times.
    var editorOpen by remember { mutableStateOf(false) }
    var editorEntry by remember { mutableStateOf<MemoryEntry?>(null) }
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
        MemoTopBar(
            title = stringResource(UiR.string.memory_entries_page_title),
            onBack = onBack,
        )

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
                onTap = {
                    editorEntry = null
                    editorOpen = true
                },
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
                            onEdit = {
                                    editorEntry = e
                                    editorOpen = true
                                },
                            onArchive = { provider.archive(e.id) },
                            onRestore = { provider.restore(e.id) },
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
                                onEdit = {
                                    editorEntry = e
                                    editorOpen = true
                                },
                                onArchive = { provider.archive(e.id) },
                                onRestore = { provider.restore(e.id) },
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
            selected = assistantFilterId,
            options = listOf<MemoryPickerOption<String?>>(
                MemoryPickerOption(null, stringResource(UiR.string.memory_ui_assistant_all)),
            ) + assistants.map { MemoryPickerOption<String?>(it.id, it.name) },
            onDismiss = { assistantSheet = false },
            onSelected = { assistantFilterId = it },
        )
    }

    // Entry editor sheet (showMemoryEntryEditor L1059-1113).
    if (editorOpen) {
        val closeEditor = {
            editorOpen = false
            editorEntry = null
        }
        MemoryEntryEditSheet(
            container = container,
            provider = provider,
            assistants = assistants,
            existing = editorEntry,
            defaultAssistantId = currentAssistantId(container),
            onDismiss = closeEditor,
            onSaved = {
                closeEditor()
            },
        )
    }

    // Confirm host mapping back to actions.
    MemoryConfirmHost(confirm) { request, ok ->
        when (request) {
            is MemoryConfirmRequest.HardDelete -> if (ok) { provider.hardDelete(request.entryId) }
            is MemoryConfirmRequest.BatchHardDelete -> if (ok) { provider.hardDeleteMany(selected.toList()); selected = HashSet(); selecting = false }
            is MemoryConfirmRequest.OrphanCleanup -> if (ok) { provider.deleteOrphanAssistantMemories(assistants.map { it.id }.toSet()) }
            is MemoryConfirmRequest.ScopeSwitch -> {
                val target = pendingScopeEntry
                if (ok && target != null) {
                    if (request.toGlobal) {
                        provider.updateScope(target.id, scope = MemoryScope.global)
                    } else {
                        provider.updateScope(target.id, scope = MemoryScope.assistant, assistantId = currentAssistantId(container))
                    }
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
 * memory_ui.dart L1115-1433 — the add/edit memory editor as a modal bottom
 * sheet (mobile branch L1379-1432): overlaySurface with a 16dp top radius,
 * 10dp above the 40x4 handle, a centered 16sp title, the form list padded
 * 16/12, and the action row padded 16/0/16/12. The sheet hugs its content up
 * to 90% of the screen, scrolling inside beyond that.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MemoryEntryEditSheet(
    container: AppContainerImpl,
    provider: MemoryProviderV2,
    assistants: List<com.psyche.memo.data.model.Assistant>,
    existing: MemoryEntry?,
    defaultAssistantId: String?,
    defaultScope: MemoryScope = MemoryScope.global,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val title = stringResource(if (existing == null) UiR.string.memory_entry_create_title else UiR.string.memory_entry_edit_title)

    var content by remember { mutableStateOf(existing?.content ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: MemoryType.identity) }
    var scope by remember { mutableStateOf(existing?.scope ?: defaultScope) }
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

    // ConstrainedBox(maxHeight: screen * 0.9) around the sheet body (L1386-1388).
    val maxBodyHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.9f).toDp()
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = app.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null, // 原版自绘 40x4 拖柄，禁用 Material 默认 handle
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .heightIn(max = maxBodyHeight),
        ) {
            Spacer(Modifier.height(10.dp))
            // Drag handle (L1391-1401).
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(withAlpha(cs.onSurface, 0.2), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            // Centered title (L1403-1414).
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 16.sp, fontWeight = AppFontWeights.semibold, color = cs.onSurface),
                )
            }
            Spacer(Modifier.height(8.dp))

            // Form list (L1416-1422): padding 16/12/16/12, hugging the content.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            ) {
                MemorySectionCard {
                    IosFormField(
                        label = "",
                        value = content,
                        onValueChange = { content = it },
                        inline = false,
                        minLines = 4,
                        maxLines = 10,
                        autofocus = true,
                        hint = stringResource(UiR.string.memory_entry_content_hint),
                    )
                }
                Spacer(Modifier.height(16.dp))
                MemorySectionLabel(stringResource(UiR.string.memory_entry_type_label))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MemoryType.values().forEach { t ->
                        MemorySelectChip(
                            label = memoryTypeLabel(t),
                            selected = type == t,
                            onTap = { type = t },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                MemorySectionLabel(stringResource(UiR.string.memory_entry_scope_label))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MemorySelectChip(
                        label = stringResource(UiR.string.memory_entry_scope_global),
                        selected = scope == MemoryScope.global,
                        onTap = { scope = MemoryScope.global },
                    )
                    MemorySelectChip(
                        label = stringResource(UiR.string.memory_entry_scope_assistant),
                        selected = scope == MemoryScope.assistant,
                        onTap = { scope = MemoryScope.assistant },
                    )
                }
                // The assistant picker only exists for assistant-scoped entries
                // (`allowAssistantPicker && _scope == assistant`, L1281).
                if (scope == MemoryScope.assistant) {
                    Spacer(Modifier.height(16.dp))
                    MemorySectionLabel(stringResource(UiR.string.memory_ui_assistant_label))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        assistants.forEach { a ->
                            MemorySelectChip(
                                label = a.name,
                                selected = resolvedAssistantId() == a.id,
                                onTap = { assistantId = a.id },
                            )
                        }
                    }
                }
            }

            // Actions (L1423-1426): padding 16/0/16/12.
            Box(Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 12.dp)) {
                MemorySheetActions(
                    onCancel = onDismiss,
                    onConfirm = { save() },
                    confirmLabel = stringResource(UiR.string.user_profile_save),
                    confirmEnabled = content.trim().isNotEmpty() && !saving,
                )
            }
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
