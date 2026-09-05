package com.psyche.memo.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
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
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Glasses
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Languages
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ChatViewModel
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

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
    onOpenHistory: () -> Unit,
    onOpenProviders: () -> Unit,
    pendingOpenConversation: androidx.compose.runtime.MutableState<String?>? = null,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var drawerOpen by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    val drawerWidth = with(LocalDensity.current) {
        windowInfo.containerSize.width.toDp() * 0.75f
    }
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    // Continuous-drag layer (Kelivo InteractiveDrawer): the chat content
    // slides right and the drawer sits flush beside it. The gesture detector
    // is hand-written so plain taps are never consumed (child clickables
    // keep working) — full-screen horizontal drags open/close the drawer.
    val contentOffsetPx = remember { mutableFloatStateOf(0f) }
    val dragVelocityPx = remember { mutableFloatStateOf(0f) }
    val lastMoveUptime = remember { mutableLongStateOf(0L) }
    var dragJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Drawer composed only while open/dragging/animating; unmounted fully
    // closed so it can never intercept taps aimed at the chat.
    var presenting by remember { mutableStateOf(false) }

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

    fun settleDrawer(open: Boolean, velocityPx: Float = 0f) {
        presenting = true
        dragJob?.cancel()
        dragJob = scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = contentOffsetPx.floatValue,
                targetValue = if (open) drawerWidthPx else 0f,
                initialVelocity = velocityPx,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 1f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
            ) { value, _ -> contentOffsetPx.floatValue = value }
            if (!open) {
                presenting = false
                contentOffsetPx.floatValue = 0f
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(drawerOpen, drawerWidthPx) {
        settleDrawer(drawerOpen)
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    // A conversation picked in ChatHistoryScreen lands here: select it.
    androidx.compose.runtime.LaunchedEffect(pendingOpenConversation?.value) {
        val id = pendingOpenConversation?.value
        if (!id.isNullOrEmpty()) {
            selectedConversationId = id
            temporaryActive = false
            pendingOpenConversation.value = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (selectedConversationId == null) {
            val latest = container.conversationDao.getAll().firstOrNull()
            selectedConversationId = latest?.id ?: run {
                val conv = Conversation.create(title = newChatTitle)
                container.conversationDao.insert(conv)
                conv.id
            }
        }
    }

    // Kelivo InteractiveDrawer (continuous look): chat content slides right
    // and the drawer sits flush beside it (no overlap). Full-screen horizontal
    // drag toggles; taps pass through untouched (hand-written detector).
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clipToBounds(),
    ) {
        // Chat content: slides right while the drawer opens (layer translate,
        // never recomposes), then sits flush beside the drawer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = contentOffsetPx.floatValue }
                .drawerDragGesture(
                    widthPx = drawerWidthPx,
                    contentOffsetPx = contentOffsetPx,
                    velocityPx = dragVelocityPx,
                    lastUptimeMillis = lastMoveUptime,
                    onPresent = { presenting = true },
                    onSettle = { open, vx ->
                        drawerOpen = open
                        settleDrawer(open, velocityPx = vx)
                    },
                ),
        ) {
            ChatContent(
                container = container,
                conversationId = if (temporaryActive) Conversation.TEMPORARY_ID
                else selectedConversationId ?: Conversation.TEMPORARY_ID,
                onOpenDrawer = { drawerOpen = true },
                onNew = ::handleTopBarAction,
                newActionToggleable = temporaryActive || currentIsEmpty(),
                modifier = modifier,
                isTemporary = temporaryActive,
                onOpenConversation = { id ->
                    selectedConversationId = id
                    temporaryActive = false
                },
            )
            // 12% scrim, alpha driven in the graphics layer (no recomposition).
            // Composed only while the drawer presents — a permanently-mounted
            // gesture layer sits in the hit path and can eat taps.
            if (presenting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val px = contentOffsetPx.floatValue
                            alpha = if (drawerWidthPx > 0f) 0.12f * px / drawerWidthPx else 0f
                        }
                        .background(MaterialTheme.colorScheme.onSurface)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null && contentOffsetPx.floatValue > 1f) {
                                    drawerOpen = false
                                }
                            }
                        },
                )
            }
        }
        // Drawer: layout-offset so the touch region follows the panel.
        if (presenting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .offset {
                        IntOffset(
                            (contentOffsetPx.floatValue - drawerWidthPx).roundToInt(),
                            0,
                        )
                    }
                    .background(MaterialTheme.colorScheme.surface)
                    .drawerDragGesture(
                        widthPx = drawerWidthPx,
                        contentOffsetPx = contentOffsetPx,
                        velocityPx = dragVelocityPx,
                        lastUptimeMillis = lastMoveUptime,
                        onPresent = {},
                        onSettle = { open, vx ->
                            drawerOpen = open
                            settleDrawer(open, velocityPx = vx)
                        },
                    ),
            ) {
                SideDrawerContent(
                    container = container,
                    selectedId = selectedConversationId,
                    onSelect = { id ->
                        selectedConversationId = id
                        drawerOpen = false
                    },
                    onNew = {
                        newConversation()
                        drawerOpen = false
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenHistory = onOpenHistory,
                    onCurrentDeleted = ::onCurrentDeleted,
                )
            }
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
    onOpenConversation: (String) -> Unit = {},
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
    val versionInfo by vm.versionInfo.collectAsState()

    val cs = MaterialTheme.colorScheme
    var showModelSheet by remember { mutableStateOf(false) }
    var showMiniMap by remember { mutableStateOf(false) }
    val timelineListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // ---- 消息操作批次状态（more sheet / 编辑 / regenerate 确认） ----
    var moreFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var editFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var regenerateFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copiedText = stringResource(UiR.string.chat_message_widget_copied_to_clipboard)
    val notImplementedText = stringResource(UiR.string.message_more_sheet_not_implemented)
    val unsupportedEditText = stringResource(UiR.string.user_message_edit_unsupported_snackbar)

    fun copyMessage(msg: ChatViewModel.UiMessage) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.content))
        com.psyche.memo.ui.snackbar.SnackbarManager.show(
            com.psyche.memo.ui.snackbar.AppNotification(
                message = copiedText,
                type = com.psyche.memo.ui.snackbar.NotificationType.SUCCESS,
            ),
        )
    }

    // 创建分支 = 复制会话语义定位到该条：新会话仅携带该条及其之前的消息
    // （drawer duplicateConversation 的事务化复制，按 message_order 截断）。
    fun forkAt(messageId: String) {
        if (isTemporary) return
        coroutineScope.launch {
            val newId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val src = container.conversationDao.get(conversationId)
                    ?: return@withContext null
                val anchor = container.messageDao.get(messageId)
                    ?: return@withContext null
                val dup = Conversation.create(
                    title = src.title,
                    assistantId = src.assistantId,
                )
                container.conversationDao.insert(dup)
                val rows = container.messageDao
                    .getAllForConversation(conversationId)
                    .filter { it.messageOrder <= anchor.messageOrder }
                container.messageDao.insertAllInTransaction(rows.map { m ->
                    ChatViewModel.forkCopy(m, dup.id)
                })
                dup.id
            }
            if (newId != null) onOpenConversation(newId)
        }
    }

    // ---- 助手名称/头像：与抽屉助手卡一致的数据源（assistant_rows） ----
    var assistantLabel by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(conversationId) {
        assistantLabel = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val aId = runCatching { container.conversationDao.get(conversationId)?.assistantId }
                .getOrNull()
            if (aId.isNullOrEmpty()) return@withContext null
            runCatching {
                com.psyche.memo.data.db.PayloadEntityDao(
                    container.database.readableDatabase,
                    "assistant_rows",
                    primaryKey = "assistant_key",
                ).get(aId)?.let { row ->
                    com.psyche.memo.data.model.Assistant.fromJsonString(
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                        row.payload,
                    ).name.trim().takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }
    }
    val resolvedAssistantLabel = assistantLabel
        ?: stringResource(UiR.string.message_export_sheet_assistant)

    // ---- 滚动导航 + 流式跟随（scroll_nav_buttons.dart / scroll_controller.dart） ----
    var navVisible by remember { mutableStateOf(false) }
    var autoStick by remember { mutableStateOf(true) }
    var navHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    androidx.compose.runtime.LaunchedEffect(timelineListState) {
        // 用户拖动 → 停止跟随并显示导航按钮；2s 无操作自动隐藏
        // （源码 scroll_controller.dart:374-425 handleUserScrollIntent /
        // _resetNavButtonsHideDelayMs=2000）。拖动停在底部则恢复跟随。
        timelineListState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    autoStick = false
                    navVisible = true
                    navHideJob?.cancel()
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel,
                -> {
                    autoStick = !timelineListState.canScrollForward
                    navHideJob?.cancel()
                    navHideJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(2000)
                        navVisible = false
                    }
                }
            }
        }
    }
    // 流式期间贴底跟随；用户上滑（autoStick=false）后停止。
    androidx.compose.runtime.LaunchedEffect(messages, streaming, autoStick) {
        if (streaming && autoStick && messages.isNotEmpty()) {
            timelineListState.animateScrollToItem(messages.lastIndex)
        }
    }
    fun jumpAdjacentQuestion(previous: Boolean) {
        val visible = timelineListState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull()?.index ?: return
        val userIdx = messages.withIndex().filter { it.value.role == "user" }.map { it.index }
        val target = if (previous) {
            userIdx.lastOrNull { it < current }
        } else {
            userIdx.firstOrNull { it > current }
        } ?: return
        coroutineScope.launch { timelineListState.animateScrollToItem(target) }
    }

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

    // Top-bar subtitle with friendly names — model_display_helper.dart
    // getModelDisplayInfo: provider display name first (cfg.name, else the raw
    // key), model display from override name > apiModelId > raw model id.
    val modelSubtitle = remember(providerId, modelId) {
        if (modelId.isEmpty() || providerId.isEmpty()) {
            ""
        } else {
            val cfg = container.providerConfig(providerId)
            val providerName = cfg?.name?.takeIf { it.isNotEmpty() } ?: providerId
            var modelDisplay = modelId
            val ov = cfg?.modelOverrides?.get(modelId) as? kotlinx.serialization.json.JsonObject
            if (ov != null) {
                val overrideName = (ov["name"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.trim()
                if (!overrideName.isNullOrEmpty()) {
                    modelDisplay = overrideName
                } else {
                    val apiId = ((ov["apiModelId"] ?: ov["api_model_id"])
                        as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
                    if (!apiId.isNullOrEmpty()) modelDisplay = apiId
                }
            }
            "$modelDisplay ($providerName)"
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
                        text = modelSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.clickable { showModelSheet = true },
                    )
                }
            }
            // Actions (memo mobile order, size/minSize from IosIconButton):
            // mini-map (22px icon) + new-chat / temporary-chat toggle (22px).
            // home_page.dart L1099-1102: empty conversation -> return, no sheet.
            IconButton(
                onClick = { if (messages.isNotEmpty()) showMiniMap = true },
                modifier = Modifier.size(44.dp),
            ) {
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
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(
                    state = timelineListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageRow(
                            msg = msg,
                            assistantLabel = resolvedAssistantLabel,
                            versionCount = versionInfo[msg.groupId]?.size ?: 1,
                            versionIndex = versionInfo[msg.groupId]
                                ?.let { it.indexOf(msg.version).coerceAtLeast(0) } ?: 0,
                            onPrevVersion = versionInfo[msg.groupId]?.let { versions ->
                                val idx = versions.indexOf(msg.version)
                                if (idx > 0) {
                                    { vm.switchVersion(msg.groupId, versions[idx - 1]) }
                                } else null
                            },
                            onNextVersion = versionInfo[msg.groupId]?.let { versions ->
                                val idx = versions.indexOf(msg.version)
                                if (idx in 0 until versions.size - 1) {
                                    { vm.switchVersion(msg.groupId, versions[idx + 1]) }
                                } else null
                            },
                            onCopy = { copyMessage(msg) },
                            onRegenerate = if (msg.role == "user") {
                                { regenerateFor = msg }
                            } else null,
                            onEdit = { editFor = msg },
                            onMore = { moreFor = msg },
                            onDelete = { vm.deleteVersion(msg.id) },
                        )
                    }
                }
                // 滚动导航面板（scroll_nav_buttons.dart）：贴输入栏上方右侧。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                ) {
                    com.psyche.memo.ui.chat.ScrollNavButtonsPanel(
                        visible = navVisible,
                        onScrollToTop = {
                            coroutineScope.launch { timelineListState.animateScrollToItem(0) }
                        },
                        onPreviousMessage = { jumpAdjacentQuestion(previous = true) },
                        onNextMessage = { jumpAdjacentQuestion(previous = false) },
                        onScrollToBottom = {
                            coroutineScope.launch {
                                timelineListState.animateScrollToItem(
                                    (messages.size - 1).coerceAtLeast(0),
                                )
                            }
                            autoStick = true
                        },
                    )
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

    if (showMiniMap) {
        MiniMapSheet(
            // UiMessage -> ChatMessage projection (mini map only reads
            // id/role/parts, matching _buildPairs' usage).
            messages = messages.map { m ->
                com.psyche.memo.data.model.ChatMessage(
                    id = m.id,
                    role = m.role,
                    parts = m.parts,
                    timestamp = 0L,
                    conversationId = "",
                    groupId = "",
                    messageOrder = 0,
                )
            },
            onDismiss = { showMiniMap = false },
            onJumpToMessage = { id ->
                showMiniMap = false
                val idx = messages.indexOfFirst { it.id == id }
                if (idx >= 0) coroutineScope.launch {
                    timelineListState.animateScrollToItem(idx)
                }
            },
        )
    }

    // ---- 消息操作批次:more sheet / 编辑 sheet / regenerate 确认 ----
    moreFor?.let { target ->
        val versions = versionInfo[target.groupId] ?: listOf(target.version)
        com.psyche.memo.ui.chat.MessageMoreSheet(
            isUserMessage = target.role == "user",
            canDeleteAllVersions = versions.size > 1,
            canCreateBranch = !isTemporary,
            onDismiss = { moreFor = null },
            onAction = { action ->
                moreFor = null
                when (action) {
                    com.psyche.memo.ui.chat.MessageMoreAction.SELECT_COPY,
                    com.psyche.memo.ui.chat.MessageMoreAction.RENDER_WEB_VIEW,
                    com.psyche.memo.ui.chat.MessageMoreAction.SHARE,
                    com.psyche.memo.ui.chat.MessageMoreAction.SELECT_MESSAGES,
                    -> com.psyche.memo.ui.snackbar.SnackbarManager.show(
                        com.psyche.memo.ui.snackbar.AppNotification(
                            message = notImplementedText,
                            type = com.psyche.memo.ui.snackbar.NotificationType.INFO,
                        ),
                    )
                    com.psyche.memo.ui.chat.MessageMoreAction.EDIT -> editFor = target
                    com.psyche.memo.ui.chat.MessageMoreAction.FORK -> forkAt(target.id)
                    com.psyche.memo.ui.chat.MessageMoreAction.DELETE_CURRENT_VERSION ->
                        vm.deleteVersion(target.id)
                    com.psyche.memo.ui.chat.MessageMoreAction.DELETE_ALL_VERSIONS ->
                        vm.deleteAllVersions(target.id)
                }
            },
        )
    }

    editFor?.let { target ->
        com.psyche.memo.ui.chat.MessageEditSheet(
            initialContent = target.content,
            onDismiss = { editFor = null },
            onConfirm = { content, shouldSend ->
                editFor = null
                if (content.isNotEmpty()) {
                    coroutineScope.launch {
                        val ok = vm.editMessage(target.id, content, shouldSend)
                        if (!ok) {
                            com.psyche.memo.ui.snackbar.SnackbarManager.show(
                                com.psyche.memo.ui.snackbar.AppNotification(
                                    message = unsupportedEditText,
                                    type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    regenerateFor?.let { target ->
        com.psyche.memo.ui.chat.RegenerateConfirmDialog(
            onDismiss = { regenerateFor = null },
            onConfirm = {
                regenerateFor = null
                vm.regenerate(target.id)
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: ChatViewModel.UiMessage,
    assistantLabel: String,
    versionCount: Int,
    versionIndex: Int,
    onPrevVersion: (() -> Unit)?,
    onNextVersion: (() -> Unit)?,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onEdit: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isUser = msg.role == "user"
    // User bubble max width = screen width * 0.75
    // (chat_message_widget.dart L1833/1853).
    val maxBubbleWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() * 0.75f
    }
    var showContextMenu by remember { mutableStateOf(false) }
    // 全屏图片查看器状态（image_viewer_page.dart 移动端路径）。
    var viewerState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // 引用来源 sheet 状态（citation_sources_sheet.dart）。
    var showCitations by remember { mutableStateOf(false) }
    // 翻译区折叠状态（chat_message_widget.dart translationExpanded）。
    var translationExpanded by remember(msg.id, msg.translation) { mutableStateOf(true) }
    // search_web / builtin_search 工具结果提取为引用来源
    // （chat_message_widget.dart _allSearchItems，从后往前、去重）。
    val searchItems = remember(msg.id, msg.parts) {
        com.psyche.memo.ui.chat.extractCitationItems(msg.parts)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // Header: name 13px α0.7 + timestamp 11px α0.5 (right-aligned),
            // matching chat_message_widget.dart. Name comes from the user
            // resource (UserProvider default 'User' in the original).
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(UiR.string.user_provider_default_user_name),
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
                // Assistant avatar: 32px primary α0.1 circle with the current
                // assistant's initial (drawer assistant-card data source).
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(cs.primary.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = assistantLabel.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = cs.primary,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = assistantLabel,
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
        // 长按浮层锚定在气泡上（chat_message_widget.dart:1812-1846 mobile
        // long-press → _showUserContextMenu）。
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .background(
                        color = if (isUser) cs.primary.copy(alpha = 0.08f)
                        else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .combinedClickable(
                        enabled = isUser,
                        onLongClick = { if (isUser) showContextMenu = true },
                        onClick = {},
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                // 图片附件（chat_message_widget.dart _buildAttachmentPreview
                // ImagePart 分支）：整组渲染，点击可跨图翻页查看。
                if (msg.parts.any { it is ImagePart }) {
                    com.psyche.memo.ui.chat.MessageImageAttachments(
                        parts = msg.parts,
                        onOpenViewer = { uris, index -> viewerState = uris to index },
                    )
                }
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
                        is ToolCallPart -> {
                            // 工具调用卡（chat_message_widget.dart _ToolCallItem
                            // 渲染层；解析失败保持原 payload 兜底文本）。
                            val toolPart = remember(part.payloadJson) {
                                com.psyche.memo.ui.chat.ToolUiPart.fromPayload(part.payloadJson)
                            }
                            if (toolPart != null) {
                                com.psyche.memo.ui.chat.ToolCallCard(part = toolPart)
                            } else {
                                Text(
                                    "‹tool_call›",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        is ImagePart -> Unit // 已整组渲染在气泡顶部
                        else -> Text("‹${part.kind}›", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (msg.parts.isEmpty() && msg.isStreaming) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(20.dp))
                }
                if (msg.failed) {
                    Text(
                        stringResource(UiR.string.generation_interrupted),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                    )
                }
                // 翻译显示分支（chat_message_widget.dart:3023-3180，显示层；
                // 翻译动作按钮属输入域批次）：primaryContainer 容器 + 可折叠
                // Languages 标题行 + 译文。
                if (!isUser && !msg.translation.isNullOrEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                cs.primaryContainer.copy(alpha = 0.28f),
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { translationExpanded = !translationExpanded }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                Lucide.Languages,
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.88f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(UiR.string.chat_message_widget_translation),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.onSurface.copy(alpha = 0.88f),
                                ),
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (translationExpanded) Lucide.ChevronDown else Lucide.ChevronRight,
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.88f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (translationExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = msg.translation,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.5.sp,
                                    lineHeight = 21.7.sp,
                                    color = cs.onSurface.copy(alpha = 0.85f),
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // 来源摘要卡（chat_message_widget.dart:3182-3189）。
                if (!isUser && searchItems.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    com.psyche.memo.ui.chat.CitationSourcesSummaryCard(
                        items = searchItems,
                        onTap = { showCitations = true },
                    )
                }
            }
            if (showContextMenu) {
                com.psyche.memo.ui.chat.UserContextMenu(
                    onCopy = onCopy,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onDismiss = { showContextMenu = false },
                )
            }
        }
        // 全屏图片查看器 + 引用来源 sheet（挂载于消息行级状态）。
        viewerState?.let { (uris, index) ->
            com.psyche.memo.ui.chat.ImageViewerOverlay(
                images = uris,
                initialIndex = index,
                onClose = { viewerState = null },
            )
        }
        if (showCitations) {
            com.psyche.memo.ui.chat.CitationSourcesSheet(
                items = searchItems,
                onDismiss = { showCitations = false },
            )
        }
        // Message actions (user messages): copy / regenerate / edit / more —
        // 28px rounded actions row, right-aligned below the bubble (kelivo
        // chat_message_widget.dart:1847-1974); assistant rows show the
        // version selector and token stats.
        val showVersionSwitcher = versionCount > 1
        if (isUser || showVersionSwitcher || msg.totalTokens != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isUser) {
                    MessageActionIcon(Lucide.Copy, "Copy", onClick = onCopy)
                    Spacer(Modifier.width(6.dp))
                    MessageActionIcon(
                        Lucide.RefreshCw,
                        "Regenerate",
                        onClick = { onRegenerate?.invoke() },
                        enabled = onRegenerate != null,
                    )
                    Spacer(Modifier.width(6.dp))
                    MessageActionIcon(Lucide.Pencil, "Edit", onClick = onEdit)
                    Spacer(Modifier.width(6.dp))
                    MessageActionIcon(Lucide.Ellipsis, "More", onClick = onMore)
                }
                if (showVersionSwitcher) {
                    if (isUser) Spacer(Modifier.width(6.dp))
                    com.psyche.memo.ui.chat.BranchSelector(
                        index = versionIndex,
                        total = versionCount,
                        onPrev = onPrevVersion,
                        onNext = onNextVersion,
                    )
                }
                if (!isUser && msg.totalTokens != null) {
                    // 源码 chat_message_widget.dart:3395-3405 —— Spacer 后
                    // 靠右的 TokenDisplayWidget。
                    Spacer(Modifier.weight(1f))
                    com.psyche.memo.ui.chat.TokenDisplay(
                        totalTokens = msg.totalTokens,
                        promptTokens = msg.promptTokens,
                        completionTokens = msg.completionTokens,
                        cachedTokens = msg.cachedTokens,
                        durationMs = msg.durationMs,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(cs.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = cs.onSurface.copy(alpha = if (enabled) 0.9f else 0.4f),
            modifier = Modifier.size(12.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 输入栏 1:1 常量与算法 —— 数值全部取自 Flutter 源码，逐项标注「源码文件:行号」
// ---------------------------------------------------------------------------

/** 源码 lib/theme/design_tokens.dart:31-37 —— AppSpacing */
private val SpacingXxs = 4.dp
private val SpacingXs = 8.dp
private val SpacingSm = 12.dp
private val SpacingMd = 16.dp

/** 源码 lib/shared/responsive/breakpoints.dart:5 —— AppBreakpoints.tablet = 900 */
private const val BREAKPOINT_TABLET_DP = 900f

/** 源码 lib/features/home/widgets/chat_input_bar.dart:247-248 —— 附件预览高度 */
private const val DOCUMENT_PREVIEW_HEIGHT_DP = 48f
private const val IMAGE_PREVIEW_HEIGHT_DP = 64f

/**
 * 源码 lib/features/home/widgets/chat_input_bar.dart:2569
 * baseChromeHeight = 120 // padding + action row + chrome buffer
 */
private const val BASE_CHROME_HEIGHT_DP = 120f

/** 源码 chat_input_bar.dart:2574 —— softCap = visibleHeight * 0.45 */
private const val SOFT_CAP_RATIO = 0.45f

/** 源码 chat_input_bar.dart:2577/2579 —— max(80.0, …) 下限 */
private const val MIN_INPUT_HEIGHT_DP = 80f

/** 源码 chat_input_bar.dart:2618-2619 / 2626 —— ClipRRect + BoxDecoration borderRadius: 20 */
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * 源码 chat_input_bar.dart:2620-2621
 * BackdropFilter(filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14))
 */
private val InputBackdropBlur = 14.dp

/** 源码 lib/core/providers/settings_provider.dart:5094-5095 —— 默认输入框背景透明度 */
private const val DEFAULT_INPUT_BG_OPACITY_LIGHT = 0.8236f
private const val DEFAULT_INPUT_BG_OPACITY_DARK = 0.7396f

/**
 * Flutter `Color.alphaBlend(foreground, background)` 的等价实现（source-over，
 * 非线性 sRGB，与 Flutter SDK 的整型算法一致）：
 *   backAlpha' = ba * (1 - fa); outAlpha = fa + backAlpha'
 *   channel    = (fc * fa + bc * backAlpha') / outAlpha
 *
 * 源码 lib/features/home/widgets/chat_input_bar.dart:284
 */
private fun alphaBlend(foreground: Color, background: Color): Color {
    val fa = foreground.alpha
    val ba = background.alpha
    if (fa == 0f) return background
    val backAlpha = ba * (1f - fa)
    val outAlpha = fa + backAlpha
    if (outAlpha == 0f) return Color.Transparent
    return Color(
        red = (foreground.red * fa + background.red * backAlpha) / outAlpha,
        green = (foreground.green * fa + background.green * backAlpha) / outAlpha,
        blue = (foreground.blue * fa + background.blue * backAlpha) / outAlpha,
        alpha = outAlpha,
    )
}

/**
 * 源码 lib/features/home/widgets/chat_input_bar.dart:260-285 —— _inputFillColor
 *
 * @param backgroundImageActive 移植版暂无聊天背景图能力，恒为 false，
 *   因此 backgroundRatio 分支（源码 :270-275）不会生效，仅保留结构。
 */
private fun inputFillColor(
    cs: androidx.compose.material3.ColorScheme,
    isDark: Boolean,
    backgroundImageActive: Boolean = false,
    lightOpacity: Float = DEFAULT_INPUT_BG_OPACITY_LIGHT,
    darkOpacity: Float = DEFAULT_INPUT_BG_OPACITY_DARK,
): Color {
    val configuredOpacity = (if (isDark) darkOpacity else lightOpacity).coerceIn(0f, 1f)
    val backgroundRatio = if (isDark) {
        0.545f / DEFAULT_INPUT_BG_OPACITY_DARK
    } else {
        0.5296f / DEFAULT_INPUT_BG_OPACITY_LIGHT
    }
    val targetOpacity = if (backgroundImageActive) {
        configuredOpacity * backgroundRatio
    } else {
        configuredOpacity
    }
    val overlayAlpha = if (isDark) {
        if (backgroundImageActive) 0.09f else 0.07f
    } else {
        0.02f
    }
    val overlayTint = (if (isDark) cs.onSurface else cs.primary).copy(alpha = overlayAlpha)
    val baseAlpha = ((targetOpacity - overlayAlpha) / (1f - overlayAlpha)).coerceIn(0f, 1f)
    val base = cs.surface.copy(alpha = baseAlpha)
    return alphaBlend(overlayTint, base).copy(alpha = targetOpacity)
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
    // 源码 chat_input_bar.dart:2547 —— theme.brightness == Brightness.dark。
    // 移植版没有暴露主题模式的 CompositionLocal，按 Material3 惯例由 surface 亮度判定
    // （浅色 surface 亮度 ≈0.96，深色 ≈0.05）。
    val isDark = cs.surface.luminance() < 0.5f

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    // 源码 chat_input_bar.dart:2558-2561
    //   size        = MediaQuery.sizeOf(context)
    //   viewInsets  = MediaQuery.viewInsetsOf(context)
    //   visibleHeight = size.height - viewInsets.bottom
    val visibleHeightDp = with(density) { windowInfo.containerSize.height.toDp().value } -
        WindowInsets.ime.getBottom(density) / density.density
    // 源码 chat_input_bar.dart:2560 —— isMobileLayout = size.width < AppBreakpoints.tablet
    val isMobileLayout =
        with(density) { windowInfo.containerSize.width.toDp() } < BREAKPOINT_TABLET_DP.dp

    // 源码 chat_input_bar.dart:2555-2556 / 2641-2642 —— 附件（图片 / 文档）内联预览。
    // 移植版当前没有附件数据，两个列表恒为空：预览高度按源码公式算得 0，
    // 结构保留，接入附件时只需让列表非空。
    val imageAttachments: List<String> = emptyList()
    val docAttachments: List<String> = emptyList()
    val hasImages = imageAttachments.isNotEmpty()
    val hasDocs = docAttachments.isNotEmpty()
    // 源码 chat_input_bar.dart:2562-2568
    val attachmentPreviewHeight = if (hasDocs || hasImages) {
        SpacingSm.value +
            (if (hasImages) IMAGE_PREVIEW_HEIGHT_DP else 0f) +
            (if (hasImages && hasDocs) SpacingXs.value else 0f) +
            (if (hasDocs) DOCUMENT_PREVIEW_HEIGHT_DP else 0f) +
            SpacingXxs.value
    } else {
        0f
    }

    // 源码 chat_input_bar.dart:2569-2581 —— maxInputHeight 计算（照搬）
    val maxInputHeightDp = if (isMobileLayout) {
        val available = visibleHeightDp - attachmentPreviewHeight - BASE_CHROME_HEIGHT_DP
        val softCap = visibleHeightDp * SOFT_CAP_RATIO
        if (available > 0) {
            val capped = minOf(softCap, available)
            minOf(available, maxOf(MIN_INPUT_HEIGHT_DP, capped))
        } else {
            maxOf(MIN_INPUT_HEIGHT_DP, softCap)
        }
    } else {
        Float.POSITIVE_INFINITY
    }
    // 源码 chat_input_bar.dart:2583-2586：只有 isMobileLayout 且高度有限且 > 0 才约束。
    val textFieldModifier = if (
        isMobileLayout && maxInputHeightDp.isFinite() && maxInputHeightDp > 0
    ) {
        Modifier.fillMaxWidth().heightIn(max = maxInputHeightDp.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    // 源码 chat_input_bar.dart:2588-2599
    //   SafeArea(top:false, left:false, right:false, bottom:true)
    //   + Padding.fromLTRB(AppSpacing.sm, AppSpacing.xxs, AppSpacing.sm, AppSpacing.xs)
    // union（而非叠加）：IME 高度已覆盖导航栏区域，叠加会把输入卡顶出可视区。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(
                start = SpacingSm,
                top = SpacingXxs,
                end = SpacingSm,
                bottom = SpacingXs,
            ),
    ) {
        // 源码 chat_input_bar.dart:2600-2602 Column(mainAxisSize: min)
        //   → 2614 Stack → 2618 ClipRRect(20) → 2620 BackdropFilter(14) → 2622 Container
        Box(modifier = Modifier.fillMaxWidth().clip(InputContainerShape)) {
            // 背景 / 模糊层。
            // 源码 chat_input_bar.dart:2620-2621 BackdropFilter(ImageFilter.blur(14, 14))。
            // Compose 没有 BackdropFilter 等价物，用 Modifier.blur 近似；该修饰符只在
            // API 31 (Android 12) 及以上生效，API 30 及以下为空实现，自动退化为「不加
            // 模糊」，不会崩溃。放在最底层，避免把容器内的文本 / 图标一起模糊掉。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(InputBackdropBlur)
                    // 源码 chat_input_bar.dart:2625 —— color: inputFillColor
                    .background(color = inputFillColor(cs, isDark), shape = InputContainerShape),
            )
            // 源码 chat_input_bar.dart:2639-2655 / 2830-2949 —— 容器内 Column：
            // ① 附件预览区 ② 输入区 ③ 底部按钮行
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 源码 chat_input_bar.dart:2628-2637
                    //   Border.all(width: 1,
                    //     color: isDark ? onSurface@0.10 : outline@0.20)
                    // 不随背景层模糊，保持 1px 描边清晰可见。
                    .border(
                        width = 1.dp,
                        color = if (isDark) {
                            cs.onSurface.copy(alpha = 0.10f)
                        } else {
                            cs.outline.copy(alpha = 0.20f)
                        },
                        shape = InputContainerShape,
                    ),
            ) {
                // ① 附件内联预览区（源码 chat_input_bar.dart:2641-2642）。
                //    移植版暂无附件数据（hasImages / hasDocs 恒为 false），该分支不渲染。

                // ② 输入区（源码 chat_input_bar.dart:2646-2654）
                //    Padding.fromLTRB(md, xxs, md, xs) + ConstrainedBox(maxHeight)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SpacingMd,
                            top = SpacingXxs,
                            end = SpacingMd,
                            bottom = SpacingXs,
                        ),
                ) {
                    TextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = textFieldModifier,
                        placeholder = { Text(stringResource(UiR.string.chat_input_bar_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!streaming) onSend() }),
                        // 源码 chat_input_bar.dart:2741 —— maxLines: 5（未展开状态）
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }

                // ③ 底部按钮行（源码 chat_input_bar.dart:2830-2949）
                //    Padding.fromLTRB(xs, 0, xs, xs)，spaceBetween：左侧工具 + 右侧 plus/mic/发送
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SpacingXs,
                            top = 0.dp,
                            end = SpacingXs,
                            bottom = SpacingXs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(modifier = Modifier.weight(1f)) {
                        InputIcon(
                            Lucide.Boxes,
                            stringResource(UiR.string.chat_input_bar_select_model_tooltip),
                            onSelectModel,
                            cs,
                        )
                        InputIcon(
                            Lucide.Globe,
                            stringResource(UiR.string.chat_input_bar_online_search_tooltip),
                            {},
                            cs,
                        )
                        InputIcon(
                            Lucide.Brain,
                            stringResource(UiR.string.chat_input_bar_reasoning_strength_tooltip),
                            {},
                            cs,
                        )
                        InputIcon(
                            Lucide.Hammer,
                            stringResource(UiR.string.chat_input_bar_mcp_servers_tooltip),
                            {},
                            cs,
                        )
                        InputIcon(
                            Lucide.Zap,
                            stringResource(UiR.string.chat_input_bar_quick_phrase_tooltip),
                            {},
                            cs,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InputIcon(
                            Lucide.Plus,
                            stringResource(UiR.string.chat_input_bar_more_tooltip),
                            {},
                            cs,
                        )
                        InputIcon(
                            Lucide.Mic,
                            stringResource(UiR.string.chat_input_bar_voice_input_tooltip),
                            {},
                            cs,
                        )
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


/**
 * Horizontal drag detector that never consumes plain taps: events flow until
 * the horizontal touch slop is exceeded, then the drag is consumed and the
 * drawer offset follows the finger. A plain tap (no slop) is left entirely
 * to the child clickables.
 */
private fun Modifier.drawerDragGesture(
    widthPx: Float,
    contentOffsetPx: androidx.compose.runtime.MutableFloatState,
    velocityPx: androidx.compose.runtime.MutableFloatState,
    lastUptimeMillis: androidx.compose.runtime.MutableLongState,
    onPresent: () -> Unit,
    onSettle: (open: Boolean, velocityPx: Float) -> Unit,
): Modifier = pointerInput(widthPx) {
    // Flutter's kTouchSlop is 18 logical pixels — much larger than the
    // Android default (8px physical on many devices). Real fingers always
    // drift a few pixels while tapping; with the small default every tap
    // would be mistaken for a drag and clicks would die. Align with the
    // original project's threshold.
    val slopPx = max(viewConfiguration.touchSlop, with(density) { 18.dp.toPx() })
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        velocityPx.floatValue = 0f
        var pastSlop = false
        var totalDx = 0f
        var lastUptime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (change.changedToUp()) break
            if (change.isConsumed) break
            val dx = change.positionChange().x
            if (!pastSlop) {
                totalDx += dx
                if (kotlin.math.abs(totalDx) > slopPx) {
                    pastSlop = true
                    onPresent()
                }
            }
            if (pastSlop) {
                change.consume()
                contentOffsetPx.floatValue =
                    (contentOffsetPx.floatValue + dx).coerceIn(0f, widthPx)
                val dtMs = (change.uptimeMillis - lastUptimeMillis.longValue).coerceIn(1L, 66L)
                velocityPx.floatValue = dx / dtMs * 1000f
                lastUptimeMillis.longValue = change.uptimeMillis
            } else {
                lastUptimeMillis.longValue = change.uptimeMillis
            }
        }
        if (pastSlop) {
            val vx = velocityPx.floatValue
            val open = if (kotlin.math.abs(vx) >= 365f) vx > 0f
            else contentOffsetPx.floatValue > widthPx / 2f
            onSettle(open, vx)
        }
    }
}
