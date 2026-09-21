package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BotMessageSquare
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Zap
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.QuickPhrase
import com.psyche.memo.data.repo.QuickPhraseRepository
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Port of quick_phrases_page.dart: global or assistant-scoped phrase list,
 * long-press drag reorder, left-swipe delete, and the title/content edit
 * sheet. [assistantId] null = global.
 */
@Composable
fun QuickPhrasesScreen(
    container: AppContainerImpl,
    assistantId: String? = null,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val repo = remember(container) { QuickPhraseRepository(container.database.writableDatabase) }
    var reload by remember { mutableIntStateOf(0) }
    val phrases = remember(reload, assistantId) {
        if (assistantId == null) repo.globalPhrases() else repo.forAssistant(assistantId)
    }
    var editing by remember { mutableStateOf<QuickPhrase?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
                MemoTopBar(
                    title = stringResource( if (assistantId == null) R.string.quick_phrase_global_title else R.string.quick_phrase_assistant_title, ),
                    onBack = onBack,
                ) {
                    IconActionButton(Lucide.Plus, cs.onSurface, stringResource(R.string.quick_phrase_add_tooltip)) {
                        Haptics.light(view)
                        adding = true
                    }
                    Spacer(Modifier.width(12.dp))
                }

        if (phrases.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Lucide.Zap,
                        contentDescription = null,
                        tint = cs.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.quick_phrase_empty_message),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            }
        } else {
            ReorderableColumn(
                items = phrases,
                keyOf = { it.id },
                onMove = { from, to ->
                    // Library reports direct-move indices; upstream expects the
                    // unadjusted onReorder index.
                    val newIndex = if (to > from) to + 1 else to
                    repo.reorder(from, newIndex, assistantId)
                    reload++
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                itemContent = { phrase, _ ->
                    SwipeDeleteRow(onDelete = {
                        Haptics.light(view)
                        repo.delete(phrase.id)
                        reload++
                    }) {
                        QuickPhraseCard(
                            phrase = phrase,
                            onClick = {
                                Haptics.light(view)
                                editing = phrase
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                },
            )
        }
    }

    if (adding || editing != null) {
        QuickPhraseEditSheet(
            phrase = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { title, content ->
                val existing = editing
                if (existing == null) {
                    repo.add(
                        QuickPhrase(
                            id = java.util.UUID.randomUUID().toString(),
                            title = title,
                            content = content,
                            isGlobal = assistantId == null,
                            assistantId = assistantId,
                        ),
                    )
                } else {
                    repo.update(existing.copy(title = title, content = content))
                }
                adding = false
                editing = null
                reload++
            },
        )
    }
}

/** Slidable endActionPane equivalent: drag left to reveal a delete action. */
@Composable
internal fun SwipeDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableStateOf(0f) }
    val maxRevealPx = widthPx * 0.35f
    val anim = remember { Animatable(0f) }
    var dragFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val fraction = if (dragging) dragFraction else anim.value

    Box(modifier = Modifier.fillMaxWidth().onSizeChangedCompat { widthPx = it }) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .width(with(density) { maxRevealPx.toDp() })
                    .fillMaxSize()
                    .background(
                        cs.error.copy(alpha = if (semantic.isDark) 0.22f else 0.14f),
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .border(1.dp, cs.error.copy(alpha = 0.35f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable {
                        scope.launch { anim.snapTo(0f) }
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Trash2, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.quick_phrase_delete_button),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.error),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(-(fraction * maxRevealPx).roundToInt(), 0) }
                .pointerInput(maxRevealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragFraction = anim.value
                        },
                        onDragEnd = {
                            dragging = false
                            scope.launch {
                                anim.snapTo(if (dragFraction > 0.5f) 1f else 0f)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { anim.snapTo(0f) }
                        },
                    ) { _, dragAmount ->
                        if (maxRevealPx > 0f) {
                            dragFraction = (dragFraction - dragAmount / maxRevealPx).coerceIn(0f, 1f)
                        }
                    }
                },
        ) {
            content()
        }
    }
}

private fun Modifier.onSizeChangedCompat(block: (Float) -> Unit): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            block(placeable.width.toFloat())
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )

@Composable
private fun QuickPhraseCard(phrase: QuickPhrase, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(
                0.6.dp,
                cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.1f else 0.08f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Zap, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = phrase.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = phrase.content,
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
internal fun QuickPhraseEditSheet(
    phrase: QuickPhrase?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var title by remember { mutableStateOf(phrase?.title ?: "") }
    var content by remember { mutableStateOf(phrase?.content ?: "") }

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
                        if (phrase == null) R.string.quick_phrase_add_title
                        else R.string.quick_phrase_edit_title,
                    ),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text(stringResource(R.string.quick_phrase_title_label)) },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                minLines = 5,
                maxLines = 8,
                label = { Text(stringResource(R.string.quick_phrase_content_label)) },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                IosSheetButton(
                    label = stringResource(R.string.quick_phrase_cancel_button),
                    onTap = onDismiss,
                    modifier = Modifier.weight(1f),
                    accentOutline = true,
                )
                Spacer(Modifier.width(12.dp))
                IosSheetButton(
                    label = stringResource(R.string.quick_phrase_save_button),
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onTap = {
                        if (title.isNotBlank() && content.isNotBlank()) onSave(title.trim(), content.trim())
                    },
                )
            }
        }
    }
}

/**
 * Port of quick_phrase_menu.dart's anchored popup: 250dp card above the input
 * bar (max 50% screen height) listing global + assistant phrases.
 */
@Composable
fun QuickPhraseMenu(
    phrases: List<QuickPhrase>,
    onSelect: (QuickPhrase) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val density = LocalDensity.current
    val maxHeight = with(density) {
        (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.5f).toDp()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 122.dp)
                .width(250.dp)
                .heightIn(max = maxHeight)
                .background(
                    semantic.surfaceCardFill.copy(alpha = 0.92f),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .border(
                    1.dp,
                    if (semantic.isDark) cs.onSurface.copy(alpha = 0.08f) else cs.outlineVariant.copy(alpha = 0.2f),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                ),
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(phrases.size, key = { phrases[it].id }) { index ->
                    val phrase = phrases[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(phrase) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (phrase.isGlobal) Lucide.Zap else Lucide.BotMessageSquare,
                                    contentDescription = null,
                                    tint = cs.primary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = phrase.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = phrase.content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                    }
                }
            }
        }
    }
}
