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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleDot
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.Folder
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
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.SnackbarManager

/** One row in the providers list (providers_page._Provider). */
internal data class ProviderItem(
    val key: String,
    val name: String,
    val enabled: Boolean,
    val modelCount: Int,
)

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
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
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

    // Reload on entry (add/import sheets mutate the store).
    LaunchedEffect(Unit) { providers = loadProviders(container) }

    // Merge builtin + dynamic keys, then apply saved order (providers_page.build).
    val items: List<ProviderItem> = remember(providers, searchQuery) {
        val dbKeys = providers.map { it.first }
        val cfgMap = providers.toMap()
        val allKeys = ProviderRepository.BUILTIN_KEYS + dbKeys.filterNot { it in ProviderRepository.BUILTIN_KEYS }
        val orderedKeys = repo.applyOrder(allKeys.distinct())
        orderedKeys.map { key ->
            val cfg = cfgMap[key]
            val name = cfg?.name?.takeIf { it.isNotEmpty() } ?: key
            ProviderItem(
                key = key,
                name = name,
                enabled = cfg?.enabled ?: false,
                modelCount = cfg?.models?.size ?: 0,
            )
        }.filter { item ->
            searchQuery.isBlank() ||
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.key.contains(searchQuery, ignoreCase = true)
        }
    }

    fun persistOrder() {
        repo.setOrder(items.map { it.key })
    }

    fun toggleSelect(key: String) {
        Haptics.light(view)
        if (selected.contains(key)) selected.remove(key) else selected.add(key)
    }

    fun exitSelectMode() {
        selected.clear()
        selectMode = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- AppBar ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconActionButton(Lucide.ArrowLeft, cs.onSurface, "Back") { onBack() }
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.providers_page_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
            )
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
            ) { /* import sheet lands with #8 */ }
            // Add.
            IconActionButton(
                Lucide.Plus,
                cs.onSurface,
                stringResource(com.psyche.memo.ui.R.string.providers_page_add_tooltip),
            ) {
                onOpenProvider(null)
            }
        }

        // ---- Search field (filled, r12, Search 16 prefix / X 14 suffix) ----
        ProvidersSearchField(
            query = searchQuery,
            hint = stringResource(com.psyche.memo.ui.R.string.providers_page_search_hint),
            onChanged = { searchQuery = it },
        )

        // ---- List card ----
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .background(
                        semantic.surfaceCard,
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        0.6.dp,
                        cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.08f else 0.06f),
                        RoundedCornerShape(12.dp),
                    )
                    .verticalScroll(rememberScrollState()),
            ) {
                val reorderEnabled = !selectMode && searchQuery.isBlank()
                ReorderableColumn(
                    items = items,
                    keyOf = { it.key },
                    onMove = { from, to ->
                        val reorderedItems = items.toMutableList()
                        val movedItem = reorderedItems.removeAt(from)
                        reorderedItems.add(to, movedItem)
                        repo.setOrder(reorderedItems.map { it.key })
                        val cfgMap = providers.toMap()
                        providers = reorderedItems.map { item -> item.key to (cfgMap[item.key] ?: ProviderConfig(id = item.key, name = item.name)) }
                    },
                ) { item, isDragging ->
                    Column {
                        ProviderListRow(
                            item = item,
                            config = providers.toMap()[item.key],
                            selectMode = selectMode,
                            selected = selected.contains(item.key),
                            onToggleSelect = { toggleSelect(item.key) },
                            onOpen = {
                                if (selectMode) toggleSelect(item.key)
                                else onOpenProvider(item.key)
                            },
                        )
                        if (item != items.last()) DividerLine()
                    }
                }
                Spacer(Modifier.height(4.dp))
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
                    onMoveToGroup = { /* group picker lands with #12 */ },
                    onExport = { /* export sheet lands with #7 */ },
                )
            }
        }
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

/** One provider row (providers_page._ProviderRow). */
@Composable
private fun ProviderListRow(
    item: ProviderItem,
    config: ProviderConfig?,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val enabled = config?.enabled ?: item.enabled

    val statusBg = if (enabled) semantic.success.copy(alpha = 0.12f) else semantic.warning.copy(alpha = 0.15f)
    val statusFg = if (enabled) semantic.success else semantic.warning

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 11.dp),
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
        // Avatar slot 36 with 22dp brand/initial avatar.
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            ProviderAvatarSmall(
                providerKey = item.key,
                displayName = config?.name?.takeIf { it.isNotEmpty() } ?: item.name,
                size = 22.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = config?.name?.takeIf { it.isNotEmpty() } ?: item.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
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
        if (!selectMode) {
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
        }
    }
}

/** Small provider avatar: emoji > brand asset > initial (provider_avatar.dart). */
@Composable
internal fun ProviderAvatarSmall(providerKey: String, displayName: String, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val asset = remember(providerKey, displayName) { BrandAssets.assetForName(displayName) }
    val initial = displayName.trim().take(1).uppercase().ifEmpty { "?" }
    Box(
        modifier = Modifier
            .size(size)
            .background(cs.primary.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = asset ?: initial,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (size.value * 0.42f).sp,
                color = cs.primary,
            ),
            maxLines = 1,
        )
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

/** Hairline divider between provider rows (_iosDivider). */
@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp)
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
    )
}
