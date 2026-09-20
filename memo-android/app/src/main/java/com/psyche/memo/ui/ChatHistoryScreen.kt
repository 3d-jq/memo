package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 1:1 port of lib/features/chat/pages/chat_history_page.dart.
 *
 * Structure (source line refs):
 * - Scaffold/AppBar (L57-130): back arrow + title + search toggle + delete-all.
 * - Body (L131-246): conditional rounded search field (r50) + list with a
 *   "Pinned" section header (13sp semibold primary) and conversation cards.
 * - _ConversationCard (L307-403): surfaceCard r14 + hairline border +
 *   MessageCircle avatar + title/time + pin pill.
 * - Dismissible (L264-304): endToStart swipe delete with errorContainer bg.
 * - _PinButton (L405-460): pin pill with Pin/PinOff + label swap.
 */
@Composable
fun ChatHistoryScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showDeleteAll by remember { mutableStateOf(false) }

    fun reload() {
        conversations = container.conversationDao.getAll()
    }
    // reload() 是同步读库（getAll）；事件回调里照旧直接调，只有进页面这第一次放到 IO。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reload() }
    }

    // L50-55: filter by search query, split pinned/others.
    val q = query.trim().lowercase(Locale.getDefault())
    val filtered = if (q.isEmpty()) conversations
    else conversations.filter { it.title.lowercase(Locale.getDefault()).contains(q) }
    val pinned = filtered.filter { it.isPinned }
    val others = filtered.filter { !it.isPinned }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // AppBar (L58-130)
                MemoTopBar(
                    title = stringResource(UiR.string.chat_history_page_title),
                    onBack = onBack,
                ) {
                    IconButton(
                        onClick = {
                            if (searching) query = ""
                            searching = !searching
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            if (searching) Lucide.X else Lucide.Search,
                            contentDescription = stringResource(UiR.string.chat_history_page_search_tooltip),
                            tint = cs.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(
                        onClick = { showDeleteAll = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Lucide.Trash2,
                            contentDescription = stringResource(UiR.string.chat_history_page_delete_all_tooltip),
                            tint = cs.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

        // Body (L131-246)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 14.dp),
        ) {
            if (searching) {
                // L153-205: rounded-50 filled search field.
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    placeholder = {
                        Text(
                            stringResource(UiR.string.chat_history_page_search_hint),
                            style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                        )
                    },
                    singleLine = true,
                    // chat_history_page.dart:166-178 —— circular(50) 是 50 逻辑
                    // 像素，不是 50%（Compose 的 Int 重载是百分比）。
                    shape = RoundedCornerShape(50.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = semantic.surfaceCard,
                        unfocusedContainerColor = semantic.surfaceCard,
                        focusedBorderColor = cs.primary.copy(alpha = 0.3f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = cs.onSurface,
                        unfocusedTextColor = cs.onSurface,
                        cursorColor = cs.primary,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    leadingIcon = {
                        Icon(
                            Lucide.Search,
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Lucide.X,
                                    contentDescription = null,
                                    tint = cs.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                )
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(UiR.string.chat_history_page_no_conversations),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (pinned.isNotEmpty()) {
                        // L223-233: pinned section header.
                        item(key = "pinned-header") {
                            Text(
                                text = stringResource(UiR.string.chat_history_page_pinned_section),
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.primary,
                                ),
                            )
                        }
                        items(pinned, key = { "p-${it.id}" }) { conv ->
                            HistoryConversationTile(
                                conversation = conv,
                                onClick = { onOpenConversation(conv.id) },
                                onPinToggle = {
                                    container.conversationDao.updatePinned(conv.id, !conv.isPinned)
                                    reload()
                                },
                                onDelete = {
                                    container.conversationDao.delete(conv.id)
                                    reload()
                                },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                    items(others, key = { it.id }) { conv ->
                        HistoryConversationTile(
                            conversation = conv,
                            onClick = { onOpenConversation(conv.id) },
                            onPinToggle = {
                                container.conversationDao.updatePinned(conv.id, !conv.isPinned)
                                reload()
                            },
                            onDelete = {
                                container.conversationDao.delete(conv.id)
                                reload()
                            },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // Delete-all confirm (L83-127). Ported condition verbatim: only
    // conversations with a null assistantId that are not pinned are removed.
    if (showDeleteAll) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { showDeleteAll = false },
            title = { Text(stringResource(UiR.string.chat_history_page_delete_all_dialog_title)) },
            text = { Text(stringResource(UiR.string.chat_history_page_delete_all_dialog_content)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    conversations
                        .filter { it.assistantId == null && !it.isPinned }
                        .forEach { container.conversationDao.delete(it.id) }
                    reload()
                }) {
                    Text(
                        stringResource(UiR.string.chat_history_page_delete),
                        color = cs.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }) {
                    Text(stringResource(UiR.string.chat_history_page_cancel))
                }
            },
        )
    }
}

/** L250-304: swipe-to-delete wrapper + card. */
@Composable
private fun HistoryConversationTile(
    conversation: Conversation,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // L267-289: error container with "Delete" + trash icon at the end.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 6.dp)
                    .background(cs.errorContainer, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(UiR.string.chat_history_page_delete),
                        style = TextStyle(
                            color = cs.onErrorContainer,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Lucide.Trash2,
                        contentDescription = null,
                        tint = cs.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    ) {
        HistoryConversationCard(
            conversation = conversation,
            onClick = onClick,
            onPinToggle = onPinToggle,
        )
    }
}

/** L307-403: conversation card. */
@Composable
private fun HistoryConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Surface(
        color = semantic.surfaceCard,
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    cs.outlineVariant.copy(alpha = 0.16f),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading avatar circle (L335-348)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(cs.primary.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.MessageCircle,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Lucide.History,
                        contentDescription = null,
                        tint = cs.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatConversationTime(conversation.updatedAt),
                        style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Pin pill (L405-460)
            Row(
                modifier = Modifier
                    .clickable { onPinToggle() }
                    .background(
                        if (conversation.isPinned) cs.primary.copy(alpha = 0.12f) else cs.surface,
                        RoundedCornerShape(MemoRadius.PILL_DP.dp),
                    )
                    .border(
                        1.dp,
                        cs.outlineVariant.copy(alpha = 0.18f),
                        RoundedCornerShape(MemoRadius.PILL_DP.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (conversation.isPinned) Lucide.PinOff else Lucide.Pin,
                    contentDescription = null,
                    tint = if (conversation.isPinned) cs.primary else cs.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (conversation.isPinned) UiR.string.chat_history_page_pinned
                        else UiR.string.chat_history_page_pin,
                    ),
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (conversation.isPinned) cs.primary else cs.onSurface.copy(alpha = 0.8f),
                    ),
                )
            }
        }
    }
}

/**
 * L396-402: zh/latin timestamp format.
 *
 * 2026-09-20 卡顿修复：以前**每一行每次重组**都 `Locale.getDefault()` + `new
 * SimpleDateFormat(pattern, locale)`（构造要解析模式、抓一遍 locale 数据），
 * 历史列表滚动时这笔固定开销按行乘一遍。formatter 是**无状态且线程安全**的
 * [java.time.format.DateTimeFormatter]（SimpleDateFormat 不是，所以不能同样静态持有，
 * 也顺带避开 lint 的 ConstantLocale），按「locale + 模式」缓存在进程级 map 里。
 *
 * 输出与旧写法逐字相同 —— 由 `ChatHistoryTimeFormatTest` 用同一批时间戳对
 * SimpleDateFormat 逐项比对钉住。
 */
internal fun conversationTimePattern(locale: Locale): String =
    if (locale.language == "zh") "yyyy年M月d日 HH:mm:ss" else "yyyy-MM-dd HH:mm:ss"

private val conversationTimeFormatters = ConcurrentHashMap<String, DateTimeFormatter>()

internal fun formatConversationTime(millis: Long, locale: Locale = Locale.getDefault()): String {
    val pattern = conversationTimePattern(locale)
    val formatter = conversationTimeFormatters.getOrPut("${locale.toLanguageTag()}|$pattern") {
        DateTimeFormatter.ofPattern(pattern, locale)
    }
    return formatter.format(
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()),
    )
}
