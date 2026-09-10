package com.psyche.memo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleDot
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.repo.ProviderRepository
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.SnackbarManager
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** One row in the providers list (providers_page._Provider). */
internal data class ProviderItem(
    val key: String,
    val name: String,
    val enabled: Boolean,
    val modelCount: Int,
)

/**
 * Applies one reorder step to [keys] — the Kotlin twin of the
 * `add(to, removeAt(from))` move that `providers_page.onReorder` performs
 * (`sh.calvin.reorderable`'s `onMove` fires repeatedly during a drag, so this
 * is called once per crossing, not once per drop).
 *
 * Returns `null` when the move must be ignored: out-of-range indices (the
 * library can emit stale layout indices when the list recomposes mid-drag) or
 * a no-op move. Returning null rather than clamping is deliberate — clamping
 * would silently reorder against the wrong indices.
 */
internal fun applyProviderMove(keys: List<String>, from: Int, to: Int): List<String>? {
    if (from !in keys.indices || to !in keys.indices || from == to) return null
    return keys.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Merge the fixed built-in keys with the stored provider rows, apply the saved
 * display order and filter by [searchQuery] — the Kotlin twin of
 * `providers_page.build`'s base + dynamic merge.
 *
 * Stored keys are folded onto their canonical built-in spelling first
 * (`"zhipu ai"` → `"Zhipu AI"`). Without that fold a hand-typed key rendered
 * the same provider twice *and* handed two LazyList items the same render key,
 * which corrupts `ReorderableItem` reuse — the cards collapsed and overlapped.
 * The startup migration drops the duplicate row; this fold keeps the list
 * correct for anything that still slips through.
 *
 * [dragOrder] short-circuits the store lookup while a drag is in flight so the
 * list follows the finger instead of a stale persisted order.
 */
internal fun buildProviderItems(
    providers: List<Pair<String, ProviderConfig>>,
    searchQuery: String,
    dragOrder: List<String>? = null,
    applyOrder: (List<String>) -> List<String>,
): List<ProviderItem> {
    val cfgMap = providers.toMap()
    val canonicalOf = providers.associate { (storedKey, _) ->
        storedKey to ProviderRepository.canonicalizeKey(storedKey)
    }
    val allKeys = LinkedHashSet<String>().apply {
        addAll(ProviderRepository.BUILTIN_KEYS)
        addAll(canonicalOf.values)
    }.toList()
    val orderedKeys = dragOrder ?: applyOrder(allKeys)

    return orderedKeys.map { key ->
        // Prefer the canonical row's config; fall back to whichever stored row
        // carries the non-canonical spelling of this key.
        val cfg = cfgMap[key]
            ?: canonicalOf.entries.firstOrNull { it.value == key }?.let { cfgMap[it.key] }
        ProviderItem(
            key = key,
            name = cfg?.name?.takeIf { it.isNotEmpty() } ?: key,
            enabled = cfg?.enabled ?: false,
            modelCount = cfg?.models?.size ?: 0,
        )
    }.filter { item ->
        searchQuery.isBlank() ||
            item.name.contains(searchQuery, ignoreCase = true) ||
            item.key.contains(searchQuery, ignoreCase = true)
    }
}

/**
 * Providers page — 1:1 port of kelivo providers_page.dart:
 * AppBar (back / multi-select / import / add), filled search field, the
 * surface-card list (long-press reorder, per-row status pill, iOS checkbox
 * multi-select) and the floating glass selection bar (delete / select-all /
 * move-to-group / export).
 */
@Composable
fun ProvidersScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenProvider: (String?) -> Unit,
    onOpenGroups: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current

    val repo = remember(container) {
        ProviderRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    val pDao = remember(container) {
        com.psyche.memo.data.db.PayloadEntityDao(
            container.database.writableDatabase,
            "provider_rows",
            primaryKey = "provider_key",
        )
    }
    var providers by remember { mutableStateOf(loadProviders(container)) }
    var searchQuery by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showGroupPickerFor by remember { mutableStateOf<String?>(null) }
    var showExportFor by remember { mutableStateOf<String?>(null) }
    // Live display order during a drag. `null` means "derive from the store".
    // While a drag is in flight `onMove` fires repeatedly (the library expects
    // the caller to apply every move immediately), so we keep the in-progress
    // order here and only persist on settle — otherwise every crossing frame
    // would hit the preference store and re-derive the whole list.
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }

    // Reload on entry (add/import sheets mutate the store); first sweep drops
    // empty builtin rows left by earlier test builds (KelivoIN / dup Tensdaq).
    LaunchedEffect(Unit) {
        repo.cleanupEmptyBuiltinRows()
        providers = loadProviders(container)
    }

    // Merge builtin + dynamic keys, then apply saved order (providers_page.build).
    val items: List<ProviderItem> = remember(providers, searchQuery, dragOrder) {
        buildProviderItems(
            providers = providers,
            searchQuery = searchQuery,
            dragOrder = dragOrder,
            applyOrder = { repo.applyOrder(it) },
        )
    }

    fun toggleSelect(key: String) {
        Haptics.light(view)
        if (selected.contains(key)) selected.remove(key) else selected.add(key)
    }

    fun exitSelectMode() {
        selected.clear()
        selectMode = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- AppBar ----
        MemoTopBar(
            title = stringResource(com.psyche.memo.ui.R.string.providers_page_title),
            onBack = onBack,
        ) {
            // Multi-select toggle: circleDot <-> Check.
            IconActionButton(
                if (selectMode) Lucide.Check else Lucide.CircleDot,
                cs.onSurface,
                stringResource(com.psyche.memo.ui.R.string.providers_page_multi_select_tooltip),
            ) {
                if (selectMode) selected.clear()
                selectMode = !selectMode
            }
            // Import.
            IconActionButton(
                Lucide.CloudDownload,
                cs.onSurface,
                stringResource(com.psyche.memo.ui.R.string.providers_page_import_tooltip),
            ) {
                showImportSheet = true
            }
            // Add.
            IconActionButton(
                Lucide.Plus,
                cs.onSurface,
                stringResource(com.psyche.memo.ui.R.string.providers_page_add_tooltip),
            ) {
                showAddSheet = true
            }
        }

        // ---- Search field (filled, r12, Search 16 prefix / X 14 suffix) ----
        ProvidersSearchField(
            query = searchQuery,
            hint = stringResource(com.psyche.memo.ui.R.string.providers_page_search_hint),
            onChanged = { searchQuery = it },
        )

        // ---- List (RikkaHub SettingProviderPage pattern: standalone cards,
        // spacedBy gaps, drag via the trailing handle) ----
        Box(modifier = Modifier.weight(1f)) {
            val reorderEnabled = !selectMode && searchQuery.isBlank()
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                // from.index/to.index are LazyColumn indices. The list has no
                // header/footer items (RikkaHub's layout), so they equal the
                // data indices — a header item would shift them by 1 and make
                // every crossing reorder the wrong pair, which is what piled
                // sibling cards on top of the dragged one.
                val guarded = !(searchQuery.isNotEmpty() || selectMode)
                val moved = if (guarded) applyProviderMove(items.map { it.key }, from.index, to.index) else null
                // Hold the live order locally; the LaunchedEffect below
                // persists it once on release. Writing the store on every
                // crossing frame used to re-derive the whole list mid-drag,
                // which is what made the list jump back to its old order.
                if (moved != null) dragOrder = moved
            }
            // Settled: persist the order the user actually dropped.
            LaunchedEffect(reorderableState.isAnyItemDragging) {
                if (!reorderableState.isAnyItemDragging) {
                    val settled = dragOrder
                    if (settled != null) {
                        repo.setOrder(settled)
                        dragOrder = null
                    }
                }
            }
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    ReorderableItem(state = reorderableState, key = item.key) { isDragging ->
                        ProviderCard(
                            item = item,
                            config = providers.toMap()[item.key],
                            selectMode = selectMode,
                            selected = selected.contains(item.key),
                            isDragging = isDragging,
                            onToggleSelect = { toggleSelect(item.key) },
                            onOpen = {
                                if (selectMode) toggleSelect(item.key)
                                else onOpenProvider(item.key)
                            },
                            dragHandle = {
                                if (!selectMode) {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier
                                            .size(36.dp)
                                            .longPressDraggableHandle(
                                                enabled = reorderEnabled,
                                                onDragStarted = { Haptics.medium(view) },
                                                onDragStopped = { Haptics.light(view) },
                                            ),
                                    ) {
                                        Icon(
                                            Lucide.GripVertical,
                                            contentDescription = null,
                                            tint = cs.onSurface.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ---- Floating selection bar ----
            androidx.compose.animation.AnimatedVisibility(
                visible = selectMode,
                enter = slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(220)) + fadeIn(tween(200)),
                exit = slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(220)) + fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                SelectionBar(
                    visibleCount = selected.size,
                    onDelete = { if (selected.isNotEmpty()) showDeleteConfirm = true },
                    onSelectAll = {
                        Haptics.light(view)
                        selected.clear()
                        items.forEach { selected.add(it.key) }
                    },
                    onMoveToGroup = {
                        if (selected.size == 1) showGroupPickerFor = selected.first()
                    },
                    onExport = {
                        if (selected.size == 1) showExportFor = selected.first()
                    },
                )
            }
        }
    }

    // ---- Import provider sheet (#8) ----
    if (showImportSheet) {
        ImportProviderSheet(
            container = container,
            onImported = { providers = loadProviders(container) },
            onDismiss = { showImportSheet = false },
        )
    }

    // ---- Add provider sheet (#7) ----
    if (showAddSheet) {
        AddProviderSheet(
            container = container,
            onAdded = { providers = loadProviders(container) },
            onDismiss = { showAddSheet = false },
        )
    }

    // ---- Group picker for the selected provider ----
    showGroupPickerFor?.let { key ->
        val cfg = providers.toMap()[key] ?: ProviderConfig(id = key, name = key)
        ProviderGroupPickerSheet(
            container = container,
            providerKey = key,
            onDismiss = { showGroupPickerFor = null },
            onOpenManager = {
                showGroupPickerFor = null
                onOpenGroups()
            },
        )
    }

    // ---- Share/export sheet for the selected provider ----
    showExportFor?.let { key ->
        val cfg = providers.toMap()[key] ?: ProviderConfig(id = key, name = key)
        ShareProviderSheet(
            providerKey = key,
            config = cfg,
            onDismiss = { showExportFor = null },
        )
    }

    // ---- Delete-selected confirmation ----
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_provider_title) +
                        " (${selected.size})",
                )
            },
            text = { Text(stringResource(com.psyche.memo.ui.R.string.providers_page_delete_selected_confirm_content)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected.forEach { key ->
                            pDao.delete(key)
                        }
                        providers = loadProviders(container)
                        showDeleteConfirm = false
                        exitSelectMode()
                        SnackbarManager.show(
                            AppNotification(
                                message = "Deleted",
                                type = NotificationType.SUCCESS,
                            ),
                        )
                    },
                ) { Text(stringResource(com.psyche.memo.ui.R.string.providers_page_delete_action), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_cancel_button)) }
            },
        )
    }
}

/** AppBar icon button: 22dp icon, no ripple, alpha press (kelivo _TactileIconButton). */
@Composable
internal fun IconActionButton(
    icon: ImageVector,
    color: Color,
    label: String,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .scale(if (pressed) 0.9f else 1f)
            .clickable {
                pressed = false
                onClick()
            }
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
    }
}

/** Filled search field, r12, no border (providers_page._ProvidersSearchField). */
@Composable
private fun ProvidersSearchField(
    query: String,
    hint: String,
    onChanged: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .background(semantic.surfaceFill, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Search, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    style = TextStyle(fontSize = 13.5.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChanged,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Lucide.X,
                contentDescription = "Clear",
                tint = cs.onSurface.copy(alpha = 0.48f),
                modifier = Modifier
                    .size(34.dp)
                    .padding(8.dp)
                    .clickable { onChanged("") },
            )
        }
    }
}

/** One provider card (RikkaHub SettingProviderPage ProviderItem style:
 * standalone card, 40dp avatar, name + status/model-count pills, trailing
 * drag handle. Disabled providers use errorContainer like RikkaHub. */
@Composable
private fun ProviderCard(
    item: ProviderItem,
    config: ProviderConfig?,
    selectMode: Boolean,
    selected: Boolean,
    isDragging: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val enabled = config?.enabled ?: item.enabled

    val statusBg = if (enabled) semantic.success.copy(alpha = 0.12f) else semantic.warning.copy(alpha = 0.15f)
    val statusFg = if (enabled) semantic.success else semantic.warning

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isDragging) 0.95f else 1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) semantic.surfaceCard else cs.errorContainer,
        ),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Select-mode checkbox area (width animates 0 <-> 28).
            if (selectMode) {
                Box(modifier = Modifier.width(28.dp)) {
                    IosCheckbox(
                        value = selected,
                        onValueChanged = { onToggleSelect() },
                        size = 20.dp,
                        hitTestSize = 22.dp,
                        borderWidth = 1.6.dp,
                        activeColor = cs.primary,
                        borderColor = cs.onSurface.copy(alpha = 0.35f),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            // Avatar 40dp with the 22dp brand/initial avatar inside (RikkaHub 40dp).
            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                ProviderAvatarSmall(
                    providerKey = item.key,
                    displayName = config?.name?.takeIf { it.isNotEmpty() } ?: item.name,
                    size = 22.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config?.name?.takeIf { it.isNotEmpty() } ?: item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Status pill: success ON / warning OFF.
                    Surface(color = statusBg, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            text = stringResource(
                                if (enabled) com.psyche.memo.ui.R.string.providers_page_enabled_status
                                else com.psyche.memo.ui.R.string.providers_page_disabled_status,
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = statusFg),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    // Model-count pill.
                    if (item.modelCount > 0) {
                        Surface(color = cs.primary.copy(alpha = 0.08f), shape = RoundedCornerShape(999.dp)) {
                            Text(
                                text = item.modelCount.toString() + stringResource(
                                    if (item.modelCount == 1) com.psyche.memo.ui.R.string.providers_page_models_count_single_suffix
                                    else com.psyche.memo.ui.R.string.providers_page_models_count_suffix,
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = cs.primary),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            dragHandle()
        }
    }
}

/** Small provider avatar — brandOrInitial path of provider_avatar.dart:
 * known brand -> its SVG at 0.7x on a primary-a circle (dark mono logos tinted
 * onSurface); unknown -> initial letter on primary a0.1 circle. */
@Composable
internal fun ProviderAvatarSmall(providerKey: String, displayName: String, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val asset = remember(displayName) { BrandAssets.assetForName(displayName) }
    if (asset == null) {
        Box(
            modifier = Modifier
                .size(size)
                .background(cs.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayName.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                ),
                maxLines = 1,
            )
        }
    } else {
        val mono = semantic.isDark && BrandAssets.assetNeedsDarkInvert(asset)
        Box(
            modifier = Modifier
                .size(size)
                .background(cs.primary.copy(alpha = if (semantic.isDark) 0.18f else 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = asset,
                contentDescription = null,
                colorFilter = if (mono) {
                    androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(size * 0.7f),
            )
        }
    }
}

/** Floating glass selection bar (providers_page._SelectionBar). */
@Composable
private fun SelectionBar(
    visibleCount: Int,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onMoveToGroup: () -> Unit,
    onExport: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current

    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 46.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCircleButton(Lucide.Trash2, cs.error, "Delete") { Haptics.light(view); onDelete() }
        GlassCircleButton(Lucide.Check, cs.primary, null) { onSelectAll() }
        GlassCircleButton(Lucide.Folder, cs.primary, "Move to group") { onMoveToGroup() }
        GlassCircleButton(Lucide.Share, cs.primary, "Export") { onExport() }
    }
}

/** 46dp frosted circle button (providers_page._GlassCircleButton). */
@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    color: Color,
    label: String?,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(46.dp)
            .scale(if (pressed) 0.95f else 1f)
            .background(cs.surface.copy(alpha = 0.06f), CircleShape)
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.10f), CircleShape)
            .clickable {
                pressed = false
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
        // semantic referenced to keep import aligned with dark-variant styling.
        @Suppress("UNUSED_EXPRESSION")
        semantic.isDark
    }
}
