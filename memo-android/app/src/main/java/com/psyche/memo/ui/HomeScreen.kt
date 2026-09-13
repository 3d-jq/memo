package com.psyche.memo.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Volume2
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
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Zap
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ChatViewModel
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.chat.AskUserInteractionService
import com.psyche.memo.ui.chat.AskUserResult
import com.psyche.memo.ui.chat.ToolApprovalService
import com.psyche.memo.ui.chat.ToolUiPart
import com.psyche.memo.ui.chat.checkpointPart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    onOpenBackup: () -> Unit = {},
    onOpenHistory: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSearchServices: () -> Unit = {},
    onOpenWorldBookPage: () -> Unit = {},
    onOpenTranslate: () -> Unit = {},
    onEditAssistant: (String) -> Unit = {},
    onManageTags: (String) -> Unit = {},
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
    // Publish the open conversation so the assistant memory tab can organize it.
    LaunchedEffect(selectedConversationId) {
        container.setCurrentConversation(selectedConversationId)
    }
    var temporaryActive by remember { mutableStateOf(false) }

    // 顶栏标题刷新信号：抽屉改写了当前会话标题（重命名 / 重新生成标题）后自增，
    // ChatContent 观察到变化即让 ChatViewModel 重读库里的标题。对齐 Flutter 端
    // 共享 _conversationsCache + notifyListeners 的自动同步语义。
    var titleRefreshTick by remember { mutableStateOf(0) }

    val newChatTitle = stringResource(UiR.string.chat_service_default_conversation_title)

    /** True when the current view is an empty normal chat or a temporary chat. */
    fun currentIsEmpty(): Boolean =
        temporaryActive ||
            (selectedConversationId?.let { container.messageDao.count(it) == 0 } ?: false)

    // Conversation creation jumps straight into a fresh (persisted) chat.
    // 新会话标记：ChatViewModel 创建时注入助手的预设对话（一次性的，选旧
    // 会话/临时会话为 false）。home_view_model.dart L993-1023。
    var pendingPresetInject by remember { mutableStateOf(false) }

    fun newConversation() {
        temporaryActive = false
        pendingPresetInject = true
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
        // draft 语义（chat_service.createDraftConversation L1869：只在内存，不落库）——
        // 一条消息都没发的空会话不该出现在历史列表里，等首条消息发送时才写
        // conversation_rows（见 ChatViewModel.ensureConversationRow）。
        val conv = Conversation.create(
            title = newChatTitle,
            assistantId = container.currentAssistantId.value,
        )
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
        // side_drawer.dart:848-880 `_handlePostDeleteNavigation`：删掉当前会话后
        // 优先「新建会话」（display_new_chat_after_delete_v1 打开），否则落到最近的
        // 一条；都没有才新建。
        temporaryActive = false
        val preferNewChat = container.preferenceRepository
            .readJson("display_new_chat_after_delete_v1") == "1"
        if (preferNewChat) {
            newConversation()
            return
        }
        val latest = container.conversationDao.getAll().firstOrNull()
        if (latest != null) {
            selectedConversationId = latest.id
        } else {
            newConversation()
        }
    }

    val drawerHaptics = LocalHapticsSettings.current
    val drawerView = LocalView.current
    fun settleDrawer(open: Boolean, velocityPx: Float = 0f) {
        // home_page_controller.dart L2364-2382: pulse when the drawer finishes
        // opening or closing, gated by the "on sidebar" switch.
        if (drawerHaptics.onDrawer) Haptics.drawerPulse(drawerView)
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
            // `display_new_chat_on_launch_v1`（默认**开**，home_page_controller.dart:782-786
            // `initChat`）：开启时每次启动都新建会话；关闭时回到最近一条。两种情况下
            // 都没有历史就开一个 draft（不入库，发首条消息才落库）。
            val newChatOnLaunch = container.preferenceRepository
                .readJson("display_new_chat_on_launch_v1")
                ?.let { it == "1" || it == "true" } ?: true
            val latest = if (newChatOnLaunch) {
                null
            } else {
                container.conversationDao.getAll().firstOrNull()
            }
            selectedConversationId = latest?.id
                ?: Conversation.create(
                    title = newChatTitle,
                    assistantId = container.currentAssistantId.value,
                ).id
        }
    }

    // Kelivo InteractiveDrawer (continuous look): chat content slides right
    // and the drawer sits flush beside it (no overlap). Full-screen horizontal
    // drag toggles; taps pass through untouched (hand-written detector).
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    // 全屏手势只挂这一处：主内容和抽屉是兄弟节点，各挂一份会让同一次拖动被两个
    // pointerInput 同时处理（offset 加两次、settle 触发两次 → 抖动）。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clipToBounds()
            .drawerDragGesture(
                widthPx = drawerWidthPx,
                contentOffsetPx = contentOffsetPx,
                velocityPx = dragVelocityPx,
                lastUptimeMillis = lastMoveUptime,
                isDrawerOpen = { drawerOpen },
                onDragStart = { dragJob?.cancel() },
                onPresent = { presenting = true },
                onSettle = { open, vx ->
                    drawerOpen = open
                    settleDrawer(open, velocityPx = vx)
                },
            ),
    ) {
        // Chat content: slides right while the drawer opens (layer translate,
        // never recomposes), then sits flush beside the drawer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = contentOffsetPx.floatValue },
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
                onOpenSearchServices = onOpenSearchServices,
                onOpenWorldBookPage = onOpenWorldBookPage,
                titleRefreshTick = titleRefreshTick,
                injectPresets = pendingPresetInject,
            )
            // 12% scrim, alpha driven in the graphics layer (no recomposition).
            // 常驻组合：之前用 `if (presenting)` 插拔节点，开合瞬间要新建布局
            // （抽屉里还带着整套会话列表），表现为"一开始拉就抖一下"。现在只靠
            // alpha 驱动；关闭时不消费点击，所以不会挡住下面的内容。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val px = contentOffsetPx.floatValue
                        alpha = if (drawerWidthPx > 0f) 0.12f * px / drawerWidthPx else 0f
                    }
                    .background(MaterialTheme.colorScheme.onSurface)
                    // 只有真的拉开过（presenting）才挂手势处理器：pointerInput 的
                    // 有无不影响布局，所以不会引起插拔抖动；关闭态则完全不参与命中，
                    // 点击照常落到聊天页（此前无条件挂载 + 无条件 awaitFirstDown 会
                    // 让整屏点击失效）。
                    .then(
                        if (drawerOpen) {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val up = waitForUpOrCancellation()
                                    if (up != null && contentOffsetPx.floatValue > 1f) {
                                        down.consume()
                                        drawerOpen = false
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        // Drawer: 常驻组合，layout-offset 驱动（关闭时整块停在屏幕左外）。
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
                .background(MaterialTheme.colorScheme.surface),
        ) {
                SideDrawerContent(
                    container = container,
                    open = presenting,
                    selectedId = selectedConversationId,
                    onSelect = { id, closeDrawer ->
                        selectedConversationId = id
                        if (closeDrawer) drawerOpen = false
                    },
                    onNew = { closeDrawer ->
                        newConversation()
                        if (closeDrawer) drawerOpen = false
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenBackup = onOpenBackup,
                    onOpenHistory = onOpenHistory,
                    onCurrentDeleted = ::onCurrentDeleted,
                    onOpenTranslate = onOpenTranslate,
                    onEditAssistant = onEditAssistant,
                    onManageTags = onManageTags,
                    onConversationTitleChanged = { id ->
                        // 仅当被改的会话正是当前展示的会话时刷新顶栏
                        // （home_view_model.dart L1531 `currentConversation?.id == convo.id`）。
                        if (com.psyche.memo.common.TitleText.shouldRefreshCurrent(id, selectedConversationId)) {
                            titleRefreshTick++
                        }
                    },
                )
        }
    }
}

private fun loadQuickPhrases(container: AppContainerImpl): List<com.psyche.memo.data.model.QuickPhrase> {
    val repo = com.psyche.memo.data.repo.QuickPhraseRepository(container.database.writableDatabase)
    val assistant = container.currentAssistant()
    return repo.globalPhrases() + if (assistant != null) repo.forAssistant(assistant.id) else emptyList()
}

/**
 * 消息头/顶栏用哪个助手（assistant_rows 主键）：会话行绑定的助手优先；**新建会话是
 * draft**（`ChatViewModel.ensureConversationRow` 要等首条消息落库才写行），行还不存在
 * 时回落到当前助手 —— 原版 draft 在内存里就带着 `assistantId`，等价于
 * `currentConversation.assistantId`（chat_message_widget 的 assistant 由
 * AssistantProvider.currentAssistant 提供）。
 */
internal fun headerAssistantId(
    conversationAssistantId: String?,
    currentAssistantId: String?,
): String? =
    conversationAssistantId?.takeIf { it.isNotEmpty() }
        ?: currentAssistantId?.takeIf { it.isNotEmpty() }

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
    onOpenSearchServices: () -> Unit = {},
    onOpenWorldBookPage: () -> Unit = {},
    titleRefreshTick: Int = 0,
    injectPresets: Boolean = false,
) {
    val vm: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(container, conversationId, injectPresets = injectPresets),
    )
    // 抽屉改写了本会话标题 → 让 vm 重读库里的标题刷新顶栏（首次 tick=0 不触发）。
    androidx.compose.runtime.LaunchedEffect(titleRefreshTick) {
        if (titleRefreshTick > 0) vm.refreshTitle()
    }
    val messages by vm.messages.collectAsState()
    // 最后一条助手消息的 id。原来每个 LazyColumn item 内都要
    // `messages.lastOrNull { it.role == "assistant" }`（每条 O(n) → O(n²)），
    // 而且 item 闭包捕获整个 messages 列表，流式时每帧更新都会让所有可见行重组。
    // 这里算一次，item 内只比较 id 值。
    val lastAssistantId = remember(messages) {
        messages.lastOrNull { it.role == "assistant" }?.id
    }
    val suggestions by vm.suggestions.collectAsState()
    val input by vm.input.collectAsState()
    val streaming by vm.streaming.collectAsState()
    // 上下文压缩：进行中 → 消息流末尾的扫光分隔线；占用 → 输入栏上方的 2dp 细条。
    val compacting by vm.compacting.collectAsState()
    val contextUsage by vm.contextUsage.collectAsState()
    val streamingMessageId = messages.lastOrNull { it.isStreaming }?.id
    val providerId by vm.selectedProviderId.collectAsState()
    val modelId by vm.selectedModelId.collectAsState()
    val versionInfo by vm.versionInfo.collectAsState()
    // 思考卡 / 工具卡的 6 个显示开关（settings_provider.dart display_*）。走
    // SharedPreferences 直读，从显示设置页返回时 NavHost 重建本页即拿到新值。
    val timelineSettings = remember {
        com.psyche.memo.ui.chat.ChatTimelineSettings.fromPrefs { key ->
            container.preferenceRepository.readJson(key)
        }
    }
    // home_page.dart:1285-1288 —— 建议气泡的外层门控：建议生成被禁用时，
    // 即便会话里仍存着上一轮的建议也立刻隐藏（原项目不删库，只门控展示）。
    // 与上面 display_* 同样是「返回本页即重建」的直读；未写过该键时按
    // settings_provider.dart:923 回退为 false（默认未启用）。
    val suggestionsEnabled = remember {
        com.psyche.memo.DefaultModelPrefs.parseJsonBool(
            container.preferenceRepository.readJson(
                com.psyche.memo.DefaultModelPrefs.SUGGESTION_GENERATION_ENABLED_V1,
            ),
        )
    }
    // 工具执行服务（tool_approval_service / ask_user_interaction_service）—— 审批卡、
    // ask-user 卡与时间线可见性都从这里取状态。
    val approvalService = container.toolApprovalService
    val askUserService = container.askUserInteractionService
    // 自动滚动（scroll_controller.dart）：总开关默认开；用户拖动后
    // `autoScrollIdleSeconds` 秒内的跟随暂停（默认 8）。
    val autoScrollEnabled = remember {
        container.preferenceRepository.readJson("display_auto_scroll_enabled_v1")
            ?.let { it == "1" } ?: true
    }
    val autoScrollIdleSeconds = remember {
        container.preferenceRepository.readJson("display_auto_scroll_idle_seconds_v1")
            ?.toIntOrNull() ?: 8
    }
    // 长粘贴转文件（display_long_paste_as_file_v1 + 阈值，默认开 / 5000 字素）。
    val longPaste = remember {
        com.psyche.memo.ui.chat.LongPasteSettings.fromPrefs { key ->
            container.preferenceRepository.readJson(key)
        }
    }
    // 消息导航按钮三态（display_mobile_message_nav_buttons_mode_v1，默认 scroll）。
    val navButtonsMode = remember {
        container.preferenceRepository.readJson("display_mobile_message_nav_buttons_mode_v1")
            ?.trim()?.trim('"')?.takeIf { it == MOBILE_NAV_ALWAYS || it == MOBILE_NAV_NEVER }
            ?: MOBILE_NAV_SCROLL
    }
    val chatHaptics = LocalHapticsSettings.current
    val chatView = LocalView.current

    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var showModelSheet by remember { mutableStateOf(false) }
    var showReasoningSheet by remember { mutableStateOf(false) }
    var reasoningBudget by remember { mutableStateOf(com.psyche.memo.ui.chat.readBudget(container)) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var quickPhrases by remember { mutableStateOf<List<com.psyche.memo.data.model.QuickPhrase>?>(null) }
    var showInstructionSheet by remember { mutableStateOf(false) }
    var showWorldBookSheet by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    var showMcpSheet by remember { mutableStateOf(false) }
    // ---- 消息多选（home_page_controller ChatSelectionMode）----
    var selecting by remember { mutableStateOf(false) }
    var selectionDeleteMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selShowThinkingTools by remember { mutableStateOf(false) }
    var selShowThinkingContent by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var exportPending by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    fun finishExport(uri: android.net.Uri?) {
        val pending = exportPending
        exportPending = null
        if (uri == null || pending == null) return
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(pending.second.toByteArray(Charsets.UTF_8))
            }
        }
        selecting = false
        selectedIds = emptySet()
    }
    val exportMdLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> finishExport(uri) }
    val exportTxtLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> finishExport(uri) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var worldBooksAvailable by remember { mutableStateOf(false) }
    var showOcrPrompt by remember { mutableStateOf(false) }
    var ocrSettings by remember {
        mutableStateOf(com.psyche.memo.provider.OcrService.settingsOf(container.preferenceRepository))
    }
    val attachments by vm.attachments.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 附件选取（bottom_tools_sheet → file_upload_service）：URI 先拷进 upload
    // 目录再进待发列表，发送后并入用户消息 parts。图片同时过画质管线
    // （image_upload_quality_v1 五档 → quality / maxLongEdge / 透明闸门）。
    val imageCompress = remember {
        com.psyche.memo.provider.ImageCompressConfig.fromPrefs { key ->
            container.preferenceRepository.readJson(key)
        }
    }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val imported = uris.mapNotNull {
                    com.psyche.memo.provider.AttachmentStore.import(context, it, imageCompress)
                }
                vm.addAttachments(imported)
            }
        }
    }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val cameraUri = remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val file = cameraFile
        cameraFile = null
        if (ok && file != null && file.length() > 0) {
            coroutineScope.launch {
                val attachment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.psyche.memo.provider.AttachmentStore.fromCapturedFile(file, imageCompress)
                }
                vm.addAttachments(listOf(attachment))
            }
        }
    }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val imported = uris.mapNotNull {
                    com.psyche.memo.provider.AttachmentStore.import(context, it, imageCompress)
                }
                vm.addAttachments(imported)
            }
        }
    }
    // 语音输入执行器（chat_input_bar.dart 的分派）：选中的云端 ASR 服务已配置时
    // 走网络识别（CloudAsrService + AudioRecord），否则回系统 SpeechRecognizer。
    // 应用上下文持有，避免持有 Activity 导致的 SpeechRecognizer 泄漏。
    val voiceAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val voiceInput = remember {
        com.psyche.memo.ui.chat.VoiceInputController(
            context = voiceAppContext,
            cloudOptions = { selectedCloudAsrService(container) },
            httpClient = container.httpClient,
        )
    }
    // 云端 ASR 需要运行时 RECORD_AUDIO 授权（原版由 permission_handler 申请）。
    val voiceFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceFocusManager.clearFocus()
            voiceInput.start()
        }
    }
    var showMiniMap by remember { mutableStateOf(false) }
    val timelineListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // ---- 消息操作批次状态（more sheet / 编辑 / regenerate 确认） ----
    var moreFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var editFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var regenerateFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var selectCopyFor by remember { mutableStateOf<String?>(null) }
    var htmlPreviewFor by remember { mutableStateOf<com.psyche.memo.ui.chat.HtmlPreviewRequest?>(null) }

    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val copiedText = stringResource(UiR.string.chat_message_widget_copied_to_clipboard)
    val notImplementedText = stringResource(UiR.string.message_more_sheet_not_implemented)
    val unsupportedEditText = stringResource(UiR.string.user_message_edit_unsupported_snackbar)
    val noTranslateModelText = stringResource(UiR.string.home_page_please_setup_translate_model)

    /**
     * 翻译结果反馈（translation_service.dart TranslationResult → UI）：
     * 仅未配置翻译模型时弹提示（home_page_please_setup_translate_model）；
     * 成功/清除直接反映在翻译区，无额外 toast。
     */
    val translateFeedback: (String) -> Unit = { result ->
        if (result == "no_model") {
            com.psyche.memo.ui.snackbar.SnackbarManager.show(
                com.psyche.memo.ui.snackbar.AppNotification(
                    message = noTranslateModelText,
                    type = com.psyche.memo.ui.snackbar.NotificationType.INFO,
                ),
            )
        }
    }
    // 翻译中占位文案（home_page_controller onTranslationStarted 写入消息的
    // homePageTranslating）；在 composable 作用域解析一次，供 onTranslate 回调使用。
    val translatingLabel = stringResource(UiR.string.home_page_translating)

    fun copyMessage(msg: ChatViewModel.UiMessage) {
        clipboardScope.launch {
            clipboard.setClipEntry(
                androidx.compose.ui.platform.ClipEntry(
                    android.content.ClipData.newPlainText("", msg.content),
                ),
            )
        }
        com.psyche.memo.ui.snackbar.SnackbarManager.show(
            com.psyche.memo.ui.snackbar.AppNotification(
                message = copiedText,
                type = com.psyche.memo.ui.snackbar.NotificationType.SUCCESS,
            ),
        )
    }

    // 创建分支 = 复制会话语义定位到该条：新会话仅携带该条及其之前的消息
    // （drawer duplicateConversation 的事务化复制，按 message_order 截断）。
    //
    // `chat_fork_keep_message_versions_v1`（默认关，chat_service.dart:3484-3543）：
    // - 开 → `forkConversationWithVersions`：按**分组首条** order 截断，保留每个
    //   分组的所有版本（分组 id 重新映射，版本号原样保留）；
    // - 关 → 每个分组只留当前可见（回退最高）版本，拍平成各自独立的新组（version 0）。
    fun forkAt(messageId: String) {
        if (isTemporary) return
        val visibleIds = messages.map { it.id }.toSet()
        coroutineScope.launch {
            val newId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val src = container.conversationDao.get(conversationId)
                    ?: return@withContext null
                val anchor = container.messageDao.get(messageId)
                    ?: return@withContext null
                val keepVersions = container.preferenceRepository
                    .readJson("chat_fork_keep_message_versions_v1") == "1"
                val dup = Conversation.create(
                    title = src.title,
                    assistantId = src.assistantId,
                )
                container.conversationDao.insert(dup)
                val all = container.messageDao.getAllForConversation(conversationId)
                // 分组首条 order：截断与「保留哪些分组」都按它判断，而不是被点那条的 order。
                val firstOrder = LinkedHashMap<String, Int>()
                for (row in all) {
                    val gid = row.groupId.ifEmpty { row.id }
                    val prev = firstOrder[gid]
                    if (prev == null || row.messageOrder < prev) firstOrder[gid] = row.messageOrder
                }
                val anchorGroupId = anchor.groupId.ifEmpty { anchor.id }
                val anchorFirst = firstOrder[anchorGroupId] ?: anchor.messageOrder
                val keptGroups = firstOrder.filterValues { it <= anchorFirst }.keys
                val copies: List<com.psyche.memo.data.model.ChatMessage> = if (keepVersions) {
                    val groupMap = keptGroups.associateWith { com.psyche.memo.data.model.ChatMessage.newId() }
                    all.filter { (it.groupId.ifEmpty { it.id }) in keptGroups }
                        .map { ChatViewModel.forkCopy(it, dup.id, groupId = groupMap[it.groupId.ifEmpty { it.id }]) }
                } else {
                    val chosen = LinkedHashMap<String, com.psyche.memo.data.model.ChatMessage>()
                    for (row in all) {
                        val gid = row.groupId.ifEmpty { row.id }
                        if (gid !in keptGroups) continue
                        val cur = chosen[gid]
                        chosen[gid] = when {
                            cur == null -> row
                            row.id in visibleIds -> row
                            cur.id in visibleIds -> cur
                            row.version > cur.version -> row
                            else -> cur
                        }
                    }
                    chosen.values.mapIndexed { index, row ->
                        ChatViewModel.forkCopy(
                            row,
                            dup.id,
                            groupId = com.psyche.memo.data.model.ChatMessage.newId(),
                            version = 0,
                            messageOrder = index,
                        )
                    }
                }
                container.messageDao.insertAllInTransaction(copies)
                dup.id
            }
            if (newId != null) onOpenConversation(newId)
        }
    }

    // ---- 助手名称/头像：与抽屉助手卡一致的数据源（assistant_rows） ----
    // 整行读出来（不只 name）：消息头要按 chat_message_widget.dart:2787-2802
    // 的规则在「助手头像」和「模型图标」之间二选一。
    //
    // 会话行绑定的助手优先；**新建会话是 draft**（`ensureConversationRow` 要等首条
    // 消息落库才写行）→ 行还不存在，此时与原版 `currentConversation.assistantId`
    // 等价的是「当前助手」。原来写成 `LaunchedEffect(conversationId)` 一次性读，
    // draft 那一刻读到 null 就再也不会重读 ⇒ 新建对话聊起来后头部一直显示兜底名
    // （"助手"）+ 模型图标，切出去再进来才正常。
    val currentAssistantId by container.currentAssistantId.collectAsState()
    val assistantRow = remember(conversationId, messages.isNotEmpty(), currentAssistantId) {
        runCatching {
            val aId = headerAssistantId(
                conversationAssistantId = container.conversationDao.get(conversationId)?.assistantId,
                currentAssistantId = currentAssistantId,
            )
            aId?.let { container.assistantStore.get(it) }
        }.getOrNull()
    }
    // settings_provider.dart:1069 —— display_show_model_icon_v1 默认 true。
    // 设置页（ChatItemDisplaySettingsScreen）的 display_* 开关走
    // readLocal/writeLocal（SharedPreferences），这里必须同样读 readLocal，
    // 否则永远只能拿到默认值（此前误用 readJson，开关实际是失效的）。
    val showModelIcon = remember(conversationId) {
        container.preferenceRepository.readJson("display_show_model_icon_v1")
            ?.let { it == "1" }
            ?: true
    }
    // 用户资料（user_provider.dart）：消息头与抽屉用户栏共用同一份。
    val userProfile by container.userProfileStore.profile
    val resolvedAssistantLabel = assistantRow?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: stringResource(UiR.string.message_export_sheet_assistant)

    // ---- 滚动导航 + 流式跟随（scroll_nav_buttons.dart / scroll_controller.dart） ----
    var navVisible by remember { mutableStateOf(false) }
    var autoStick by remember { mutableStateOf(true) }
    var navHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var idleStickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
                    // scroll_controller.dart:384-392 handleUserScrollIntent 的空闲
                    // 计时：拖动结束后等 `autoScrollIdleSeconds` 再判一次贴底
                    // （惯性滑到底的情况，收手那一刻还不在底部）。
                    idleStickJob?.cancel()
                    idleStickJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(autoScrollIdleSeconds.coerceAtLeast(1) * 1000L)
                        autoStick = !timelineListState.canScrollForward
                    }
                    navHideJob?.cancel()
                    navHideJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(2000)
                        navVisible = false
                    }
                }
            }
        }
    }
    // 后台预热 Markdown 解析缓存（列表稳定 / 非流式时）：滚动到任意一条都命中
    // 缓存，不会再有"首帧在主线程同步解析 CommonMark"的那一下卡顿。放在 Default
    // 线程，和渲染不抢主线程。
    androidx.compose.runtime.LaunchedEffect(messages, streaming) {
        if (streaming || messages.isEmpty()) return@LaunchedEffect
        val texts = messages.takeLast(MARKDOWN_PRELOAD_MESSAGES).flatMap { m ->
            m.parts.filterIsInstance<com.psyche.memo.data.model.TextPart>().map { it.text }
        }
        if (texts.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.psyche.memo.ui.markdown.preloadMarkdown(texts.map { it to true })
        }
    }

    // 进入会话先落到最新一条 —— RikkaHub ChatPage.kt:170-183 同款：首次拿到
    // 非空消息时滚到底（requestScrollToItem 传入末条 index），之后置位不再触发，
    // 免得抢用户的滚动。此前 LazyListState 默认停在 index 0，打开长会话看到的
    // 是最旧那一页。
    var listInitialized by remember(conversationId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(messages) {
        if (!listInitialized && messages.isNotEmpty()) {
            timelineListState.requestScrollToItem(messages.lastIndex)
            listInitialized = true
        }
    }
    // 流式期间贴底跟随；用户上滑（autoStick=false）后停止。
    // scroll_controller.dart:520-527 autoScrollToBottomIfNeeded —— 关掉
    // `display_auto_scroll_enabled_v1` 后流式内容不再把视口拽到底。
    androidx.compose.runtime.LaunchedEffect(messages, streaming, autoStick, autoScrollEnabled) {
        if (streaming && autoStick && autoScrollEnabled && messages.isNotEmpty()) {
            timelineListState.animateScrollToItem(messages.lastIndex)
        }
    }
    // scroll_controller.dart:513-533 stickToBottomAfterGeneration：生成结束那一刻
    // 尾部还会长高（操作行/Token 统计出现、思考卡收起），跟随条件里的 streaming 已经
    // 翻假，需要在同一个窗口里再贴一次底。
    var wasStreaming by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(streaming, autoStick, autoScrollEnabled, messages.size) {
        if (wasStreaming && !streaming && autoStick && autoScrollEnabled && messages.isNotEmpty()) {
            timelineListState.animateScrollToItem(messages.lastIndex)
        }
        wasStreaming = streaming
    }
    // 滚到顶部附近自动加载更早的历史 —— message_list_view.dart:1816-1830
    // （isNearTop = 距顶 <= 96 逻辑像素，120ms 节流；Compose 侧用
    // index==0 + firstVisibleItemScrollOffset 表达同样的判定）。
    // 前插之后把视口锚回原内容：LazyColumn 按 index 保持位置，不补偿会直接
    // 跳到新加载内容的顶部；锚回后 firstVisibleItemIndex 变成插入条数（≠0），
    // near-top 自然变 false，用户继续往上滑才会触发下一页。
    val hasMoreBefore by vm.hasMoreBefore.collectAsState()
    val historyTriggerDensity = LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(vm, hasMoreBefore) {
        if (!hasMoreBefore) return@LaunchedEffect
        val nearTopThresholdPx =
            with(historyTriggerDensity) { HISTORY_LOAD_TRIGGER_DP.dp.toPx() }.toInt()
        snapshotFlow {
            timelineListState.firstVisibleItemIndex == 0 &&
                timelineListState.firstVisibleItemScrollOffset <= nearTopThresholdPx
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                val anchorOffset = timelineListState.firstVisibleItemScrollOffset
                val inserted = vm.loadOlderMessages()
                if (inserted > 0) {
                    timelineListState.requestScrollToItem(inserted, anchorOffset)
                }
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
    // Detail-sheet saves bump optionsVersion so the list reloads
    // (model_select_sheet.dart _loadModelsAsync after showModelDetailSheet).
    var optionsVersion by remember { mutableIntStateOf(0) }
    val modelOptions = remember(container, optionsVersion, providerId, modelId) {
        loadModelOptions(container, providerId, modelId)
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

    // chat_assistant_background.dart —— 当前助手壁纸 + surface 遮罩渐变（0.20→0.50 × 强度）。
    val bgAssistantId by container.currentAssistantId.collectAsState()
    val chatBackground = remember(bgAssistantId) { container.currentAssistant()?.background }
    val chatMaskStrength = remember {
        container.preferenceRepository.readJson("display_chat_background_mask_strength_v1")
            ?.toFloatOrNull() ?: 1f
    }
    // 聊天背景（助手壁纸）是否真的在显示 —— chat_frosted_backdrop.dart:104-113
    // `isBackgroundActive`：http(s) 或本地文件存在。输入栏底色按它再乘一个比例，
    // 有壁纸时更透。
    val backgroundImageActive = remember(chatBackground) {
        com.psyche.memo.ui.chat.isBackgroundActive(chatBackground?.trim().orEmpty())
    }
    // 输入栏底色不透明度（display_settings_page.dart L382-404 两键，settings_provider
    // 默认浅 0.8236 / 深 0.7396）。
    val inputOpacityLight = remember {
        container.preferenceRepository.readJson("display_chat_input_background_opacity_light_v1")
            ?.toFloatOrNull() ?: 0.8236f
    }
    val inputOpacityDark = remember {
        container.preferenceRepository.readJson("display_chat_input_background_opacity_dark_v1")
            ?.toFloatOrNull() ?: 0.7396f
    }
    Box(modifier = modifier) {
        com.psyche.memo.ui.chat.ChatAssistantBackground(
            background = chatBackground,
            maskStrength = chatMaskStrength,
        )
        Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            // ChatSelectionAppBar：关闭 + 已选计数 + 反选 + 全选。
            val selectable = messages.filter { it.role == "user" || it.role == "assistant" }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        selecting = false
                        selectedIds = emptySet()
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Lucide.X, contentDescription = stringResource(UiR.string.home_page_cancel), tint = cs.onSurface)
                }
                Text(
                    text = stringResource(UiR.string.chat_selection_selected_count_title, selectedIds.size.toString()),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(UiR.string.model_fetch_invert_tooltip),
                    style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.9f)),
                    modifier = Modifier
                        .clickable {
                            selectedIds = selectable.map { it.id }.toSet() - selectedIds
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
                Row(
                    modifier = Modifier
                        .clickable {
                            val all = selectable.map { it.id }.toSet()
                            selectedIds = if (selectedIds.containsAll(all) && all.isNotEmpty()) emptySet() else all
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosCheckbox(
                        value = selectable.isNotEmpty() && selectedIds.containsAll(selectable.map { it.id }),
                        onValueChanged = {},
                        size = 18.dp,
                        hitTestSize = 32.dp,
                        interactive = false,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(UiR.string.storage_space_select_all),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                }
            }
        } else {
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
                    // home_page.dart —— mini-map 图标 20（新对话保持 22）。
                    modifier = Modifier.size(ChatStyleSpec.TOP_BAR_MAP_ICON_DP.dp),
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
            // home_page.dart —— 顶栏末尾 4px 尾距。
            Spacer(Modifier.width(ChatStyleSpec.TOP_BAR_TRAILING_GAP_DP.dp))
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
                    // MLV:1684-1690 —— 列表自身只留 top 8 / bottom 16；水平与
                    // 消息间垂直间距由每条消息的 Padding 承担（CMW:1768/2780）。
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = ChatStyleSpec.LIST_TOP_PADDING_DP.dp,
                        bottom = ChatStyleSpec.LIST_BOTTOM_PADDING_DP.dp,
                    ),
                ) {
                    // contentType 让 LazyColumn 按 user/assistant 复用两种布局。
                    items(
                        messages,
                        key = { it.id },
                        contentType = { if (it.checkpointPart() != null) "compaction" else it.role },
                    ) { msg ->
                        // 压缩检查点不画气泡：渲染成「上下文已压缩」分隔线（用户点名：
                        // 摘要不进对话界面）。
                        if (msg.checkpointPart() != null) {
                            com.psyche.memo.ui.chat.CompactionDivider(inProgress = false)
                            return@items
                        }
                        // 自动压缩进行中：分隔线排在流式骨架之前。
                        if (compacting && msg.id == streamingMessageId) {
                            com.psyche.memo.ui.chat.CompactionDivider(inProgress = true)
                        }
                        val isLastAssistant = msg.id == lastAssistantId
                        // 压缩检查点不是真实发言：不参与多选/导出（用户点名摘要不进对话）。
                        val canSelect = (msg.role == "user" || msg.role == "assistant") &&
                            msg.checkpointPart() == null
                        Row(verticalAlignment = Alignment.Top) {
                            if (selecting && canSelect) {
                                Box(modifier = Modifier.padding(start = 10.dp, top = 10.dp)) {
                                    IosCheckbox(
                                        value = msg.id in selectedIds,
                                        onValueChanged = { checked ->
                                            selectedIds = if (checked) selectedIds + msg.id else selectedIds - msg.id
                                        },
                                        size = 20.dp,
                                        hitTestSize = 28.dp,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (selecting && canSelect) {
                                            Modifier.clickable {
                                                selectedIds = if (msg.id in selectedIds) selectedIds - msg.id else selectedIds + msg.id
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                        MessageRow(
                            msg = msg,
                            skipRegenerateConfirm = remember {
                                container.preferenceRepository.readJson(
                                    "display_show_regenerate_confirm_dialog_v1",
                                )?.let { it == "1" } ?: true
                            },
                            selecting = selecting,
                            suggestions = if (isLastAssistant && suggestionsEnabled) {
                                suggestions
                            } else {
                                emptyList()
                            },
                            onSuggestionTap = { vm.sendSuggestion(it) },
                            assistantLabel = resolvedAssistantLabel,
                            assistant = assistantRow,
                            userProfile = userProfile,
                            showModelIcon = showModelIcon,
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
                            onRegenerateAssistant = if (msg.role == "assistant") {
                                {
                                    // 助手重新生成 = 从它前面最近一条用户消息重发。
                                    val idx = messages.indexOfFirst { it.id == msg.id }
                                    val anchor = messages.take(idx).lastOrNull { it.role == "user" }
                                    if (anchor != null) regenerateFor = anchor
                                }
                            } else null,
                            onTranslate = { code ->
                                if (code == com.psyche.memo.ui.chat.TranslateLanguage.CLEAR_TRANSLATION) {
                                    vm.translateMessage(msg.id, code, "", translateFeedback)
                                } else {
                                    vm.translateMessage(
                                        msg.id,
                                        code,
                                        translatingLabel,
                                        translateFeedback,
                                    )
                                }
                            },
                            onEdit = { editFor = msg },
                            onMore = { moreFor = msg },
                            onDelete = { vm.deleteVersion(msg.id) },
                            timelineSettings = timelineSettings,
                            onToggleReasoning = { segmentIndex ->
                                vm.toggleReasoningSegment(msg.id, segmentIndex)
                            },
                            conversationId = conversationId,
                            // 代码块上的「预览」是**原始 HTML**（原版 HtmlPreviewPage），
                            // 不是消息正文的 markdown 渲染。
                            onOpenHtmlPreview = { code ->
                                htmlPreviewFor = com.psyche.memo.ui.chat.HtmlPreviewRequest(code, rawHtml = true)
                            },
                            approvalService = approvalService,
                            askUserService = askUserService,
                            onRecoveredAnswer = { part, result ->
                                vm.resumeAfterToolAnswer(msg.id, part, result.jsonString)
                            },
                        )
                            }
                        }
                    }
                    // 手动压缩（没有流式骨架）时，分隔线补在列表末尾。
                    if (compacting && streamingMessageId == null) {
                        item(key = "compaction-progress") {
                            com.psyche.memo.ui.chat.CompactionDivider(inProgress = true)
                        }
                    }
                }
                // 滚动导航面板（scroll_nav_buttons.dart）：贴输入栏上方右侧。
                // home_page.dart:1504-1516 移动端三态：always 常显 / scroll 跟随
                // 滚动（默认）/ never 整块不渲染。
                if (navButtonsMode != MOBILE_NAV_NEVER) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 12.dp),
                    ) {
                        com.psyche.memo.ui.chat.ScrollNavButtonsPanel(
                            visible = navButtonsMode == MOBILE_NAV_ALWAYS || navVisible,
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
        }

        if (selecting) {
            // 选择态：底部换成导出/删除操作栏（home_page.dart
            // _buildSelectionActionBar）。
            if (selectionDeleteMode) {
                com.psyche.memo.ui.chat.ChatSelectionDeleteBar(
                    hasMultiVersionSelection = selectedIds.size > 1,
                    onDeleteCurrentVersions = {
                        val ids = selectedIds
                        ids.forEach { vm.deleteVersion(it) }
                        selecting = false
                        selectedIds = emptySet()
                    },
                    onDeleteAllVersions = {
                        val ids = selectedIds
                        ids.forEach { vm.deleteAllVersions(it) }
                        selecting = false
                        selectedIds = emptySet()
                    },
                )
            } else {
                com.psyche.memo.ui.chat.ChatSelectionExportBar(
                    showThinkingTools = selShowThinkingTools,
                    showThinkingContent = selShowThinkingContent,
                    onExportMarkdown = { showExportSheet = true },
                    onExportTxt = { showExportSheet = true },
                    onExportImage = {},
                    onToggleThinkingTools = {
                        selShowThinkingTools = !selShowThinkingTools
                        if (!selShowThinkingTools) selShowThinkingContent = false
                    },
                    onToggleThinkingContent = {
                        if (selShowThinkingTools) selShowThinkingContent = !selShowThinkingContent
                    },
                )
            }
        } else {
        // 模型按钮品牌图标（CurrentModelIcon：modelId 优先、providerKey 兜底；
        // 无 asset 用首字母圆；都没选显示 Boxes）。
        val modelIconAsset = remember(providerId, modelId) {
            val mid = modelId.takeIf { it.isNotEmpty() }
            val pid = providerId.takeIf { it.isNotEmpty() }
            if (mid == null && pid == null) null
            else BrandAssets.assetForName(mid.orEmpty())
                ?: pid?.let { BrandAssets.assetForName(it) }
        }
        val modelIconInitial = modelId.ifEmpty { providerId }
        // 搜索按钮（CIB:1782-1863）：当前助手启用搜索 → 所选搜索服务的品牌
        // 图标；未启用 → Globe。内置搜索（builtinSearchActive）未移植，恒 false。
        val assistantForSearch = container.currentAssistant()
        val searchActive = assistantForSearch?.searchEnabled == true
        // showSearchSheet 关闭后重组时重算，让 sheet 里改的服务/开关即时反映。
        val searchSvc = remember(container, showSearchSheet, searchActive) {
            if (!searchActive) null
            else runCatching {
                val svcs = container.searchSettingsRepository.services()
                val i = container.searchSettingsRepository.selectedIndex()
                    .coerceIn(0, (svcs.size - 1).coerceAtLeast(0))
                svcs.getOrNull(i)
            }.getOrNull()
        }
        val searchSvcName = searchSvc?.let { stringResource(com.psyche.memo.ui.SearchServiceUi.nameRes(it)) }
        val searchIconAsset = searchSvcName?.let { BrandAssets.assetForName(it) }
        ChatInputBar(
            input = input,
            enterToSend = remember {
                container.preferenceRepository.readJson("display_enter_to_send_on_mobile_v1")
                    ?.let { it == "1" } ?: false
            },
            streaming = streaming,
            onInputChange = vm::updateInput,
            onSend = {
                // home_page_controller.dart L532-537: generation start fires a
                // light tick when "haptics on generate" is enabled.
                if (chatHaptics.onGenerate) Haptics.light(chatView)
                vm.send()
            },
            onStop = vm::stop,
            onSelectModel = { showModelSheet = true },
            onOpenSearch = { showSearchSheet = true },
            modelIconAsset = modelIconAsset,
            modelIconInitial = modelIconInitial,
            searchActive = searchActive,
            searchIconAsset = searchIconAsset,
            onOpenTools = {
                worldBooksAvailable = runCatching {
                    com.psyche.memo.data.repo.WorldBookRepository(
                        container.database.readableDatabase,
                        container.preferenceRepository,
                    ).books().isNotEmpty()
                }.getOrDefault(false)
                showToolsSheet = true
            },
            onQuickPhrase = { quickPhrases = loadQuickPhrases(container) },
            reasoningBudget = reasoningBudget,
            onOpenReasoning = { showReasoningSheet = true },
            onOpenMcp = { showMcpSheet = true },
            attachments = attachments,
            onRemoveAttachment = { index -> vm.removeAttachment(index) },
            voice = voiceInput,
            backgroundImageActive = backgroundImageActive,
            inputOpacityLight = inputOpacityLight,
            inputOpacityDark = inputOpacityDark,
            longPaste = longPaste,
            onRequestMicPermission = {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            onPasteText = { text ->
                // 写失败（IO 异常）时退回「直接插入」，与原版一致。
                val attachment = com.psyche.memo.provider.AttachmentStore
                    .importPastedText(context, text)
                if (attachment != null) vm.addAttachments(listOf(attachment)) else vm.updateInput(text)
            },
        )
        }
    }
    }

    if (showReasoningSheet) {
        com.psyche.memo.ui.chat.ReasoningBudgetSheet(
            initialBudget = com.psyche.memo.ui.chat.readBudget(container),
            onSelect = { v ->
                container.preferenceRepository.writeJson(
                    "thinking_budget_v1",
                    kotlinx.serialization.json.JsonPrimitive(v).toString(),
                )
            },
            modelId = modelId,
            onDismiss = {
                showReasoningSheet = false
                reasoningBudget = com.psyche.memo.ui.chat.readBudget(container)
            },
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
            onOptionsInvalidated = { optionsVersion++ },
        )
    }

    quickPhrases?.let { phrases ->
        if (phrases.isNotEmpty()) {
            com.psyche.memo.ui.QuickPhraseMenu(
                phrases = phrases,
                onSelect = { phrase ->
                    quickPhrases = null
                    // 无选区信息：按「追加到末尾」处理（handleQuickPhraseSelection 的常见路径）。
                    vm.updateInput(vm.input.value + phrase.content)
                },
                onDismiss = { quickPhrases = null },
            )
        } else {
            quickPhrases = null
        }
    }

    if (showToolsSheet) {
        BottomToolsSheet(
            onCamera = {
                showToolsSheet = false
                val file = com.psyche.memo.provider.AttachmentStore.captureFile(context)
                cameraFile = file
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file,
                )
                cameraUri.value = uri
                cameraPicker.launch(uri)
            },
            onPhotos = {
                showToolsSheet = false
                photoPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            onUpload = {
                showToolsSheet = false
                filePicker.launch(arrayOf("*/*"))
            },
            onDismiss = { showToolsSheet = false },
            ocrAvailable = ocrSettings.providerId != null && ocrSettings.modelId != null,
            ocrEnabled = ocrSettings.enabled,
            onToggleOcr = {
                showToolsSheet = false
                val next = !ocrSettings.enabled
                container.preferenceRepository.writeJson(
                    com.psyche.memo.provider.OcrService.ENABLED_KEY,
                    kotlinx.serialization.json.JsonPrimitive(if (next) 1 else 0).toString(),
                )
                ocrSettings = com.psyche.memo.provider.OcrService.settingsOf(container.preferenceRepository)
            },
            onOpenOcrPrompt = {
                showToolsSheet = false
                showOcrPrompt = true
            },
            onOpenInstructionInjection = {
                showToolsSheet = false
                showInstructionSheet = true
            },
            worldBooksAvailable = worldBooksAvailable,
            onOpenWorldBook = {
                showToolsSheet = false
                showWorldBookSheet = true
            },
            onOpenWorldBookPage = {
                showToolsSheet = false
                onOpenWorldBookPage()
            },
            onOpenContextManagement = {
                showToolsSheet = false
                showContextSheet = true
            },
        )
    }

    if (showContextSheet) {
        com.psyche.memo.ui.chat.ContextManagementSheet(
            clearLabel = vm.clearContextLabel(),
            usage = contextUsage,
            onCompress = {
                showContextSheet = false
                showCompressDialog = true
            },
            onClear = {
                showContextSheet = false
                vm.clearContext()
            },
            onDismiss = { showContextSheet = false },
        )
    }

    if (showExportSheet) {
        val exportTitle = (container.conversationDao.get(conversationId)?.title ?: "")
            .ifBlank { container.appContext.getString(UiR.string.message_export_sheet_default_title) }
        val selectedMessages = messages
            .filter { it.id in selectedIds && it.checkpointPart() == null }
            .map {
                com.psyche.memo.ui.chat.MessageExport.ExportMessage(
                    role = it.role,
                    parts = it.parts,
                    timestamp = it.timestamp,
                    modelName = it.model.takeIf { name -> name.isNotBlank() },
                )
            }
        val roleNameOf: (com.psyche.memo.ui.chat.MessageExport.ExportMessage) -> String = { m ->
            if (m.role == "user") {
                container.preferenceRepository.readJson("user_name")
                    ?.takeIf { it.isNotBlank() }
                    ?: container.appContext.getString(UiR.string.user_provider_default_user_name)
            } else {
                val assistant = container.currentAssistant()
                if (assistant?.useAssistantName == true && assistant.name.isNotBlank()) {
                    assistant.name
                } else {
                    m.modelName
                        ?: container.appContext.getString(UiR.string.message_export_sheet_assistant)
                }
            }
        }
        val timeOf: (Long) -> String = { millis ->
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(millis))
        }
        fun buildExport(markdown: Boolean): String = com.psyche.memo.ui.chat.MessageExport.export(
            title = exportTitle,
            messages = selectedMessages,
            roleNameOf = roleNameOf,
            timeOf = timeOf,
            thinkingLabel = container.appContext.getString(UiR.string.message_export_thinking_content_label),
            includeThinking = selShowThinkingTools && selShowThinkingContent,
            includeTools = selShowThinkingTools,
            markdown = markdown,
            imageLine = { uri -> if (markdown) "![image]($uri)" else uri },
        )
        com.psyche.memo.ui.chat.MessageExportSheet(
            onMarkdown = {
                showExportSheet = false
                exportPending = true to buildExport(true)
                exportMdLauncher.launch("chat-export-${System.currentTimeMillis()}.md")
            },
            onTxt = {
                showExportSheet = false
                exportPending = false to buildExport(false)
                exportTxtLauncher.launch("chat-export-${System.currentTimeMillis()}.txt")
            },
            onDismiss = { showExportSheet = false },
        )
    }

    if (showMcpSheet) {
        com.psyche.memo.ui.chat.McpAssistantSheet(
            container = container,
            onDismiss = { showMcpSheet = false },
        )
    }

    if (showCompressDialog) {
        com.psyche.memo.ui.chat.CompressContextDialog(
            container = container,
            messages = messages.map { it.role to it.content },
            // 阈值基准取当前会话的聊天模型（模型编辑页的「上下文长度」）。
            providerId = providerId,
            modelId = modelId,
            onDismiss = { showCompressDialog = false },
            // opencode 阈值机制：压缩就地插入检查点，不再新建会话；进度由消息流里的
            // 「上下文压缩中」扫光分隔线表达（用户点名：不要弹压缩对话框）。
            onConfirm = {
                showCompressDialog = false
                vm.compactContextNow { errorKey ->
                    if (errorKey != null) {
                        val message = when (errorKey) {
                            "no_messages" -> container.appContext.getString(UiR.string.compress_context_no_messages)
                            "no_model" -> container.appContext.getString(UiR.string.compress_context_no_model)
                            "empty_summary" -> container.appContext.getString(UiR.string.compress_context_empty_summary)
                            else -> container.appContext.getString(UiR.string.compress_context_failed)
                        }
                        com.psyche.memo.ui.snackbar.SnackbarManager.show(
                            com.psyche.memo.ui.snackbar.AppNotification(
                                message = message,
                                type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
                                durationMs = 6000,
                            ),
                        )
                    }
                }
            },
        )
    }

    if (showWorldBookSheet) {
        com.psyche.memo.ui.WorldBookSheet(
            container = container,
            assistantId = container.currentAssistant()?.id,
            onDismiss = { showWorldBookSheet = false },
        )
    }

    if (showInstructionSheet) {
        com.psyche.memo.ui.InstructionInjectionSheet(
            container = container,
            assistantId = container.currentAssistant()?.id,
            onDismiss = { showInstructionSheet = false },
        )
    }

    if (showOcrPrompt) {
        OcrPromptSheet(
            container = container,
            onDismiss = {
                showOcrPrompt = false
                ocrSettings = com.psyche.memo.provider.OcrService.settingsOf(container.preferenceRepository)
            },
        )
    }

    if (showSearchSheet) {
        SearchSettingsSheet(
            container = container,
            onDismiss = { showSearchSheet = false },
            onOpenServices = {
                showSearchSheet = false
                onOpenSearchServices()
            },
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
                    com.psyche.memo.ui.chat.MessageMoreAction.SELECT_COPY ->
                        selectCopyFor = target.content
                    com.psyche.memo.ui.chat.MessageMoreAction.RENDER_WEB_VIEW ->
                        htmlPreviewFor = com.psyche.memo.ui.chat.HtmlPreviewRequest(
                            target.content,
                            rawHtml = false,
                        )
                    com.psyche.memo.ui.chat.MessageMoreAction.SHARE -> {
                        // message_more_sheet.dart Share —— 系统分享纯文本。
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, target.content)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    }
                    com.psyche.memo.ui.chat.MessageMoreAction.SELECT_MESSAGES -> {
                        // startMessageSelection：锚点消息 + 配对的 user/assistant。
                        val list = messages
                        val index = list.indexOfFirst { it.id == target.id }
                        val picked = linkedSetOf<String>()
                        fun addIfSelectable(i: Int?) {
                            val m = i?.let { list.getOrNull(it) } ?: return
                            if (m.role == "user" || m.role == "assistant") picked.add(m.id)
                        }
                        if (index >= 0) {
                            val anchor = list[index]
                            when (anchor.role) {
                                "assistant" -> {
                                    addIfSelectable(index)
                                    addIfSelectable(list.take(index).indexOfLast { it.role == "user" }
                                        .takeIf { it >= 0 })
                                }
                                "user" -> {
                                    addIfSelectable(index)
                                    addIfSelectable(list.drop(index + 1).indexOfFirst { it.role == "assistant" }
                                        .takeIf { it >= 0 }?.plus(index + 1))
                                }
                                else -> {
                                    addIfSelectable(list.take(index).indexOfLast { it.role == "user" }
                                        .takeIf { it >= 0 })
                                    addIfSelectable(list.drop(index).indexOfFirst { it.role == "assistant" }
                                        .takeIf { it >= 0 }?.plus(index))
                                }
                            }
                        }
                        if (picked.isEmpty()) picked.add(target.id)
                        selectedIds = picked
                        selectionDeleteMode = false
                        selShowThinkingTools = false
                        selShowThinkingContent = false
                        selecting = true
                    }
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

    selectCopyFor?.let { content ->
        com.psyche.memo.ui.chat.SelectCopySheet(
            content = content,
            onDismiss = { selectCopyFor = null },
        )
    }

    htmlPreviewFor?.let { request ->
        com.psyche.memo.ui.chat.HtmlPreviewScreen(
            request = request,
            onBack = { htmlPreviewFor = null },
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
                if (chatHaptics.onGenerate) Haptics.light(chatView)
                vm.regenerate(target.id)
            },
        )
    }
}

/**
 * model_icon.dart `CurrentModelIcon` in the shape the chat header uses it
 * (chat_message_widget.dart:2278-2287 → `CurrentModelIcon(size: 30)`):
 * primary-tinted circle with the model's brand glyph at 0.5x, falling back to
 * the first character of the model id. Mono assets that need inverting in dark
 * theme are tinted onSurface, exactly like the Flutter original.
 */
@Composable
private fun MessageModelIcon(
    providerKey: String,
    modelId: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val asset = remember(modelId, providerKey) {
        modelId.takeIf { it.isNotEmpty() }?.let { BrandAssets.assetForName(it) }
            ?: providerKey.takeIf { it.isNotEmpty() }?.let { BrandAssets.assetForName(it) }
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(cs.primary.copy(alpha = if (isDark) 0.18f else 0.1f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (asset != null) {
            coil.compose.AsyncImage(
                model = asset,
                contentDescription = null,
                colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(asset)) {
                    androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Text(
                text = modelId.trim().take(1).uppercase().ifEmpty { "?" },
                style = TextStyle(
                    fontSize = (size.value * 0.43f).sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                ),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: ChatViewModel.UiMessage,
    selecting: Boolean = false,
    suggestions: List<String> = emptyList(),
    onSuggestionTap: (String) -> Unit = {},
    assistantLabel: String,
    /** 当前助手：消息头在「助手头像 / 模型图标」之间二选一（CMW:2787-2802）。 */
    assistant: com.psyche.memo.data.model.Assistant? = null,
    /** 用户资料（user_provider.dart）：用户头的头像/名字。 */
    userProfile: UserProfileStore.Profile = UserProfileStore.Profile(),
    /** display_show_model_icon_v1，默认 true（settings_provider.dart:1069）。 */
    showModelIcon: Boolean = true,
    versionCount: Int,
    versionIndex: Int,
    onPrevVersion: (() -> Unit)?,
    onNextVersion: (() -> Unit)?,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    /** 助手消息的重新生成（CMW:3239-3249 _confirmRegeneration 确认后执行）。 */
    onRegenerateAssistant: (() -> Unit)? = null,
    /** Translate 按钮（CMW:3298-3339）：传入所选语言 code（含 __clear__）。 */
    onTranslate: (String) -> Unit = {},
    onEdit: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    /** 思考卡 / 工具卡的 6 个显示开关（settings_provider.dart display_*）。 */
    timelineSettings: com.psyche.memo.ui.chat.ChatTimelineSettings,
    /** 展开/折叠某个思考段（home_page_controller.toggleReasoningSegment）。 */
    onToggleReasoning: (segmentIndex: Int) -> Unit,
    /** 当前会话 id（审批卡 / ask-user 卡按会话匹配 pending 请求）。 */
    conversationId: String?,
    /** 代码块「预览」（HTML 块）→ 打开 WebView 预览页（core:ui 不认识该页面）。 */
    onOpenHtmlPreview: ((String) -> Unit)? = null,
    /** 工具审批服务（tool_approval_service.dart）—— 审批卡与时间线可见性。 */
    approvalService: ToolApprovalService?,
    /** ask-user 交互服务（ask_user_interaction_service.dart）。 */
    askUserService: AskUserInteractionService?,
    /** 恢复已持久化 ask-user 回答（home_page_controller.submitRecoveredAskUserAnswer）。 */
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)?,
    /** display_show_regenerate_confirm_dialog_v1 = false 时跳过确认弹窗。 */
    skipRegenerateConfirm: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val rowView = LocalView.current
    val isUser = msg.role == "user"
    // 该消息所属助手的正则规则（visual 目标只影响这里显示的文本）。
    val assistantRegexRulesCache = remember(msg.id) {
        com.psyche.memo.data.model.AssistantRegexApplier.decodeRules(
            assistant?.regexRules.orEmpty(),
        )
    }
    // 时间戳文本：滚动时每行都会重组，格式化一次就够（头部 user/assistant
    // 两个分支共用）。
    val timeLabel = remember(msg.timestamp) { timeStr(msg.timestamp) }
    // 暗色判定（chat_input_bar.dart:2547 同款口径）。
    val isDark = cs.surface.luminance() < 0.5f
    // 每条消息外边距：用户 h16 / 助手 h20，垂直 12（CMW:1768 / 2780）。
    val rowHorizontal = if (isUser) ChatStyleSpec.USER_MESSAGE_HORIZONTAL_DP.dp
    else ChatStyleSpec.ASSISTANT_MESSAGE_HORIZONTAL_DP.dp
    // User bubble max width = screen width * 0.75
    // (chat_message_widget.dart L1833/1853).
    val maxBubbleWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() * ChatStyleSpec.USER_MAX_WIDTH_RATIO
    }
    var showContextMenu by remember { mutableStateOf(false) }
    // 全屏图片查看器状态（image_viewer_page.dart 移动端路径）。
    var viewerState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // 引用来源 sheet 状态（citation_sources_sheet.dart）。
    var showCitations by remember { mutableStateOf(false) }
    // 翻译区折叠状态（chat_message_widget.dart translationExpanded）。
    var translationExpanded by remember(msg.id, msg.translation) { mutableStateOf(true) }
    // 助手操作行：语言选择 sheet + 重新生成确认（CMW:3298-3339 / 1328-1358）。
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    // TTS 播放状态 → Speak/Stop/Resume 图标。原版用全局 isActive，导致读一条消息
    // 时**所有**消息都显示停止；这里按 ownerId 只让被朗读的那条消息响应，并在暂停
    // 时显示继续（用户实测反馈）。
    val ttsState by com.psyche.memo.ui.chat.TtsPlayer.state.collectAsState()
    val ttsAction = com.psyche.memo.ui.chat.messageTtsAction(ttsState, msg.id)
    // search_web / builtin_search 工具结果提取为引用来源
    // （chat_message_widget.dart _allSearchItems，从后往前、去重）。
    val searchItems = remember(msg.id, msg.parts) {
        com.psyche.memo.ui.chat.extractCitationItems(msg.parts)
    }
    // 引用元数据解析 + 点击处理（渲染为 RikkaHub 圆形域名胶囊：新格式
    // [citation,domain](id) 由模型直写域名；历史 [cite:id] 归一化后经此
    // 反查 search_items 得到域名/序号回退）。仅当本条消息含搜索来源时有效。
    val context = androidx.compose.ui.platform.LocalContext.current
    val citationResolver: (String) -> com.psyche.memo.ui.markdown.CitationInfo? = { id ->
        val key = id.trim()
        if (key.isEmpty()) {
            null
        } else {
            val item = searchItems.firstOrNull { it.id == key }
                ?: key.toIntOrNull()?.let { n -> searchItems.firstOrNull { it.index == n } }
                // 正文里的普通 Markdown 链接按 **URL** 命中来源（模型实测会写
                // `[链接](https://…)`，没有 id 可查）。
                ?: searchItems.firstOrNull { it.url.isNotEmpty() && it.url.equals(key, ignoreCase = true) }
                ?: searchItems.firstOrNull {
                    val normalized = com.psyche.memo.ui.chat.normalizeExternalUri(it.url)?.toString()
                    normalized != null && normalized.equals(key, ignoreCase = true)
                }
            if (item == null) {
                null
            } else {
                com.psyche.memo.ui.markdown.CitationInfo(
                    domain = com.psyche.memo.ui.chat.citationDomain(item.url).takeIf { it.isNotEmpty() },
                    index = item.index,
                )
            }
        }
    }
    val handleCitationTap: (String) -> Unit = { id ->
        val item = searchItems.firstOrNull { it.id == id }
            ?: id.toIntOrNull()?.let { n -> searchItems.firstOrNull { it.index == n } }
        val url = item?.url ?: if (id.contains('/') || id.contains('.')) id else null
        if (!url.isNullOrEmpty()) com.psyche.memo.ui.chat.openExternal(context, url)
    }
    // 表格工具栏（_MarkdownTableToolbar）：复制 / 存图 / 导出 CSV 的平台侧实现，
    // 由 app 注入给 core:ui（core:ui 拿不到剪贴板、MediaStore、SAF）。
    val tableActions = com.psyche.memo.ui.chat.rememberMarkdownTableActions()
    // 代码块：折叠/换行三个设置 + 「预览」动作（另存为暂未接线，按钮自动隐藏）。
    val codeBlockConfig = remember(
        timelineSettings.autoCollapseCodeBlock,
        timelineSettings.autoCollapseCodeBlockLines,
        timelineSettings.mobileCodeBlockWrap,
    ) {
        com.psyche.memo.ui.markdown.CodeBlockConfig(
            autoCollapse = timelineSettings.autoCollapseCodeBlock,
            autoCollapseLines = timelineSettings.autoCollapseCodeBlockLines,
            wrap = timelineSettings.mobileCodeBlockWrap,
        )
    }
    val codeBlockActions = remember(onOpenHtmlPreview) {
        com.psyche.memo.ui.markdown.CodeBlockActions(
            onPreviewHtml = onOpenHtmlPreview,
        )
    }
    // 数学公式两开关（渲染页）：总开关 + 是否把 `$…$` 当公式。
    val mathConfig = remember(timelineSettings.mathRendering, timelineSettings.dollarLatex) {
        com.psyche.memo.ui.markdown.MathConfig(
            enabled = timelineSettings.mathRendering,
            dollarLatex = timelineSettings.dollarLatex,
        )
    }
    // CMW:3707-3711 的第三分支 _buildToolMessage(1662-1706)：role == tool 的
    // 消息没有头像/气泡/操作行，正文本身就是 {tool, arguments, result, metadata}，
    // 渲染成 h16 v6 里的一张工具卡；按显示设置不可见时整条不占位。
    if (msg.role == "tool") {
        val toolPart = remember(msg.id, msg.parts) {
            com.psyche.memo.ui.chat.ToolUiPart.fromToolMessage(msg.id, msg.content)
        }
        // `visible` already implies a non-null part, so the extra null check
        // is redundant (and smart-casts through the local).
        val visible = toolPart != null && com.psyche.memo.ui.chat.isTimelineToolVisible(
            toolName = toolPart.toolName,
            loading = toolPart.loading,
            showToolCards = timelineSettings.showToolCards,
            filterBuiltinSearch = false,
        )
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                // CMW:3700-3713 —— role == tool 的消息同样套 _ChatSurfaceTheme，
                // 工具卡的前景色板跟随当前气泡样式。
                androidx.compose.runtime.CompositionLocalProvider(
                    com.psyche.memo.ui.chat.LocalChatSurfaceFg provides
                        com.psyche.memo.ui.chat.computeChatSurfaceFg(
                            cs, isDark, false, timelineSettings.bubbleStyles,
                        ),
                    com.psyche.memo.ui.chat.LocalChatBubbleStyles provides timelineSettings.bubbleStyles,
                ) {
                    com.psyche.memo.ui.chat.ToolCallCard(
                        part = toolPart,
                        hideToolResultImages = timelineSettings.hideToolResultImages,
                        conversationId = conversationId,
                        approval = approvalService,
                        askUser = askUserService,
                        onRecoveredAnswer = onRecoveredAnswer,
                    )
                }
            }
        }
        return
    }
    // CMW:2870-2884 timelineProjection → visibleBlocks：助手气泡里的文本块与思考
    // 卡按 part 到达顺序排列（用户消息不走投影）。
    val assistantBlocks = if (isUser) {
        emptyList()
    } else {
        remember(msg.id, msg.parts, msg.reasoningSegmentsJson, msg.isStreaming) {
            com.psyche.memo.ui.chat.projectAssistantBlocks(
                parts = msg.parts,
                segmentsJson = msg.reasoningSegmentsJson,
                isStreaming = msg.isStreaming,
            )
        }
    }
    // CMW 侧的等价物：message_list_view.dart:2018-2024 把整条消息包进
    // `MediaQuery(textScaler: 系统缩放 × chatFontScale)`，所以消息头/正文/思考卡/
    // 代码块的字号一起缩放，而 dp（头像、图标、内边距）不变。Compose 里 sp 的缩放
    // 因子就是 LocalDensity.fontScale，照原样乘上去即可。
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val messageDensity = remember(baseDensity, timelineSettings.chatFontScale) {
        if (timelineSettings.chatFontScale == 1f) {
            baseDensity
        } else {
            androidx.compose.ui.unit.Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * timelineSettings.chatFontScale,
            )
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides messageDensity,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = rowHorizontal,
                vertical = ChatStyleSpec.MESSAGE_VERTICAL_DP.dp,
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            // Header: name 13px α0.7 + timestamp 11px α0.5 (right-aligned) +
            // 用户头像（CMW:1773-1809）：名字/时间戳/头像分别受
            // display_show_user_name_v1 / _timestamp_v1 / _avatar_v1 控制，
            // 头像四态（emoji/url/file/空回退 User 图标）取自 UserProvider。
            val userLabel = userProfile.name.ifEmpty {
                stringResource(UiR.string.user_provider_default_user_name)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    if (timelineSettings.showUserName) {
                        Text(
                            text = userLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface.copy(alpha = 0.7f),
                            ),
                        )
                    }
                    // 名与时间戳同时显示时才留那 2dp（CMW:1790-1792）。
                    if (timelineSettings.showUserName && timelineSettings.showUserTimestamp) {
                        Spacer(Modifier.height(ChatStyleSpec.NAME_TIME_GAP_DP.dp))
                    }
                    if (timelineSettings.showUserTimestamp) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = cs.onSurface.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
                if (timelineSettings.showUserAvatar) {
                    Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                    UserAvatar(
                        profile = userProfile,
                        name = userLabel,
                        size = ChatStyleSpec.AVATAR_SIZE_DP.dp,
                        fallback = UserAvatarFallback.Icon,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // chat_message_widget.dart:2787-2802 —— useAssistantAvatar 优先
                // （助手头像四态），否则 showModelIcon 时显示该消息的模型品牌
                // 图标；两者都不显示时头部只有名字。
                val headerAssistant = assistant
                if (headerAssistant != null && headerAssistant.useAssistantAvatar) {
                    AssistantListAvatar(headerAssistant, 32.dp)
                    Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                } else if (showModelIcon) {
                    MessageModelIcon(
                        providerKey = msg.providerId,
                        modelId = msg.model,
                        size = 30.dp,
                    )
                    Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                }
                Column {
                    Text(
                        text = assistantLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface.copy(alpha = 0.7f),
                        ),
                    )
                    Spacer(Modifier.height(ChatStyleSpec.NAME_TIME_GAP_DP.dp))
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            color = cs.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(ChatStyleSpec.HEADER_CONTENT_GAP_DP.dp))
        // 长按浮层锚定在气泡上（chat_message_widget.dart:1812-1846 mobile
        // long-press → _showUserContextMenu）。
        Box {
            // CMW:3700-3713 —— 整条消息套一层 _ChatSurfaceTheme：卡片类子组件
            // 通过继承拿到本角色的前景色板；气泡外壳再叠 LocalChatBubbleStyles。
            val surfaceFg = remember(timelineSettings.bubbleStyles, isDark, isUser) {
                com.psyche.memo.ui.chat.computeChatSurfaceFg(cs, isDark, isUser, timelineSettings.bubbleStyles)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.psyche.memo.ui.chat.LocalChatSurfaceFg provides surfaceFg,
                com.psyche.memo.ui.chat.LocalChatBubbleStyles provides timelineSettings.bubbleStyles,
            ) {
            Column(
                modifier = Modifier
                    .then(
                        if (isUser) Modifier.widthIn(max = maxBubbleWidth)
                        // 助手块默认撑满整行（CMW:2478-2484
                        // _assistantBlockWidth，assistantBubbleFitContent 默认关）。
                        else Modifier.fillMaxWidth()
                    )
                    .combinedClickable(
                        enabled = isUser,
                        onLongClick = {
                            if (isUser && !selecting) {
                                Haptics.light(rowView)
                                showContextMenu = true
                            }
                        },
                        onClick = {},
                    ),
            ) {
                // 图片附件（chat_message_widget.dart _buildAttachmentPreview
                // ImagePart 分支）：整组渲染，点击可跨图翻页查看。用户侧的附件
                // 是文本气泡的**兄弟**（CMW:1835-1843，同一个 0.75w 列），助手侧
                // 每块图片自带气泡（_buildAssistantImageBlock CMW:2545-2566，
                // 恒定撑满整行，不受 assistantBubbleFitContent 影响）。
                if (msg.parts.any { it is ImagePart }) {
                    if (isUser) {
                        com.psyche.memo.ui.chat.MessageImageAttachments(
                            parts = msg.parts,
                            onOpenViewer = { uris, index -> viewerState = uris to index },
                        )
                    } else {
                        com.psyche.memo.ui.chat.ChatBubbleSurface(
                            isUser = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            com.psyche.memo.ui.chat.MessageImageAttachments(
                                parts = msg.parts,
                                onOpenViewer = { uris, index -> viewerState = uris to index },
                            )
                        }
                    }
                }
                if (isUser) {
                    // CMW:1752-1765 —— 只有正文非空才有文本气泡；纯图片的用户消息
                    // 不该留一个空底色块（原版 textBubble == null）。
                    val userHasText = msg.parts.any { it is TextPart && it.text.isNotEmpty() }
                    val userContent: @Composable () -> Unit = {
                        for (part in msg.parts) {
                            when (part) {
                                is TextPart -> {
                                    // visual 规则在显示层改写（chat_message_widget.dart L1291）。
                                    val visual = remember(part.text) {
                                        com.psyche.memo.data.model.AssistantRegexApplier.applyAll(
                                            part.text,
                                            assistantRegexRulesCache,
                                            com.psyche.memo.data.model.AssistantRegexScope.USER,
                                            com.psyche.memo.data.model.AssistantRegexApplier.Target.VISUAL,
                                        )
                                    }
                                    if (timelineSettings.enableUserMarkdown) {
                                        // CMW:2046-2054 —— 用户正文 15.5 / 行高 1.45×15.5。
                                        com.psyche.memo.ui.markdown.MarkdownText(
                                            markdown = visual,
                                            baseFontSize = ChatStyleSpec.USER_TEXT_SP,
                                            baseLineHeight = ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP,
                                            onCitationTap = handleCitationTap,
                                            citationInfoResolver = citationResolver,
                                            codeBlock = codeBlockConfig,
                                            math = mathConfig,
                                        )
                                    } else {
                                        // 关掉 Markdown：同字号/行高的纯文本（CMW:2055-2066）。
                                        Text(
                                            text = visual,
                                            style = TextStyle(
                                                fontSize = ChatStyleSpec.USER_TEXT_SP.sp,
                                                lineHeight = ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP.sp,
                                                color = com.psyche.memo.ui.chat.chatSurfacePlainTextColor(isUser = true),
                                            ),
                                        )
                                    }
                                }
                                is ImagePart -> Unit // 已整组渲染在气泡上方
                                is com.psyche.memo.data.model.FilePart ->
                                    com.psyche.memo.ui.chat.MessageDocCard(part)
                                else -> Text("‹${part.kind}›", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    // CMW:2382-2403 _buildBubbleContainer(isUser: true)：整条用户
                    // 正文一个气泡（primary@0.15/0.08 + r16 + 内边距 12）。
                    if (userHasText) {
                        com.psyche.memo.ui.chat.ChatBubbleSurface(isUser = true) { userContent() }
                    } else {
                        userContent()
                    }
                } else {
                    // CMW:2951-3009 —— 文本气泡与思考卡按 part 到达顺序交替出现，
                    // addVisible 在相邻块之间插 8pt；每段文本各自一个气泡
                    // （_buildAssistantTextBubbles，assistantBubbleSplitParagraphs
                    // 打开时按段落再拆）。助手正文 15.7 / 行高 1.5×15.7。
                    assistantBlocks.forEachIndexed { index, block ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        when (block) {
                            is com.psyche.memo.ui.chat.AssistantBlock.Text -> {
                                // visual 规则（chat_message_widget.dart L1276）。
                                val visual = remember(block.text) {
                                    com.psyche.memo.data.model.AssistantRegexApplier.applyAll(
                                        block.text,
                                        assistantRegexRulesCache,
                                        com.psyche.memo.data.model.AssistantRegexScope.ASSISTANT,
                                        com.psyche.memo.data.model.AssistantRegexApplier.Target.VISUAL,
                                    )
                                }
                                // CMW:2505-2520 —— 拆段开关只在助手正文生效。
                                val parts = remember(visual, timelineSettings.assistantBubbleSplitParagraphs) {
                                    if (timelineSettings.assistantBubbleSplitParagraphs) {
                                        com.psyche.memo.ui.chat.splitAssistantParagraphs(visual)
                                    } else {
                                        listOf(visual)
                                    }
                                }
                                parts.forEachIndexed { partIndex, part ->
                                    if (partIndex > 0) Spacer(Modifier.height(8.dp))
                                    com.psyche.memo.ui.chat.ChatBubbleSurface(
                                        isUser = false,
                                        // CMW:2478-2484 _assistantBlockWidth：
                                        // 贴合内容时不给宽度约束，气泡裹住文字。
                                        modifier = if (timelineSettings.assistantBubbleFitContent) {
                                            Modifier
                                        } else {
                                            Modifier.fillMaxWidth()
                                        },
                                    ) {
                                        if (timelineSettings.enableAssistantMarkdown) {
                                            com.psyche.memo.ui.markdown.MarkdownText(
                                                markdown = part,
                                                baseFontSize = 15.7f,
                                                baseLineHeight = 23.55f,
                                                onCitationTap = handleCitationTap,
                                                citationInfoResolver = citationResolver,
                                                tableActions = tableActions,
                                                codeBlock = codeBlockConfig,
                                                codeBlockActions = codeBlockActions,
                                                math = mathConfig,
                                            )
                                        } else {
                                            // 关掉 Markdown：同字号/行高纯文本（CMW:2432-2441）。
                                            Text(
                                                text = part,
                                                style = TextStyle(
                                                    fontSize = 15.7.sp,
                                                    lineHeight = 23.55.sp,
                                                    color = com.psyche.memo.ui.chat.chatSurfacePlainTextColor(),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            is com.psyche.memo.ui.chat.AssistantBlock.Thinking ->
                                com.psyche.memo.ui.chat.ChainOfThoughtCard(
                                    steps = block.steps,
                                    settings = timelineSettings,
                                    conversationId = conversationId,
                                    approval = approvalService,
                                    askUser = askUserService,
                                    onRecoveredAnswer = onRecoveredAnswer,
                                    onToggleReasoning = onToggleReasoning,
                                )
                        }
                    }
                }
                if (!isUser && msg.isStreaming) {
                    // **用户 2026-09-12 点名**：原版三点脉动（CMW:2885-2925 /
                    // 3011-3020）换成「扫光文字」，并且挪成列表末尾**单独一行靠左**。
                    Spacer(Modifier.height(6.dp))
                    com.psyche.memo.ui.chat.ThinkingShimmerText(
                        modifier = Modifier.padding(start = 2.dp),
                        phrases = timelineSettings.thinkingIndicator.phrases,
                        fontSize = timelineSettings.thinkingIndicator.fontSizeSp.sp,
                        colorArgb = timelineSettings.thinkingIndicator.colorArgb,
                    )
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
                    // CMW:3025-3038 —— 译文卡走同一个 _buildSharedChatSurface：
                    // default 样式用 primaryContainer@0.25(dark)/0.30(light)，
                    // 选了 frosted/solid 时一起换成气泡皮肤。
                    com.psyche.memo.ui.chat.ChatBubbleSurface(
                        isUser = false,
                        modifier = Modifier.fillMaxWidth(),
                        defaultColor = cs.primaryContainer.copy(alpha = if (isDark) 0.25f else 0.30f),
                        padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Column {
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
        // chat_message_widget.dart:1847-1974); assistant rows: copy /
        // regenerate / speak / translate / more (CMW:3191-3410), version
        // selector and token stats trail the row.
        val showVersionSwitcher = versionCount > 1
        // CMW:1979 —— 多选态隐藏操作行与建议气泡。
        if (!selecting && (isUser || showVersionSwitcher || msg.totalTokens != null || !isUser)) {
            // CMW:1848 / 3209 —— 按钮行上方 8（用户与助手一致）。
            Spacer(Modifier.height(ChatStyleSpec.ACTIONS_TOP_GAP_DP.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isUser) {
                    MessageActionIcon(Lucide.Copy, "Copy", onClick = onCopy)
                    Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    MessageActionIcon(
                        Lucide.RefreshCw,
                        "Regenerate",
                        onClick = { onRegenerate?.invoke() },
                        enabled = onRegenerate != null,
                    )
                    Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    MessageActionIcon(Lucide.Pencil, "Edit", onClick = onEdit)
                    Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                    MessageActionIcon(Lucide.Ellipsis, "More", onClick = onMore)
                }
                if (!isUser) {
                    // CMW:3191-3209 —— 生成中整行隐藏（AnimatedSwitcher 220ms
                    // SizeTransition+FadeTransition）。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !msg.isStreaming,
                        enter = androidx.compose.animation.expandVertically(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ),
                        exit = androidx.compose.animation.shrinkVertically(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ) + androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MessageActionIcon(Lucide.Copy, "Copy", onClick = onCopy)
                            Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                            MessageActionIcon(
                                Lucide.RefreshCw,
                                "Regenerate",
                                // display_show_regenerate_confirm_dialog_v1（默认开）：
                                // 关闭时跳过确认直接重生成（CMW:1330）。
                                onClick = {
                                    if (skipRegenerateConfirm) onRegenerateAssistant?.invoke()
                                    else showRegenerateConfirm = true
                                },
                                enabled = onRegenerateAssistant != null,
                            )
                            Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                            // Speak/Stop/Resume：只有被朗读的那条消息切图标。
                            MessageActionIcon(
                                when (ttsAction) {
                                    com.psyche.memo.ui.chat.MessageTtsAction.STOP -> Lucide.CircleStop
                                    com.psyche.memo.ui.chat.MessageTtsAction.RESUME -> Lucide.Play
                                    else -> Lucide.Volume2
                                },
                                when (ttsAction) {
                                    com.psyche.memo.ui.chat.MessageTtsAction.STOP -> "Stop"
                                    com.psyche.memo.ui.chat.MessageTtsAction.RESUME -> "Resume"
                                    else -> "Speak"
                                },
                                onClick = {
                                    when (ttsAction) {
                                        com.psyche.memo.ui.chat.MessageTtsAction.STOP ->
                                            com.psyche.memo.ui.chat.TtsPlayer.stop()

                                        com.psyche.memo.ui.chat.MessageTtsAction.RESUME ->
                                            com.psyche.memo.ui.chat.TtsPlayer.togglePause()

                                        com.psyche.memo.ui.chat.MessageTtsAction.SPEAK ->
                                            com.psyche.memo.ui.chat.TtsPlayer.speak(context, msg.content, ownerId = msg.id)
                                    }
                                },
                            )
                            Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                            MessageActionIcon(
                                Lucide.Languages,
                                "Translate",
                                onClick = { showLanguageSheet = true },
                            )
                            Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                            MessageActionIcon(Lucide.Ellipsis, "More", onClick = onMore)
                        }
                    }
                }
                if (showVersionSwitcher) {
                    if (isUser || !msg.isStreaming) Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
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
            // 建议气泡（chat_message_widget.dart:3410-3418）—— 最后一条助手
            // 消息、非流式时显示。
            if (!selecting && !isUser && !msg.isStreaming && suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                com.psyche.memo.ui.chat.ChatSuggestionBubbles(
                    suggestions = suggestions,
                    onTap = onSuggestionTap,
                )
            }
        }
    }
    }
    if (showLanguageSheet) {
        com.psyche.memo.ui.chat.LanguageSelectSheet(
            onSelect = { lang ->
                showLanguageSheet = false
                onTranslate(lang.code)
            },
            onDismiss = { showLanguageSheet = false },
        )
    }
    if (showRegenerateConfirm) {
        // CMW:1328-1358 _confirmRegeneration —— 确认弹窗（标题/正文/取消/确定）。
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = {
                Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_title))
            },
            text = {
                Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_content))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showRegenerateConfirm = false
                        onRegenerateAssistant?.invoke()
                    },
                ) { Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showRegenerateConfirm = false },
                ) { Text(stringResource(UiR.string.chat_message_widget_regenerate_confirm_cancel)) }
            },
        )
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
    // CMW:1859-1959 —— 28×28 槽位内裸 IosIconButton(16, pad4)，无背景，
    // 色 onSurface@0.9；禁用态 alpha×0.45（ios_tactile.dart:54-59）。
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(ChatStyleSpec.ACTION_SLOT_DP.dp)
            .clickable(enabled = enabled) {
                // chat_message_widget.dart L3961: menu-row taps tick.
                Haptics.light(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = cs.onSurface.copy(
                alpha = if (enabled) ChatStyleSpec.ACTION_ICON_ALPHA
                else ChatStyleSpec.ACTION_DISABLED_ALPHA,
            ),
            modifier = Modifier.size(ChatStyleSpec.ACTION_ICON_DP.dp),
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
    enterToSend: Boolean = false,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    reasoningBudget: Int? = null,
    onOpenReasoning: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onSelectModel: () -> Unit,
    onOpenSearch: () -> Unit = {},
    // 模型按钮（chat_input_bar.dart CIB:1763-1773 + model_icon.dart
    // CurrentModelIcon）：选中模型后按钮显示品牌图标圆（modelIconAsset），
    // 无品牌资产时用首字母圆（modelIconInitial）；两者都空 → Boxes。
    modelIconAsset: String? = null,
    modelIconInitial: String? = null,
    // 搜索按钮（CIB:1782-1863）：searchActive 时显示所选搜索服务的品牌
    // 图标（searchIconAsset），否则 Globe。
    searchActive: Boolean = false,
    searchIconAsset: String? = null,
    onOpenTools: () -> Unit = {},
    onQuickPhrase: () -> Unit = {},
    attachments: List<ChatViewModel.PendingAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    // 语音输入执行器（chat_input_bar.dart asrProvider 的系统分支）；null =
    // 不可用，麦克风按钮按 CIB:2542-2546 showVoiceInput 条件隐藏。
    voice: com.psyche.memo.ui.chat.VoiceInputController? = null,
    // chat_input_bar.dart:2548-2553 `_inputFillColor(...)` 的三个入参：当前助手有壁纸
    // 时底色再乘一个比例，浅/深色不透明度来自显示设置（默认 0.8236 / 0.7396）。
    backgroundImageActive: Boolean = false,
    inputOpacityLight: Float = DEFAULT_INPUT_BG_OPACITY_LIGHT,
    inputOpacityDark: Float = DEFAULT_INPUT_BG_OPACITY_DARK,
    /** 长粘贴转文件（settings_provider.dart:252-255 两键）。 */
    longPaste: com.psyche.memo.ui.chat.LongPasteSettings = com.psyche.memo.ui.chat.LongPasteSettings(),
    /** 判定为长粘贴时把文本交出去（写文件 + 变成附件，不插入输入框）。 */
    onPasteText: (String) -> Unit = {},
    /** 麦克风权限未授予时请求（HomeScreen 持有 launcher）。 */
    onRequestMicPermission: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // 源码 chat_input_bar.dart:2547 —— theme.brightness == Brightness.dark。
    // 移植版没有暴露主题模式的 CompositionLocal，按 Material3 惯例由 surface 亮度判定
    // （浅色 surface 亮度 ≈0.96，深色 ≈0.05）。
    val isDark = cs.surface.luminance() < 0.5f

    // 语音会话状态（CIB:852-932 录音行、2750-2752 readOnly、2542-2546 可见性）。
    val voiceState: com.psyche.memo.ui.chat.VoiceInputController.State =
        if (voice != null) {
            voice.state.collectAsState().value
        } else {
            com.psyche.memo.ui.chat.VoiceInputController.State.Idle
        }
    val voiceLevels = voice?.levels?.collectAsState()?.value ?: emptyList()
    val voiceActive = voiceState != com.psyche.memo.ui.chat.VoiceInputController.State.Idle
    // CIB:2542-2546 showVoiceInput —— asr 可用才显示麦克风。
    val voiceAvailable = remember(voice) { voice?.canUse() == true }
    // CIB:_startVoiceInput —— 成功启动后 unfocus。
    val voiceFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    /** 麦克风权限查询用（点击回调里不能读 LocalContext）。 */
    val voiceMicContext = androidx.compose.ui.platform.LocalContext.current
    // CIB onPartialResults —— 实时转写进输入框。
    LaunchedEffect(voiceState) {
        val listening = voiceState as? com.psyche.memo.ui.chat.VoiceInputController.State.Listening
        if (listening != null && listening.partial.isNotEmpty()) {
            onInputChange(listening.partial)
        }
    }

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
                    // 源码 chat_input_bar.dart:2625 —— color: inputFillColor。
                    // 原版叠的是 BackdropFilter（真模糊背后的聊天内容）；Compose 的
                    // Modifier.blur 只模糊**自身内容**，而本层内容就是这块纯色 —— 模糊
                    // 纯色在观感上没有任何变化，却每帧都要走一遍模糊渲染管线。去掉它，
                    // 视觉一致、省掉一笔常驻开销。
                    .background(
                        color = inputFillColor(
                            cs = cs,
                            isDark = isDark,
                            backgroundImageActive = backgroundImageActive,
                            lightOpacity = inputOpacityLight,
                            darkOpacity = inputOpacityDark,
                        ),
                        shape = InputContainerShape,
                    ),
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
                // ① 附件内联预览区（源码 chat_input_bar.dart:2641-2642）
                AttachmentPreviewStrip(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                )

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
                    // CIB:2779-2782 —— 原版是 border:none + contentPadding
                    // (vertical:2, horizontal:0) 的裸输入框：M3 TextField 自带
                    // 16dp 横向内边距（会把打字区左右收窄）与 56dp 最小高，改用
                    // BasicTextField 复刻——横向零内边距，单行时文本垂直居中
                    // （interactiveAdjustment 语义），多行时内容撑高。
                    // 最小高按用户要求定：原版 kMinInteractiveDimension 是
                    // 48dp（M3 非 dense 字段默认 56dp），用户两次反馈上下留白
                    // 偏小 → 48 → 56 → 64。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // chat_input_bar.dart:1602-1665 `_handlePastedText` —— 剪贴板
                        // 文本超过阈值（按字素簇算）且开关打开时，**不插入输入框**，
                        // 而是写成一个 .txt 附件。原版把自定义 Paste 菜单项接到这条
                        // 路径上；Compose 的等价入口是 TextToolbar：包一层平台工具栏，
                        // 只替换 paste 回调，其余（复制/剪切/全选）原样透传。
                        val baseToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        val pasteToolbar = remember(baseToolbar, clipboard, longPaste, onPasteText) {
                            object : androidx.compose.ui.platform.TextToolbar {
                                override val status: androidx.compose.ui.platform.TextToolbarStatus
                                    get() = baseToolbar.status

                                override fun showMenu(
                                    rect: androidx.compose.ui.geometry.Rect,
                                    onCopyRequested: (() -> Unit)?,
                                    onPasteRequested: (() -> Unit)?,
                                    onCutRequested: (() -> Unit)?,
                                    onSelectAllRequested: (() -> Unit)?,
                                ) {
                                    baseToolbar.showMenu(
                                        rect,
                                        onCopyRequested,
                                        {
                                            val text = clipboard.getText()?.text.orEmpty()
                                            if (text.isEmpty() || !longPaste.isLongPaste(text)) {
                                                onPasteRequested?.invoke()
                                            } else {
                                                onPasteText(text)
                                            }
                                        },
                                        onCutRequested,
                                        onSelectAllRequested,
                                    )
                                }

                                override fun hide() = baseToolbar.hide()
                            }
                        }
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalTextToolbar provides pasteToolbar,
                        ) {
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            modifier = textFieldModifier,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = ChatStyleSpec.INPUT_TEXT_SP.sp,
                                color = cs.onSurface,
                            ),
                            cursorBrush = SolidColor(cs.primary),
                            // CIB:2750-2752 readOnly —— composerLocked || _ownsVoiceSession。
                            readOnly = voiceActive,
                            // 源码 chat_input_bar.dart:2741 —— maxLines: 5（未展开状态）
                            maxLines = 5,
                            // display_enter_to_send_on_mobile_v1（chat_input_bar.dart
                            // L2727）：关闭时回车换行，不再触发发送。
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                            ),
                            // CIB:934-938 _handleSend —— 语音会话中不触发发送。
                            keyboardActions = KeyboardActions(
                                onSend = { if (enterToSend && !streaming && !voiceActive) onSend() },
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) {
                                        Text(
                                            stringResource(UiR.string.chat_input_bar_hint),
                                            style = androidx.compose.ui.text.TextStyle(
                                                fontSize = ChatStyleSpec.INPUT_TEXT_SP.sp,
                                                color = cs.onSurface.copy(
                                                    alpha = ChatStyleSpec.INPUT_HINT_ALPHA,
                                                ),
                                            ),
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        }
                    }
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
                    // CIB:2837-2853 —— 录音行与常规按钮行经 AnimatedSwitcher
                    // (260ms) fade + 0.35 高度竖滑切换（入场上滑、出场下滑），
                    // AnimatedContent 等价实现。
                    AnimatedContent(
                        targetState = voiceActive,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(260)) { (it * 0.35f).toInt() }) togetherWith
                                (fadeOut(tween(260)) + slideOutVertically(tween(260)) { (it * 0.35f).toInt() })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "inputBottomRowSwitch",
                    ) { recording ->
                        if (recording && voice != null) {
                            ChatVoiceRecordingRow(
                                state = voiceState,
                                levels = voiceLevels,
                                isDark = isDark,
                                cs = cs,
                                voice = voice,
                                onFinalText = { text, send ->
                                    if (text.isNotEmpty()) {
                                        onInputChange(text)
                                        if (send) onSend()
                                    }
                                },
                            )
                        } else {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    // CIB:2891-2926 —— 左侧工具图标间 8。
                                    horizontalArrangement = Arrangement.spacedBy(
                                        ChatStyleSpec.INPUT_ACTIONS_GAP_DP.dp,
                                    ),
                                ) {
                                    // CIB:1763-1773 —— 模型按钮：选中模型后显示
                                    // CurrentModelIcon。底色透明（原版
                                    // backgroundColor: Colors.transparent，
                                    // RikkaHub ModelSelectorButton 同样是
                                    // AutoAIIcon(color = Color.Transparent)），
                                    // 品牌图标保留原本的品牌色，只有深色模式下
                                    // 需要反色的 mono 资产才 tint onSurface
                                    // （model_icon.dart 的 assetNeedsDarkInvert）。
                                    val modelAsset = modelIconAsset
                                    if (modelAsset != null || !modelIconInitial.isNullOrEmpty()) {
                                        IconButton(
                                            onClick = onSelectModel,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            if (modelAsset != null) {
                                                coil.compose.AsyncImage(
                                                    model = modelAsset,
                                                    contentDescription = stringResource(UiR.string.chat_input_bar_select_model_tooltip),
                                                    colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(modelAsset)) {
                                                        androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                                                    } else {
                                                        null
                                                    },
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            } else {
                                                Text(
                                                    text = modelIconInitial!!.trim().take(1).uppercase(),
                                                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.primary),
                                                )
                                            }
                                        }
                                    } else {
                                        InputIcon(
                                            Lucide.Boxes,
                                            stringResource(UiR.string.chat_input_bar_select_model_tooltip),
                                            onSelectModel,
                                            cs,
                                        )
                                    }
                                    // CIB:1811-1863 —— 搜索按钮：启用搜索时显示
                                    // 所选服务的品牌图标（active 色），否则 Globe。
                                    if (searchActive && searchIconAsset != null) {
                                        InputIconAsset(
                                            searchIconAsset,
                                            stringResource(UiR.string.chat_input_bar_online_search_tooltip),
                                            onOpenSearch,
                                            cs,
                                            active = true,
                                        )
                                    } else {
                                        InputIcon(
                                            Lucide.Globe,
                                            stringResource(UiR.string.chat_input_bar_online_search_tooltip),
                                            onOpenSearch,
                                            cs,
                                        )
                                    }
                                    // CIB:1880-1920 —— Brain 按钮渲染当前预算图标
                                    // （ReasoningIcons.budgetIcon），点开预算 sheet。
                                    InputIconAsset(
                                        asset = com.psyche.memo.ui.chat.ReasoningBudgetIcons
                                            .assetForBudget(reasoningBudget),
                                        label = stringResource(UiR.string.chat_input_bar_reasoning_strength_tooltip),
                                        onClick = onOpenReasoning,
                                        cs = cs,
                                    )
                                    InputIcon(
                                        Lucide.Hammer,
                                        stringResource(UiR.string.chat_input_bar_mcp_servers_tooltip),
                                        onOpenMcp,
                                        cs,
                                    )
                                    InputIcon(
                                        Lucide.Zap,
                                        stringResource(UiR.string.chat_input_bar_quick_phrase_tooltip),
                                        onQuickPhrase,
                                        cs,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    // CIB:2912/2928 —— 右侧 + 与语音按钮后各 8。
                                    horizontalArrangement = Arrangement.spacedBy(
                                        ChatStyleSpec.INPUT_ACTIONS_GAP_DP.dp,
                                    ),
                                ) {
                                    InputIcon(
                                        Lucide.Plus,
                                        stringResource(UiR.string.chat_input_bar_more_tooltip),
                                        onOpenTools,
                                        cs,
                                    )
                                    // CIB:2542-2546 —— asr 可用才显示麦克风；
                                    // 点击 _startVoiceInput（unfocus + start）。
                                    if (voiceAvailable) {
                                        InputIcon(
                                            Lucide.Mic,
                                            stringResource(UiR.string.chat_input_bar_voice_input_tooltip),
                                            {
                                                if (!voiceActive) {
                                                    // 先确保麦克风权限（云端与系统识别都要），
                                                    // 授权回调里再真正开始（原版 permission_handler 同款顺序）。
                                                    val micContext = voiceMicContext
                                                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                        micContext,
                                                        android.Manifest.permission.RECORD_AUDIO,
                                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    if (granted) {
                                                        voiceFocusManager.clearFocus()
                                                        voice?.start()
                                                    } else {
                                                        onRequestMicPermission()
                                                    }
                                                }
                                            },
                                            cs,
                                        )
                                    }
                                    // CIB:3264-3315 _CompactSendButton —— 32 圆（icon 18 +
                                    // pad 7）；可用/流式: primary 底 + onPrimary 图标；
                                    // 禁用: onSurface@0.12 底 + onSurface@0.38 图标。
                                    // 流式时图标换成原项目 assets/icons/stop.svg 的实心
                                    // 圆角方块（24 viewBox 内 14×14、rx2），并用
                                    // AnimatedSwitcher 等价的 Scale+Fade 做 200ms 形变。
                                    // CIB:2929-2942 _CompactSendButton enabled：
                                    // hasText || hasImages || hasDocs（只有附件的消息
                                    // 也能发，长粘贴转文件后输入框本来就是空的）。
                                    val canSend = input.isNotBlank() || attachments.isNotEmpty()
                                    val sendBg = if (canSend || streaming) cs.primary
                                    else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_BG_ALPHA)
                                    val sendFg = if (canSend || streaming) cs.onPrimary
                                    else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_FG_ALPHA)
                                    Box(
                                        modifier = Modifier
                                            .size(ChatStyleSpec.SEND_BUTTON_DP.dp)
                                            .background(sendBg, CircleShape)
                                            .clickable {
                                                if (streaming) onStop() else if (canSend) onSend()
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AnimatedContent(
                                            targetState = streaming,
                                            transitionSpec = {
                                                (scaleIn(
                                                    initialScale = 0.6f,
                                                    animationSpec = tween(
                                                        ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                    ),
                                                ) + fadeIn(
                                                    animationSpec = tween(
                                                        ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                    ),
                                                )) togetherWith
                                                    (scaleOut(
                                                        targetScale = 0.6f,
                                                        animationSpec = tween(
                                                            ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                        ),
                                                    ) + fadeOut(
                                                        animationSpec = tween(
                                                            ChatStyleSpec.SEND_ICON_SWITCH_MS,
                                                        ),
                                                    ))
                                            },
                                            label = "send-stop",
                                        ) { isStreaming ->
                                            if (isStreaming) {
                                                ChatStopSquare(
                                                    color = sendFg,
                                                    size = ChatStyleSpec.SEND_ICON_DP.dp,
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Lucide.ArrowUp,
                                                    contentDescription = "Send",
                                                    tint = sendFg,
                                                    modifier = Modifier.size(
                                                        ChatStyleSpec.SEND_ICON_DP.dp,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 停止图标 —— 原项目 `assets/icons/stop.svg` 的 Compose 等价物：
 * 24 viewBox 内一个 14×14、圆角 2 的**实心**方块（fill=currentColor）。
 * 按比例缩放到给定的 [size]（发送按钮用 18dp → 方块 10.5dp、圆角 1.5dp）。
 * 不用 Lucide 的描边方块：原项目是实心且比例不同，描边版观感偏"取消"。
 */
@Composable
private fun ChatStopSquare(
    color: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
) {
    val scale = size / ChatStyleSpec.STOP_SVG_VIEWBOX_DP.dp
    val side = ChatStyleSpec.STOP_SVG_SIDE_DP.dp * scale
    val radius = ChatStyleSpec.STOP_SVG_RADIUS_DP.dp * scale
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(side)
                .background(color, androidx.compose.foundation.shape.RoundedCornerShape(radius)),
        )
    }
}

/**
 * 录音行 —— chat_input_bar.dart `_buildVoiceRecordingRow`（CIB:852-932）1:1：
 * 取消 X — 波形/转写指示（Expanded）— 停止方块 — 发送 Check。
 * [onFinalText] 收到 (最终文本, 是否随后发送)。
 */
@Composable
private fun ChatVoiceRecordingRow(
    state: com.psyche.memo.ui.chat.VoiceInputController.State,
    levels: List<Float>,
    isDark: Boolean,
    cs: androidx.compose.material3.ColorScheme,
    voice: com.psyche.memo.ui.chat.VoiceInputController,
    onFinalText: (String, Boolean) -> Unit,
) {
    // CIB:854-855 canFinish = isListening && !_finishingVoice。
    val canFinish = state is com.psyche.memo.ui.chat.VoiceInputController.State.Listening
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ① 取消（CIB:859-863）—— 收尾中禁用。
        InputIcon(
            Lucide.X,
            stringResource(UiR.string.chat_input_bar_voice_cancel_tooltip),
            onClick = { voice.cancel() },
            cs = cs,
            enabled = canFinish,
        )
        // ② 波形 / 转写指示（CIB:864-901：Expanded + 左 8 右 2 + 32 高，
        // AnimatedSwitcher 180ms fade 切换）。
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 2.dp)
                .height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = !canFinish,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "voiceTranscribingSwitch",
            ) { transcribing ->
                if (transcribing) {
                    com.psyche.memo.ui.chat.VoiceTranscribingIndicator(
                        label = stringResource(UiR.string.chat_input_bar_voice_transcribing),
                        color = cs.onSurface.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    com.psyche.memo.ui.chat.VoiceWaveform(
                        levels = levels,
                        color = cs.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // ③ 停止（CIB:904-920）：12×12 圆角 3.5 方块，颜色 = 图标前景色。
        val stopTint = cs.onSurface.copy(
            alpha = if (isDark) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
            else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT,
        )
        IconButton(
            onClick = { voice.finish { text -> onFinalText(text, false) } },
            enabled = canFinish,
            modifier = Modifier.size(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(stopTint, RoundedCornerShape(3.5f.dp)),
            )
        }
        Spacer(Modifier.width(8.dp))
        // ④ 发送（CIB:923-929）：_CompactSendButton —— primary 底 Check 图标，
        // 禁用态灰底灰字。
        Box(
            modifier = Modifier
                .size(ChatStyleSpec.SEND_BUTTON_DP.dp)
                .background(
                    if (canFinish) cs.primary
                    else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_BG_ALPHA),
                    CircleShape,
                )
                .clickable(enabled = canFinish) {
                    voice.finish { text -> onFinalText(text, true) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Check,
                contentDescription = stringResource(UiR.string.chat_input_bar_voice_send_tooltip),
                tint = if (canFinish) cs.onPrimary
                else cs.onSurface.copy(alpha = ChatStyleSpec.SEND_DISABLED_FG_ALPHA),
                modifier = Modifier.size(ChatStyleSpec.SEND_ICON_DP.dp),
            )
        }
    }
}

@Composable
private fun InputIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    cs: androidx.compose.material3.ColorScheme,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            icon,
            contentDescription = label,
            // CIB:3211-3213 —— 非 active 色 onSurface@0.70(dark)/0.54(light)；
            // 禁用态 = 原色 ×0.45（ios_tactile.dart:54-59）。
            tint = cs.onSurface.copy(
                alpha = (if (cs.surface.luminance() < 0.5f) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
                else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT) * if (enabled) 1f else 0.45f,
            ),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun InputIconAsset(
    asset: String,
    label: String,
    onClick: () -> Unit,
    cs: androidx.compose.material3.ColorScheme,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        // SVG brand glyphs are tinted (kelivo's SvgPicture/Image with
        // colorBlendMode srcIn) so a mono logo stays legible in both themes;
        // raster brand icons keep their own colours, like RikkaHub's
        // AutoAIIcon, which only injects a fill for SVGs.
        val tint = if (asset.endsWith(".svg")) {
            androidx.compose.ui.graphics.ColorFilter.tint(
                if (active) {
                    cs.primary
                } else {
                    cs.onSurface.copy(
                        alpha = if (cs.surface.luminance() < 0.5f) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
                        else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT,
                    )
                },
            )
        } else {
            null
        }
        coil.compose.AsyncImage(
            model = asset,
            contentDescription = label,
            colorFilter = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Shared formatter: constructing a SimpleDateFormat per call is one of the
 * heavier allocations in a list row, and this runs for every message header on
 * every recomposition while scrolling. SimpleDateFormat is not thread-safe, but
 * this is only touched from composition on the UI thread.
 */
/** 预热 Markdown 解析缓存时最多处理的最近消息条数。 */
private const val MARKDOWN_PRELOAD_MESSAGES = 60

/**
 * 选中的云端 ASR 服务（`asr_selected_service_id_v1` + `asr_services_v1`）：
 * 没选 / 不存在 / 未配置 → null（回系统识别）。已接的 kind 只有 MiMo 与 Step，
 * 其余云端 kind 交给 [com.psyche.memo.provider.CloudAsrService] 抛错——这里先按
 * 「已配置」返回，让 UI 能显示麦克风，失败时控制器自己回 Idle。
 */
private fun selectedCloudAsrService(container: AppContainerImpl): com.psyche.memo.ui.AsrServiceOptions? {
    val store = container.asrServicesStore
    if (store.services.isEmpty()) store.load()
    val id = store.selectedServiceId ?: return null
    val service = store.services.firstOrNull { it.id == id } ?: return null
    return service.takeIf { it.isConfigured }
}

/** 距顶多少 dp 内触发往前加载历史（message_list_view.dart:1816 的 96 逻辑像素）。 */
private const val HISTORY_LOAD_TRIGGER_DP = 96f

/** 消息导航按钮三态（settings_provider.dart:4991-5005 的默认值为 scroll）。 */
private const val MOBILE_NAV_ALWAYS = "always"
private const val MOBILE_NAV_SCROLL = "scroll"
private const val MOBILE_NAV_NEVER = "never"

private val TIME_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun timeStr(millis: Long): String = TIME_FORMATTER.format(Date(millis))


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
    /** 逻辑开合状态（不是动画中的位移）——方向门控必须用它，否则"关闭动画还没跑完"
     *  会被当成"已打开"，紧接着的反向拖动会被判成反方向而毫无响应。 */
    isDrawerOpen: () -> Boolean,
    /** 拖动真正开始时调用：取消正在跑的收尾动画，免得它和手指抢 offset。 */
    onDragStart: () -> Unit,
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
        // 起手位置不限：用户要的是「在对话界面任意位置横滑就能拉出侧边栏」。
        // 方向仍然门控（见下），所以反方向拖动不会被吃掉。
        val startedOpen = isDrawerOpen()
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
                // 方向门控：关闭态只认右拖、打开态只认左拖。反方向完全不参与，
                // 因此不会调 onPresent() —— 它会把 presenting 置真、让 `if
                // (presenting)` 插入 scrim 节点，那一下布局变化正是用户看到的
                // "界面向反方向也抖一下"。
                val wrongWay = if (startedOpen) totalDx > 0f else totalDx < 0f
                if (!wrongWay && kotlin.math.abs(totalDx) > slopPx) {
                    pastSlop = true
                    onDragStart()
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
