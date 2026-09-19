package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.WorldBook
import com.psyche.memo.data.model.WorldBookEntry
import com.psyche.memo.data.model.WorldBookInjectionPosition
import com.psyche.memo.data.model.WorldBookInjectionRole
import com.psyche.memo.data.repo.WorldBookRepository
import com.psyche.memo.ui.reorder.ReorderableColumnWithHandle
import com.psyche.memo.ui.reorder.ReorderableInlineColumnWithHandle
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import java.util.UUID

/**
 * Port of world_book_page.dart: collapsible book sections with drag reorder,
 * entry rows that tap to edit / long-press for actions, and the book + entry
 * edit sheets (keywords chips, injection position / role pickers, import and
 * RikkaHub-format export).
 */
@Composable
fun WorldBookScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val view = LocalView.current
    val repo = remember(container) {
        WorldBookRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var reload by remember { mutableIntStateOf(0) }
    val books = remember(reload) {
        repo.books().also { repo.pruneCollapsed(it.map { book -> book.id }.toSet()) }
    }

    var addingBook by remember { mutableStateOf(false) }
    var editingBook by remember { mutableStateOf<WorldBook?>(null) }
    var entryTarget by remember { mutableStateOf<Pair<WorldBook, WorldBookEntry?>?>(null) }
    var deletingBook by remember { mutableStateOf<WorldBook?>(null) }
    var exportBook by remember { mutableStateOf<WorldBook?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()
        when {
            content.isNullOrBlank() -> SnackbarManager.show(
                AppNotification(context.getString(R.string.assistant_edit_system_prompt_import_empty), NotificationType.WARNING),
            )
            else -> {
                val parsed = WorldBookRepository.parseImportedBook(IMPORT_JSON, content)
                if (parsed == null) {
                    SnackbarManager.show(
                        AppNotification(context.getString(R.string.mcp_json_edit_parse_failed), NotificationType.ERROR),
                    )
                } else {
                    repo.add(
                        WorldBookRepository.normalizeImportedBook(parsed, books.map { it.id }.toSet()),
                    )
                    reload++
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val book = exportBook
        exportBook = null
        if (uri == null || book == null) return@rememberLauncherForActivityResult
        val name = WorldBookRepository.safeFileName(book.name.trim().ifEmpty { "lorebook" }) + ".json"
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(WorldBookRepository.toExportJson(book).toByteArray(Charsets.UTF_8))
            }
        }
        if (written.isSuccess) {
            SnackbarManager.show(
                AppNotification(context.getString(R.string.message_export_sheet_exported_as, name), NotificationType.SUCCESS),
            )
        } else {
            SnackbarManager.show(
                AppNotification(
                    context.getString(R.string.world_book_export_failed, written.exceptionOrNull()?.toString() ?: name),
                    NotificationType.ERROR,
                ),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
                MemoTopBar(
                    title = stringResource(R.string.world_book_title),
                    onBack = onBack,
                ) {
                    IconActionButton(
                        Lucide.CloudDownload,
                        cs.onSurface,
                        stringResource(R.string.providers_page_import_tooltip),
                    ) { importLauncher.launch(arrayOf("application/json")) }
                    IconActionButton(Lucide.Plus, cs.onSurface, stringResource(R.string.world_book_add)) {
                        addingBook = true
                    }
                    Spacer(Modifier.width(12.dp))
                }

        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Lucide.BookOpen,
                        contentDescription = null,
                        tint = withAlpha(cs.onSurface, 0.3),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.world_book_empty_message),
                        style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.6)),
                    )
                }
            }
        } else {
            ReorderableColumnWithHandle(
                items = books,
                keyOf = { it.id },
                onMove = { from, to ->
                    // The library reports direct-move indices; WorldBookStore.reorder
                    // also takes them directly (removeAt + insert).
                    repo.reorder(from, to)
                    reload++
                },
                handleEnabled = books.size > 1,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 100.dp),
            ) { book, _, dragHandle ->
                Column {
                    BookSection(
                        book = book,
                        dragHandle = dragHandle,
                        collapsed = repo.isCollapsed(book.id),
                        onToggleCollapsed = {
                            Haptics.light(view)
                            repo.toggleCollapsed(book.id)
                            reload++
                        },
                        onAddEntry = {
                            Haptics.light(view)
                            entryTarget = book to null
                        },
                        onExport = {
                            Haptics.light(view)
                            exportBook = book
                            exportLauncher.launch(
                                WorldBookRepository.safeFileName(book.name.trim().ifEmpty { "lorebook" }) + ".json",
                            )
                        },
                        onConfig = {
                            Haptics.light(view)
                            editingBook = book
                        },
                        onDelete = {
                            Haptics.light(view)
                            deletingBook = book
                        },
                        onEditEntry = {
                            Haptics.light(view)
                            entryTarget = book to it
                        },
                        onDeleteEntry = { entry ->
                            Haptics.light(view)
                            repo.update(book.copy(entries = book.entries.filterNot { it.id == entry.id }))
                            reload++
                        },
                        onReorderEntries = { from, to ->
                            repo.reorderEntries(book.id, from, to)
                            reload++
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }

    if (addingBook || editingBook != null) {
        WorldBookEditSheet(
            book = editingBook,
            onDismiss = {
                addingBook = false
                editingBook = null
            },
            onSave = { book ->
                if (editingBook == null) repo.add(book) else repo.update(book)
                addingBook = false
                editingBook = null
                reload++
            },
        )
    }

    entryTarget?.let { (book, entry) ->
        WorldBookEntryEditSheet(
            entry = entry,
            onDismiss = { entryTarget = null },
            onSave = { edited ->
                val entries = if (entry == null) {
                    book.entries + edited
                } else {
                    book.entries.map { if (it.id == entry.id) edited else it }
                }
                repo.update(book.copy(entries = entries))
                entryTarget = null
                reload++
            },
        )
    }

    deletingBook?.let { book ->
        val name = book.name.trim().ifEmpty { stringResource(R.string.world_book_unnamed) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletingBook = null },
            title = { Text(stringResource(R.string.world_book_delete_title)) },
            text = { Text(stringResource(R.string.world_book_delete_message, name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deletingBook = null
                    repo.delete(book.id)
                    reload++
                }) { Text(stringResource(R.string.world_book_delete), color = cs.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deletingBook = null }) {
                    Text(stringResource(R.string.world_book_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }
}

private val IMPORT_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Composable
private fun BookSection(
    book: WorldBook,
    dragHandle: Modifier,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onAddEntry: () -> Unit,
    onExport: () -> Unit,
    onConfig: () -> Unit,
    onDelete: () -> Unit,
    onEditEntry: (WorldBookEntry) -> Unit,
    onDeleteEntry: (WorldBookEntry) -> Unit,
    onReorderEntries: (Int, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val title = book.name.trim().ifEmpty { stringResource(R.string.world_book_unnamed) }
    val subtitle = book.description.trim()
    var actionEntry by remember { mutableStateOf<WorldBookEntry?>(null) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The source starts the book drag from the header only
            // (ReorderableDelayedDragStartListener around this press area).
            Row(
                modifier = dragHandle
                    .weight(1f)
                    .clickable {
                        Haptics.light(view)
                        onToggleCollapsed()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rotation by animateFloatAsState(
                    targetValue = if (collapsed) 0f else 90f,
                    animationSpec = tween(durationMillis = 240, easing = EaseOutCubic),
                    label = "worldBookChevron",
                )
                Icon(
                    Lucide.ChevronRight,
                    contentDescription = null,
                    tint = withAlpha(cs.onSurface, 0.62),
                    modifier = Modifier.size(16.dp).rotate(rotation),
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                        )
                        if (!book.enabled) {
                            Spacer(Modifier.width(8.dp))
                            TagPill(text = stringResource(R.string.world_book_disabled_tag), color = cs.error)
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 12.5.sp, color = withAlpha(cs.onSurface, 0.65)),
                        )
                    }
                }
            }
            HeaderIconButton(Lucide.Plus, stringResource(R.string.world_book_add_entry), onAddEntry)
            HeaderIconButton(Lucide.Share2, stringResource(R.string.world_book_export), onExport)
            HeaderIconButton(Lucide.Settings2, stringResource(R.string.world_book_config), onConfig)
            HeaderIconButton(Lucide.Trash2, stringResource(R.string.world_book_delete), onDelete, color = cs.error)
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(tween(240, easing = EaseOutCubic)) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(240, easing = EaseInCubic)) + fadeOut(tween(240)),
        ) {
            SettingsSectionCard {
                if (book.entries.isEmpty()) {
                    EntryRow(
                        icon = Lucide.ListTree,
                        label = stringResource(R.string.world_book_no_entries_hint),
                        enabled = true,
                        detailText = null,
                        onTap = null,
                        onLongPress = null,
                        leadingModifier = Modifier,
                    )
                } else {
                    ReorderableInlineColumnWithHandle(
                        items = book.entries,
                        keyOf = { it.id },
                        onMove = onReorderEntries,
                        handleEnabled = book.entries.size > 1,
                    ) { entry, _, handle ->
                        val detail = when {
                            !entry.enabled -> stringResource(R.string.world_book_disabled_tag)
                            entry.constantActive -> stringResource(R.string.world_book_always_on_tag)
                            else -> null
                        }
                        Column {
                            EntryRow(
                                icon = Lucide.Bookmark,
                                label = entry.name.trim().ifEmpty { stringResource(R.string.world_book_unnamed_entry) },
                                enabled = entry.enabled,
                                detailText = detail,
                                onTap = { onEditEntry(entry) },
                                onLongPress = {
                                    Haptics.medium(view)
                                    actionEntry = entry
                                },
                                leadingModifier = handle,
                            )
                            if (entry.id != book.entries.last().id) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 54.dp, end = 12.dp)
                                        .height(0.6.dp)
                                        .background(withAlpha(cs.outlineVariant, 0.18)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionEntry?.let { entry ->
        EntryActionSheet(
            onDismiss = { actionEntry = null },
            onEdit = {
                Haptics.light(view)
                actionEntry = null
                onEditEntry(entry)
            },
            onDelete = {
                Haptics.light(view)
                actionEntry = null
                onDeleteEntry(entry)
            },
        )
    }
}

@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    detailText: String?,
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    leadingModifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val opacity = if (enabled) 1f else 0.55f
    val baseColor = withAlpha(cs.onSurface, 0.9 * opacity)
    val interactive = onTap != null || onLongPress != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (interactive) {
                    Modifier.combinedClickable(onClick = { onTap?.invoke() }, onLongClick = { onLongPress?.invoke() })
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(
                icon,
                contentDescription = null,
                tint = baseColor,
                modifier = leadingModifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = baseColor),
            modifier = Modifier.weight(1f),
        )
        if (detailText != null) {
            Text(
                text = detailText,
                style = TextStyle(fontSize = 13.sp, color = withAlpha(cs.onSurface, 0.6 * opacity)),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (onTap != null) {
            Icon(Lucide.ChevronRight, contentDescription = null, tint = baseColor, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    onTap: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
) {
    IosIconButton(
        icon = icon,
        onTap = onTap,
        color = color,
        size = 18.dp,
        contentPadding = 8.dp,
        semanticLabel = tooltip,
    )
}

@Composable
private fun TagPill(text: String, color: Color) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(withAlpha(color, 0.14), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .border(1.dp, withAlpha(color, 0.35), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionSheet(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp)) {
            SheetHandleBar()
            Spacer(Modifier.height(12.dp))
            SettingsSectionCard {
                SheetActionRow(Lucide.Settings2, stringResource(R.string.world_book_edit_entry), onEdit)
                SheetDivider()
                SheetActionRow(Lucide.Trash2, stringResource(R.string.world_book_delete_entry), onDelete, color = cs.error)
            }
            Spacer(Modifier.height(10.dp))
            SettingsSectionCard {
                SheetActionRow(Lucide.X, stringResource(R.string.world_book_cancel), onDismiss)
            }
        }
    }
}

@Composable
private fun SheetHandleBar() {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(withAlpha(cs.onSurface, 0.2), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
        )
    }
}

@Composable
private fun SheetDivider() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(0.6.dp)
            .background(withAlpha(cs.outlineVariant, 0.18)),
    )
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onTap: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color))
    }
}

/** _WorldBookEditSheet — name / description / enabled plus the button pair. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldBookEditSheet(
    book: WorldBook?,
    onDismiss: () -> Unit,
    onSave: (WorldBook) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var name by remember { mutableStateOf(book?.name ?: "") }
    var description by remember { mutableStateOf(book?.description ?: "") }
    var enabled by remember { mutableStateOf(book?.enabled ?: true) }

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
                .padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 16.dp),
        ) {
            SheetHandleBar()
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(if (book == null) R.string.world_book_add else R.string.world_book_config),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                SettingsSectionCard {
                    IosFormField(
                        label = stringResource(R.string.world_book_name_label),
                        value = name,
                        onValueChange = { name = it },
                        autofocus = book == null,
                        inline = false,
                    )
                    IosFormField(
                        label = stringResource(R.string.world_book_description_label),
                        value = description,
                        onValueChange = { description = it },
                        inline = false,
                        minLines = 2,
                        maxLines = 2,
                    )
                    SheetSwitchRow(
                        label = stringResource(R.string.world_book_enabled_label),
                        value = enabled,
                        onToggle = { enabled = it },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                IosSheetButton(
                    label = stringResource(R.string.world_book_cancel),
                    onTap = onDismiss,
                    modifier = Modifier.weight(1f),
                    useSurfaceFill = true,
                    fontSize = 15.sp,
                    lightHaptic = true,
                )
                Spacer(Modifier.width(12.dp))
                IosSheetButton(
                    label = stringResource(R.string.world_book_save),
                    filled = true,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    lightHaptic = true,
                    onTap = {
                        onSave(
                            WorldBook(
                                id = book?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                description = description.trim(),
                                enabled = enabled,
                                entries = book?.entries ?: emptyList(),
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** _WorldBookEntryEditSheet — the largest form in the page. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WorldBookEntryEditSheet(
    entry: WorldBookEntry?,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var name by remember { mutableStateOf(entry?.name ?: "") }
    var content by remember { mutableStateOf(entry?.content ?: "") }
    var enabled by remember { mutableStateOf(entry?.enabled ?: true) }
    var useRegex by remember { mutableStateOf(entry?.useRegex ?: false) }
    var caseSensitive by remember { mutableStateOf(entry?.caseSensitive ?: false) }
    var constantActive by remember { mutableStateOf(entry?.constantActive ?: false) }
    var position by remember { mutableStateOf(entry?.position ?: WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT) }
    var role by remember { mutableStateOf(entry?.role ?: WorldBookInjectionRole.USER) }
    var priority by remember { mutableStateOf((entry?.priority ?: 0).toString()) }
    var scanDepth by remember { mutableStateOf((entry?.scanDepth ?: 4).toString()) }
    var injectDepth by remember { mutableStateOf((entry?.injectDepth ?: 4).toString()) }
    var keywords by remember { mutableStateOf(entry?.keywords ?: emptyList()) }
    var keywordInput by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }

    val canSave = constantActive || keywords.isNotEmpty()

    fun addKeyword() {
        val value = keywordInput.trim()
        if (value.isEmpty()) return
        if (!keywords.contains(value)) keywords = keywords + value
        keywordInput = ""
    }

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
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            SheetHandleBar()
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(if (entry == null) R.string.world_book_add_entry else R.string.world_book_edit_entry),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                SettingsSectionCard {
                    IosFormField(
                        label = stringResource(R.string.world_book_entry_name_label),
                        value = name,
                        onValueChange = { name = it },
                        autofocus = entry == null,
                    )
                    SheetSwitchRow(
                        label = stringResource(R.string.world_book_entry_enabled_label),
                        value = enabled,
                        onToggle = { enabled = it },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsSectionCard {
                    IosFormField(
                        label = stringResource(R.string.world_book_entry_content_label),
                        value = content,
                        onValueChange = { content = it },
                        inline = false,
                        minLines = 8,
                        maxLines = 12,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsSectionCard {
                    SheetSwitchRow(
                        label = stringResource(R.string.world_book_entry_always_on_label),
                        hint = stringResource(R.string.world_book_entry_always_on_hint),
                        value = constantActive,
                        onToggle = { constantActive = it },
                    )
                    KeywordsBlock(
                        keywords = keywords,
                        input = keywordInput,
                        onInputChange = { keywordInput = it },
                        onAdd = { addKeyword() },
                        onRemove = { keywords = keywords - it },
                    )
                    SheetSwitchRow(
                        label = stringResource(R.string.world_book_entry_use_regex_label),
                        value = useRegex,
                        onToggle = { useRegex = it },
                    )
                    SheetSwitchRow(
                        label = stringResource(R.string.world_book_entry_case_sensitive_label),
                        value = caseSensitive,
                        onToggle = { caseSensitive = it },
                    )
                    IosFormField(
                        label = stringResource(R.string.world_book_entry_scan_depth_label),
                        value = scanDepth,
                        onValueChange = { scanDepth = it.filter(Char::isDigit) },
                        fieldWidth = 64.dp,
                        keyboardType = KeyboardType.Number,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingsSectionCard {
                    ValueRow(
                        label = stringResource(R.string.world_book_entry_injection_position_label),
                        valueText = positionLabel(position),
                        onTap = { picker = "position" },
                    )
                    if (position == WorldBookInjectionPosition.AT_DEPTH) {
                        IosFormField(
                            label = stringResource(R.string.world_book_entry_inject_depth_label),
                            value = injectDepth,
                            onValueChange = { injectDepth = it.filter(Char::isDigit) },
                            fieldWidth = 64.dp,
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    ValueRow(
                        label = stringResource(R.string.world_book_entry_injection_role_label),
                        valueText = roleLabel(role),
                        onTap = { picker = "role" },
                    )
                    IosFormField(
                        label = stringResource(R.string.world_book_entry_priority_label),
                        value = priority,
                        onValueChange = { priority = it },
                        fieldWidth = 64.dp,
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                IosSheetButton(
                    label = stringResource(R.string.world_book_cancel),
                    onTap = onDismiss,
                    modifier = Modifier.weight(1f),
                    useSurfaceFill = true,
                    fontSize = 15.sp,
                    lightHaptic = true,
                )
                Spacer(Modifier.width(12.dp))
                IosSheetButton(
                    label = stringResource(R.string.world_book_save),
                    filled = true,
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    lightHaptic = true,
                    onTap = {
                        onSave(
                            WorldBookEntry(
                                id = entry?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                enabled = enabled,
                                priority = priority.trim().toIntOrNull() ?: (entry?.priority ?: 0),
                                position = position,
                                content = content,
                                injectDepth = (injectDepth.trim().toIntOrNull() ?: (entry?.injectDepth ?: 4)).coerceIn(1, 200),
                                role = role,
                                keywords = keywords,
                                useRegex = useRegex,
                                caseSensitive = caseSensitive,
                                scanDepth = (scanDepth.trim().toIntOrNull() ?: (entry?.scanDepth ?: 4)).coerceIn(1, 200),
                                constantActive = constantActive,
                            ),
                        )
                    },
                )
            }
        }
    }

    when (picker) {
        "position" -> InjectionPickerSheet(
            title = stringResource(R.string.world_book_entry_injection_position_label),
            options = WorldBookInjectionPosition.entries,
            labelOf = { positionLabel(it) },
            selected = position,
            onDismiss = { picker = null },
            onPick = {
                position = it
                picker = null
            },
        )
        "role" -> InjectionPickerSheet(
            title = stringResource(R.string.world_book_entry_injection_role_label),
            options = WorldBookInjectionRole.entries,
            labelOf = { roleLabel(it) },
            selected = role,
            onDismiss = { picker = null },
            onPick = {
                role = it
                picker = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> InjectionPickerSheet(
    title: String,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(10.dp))
            SettingsSectionCard {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(option) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = labelOf(option),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                            modifier = Modifier.weight(1f),
                        )
                        if (option == selected) {
                            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (index != options.lastIndex) SheetDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordsBlock(
    keywords: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val isDark = semantic.isDark
    Column(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp)) {
        Text(
            text = stringResource(R.string.world_book_entry_keywords_label),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.85)),
        )
        Spacer(Modifier.height(10.dp))
        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                keywords.forEach { keyword -> KeywordChip(keyword, isDark = isDark, onRemove = { onRemove(keyword) }) }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = withAlpha(cs.onSurface, 0.92)),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onAdd() }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.world_book_entry_keyword_input_hint),
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = withAlpha(cs.onSurface, if (isDark) 0.42 else 0.46),
                                    ),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable(enabled = input.isNotBlank()) { onAdd() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Plus,
                    contentDescription = stringResource(R.string.world_book_entry_keyword_add_tooltip),
                    tint = withAlpha(cs.onSurface, if (input.isBlank()) 0.35 else 0.9),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.world_book_entry_keywords_hint),
            style = TextStyle(fontSize = 12.5.sp, color = withAlpha(cs.onSurface, 0.6)),
        )
    }
}

@Composable
private fun KeywordChip(keyword: String, isDark: Boolean, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(withAlpha(cs.primary, if (isDark) 0.22 else 0.12), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .border(0.6.dp, withAlpha(cs.primary, if (isDark) 0.36 else 0.26), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = keyword,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        )
        IosIconButton(
            icon = Lucide.X,
            onTap = onRemove,
            color = withAlpha(cs.onSurface, 0.65),
            size = 14.dp,
            contentPadding = 6.dp,
        )
        Spacer(Modifier.width(2.dp))
    }
}

@Composable
private fun SheetSwitchRow(
    label: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
    hint: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!value) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = TextStyle(fontSize = 12.5.sp, color = withAlpha(cs.onSurface, 0.6), lineHeight = 15.sp),
                )
            }
        }
        IosSwitch(value = value, onValueChanged = onToggle)
    }
}

@Composable
private fun ValueRow(label: String, valueText: String, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = withAlpha(cs.onSurface, 0.9)),
            modifier = Modifier.weight(1f),
        )
        Text(valueText, style = TextStyle(fontSize = 13.sp, color = withAlpha(cs.onSurface, 0.62)))
        Spacer(Modifier.width(6.dp))
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = withAlpha(cs.onSurface, 0.55),
            modifier = Modifier.size(16.dp),
        )
    }
}


@Composable
private fun positionLabel(position: WorldBookInjectionPosition): String = stringResource(
    when (position) {
        WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT -> R.string.world_book_injection_position_before_system_prompt
        WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT -> R.string.world_book_injection_position_after_system_prompt
        WorldBookInjectionPosition.TOP_OF_CHAT -> R.string.world_book_injection_position_top_of_chat
        WorldBookInjectionPosition.BOTTOM_OF_CHAT -> R.string.world_book_injection_position_bottom_of_chat
        WorldBookInjectionPosition.AT_DEPTH -> R.string.world_book_injection_position_at_depth
    },
)

@Composable
private fun roleLabel(role: WorldBookInjectionRole): String = stringResource(
    when (role) {
        WorldBookInjectionRole.USER -> R.string.world_book_injection_role_user
        WorldBookInjectionRole.ASSISTANT -> R.string.world_book_injection_role_assistant
    },
)
