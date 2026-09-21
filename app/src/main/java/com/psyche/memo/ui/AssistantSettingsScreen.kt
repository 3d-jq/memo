package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.assistant.buildCopyName
import com.psyche.memo.data.assistant.canDeleteAssistant
import com.psyche.memo.data.assistant.reorderMove
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Assistant settings list — lib/features/assistant/pages/
 * assistant_settings_page.dart 1:1: AppBar with back + add, a long-press
 * reorderable card list (ReorderableDelayedDragStartListener →
 * ReorderableColumn's long-press handle), cards with a 44dp avatar, name
 * (16 emphasis) and prompt subtitle (13/1.25, placeholder when empty),
 * swipe-revealed copy/delete actions (Slidable endActionPane 0.6), the
 * iOS-style add-name sheet and the delete confirmation dialog.
 */
@Composable
fun AssistantSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenEdit: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var assistants by remember { mutableStateOf<List<Assistant>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var addSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Assistant?>(null) }

    LaunchedEffect(reloadKey) {
        assistants = withContext(Dispatchers.IO) {
            AssistantStore(container.database.readableDatabase).getAll()
        }
    }

    fun store() = AssistantStore(container.database.readableDatabase)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.assistant_settings_page_title),
            onBack = onBack,
        ) {
            Spacer(Modifier.weight(1f))
            // actions[0] — Plus → add sheet (L42-67).
            IconButton(onClick = { addSheet = true }, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.Plus, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        ReorderableColumn(
            items = assistants,
            keyOf = { it.id },
            reorderEnabled = true,
            onMove = { from, to ->
                // The reorderable lib can fire with stale layout indices
                // mid-drag — validate against the current snapshot.
                val moved = reorderMove(assistants.map { it.id }, from, to) ?: return@ReorderableColumn
                val snapshot = assistants.associateBy { it.id }
                assistants = moved.mapNotNull { id -> snapshot[id] }
                scope.launch(Dispatchers.IO) { store().setOrder(moved) }
            },
            modifier = Modifier.fillMaxSize(),
            contentPadding = Padding12(),
        ) { item, _ ->
            Box(Modifier.padding(bottom = 10.dp)) {
                SwipeRevealRow(
                    actions = listOf(
                        SwipeActionData(
                            icon = Lucide.Copy,
                            label = stringResource(UiR.string.assistant_settings_copy_button),
                            container = cs.primary.copy(alpha = if (semantic.isDark) 0.16f else 0.12f),
                            border = cs.primary.copy(alpha = 0.35f),
                            content = cs.primary,
                            onClick = {
                                Haptics.light(view)
                                val copySuffix = context.getString(UiR.string.assistant_settings_copy_suffix)
                                val fallbackName = context.getString(UiR.string.assistant_provider_new_assistant_name)
                                scope.launch(Dispatchers.IO) {
                                    val existing = store().getAll()
                                    val newId = store().duplicate(
                                        id = item.id,
                                        copyName = buildCopyName(
                                            sourceName = item.name,
                                            existingNames = existing.map { it.name }.toSet(),
                                            copySuffix = copySuffix,
                                            fallbackName = fallbackName,
                                        ),
                                        // assistant_provider.dart L333-342 ——
                                        // 副本的本地头像/背景复制成新文件。
                                        copyLocalFile = { path, dupId, isAvatar ->
                                            duplicateAssistantLocalFile(
                                                container.appContext, path, dupId, isAvatar,
                                            )
                                        },
                                    )
                                    withContext(Dispatchers.Main) {
                                        reloadKey++
                                        if (newId != null) {
                                            SnackbarManager.show(
                                                AppNotification(
                                                    message = context.getString(UiR.string.assistant_settings_copy_success),
                                                    type = NotificationType.SUCCESS,
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                        SwipeActionData(
                            icon = Lucide.Trash2,
                            label = stringResource(UiR.string.assistant_settings_delete_button),
                            container = cs.error.copy(alpha = if (semantic.isDark) 0.22f else 0.14f),
                            border = cs.error.copy(alpha = 0.35f),
                            content = cs.error,
                            onClick = {
                                Haptics.light(view)
                                // L262-285 — at-least-one guard before the dialog.
                                if (!canDeleteAssistant(assistants.size)) {
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = context.getString(UiR.string.assistant_settings_at_least_one_assistant_required),
                                            type = NotificationType.WARNING,
                                        ),
                                    )
                                } else {
                                    deleteTarget = item
                                }
                            },
                        ),
                    ),
                    onFrontTap = { onOpenEdit(item.id) },
                ) { pressed ->
                    AssistantCard(item = item, pressed = pressed)
                }
            }
        }
    }

    if (addSheet) {
        AddAssistantSheet(
            onSubmitted = { name ->
                addSheet = false
                if (!name.isNullOrEmpty()) {
                    Haptics.light(view)
                    scope.launch(Dispatchers.IO) {
                        store().add(name)
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                }
            },
            onDismiss = { addSheet = false },
        )
    }

    deleteTarget?.let { target ->
        // _confirmDelete L532-559.
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(UiR.string.assistant_settings_delete_dialog_title)) },
            text = { Text(stringResource(UiR.string.assistant_settings_delete_dialog_content)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        Haptics.light(view)
                        scope.launch(Dispatchers.IO) {
                            val success = store().delete(target.id)
                            withContext(Dispatchers.Main) {
                                reloadKey++
                                if (!success) {
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = context.getString(UiR.string.assistant_settings_at_least_one_assistant_required),
                                            type = NotificationType.WARNING,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(UiR.string.assistant_settings_delete_dialog_confirm),
                        color = cs.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(UiR.string.assistant_settings_delete_dialog_cancel))
                }
            },
        )
    }
}

/** Body padding LTRB(12, 12, 12, 100) of the ReorderableListView (L70). */
private fun Padding12(): androidx.compose.foundation.layout.PaddingValues =
    androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 100.dp)

/** _AssistantCard L113-326 (visual part). */
@Composable
private fun AssistantCard(item: Assistant, pressed: Boolean) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val base = semantic.surfaceCard
    val overlay = cs.onSurface.copy(alpha = if (semantic.isDark) 0.06f else 0.04f)
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) overlay.compositeOver(base) else base, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(
                0.8.dp,
                cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.12f else 0.08f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistantListAvatar(item = item, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.systemPrompt.trim().ifEmpty {
                        stringResource(UiR.string.assistant_settings_no_prompt_placeholder)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 13.sp,
                        lineHeight = (13 * 1.25).sp,
                        color = cs.onSurface.copy(alpha = 0.7f),
                    ),
                )
            }
        }
    }
}

/** _AssistantAvatar L561-654 — http / local file / emoji / initial. */
@Composable
internal fun AssistantListAvatar(item: Assistant, size: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    val av = item.avatar?.trim().orEmpty()
    val bg = cs.primary.copy(alpha = 0.15f)
    val fallbackName = item.name.trim()
    val initial = firstGrapheme(fallbackName).uppercase().ifEmpty { "?" }

    Box(
        Modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            av.isNotEmpty() && av.startsWith("http") -> AsyncImage(
                model = av,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
            av.isNotEmpty() && (av.startsWith("/") || av.contains(":")) -> AsyncImage(
                model = File(av),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
            av.isNotEmpty() -> Text(
                text = firstGrapheme(av),
                style = TextStyle(fontSize = (size.value * 0.5f).sp, color = cs.onSurface),
            )
            else -> Text(
                text = initial,
                style = TextStyle(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = cs.primary,
                ),
            )
        }
    }
}

private data class SwipeActionData(
    val icon: ImageVector,
    val label: String,
    val container: Color,
    val border: Color,
    val content: Color,
    val onClick: () -> Unit,
)

/**
 * Slidable endActionPane (L199-324) — stretch pane at 0.6 width with the
 * copy/delete actions behind the card; drag the card left to reveal,
 * tap an action to fire it, tap the card again to close.
 */
@Composable
private fun SwipeRevealRow(
    actions: List<SwipeActionData>,
    onFrontTap: () -> Unit,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var rowWidthPx by remember { mutableStateOf(0f) }
    val maxRevealPx = rowWidthPx * 0.6f
    val anim = remember { Animatable(0f) }
    var dragFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val fraction = if (dragging) dragFraction else anim.value

    fun snapTo(target: Float) {
        scope.launch {
            anim.animateTo(target, tween(durationMillis = 160, easing = EaseOutCubic))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { rowWidthPx = it.width.toFloat() },
    ) {
        // Flutter CustomSlidableAction: transparent actions padded 4,
        // each child fills the pane (width & height infinity) — buttons
        // ride the full card height, 8px gap between the two actions.
        Box(Modifier.matchParentSize()) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(with(density) { maxRevealPx.toDp() })
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            actions.forEach { action ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(action.container, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .border(0.8.dp, action.border, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .clickable {
                            Haptics.light(view)
                            snapTo(0f)
                            action.onClick()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(action.icon, contentDescription = null, tint = action.content, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = action.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = action.content, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
                        )
                    }
                }
            }
            }
        }
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.98f else 1f,
            animationSpec = tween(durationMillis = 110, easing = EaseOutCubic),
            label = "cardPressScale",
        )
        Box(
            Modifier
                .offset(x = with(density) { (-fraction * maxRevealPx).toDp() })
                .pointerInput(maxRevealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragFraction = anim.value
                        },
                        onDragEnd = {
                            dragging = false
                            snapTo(if (dragFraction > 0.5f) 1f else 0f)
                        },
                        onDragCancel = {
                            dragging = false
                            snapTo(if (dragFraction > 0.5f) 1f else 0f)
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        if (maxRevealPx > 0f) {
                            dragFraction = (dragFraction - dragAmount / maxRevealPx).coerceIn(0f, 1f)
                        }
                    }
                }
                .scale(scale)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) {
                    if (fraction > 0.02f) {
                        snapTo(0f)
                    } else {
                        Haptics.light(view)
                        onFrontTap()
                    }
                },
        ) {
            content(pressed)
        }
    }
}

/** _showAddAssistantSheet L425-530 — 40x4 handle, title, field, iOS buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAssistantSheet(
    onSubmitted: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null, // 原版自绘 40x4 拖柄
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = semantic.overlaySurface(cs),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(UiR.string.assistant_settings_add_sheet_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(UiR.string.assistant_settings_add_sheet_hint),
                        color = cs.onSurface.copy(alpha = 0.45f),
                    )
                },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LocalSemanticColors.current.surfaceFill,
                    unfocusedContainerColor = LocalSemanticColors.current.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                    cursorColor = cs.primary,
                    focusedTextColor = cs.onSurface,
                    unfocusedTextColor = cs.onSurface,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmitted(text.trim()) }),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Box(Modifier.weight(1f)) {
                    IosSheetButton(
                        label = stringResource(UiR.string.assistant_settings_add_sheet_cancel),
                        filled = false,
                        onClick = { onDismiss() },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    IosSheetButton(
                        label = stringResource(UiR.string.assistant_settings_add_sheet_save),
                        filled = true,
                        onClick = { onSubmitted(text.trim()) },
                    )
                }
            }
        }
    }
}

/** _IosOutlineButton/_IosFilledButton L656-756 (scale 0.97 while pressed). */
@Composable
private fun IosSheetButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 110, easing = EaseOutCubic),
        label = "sheetButtonScale",
    )
    Box(
        Modifier
            .scale(scale)
            .fillMaxWidth()
            .background(if (filled) cs.primary else Color.Transparent, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.8.dp, cs.primary.copy(alpha = 0.5f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = if (filled) cs.onPrimary else cs.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            ),
        )
    }
}
