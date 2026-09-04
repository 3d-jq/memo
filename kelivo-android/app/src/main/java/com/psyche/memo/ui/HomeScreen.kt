package com.psyche.memo.ui

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Glasses
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Map
import com.composables.icons.lucide.MessageCircleDashed
import com.composables.icons.lucide.MessageCirclePlus
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Zap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ChatViewModel
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen mirroring the Kelivo mobile layout:
 * ModalNavigationDrawer (search + assistant card + conversation list +
 * user bar) wrapping the chat content (transparent AppBar + timeline +
 * rounded frosted input bar).
 */
@Composable
fun HomeScreen(
    container: AppContainerImpl,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    var temporaryActive by remember { mutableStateOf(false) }

    val newChatTitle = stringResource(UiR.string.chat_service_default_conversation_title)

    /** True when the current view is an empty normal chat or a temporary chat. */
    fun currentIsEmpty(): Boolean =
        temporaryActive ||
            (selectedConversationId?.let { container.messageDao.count(it) == 0 } ?: false)

    // Conversation creation jumps straight into a fresh (persisted) chat.
    fun newConversation() {
        temporaryActive = false
        // Mirror chat_service.createDraftConversation: creating a new chat
        // while the current conversation is still empty replaces the empty
        // row instead of stacking "New Chat" conversations in the drawer.
        val currentId = selectedConversationId
        if (currentId != null &&
            currentId != Conversation.TEMPORARY_ID &&
            container.messageDao.count(currentId) == 0
        ) {
            container.conversationDao.delete(currentId)
        }
        val conv = Conversation.create(title = newChatTitle)
        container.conversationDao.insert(conv)
        selectedConversationId = conv.id
    }

    /**
     * Mirrors home_mobile_layout: while the current chat is empty the top-bar
     * action toggles Temporary Chat (in → back out via a normal new chat);
     * once the conversation has messages it becomes a plain "new chat".
     */
    fun handleTopBarAction() {
        if (temporaryActive) {
            newConversation()
            return
        }
        val currentId = selectedConversationId
        if (currentId != null && currentId != Conversation.TEMPORARY_ID &&
            container.messageDao.count(currentId) == 0
        ) {
            container.conversationDao.delete(currentId)
            selectedConversationId = null
            temporaryActive = true
            return
        }
        newConversation()
    }

    fun onCurrentDeleted() {
        // Mirror _handlePostDeleteNavigation: land on the most recent
        // conversation or a fresh one.
        temporaryActive = false
        val latest = container.conversationDao.getAll().firstOrNull()
        if (latest != null) {
            selectedConversationId = latest.id
        } else {
            newConversation()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (selectedConversationId == null) {
            val latest = container.conversationDao.getAll().firstOrNull()
            selectedConversationId = latest?.id ?: run {
                val conv = Conversation.create(title = "New chat")
                container.conversationDao.insert(conv)
                conv.id
            }
        }
    }

    // Material drawer (Rikkahub-identical): official component — edge swipe,
    // drag-to-close, scrim tap and back-button all handled by the framework.
    // 75% width + 12% scrim + flat panel to match Kelivo's proportions.
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        scrimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(0.dp),
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier.width(
                    LocalConfiguration.current.screenWidthDp.dp * 0.75f,
                ),
            ) {
                SideDrawerContent(
                    container = container,
                    selectedId = selectedConversationId,
                    onSelect = { id ->
                        selectedConversationId = id
                        scope.launch { drawerState.close() }
                    },
                    onNew = {
                        newConversation()
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = onOpenSettings,
                    onCurrentDeleted = ::onCurrentDeleted,
                )
            }
        },
    ) {
        // Opaque page background so the window never shows through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            ChatContent(
                container = container,
                conversationId = if (temporaryActive) Conversation.TEMPORARY_ID
                else selectedConversationId ?: Conversation.TEMPORARY_ID,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNew = ::handleTopBarAction,
                newActionToggleable = temporaryActive || currentIsEmpty(),
                modifier = modifier,
                isTemporary = temporaryActive,
            )
        }
    }
}

@Composable
fun ChatContent(
    container: AppContainerImpl,
    conversationId: String,
    onOpenDrawer: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    isTemporary: Boolean = false,
    newActionToggleable: Boolean = false,
) {
    val vm: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(container, conversationId),
    )
    val messages by vm.messages.collectAsState()
    val input by vm.input.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val providerId by vm.selectedProviderId.collectAsState()
    val modelId by vm.selectedModelId.collectAsState()

    val cs = MaterialTheme.colorScheme
    var showModelSheet by remember { mutableStateOf(false) }

    // Model choices from provider_rows payloads (kelivo showModelSelectSheet).
    val modelOptions = remember(container) {
        com.psyche.memo.data.db.PayloadEntityDao(
            container.database.readableDatabase,
            "provider_rows",
            primaryKey = "provider_key",
        ).getAll().flatMap { row ->
            val config = runCatching {
                com.psyche.memo.data.model.ProviderConfig.fromJsonString(
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                    row.payload,
                )
            }.getOrNull() ?: return@flatMap emptyList()
            config.models.map { id ->
                ModelOption(
                    providerId = config.id,
                    providerName = config.name,
                    modelId = id,
                    selected = id == modelId && config.id == providerId,
                )
            }
        }
    }

    Column(modifier = modifier) {
        // Transparent-ish AppBar: menu, title+model, new-conversation.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // memo's list.svg (20x16 two-bar drawer icon), drawn at 14dp.
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(44.dp)) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        com.psyche.memo.R.drawable.drawer_menu_list,
                    ),
                    contentDescription = null,
                    tint = cs.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isTemporary) stringResource(UiR.string.temporary_chat_title)
                    else vm.title.collectAsState().value.ifEmpty { UIStrings.newChatTitle() },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (modelId.isNotEmpty()) {
                    Text(
                        text = "$modelId ($providerId)",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.clickable { showModelSheet = true },
                    )
                }
            }
            // Actions (memo mobile order, size/minSize from IosIconButton):
            // mini-map (22px icon) + new-chat / temporary-chat toggle (22px).
            IconButton(onClick = { /* mini map */ }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Lucide.Map,
                    contentDescription = UIStrings.miniMapTooltip(),
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onNew, modifier = Modifier.size(44.dp)) {
                // memo mobile layout: while the current chat is empty the
                // action is a temporary-chat toggle (dashed = enter, check =
                // active); once the conversation has messages it is a plain
                // new-chat button. The active icon is the original
                // temporary_chat_checked.svg (lucide message-circle-check).
                when {
                    newActionToggleable && isTemporary -> Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            com.psyche.memo.R.drawable.temporary_chat_checked,
                        ),
                        contentDescription = UIStrings.temporaryChatToggleTooltip(),
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                    newActionToggleable -> Icon(
                        Lucide.MessageCircleDashed,
                        contentDescription = UIStrings.temporaryChatToggleTooltip(),
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                    else -> Icon(
                        Lucide.MessageCirclePlus,
                        contentDescription = UIStrings.newChatTitle(),
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Message timeline: empty temporary conversation shows the hat/glasses
        // hint inside the message area (kelivo's _TemporaryConversationEmptyState).
        if (messages.isEmpty() && isTemporary) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Lucide.Glasses,
                        contentDescription = null,
                        tint = cs.onSurface.copy(alpha = 0.42f),
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(UiR.string.temporary_chat_empty_message),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 21.75.sp,
                            color = cs.onSurface.copy(alpha = 0.68f),
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageRow(msg)
                }
            }
        }

        ChatInputBar(
            input = input,
            streaming = streaming,
            onInputChange = vm::updateInput,
            onSend = vm::send,
            onStop = vm::stop,
            onSelectModel = { showModelSheet = true },
        )
    }

    if (showModelSheet) {
        ModelSelectSheet(
            container = container,
            options = modelOptions,
            onSelect = { option ->
                vm.selectProvider(option.providerId, option.modelId)
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

@Composable
private fun MessageRow(msg: ChatViewModel.UiMessage) {
    val cs = MaterialTheme.colorScheme
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // Header: name 13px α0.7 + timestamp 11px α0.5 (right-aligned),
            // matching chat_message_widget.dart.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface.copy(alpha = 0.7f),
                    ),
                )
                Text(
                    text = timeStr(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = cs.onSurface.copy(alpha = 0.5f),
                    ),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Assistant avatar: 32px primary α0.1 circle with initial.
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(cs.primary.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "A",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = cs.primary,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Assistant",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface.copy(alpha = 0.7f),
                        ),
                    )
                    Text(
                        text = timeStr(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            color = cs.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = if (isUser) cs.primary.copy(alpha = 0.08f)
                    else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            for (part in msg.parts) {
                when (part) {
                    is TextPart -> com.psyche.memo.ui.markdown.MarkdownText(
                        markdown = part.text,
                        baseFontSize = 15.7f,
                        baseLineHeight = 23.55f,
                    )
                    is ReasoningPart -> com.psyche.memo.ui.markdown.ThinkingCard(
                        thinking = part.text,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is ToolCallPart -> Text(
                        "Tool: ${part.payloadJson.take(80)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    else -> Text("‹${part.kind}›", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (msg.parts.isEmpty() && msg.isStreaming) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(20.dp))
            }
            if (msg.failed) {
                Text(
                    "⚠ Generation failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                )
            }
        }
        // Message actions (user messages): copy / regenerate / edit — 28px
        // rounded actions row, right-aligned below the bubble (kelivo).
        if (isUser) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageActionIcon(Lucide.Copy, "Copy") {}
                Spacer(Modifier.width(6.dp))
                MessageActionIcon(Lucide.RefreshCw, "Regenerate") {}
                Spacer(Modifier.width(6.dp))
                MessageActionIcon(Lucide.Pencil, "Edit") {}
            }
        }
    }
}

@Composable
private fun MessageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(cs.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = cs.onSurface,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    streaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Mirrors kelivo ChatInputBar: one rounded frosted container which holds
    // the TextField on top and an action row (spaceBetween: left tools, right
    // plus / mic / circular send) at the bottom.
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surface.copy(alpha = 0.53f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, cs.onSurface.copy(alpha = 0.10f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // SafeArea(bottom) equivalent: keep the input bar above the
            // navigation bar (kelivo's ChatInputBar behaviour).
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // Input field (top).
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(UiR.string.chat_input_bar_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!streaming) onSend() }),
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            // Bottom action row: left tools, right plus / mic / send.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    InputIcon(Lucide.Boxes, "Model", onSelectModel, cs)
                    InputIcon(Lucide.Globe, "Search", {}, cs)
                    InputIcon(Lucide.Brain, "Reasoning budget", {}, cs)
                    InputIcon(Lucide.Hammer, "MCP servers", {}, cs)
                    InputIcon(Lucide.Zap, "Quick phrases", {}, cs)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InputIcon(Lucide.Plus, "More tools", {}, cs)
                    InputIcon(Lucide.Mic, "Voice input", {}, cs)
                    FilledIconButton(
                        onClick = if (streaming) onStop else onSend,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = if (streaming) Lucide.CircleStop else Lucide.ArrowUp,
                            contentDescription = if (streaming) "Stop" else "Send",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    cs: androidx.compose.material3.ColorScheme,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = cs.onSurface.copy(alpha = 0.54f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun timeStr(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(millis))
}
