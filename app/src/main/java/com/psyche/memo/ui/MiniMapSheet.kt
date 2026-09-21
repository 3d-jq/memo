@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronsDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Map
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of lib/features/home/widgets/mini_map_sheet.dart.
 *
 * Bottom sheet listing Q/A pairs of the current conversation; tapping a
 * bubble jumps back to that message. Search filters pairs locally (the
 * no-onSearch branch of _filteredPairs, L451-467) — matches the original
 * behaviour when no remote search service is wired.
 */
private data class QaPair(val user: ChatMessage?, val assistant: ChatMessage?)

/** L425-449: pair user messages with the following assistant reply. */
private fun buildQaPairs(items: List<ChatMessage>): List<QaPair> {
    val pairs = mutableListOf<QaPair>()
    var pendingUser: ChatMessage? = null
    for (m in items) {
        if (m.role == "user") {
            if (pendingUser != null) pairs.add(QaPair(pendingUser, null))
            pendingUser = m
        } else if (m.role == "assistant") {
            if (pendingUser != null) {
                pairs.add(QaPair(pendingUser, m))
                pendingUser = null
            } else {
                pairs.add(QaPair(null, m))
            }
        }
    }
    if (pendingUser != null) pairs.add(QaPair(pendingUser, null))
    return pairs
}

/** L495-523: summary text = TextParts joined, think blocks stripped, flattened. */
private fun summaryText(message: ChatMessage?): String {
    if (message == null) return ""
    val text = message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    return flattenWhitespace(
        text.replace(
            Regex("<(?:think|thought)>[\\s\\S]*?</(?:think|thought)>", RegexOption.IGNORE_CASE),
            "",
        ),
    )
}

private fun flattenWhitespace(s: String): String =
    s.replace("\n", " ").replace(Regex("\\s+"), " ").trim()

@Composable
private fun isDarkTheme(): Boolean =
    MaterialTheme.colorScheme.surface.luminance() < 0.5f

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * Mini map bottom sheet. [onJumpToMessage] receives the tapped message id;
 * the caller closes the sheet and scrolls the timeline to that message.
 */
@Composable
fun MiniMapSheet(
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onJumpToMessage: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var needJumpToBottom by remember { mutableStateOf(false) }

    // Streaming skeletons are excluded from the map.
    val pairs = remember(messages) {
        buildQaPairs(messages.filter { !it.isStreaming })
    }
    val needle = query.trim().lowercase()
    val visiblePairs = if (needle.isEmpty()) pairs
    else pairs.filter { pair ->
        summaryText(pair.user).lowercase().contains(needle) ||
            summaryText(pair.assistant).lowercase().contains(needle)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
        dragHandle = null,
    ) {
        // DraggableScrollableSheet initial 0.55 — approximated with a
        // fixed 55% screen height content area inside the sheet.
        val screenH = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height.toDp()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenH * 0.55f)
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // Drag handle (L255-264)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .background(
                            cs.onSurface.copy(alpha = 0.2f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            Spacer(Modifier.height(10.dp))
            // Title row (L267-307)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.Map,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(UiR.string.mini_map_title),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.width(4.dp))
                // Jump-to-bottom button (L282-304)
                IconButton(onClick = { needJumpToBottom = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Lucide.ChevronsDown,
                        contentDescription = stringResource(UiR.string.mini_map_scroll_to_bottom_tooltip),
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                MiniMapSearchToggle(
                    searching = searching,
                    query = query,
                    onStartSearch = { searching = true },
                    onQueryChange = { query = it },
                    onClearOrClose = {
                        query = ""
                        searching = false
                    },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (needle.isNotEmpty() && visiblePairs.isEmpty()) {
                // L212-227: no-results hint.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = stringResource(UiR.string.mini_map_search_no_results),
                        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                    )
                }
            } else {
                val listState = rememberLazyListState()
                // ChevronsDown jumps to the newest pair (L295-302).
                LaunchedEffect(needJumpToBottom) {
                    val total = listState.layoutInfo.totalItemsCount
                        if (needJumpToBottom && total > 0) {
                        listState.scrollToItem(total - 1)
                        needJumpToBottom = false
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    items(visiblePairs, key = { pair ->
                        (pair.user?.id ?: "u") + "-" + (pair.assistant?.id ?: "a")
                    }) { pair ->
                        MiniMapRow(
                            pair = pair,
                            needle = needle,
                            onJump = onJumpToMessage,
                        )
                    }
                }
            }
        }
    }
}

/** L326-423: search button <-> search field animated toggle. */
@Composable
private fun MiniMapSearchToggle(
    searching: Boolean,
    query: String,
    onStartSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearOrClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (!searching) {
        IconButton(onClick = onStartSearch, modifier = Modifier.size(36.dp)) {
            Icon(
                Lucide.Search,
                contentDescription = stringResource(UiR.string.chat_history_page_search_tooltip),
                tint = cs.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        val isDark = cs.surface.luminance() < 0.5f
        val borderColor = cs.outlineVariant.copy(alpha = if (isDark) 0.5f else 0.8f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                singleLine = true,
                shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cs.surfaceVariant.copy(alpha = if (isDark) 0.35f else 0.6f),
                    unfocusedContainerColor = cs.surfaceVariant.copy(alpha = if (isDark) 0.35f else 0.6f),
                    focusedBorderColor = cs.primary,
                    unfocusedBorderColor = borderColor,
                    focusedTextColor = cs.onSurface,
                    unfocusedTextColor = cs.onSurface,
                    cursorColor = cs.primary,
                ),
                placeholder = {
                    Text(
                        stringResource(UiR.string.chat_history_page_search_hint),
                        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                    )
                },
                textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClearOrClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    Lucide.X,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** L476-762: one Q/A pair row with user (right) and assistant (left) bubbles. */
@Composable
private fun MiniMapRow(
    pair: QaPair,
    needle: String,
    onJump: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val screenW = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }

    val userBg = cs.primary.copy(alpha = if (isDark) 0.15f else 0.08f)
    val assistantBg = cs.onSurface.copy(alpha = if (isDark) 0.06f else 0.04f)
    val userStyle = TextStyle(fontSize = 15.5.sp, color = cs.onSurface)
    val assistantStyle = TextStyle(fontSize = 15.7.sp, color = cs.onSurface)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        // User bubble (L627-689)
        pair.user?.let { user ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                // L630-634: maxWidth = screen * 0.75 - 32.
                val maxBubbleWidth = screenW * 0.75f - 32.dp
                Text(
                    text = summaryText(user).ifEmpty { " " },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = userStyle,
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .clickable { onJump(user.id) }
                        .background(userBg, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        // Assistant bubble (L692-757)
        pair.assistant?.let { assistant ->
            Text(
                text = summaryText(assistant).ifEmpty { " " },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = assistantStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJump(assistant.id) }
                    .background(assistantBg, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}


