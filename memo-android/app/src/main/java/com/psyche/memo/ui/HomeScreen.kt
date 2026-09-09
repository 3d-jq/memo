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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
        // 新建的会话挂到全局当前助手（chat_service.createDraftConversation 语义），
        // 否则切换助手后新建的会话会被抽屉按助手过滤掉。
        val conv = Conversation.create(
            title = newChatTitle,
            assistantId = container.currentAssistantId.value,
        )
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
                onOpenSearchServices = onOpenSearchServices,
                onOpenWorldBookPage = onOpenWorldBookPage,
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
                    onOpenTranslate = onOpenTranslate,
                    onEditAssistant = onEditAssistant,
                    onManageTags = onManageTags,
                )
            }
        }
    }
}

private fun loadQuickPhrases(container: AppContainerImpl): List<com.psyche.memo.data.model.QuickPhrase> {
    val repo = com.psyche.memo.data.repo.QuickPhraseRepository(container.database.writableDatabase)
    val assistant = container.currentAssistant()
    return repo.globalPhrases() + if (assistant != null) repo.forAssistant(assistant.id) else emptyList()
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
    onOpenSearchServices: () -> Unit = {},
    onOpenWorldBookPage: () -> Unit = {},
) {
    val vm: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(container, conversationId),
    )
    val messages by vm.messages.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val input by vm.input.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val providerId by vm.selectedProviderId.collectAsState()
    val modelId by vm.selectedModelId.collectAsState()
    val versionInfo by vm.versionInfo.collectAsState()
    // 思考卡 / 工具卡的 6 个显示开关（settings_provider.dart display_*）。走
    // SharedPreferences 直读，从显示设置页返回时 NavHost 重建本页即拿到新值。
    val timelineSettings = remember {
        com.psyche.memo.ui.chat.ChatTimelineSettings.fromPrefs { key ->
            container.preferenceRepository.readLocal(key)
        }
    }
    // 工具执行服务（tool_approval_service / ask_user_interaction_service）—— 审批卡、
    // ask-user 卡与时间线可见性都从这里取状态。
    val approvalService = container.toolApprovalService
    val askUserService = container.askUserInteractionService
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
    var compressing by remember { mutableStateOf(false) }
    var worldBooksAvailable by remember { mutableStateOf(false) }
    var showOcrPrompt by remember { mutableStateOf(false) }
    var ocrSettings by remember {
        mutableStateOf(com.psyche.memo.provider.OcrService.settingsOf(container.preferenceRepository))
    }
    val attachments by vm.attachments.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 附件选取（bottom_tools_sheet → file_upload_service）：URI 先拷进 upload
    // 目录再进待发列表，发送后并入用户消息 parts。
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val imported = uris.mapNotNull {
                    com.psyche.memo.provider.AttachmentStore.import(context, it)
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
            vm.addAttachments(listOf(com.psyche.memo.provider.AttachmentStore.fromCapturedFile(file)))
        }
    }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val imported = uris.mapNotNull {
                    com.psyche.memo.provider.AttachmentStore.import(context, it)
                }
                vm.addAttachments(imported)
            }
        }
    }
    // 语音输入执行器（chat_input_bar.dart 的系统 ASR 分支）。应用上下文持有，
    // 避免持有 Activity 导致的 SpeechRecognizer 泄漏。
    val voiceAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val voiceInput = remember {
        com.psyche.memo.ui.chat.VoiceInputController(voiceAppContext)
    }
    var showMiniMap by remember { mutableStateOf(false) }
    val timelineListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // ---- 消息操作批次状态（more sheet / 编辑 / regenerate 确认） ----
    var moreFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var editFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var regenerateFor by remember { mutableStateOf<ChatViewModel.UiMessage?>(null) }
    var selectCopyFor by remember { mutableStateOf<String?>(null) }
    var htmlPreviewFor by remember { mutableStateOf<String?>(null) }

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
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
                    primaryKey = "id",
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
        container.preferenceRepository.readLocal("display_chat_background_mask_strength_v1")
            ?.toFloatOrNull() ?: 1f
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
                    items(messages, key = { it.id }) { msg ->
                        val isLastAssistant = msg.role == "assistant" &&
                            messages.lastOrNull { it.role == "assistant" }?.id == msg.id
                        val canSelect = msg.role == "user" || msg.role == "assistant"
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
                            selecting = selecting,
                            suggestions = if (isLastAssistant) suggestions else emptyList(),
                            onSuggestionTap = { vm.sendSuggestion(it) },
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
                            onRegenerateAssistant = if (msg.role == "assistant") {
                                {
                                    // 助手重新生成 = 从它前面最近一条用户消息重发。
                                    val idx = messages.indexOfFirst { it.id == msg.id }
                                    val anchor = messages.take(idx).lastOrNull { it.role == "user" }
                                    if (anchor != null) regenerateFor = anchor
                                }
                            } else null,
                            onSpeak = {
                                // CMW HPC.speakMessage —— 播放中再点即停止。
                                if (com.psyche.memo.ui.chat.TtsPlayer.speaking.value) {
                                    com.psyche.memo.ui.chat.TtsPlayer.stop()
                                } else {
                                    com.psyche.memo.ui.chat.TtsPlayer.speak(context, msg.content)
                                }
                            },
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
                            approvalService = approvalService,
                            askUserService = askUserService,
                            onRecoveredAnswer = { part, result ->
                                vm.resumeAfterToolAnswer(msg.id, part, result.jsonString)
                            },
                        )
                            }
                        }
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
        ChatInputBar(
            input = input,
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
        )
        }
    }
    }

    if (showReasoningSheet) {
        com.psyche.memo.ui.chat.ReasoningBudgetSheet(
            container = container,
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
        val selectedMessages = messages.filter { it.id in selectedIds }
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
                container.preferenceRepository.readLocal("user_name")
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
            onDismiss = { showCompressDialog = false },
            onConfirm = { mode, maxChars, keepUserMessages ->
                showCompressDialog = false
                compressing = true
                vm.compressContext(mode, maxChars, keepUserMessages) { newId, errorKey ->
                    compressing = false
                    if (newId != null) {
                        onOpenConversation(newId)
                    } else {
                        val message = when (errorKey) {
                            "no_messages" -> container.appContext.getString(UiR.string.compress_context_no_messages)
                            "no_conversation" -> container.appContext.getString(UiR.string.compress_context_no_conversation)
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

    if (compressing) {
        com.psyche.memo.ui.chat.CompressLoadingDialog()
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
                        htmlPreviewFor = target.content
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

    htmlPreviewFor?.let { markdown ->
        com.psyche.memo.ui.chat.HtmlPreviewScreen(
            markdown = markdown,
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: ChatViewModel.UiMessage,
    selecting: Boolean = false,
    suggestions: List<String> = emptyList(),
    onSuggestionTap: (String) -> Unit = {},
    assistantLabel: String,
    versionCount: Int,
    versionIndex: Int,
    onPrevVersion: (() -> Unit)?,
    onNextVersion: (() -> Unit)?,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    /** 助手消息的重新生成（CMW:3239-3249 _confirmRegeneration 确认后执行）。 */
    onRegenerateAssistant: (() -> Unit)? = null,
    /** Speak 按钮（CMW:3253-3291）：调用方切换 TtsPlayer 播放/停止。 */
    onSpeak: () -> Unit = {},
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
    /** 工具审批服务（tool_approval_service.dart）—— 审批卡与时间线可见性。 */
    approvalService: ToolApprovalService?,
    /** ask-user 交互服务（ask_user_interaction_service.dart）。 */
    askUserService: AskUserInteractionService?,
    /** 恢复已持久化 ask-user 回答（home_page_controller.submitRecoveredAskUserAnswer）。 */
    onRecoveredAnswer: ((ToolUiPart, AskUserResult) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val rowView = LocalView.current
    val isUser = msg.role == "user"
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
    // TTS 播放状态 → Speak/Stop 图标切换（CMW:3253-3291 isActive）。
    val ttsSpeaking by com.psyche.memo.ui.chat.TtsPlayer.speaking.collectAsState()
    // search_web / builtin_search 工具结果提取为引用来源
    // （chat_message_widget.dart _allSearchItems，从后往前、去重）。
    val searchItems = remember(msg.id, msg.parts) {
        com.psyche.memo.ui.chat.extractCitationItems(msg.parts)
    }
    // CMW:3707-3711 的第三分支 _buildToolMessage(1662-1706)：role == tool 的
    // 消息没有头像/气泡/操作行，正文本身就是 {tool, arguments, result, metadata}，
    // 渲染成 h16 v6 里的一张工具卡；按显示设置不可见时整条不占位。
    if (msg.role == "tool") {
        val toolPart = remember(msg.id, msg.parts) {
            com.psyche.memo.ui.chat.ToolUiPart.fromToolMessage(msg.id, msg.content)
        }
        val visible = toolPart != null && com.psyche.memo.ui.chat.isTimelineToolVisible(
            toolName = toolPart.toolName,
            loading = toolPart.loading,
            showToolCards = timelineSettings.showToolCards,
            filterBuiltinSearch = false,
        )
        if (visible && toolPart != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
            // 32 用户头像（CMW:1773-1807 + 1590-1659；默认分支 Lucide.User 18
            // primary 于 primary@0.1 圆底）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(UiR.string.user_provider_default_user_name),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface.copy(alpha = 0.7f),
                        ),
                    )
                    Spacer(Modifier.height(ChatStyleSpec.NAME_TIME_GAP_DP.dp))
                    Text(
                        text = timeStr(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            color = cs.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                }
                Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
                Box(
                    modifier = Modifier
                        .size(ChatStyleSpec.AVATAR_SIZE_DP.dp)
                        .background(
                            cs.primary.copy(alpha = ChatStyleSpec.AVATAR_BG_ALPHA),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.User,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(ChatStyleSpec.AVATAR_ICON_DP.dp),
                    )
                }
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
                Spacer(Modifier.width(ChatStyleSpec.ASSISTANT_AVATAR_NAME_GAP_DP.dp))
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
                        text = timeStr(msg.timestamp),
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
            Column(
                modifier = Modifier
                    .then(
                        if (isUser) Modifier.widthIn(max = maxBubbleWidth)
                        // 助手块默认撑满整行（CMW:2478-2484
                        // _assistantBlockWidth，assistantBubbleFitContent 默认关）。
                        else Modifier.fillMaxWidth()
                    )
                    .background(
                        // CMW:2394-2398 —— 用户 primary@0.15(dark)/0.08(light)，
                        // 助手无底色（bareOnDefault）。
                        color = if (isUser) cs.primary.copy(
                            alpha = if (isDark) ChatStyleSpec.USER_BUBBLE_ALPHA_DARK
                            else ChatStyleSpec.USER_BUBBLE_ALPHA_LIGHT,
                        ) else Color.Transparent,
                        shape = RoundedCornerShape(ChatStyleSpec.BUBBLE_CORNER_DP.dp),
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
                    )
                    // CMW:2393 —— 气泡内边距 all 12；助手是 bareOnDefault，
                    // _buildSharedChatSurface 直接返回无内边距的 child，只有用户
                    // 气泡才留这 12（CMW:3824-3826），否则助手内容会窄一圈。
                    .then(
                        if (isUser) Modifier.padding(ChatStyleSpec.BUBBLE_PADDING_DP.dp)
                        else Modifier
                    ),
            ) {
                // 图片附件（chat_message_widget.dart _buildAttachmentPreview
                // ImagePart 分支）：整组渲染，点击可跨图翻页查看。
                if (msg.parts.any { it is ImagePart }) {
                    com.psyche.memo.ui.chat.MessageImageAttachments(
                        parts = msg.parts,
                        onOpenViewer = { uris, index -> viewerState = uris to index },
                    )
                }
                if (isUser) {
                    for (part in msg.parts) {
                        when (part) {
                            is TextPart ->
                                // CMW:2046-2054 —— 用户正文 15.5 / 行高 1.45×15.5。
                                com.psyche.memo.ui.markdown.MarkdownText(
                                    markdown = part.text,
                                    baseFontSize = ChatStyleSpec.USER_TEXT_SP,
                                    baseLineHeight = ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP,
                                )
                            is ImagePart -> Unit // 已整组渲染在气泡顶部
                            else -> Text("‹${part.kind}›", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    // CMW:2951-3009 —— 文本气泡与思考卡按 part 到达顺序交替出现，
                    // addVisible 在相邻块之间插 8pt；助手正文 15.7 / 行高 1.5×15.7。
                    assistantBlocks.forEachIndexed { index, block ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        when (block) {
                            is com.psyche.memo.ui.chat.AssistantBlock.Text ->
                                com.psyche.memo.ui.markdown.MarkdownText(
                                    markdown = block.text,
                                    baseFontSize = 15.7f,
                                    baseLineHeight = 23.55f,
                                )
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
                    if (assistantBlocks.isEmpty() && msg.content.isEmpty()) {
                        // CMW:2885-2925 —— 还没有任何可见内容时的等待气泡：三点
                        // LoadingIndicator（4105-4196），非 Material 圆环。
                        com.psyche.memo.ui.chat.LoadingDotsIndicator(
                            color = cs.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else if (msg.content.isNotEmpty()) {
                        // CMW:3011-3020 —— 已有正文时把指示器挂在最后一个块之后。
                        com.psyche.memo.ui.chat.LoadingDotsIndicator(
                            color = cs.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
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
                                onClick = { showRegenerateConfirm = true },
                                enabled = onRegenerateAssistant != null,
                            )
                            Spacer(Modifier.width(ChatStyleSpec.ACTION_GAP_DP.dp))
                            // Speak/Stop：播放中切 CircleStop（CMW:3253-3291）。
                            MessageActionIcon(
                                if (ttsSpeaking) Lucide.CircleStop else Lucide.Volume2,
                                if (ttsSpeaking) "Stop" else "Speak",
                                onClick = onSpeak,
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
    reasoningBudget: Int? = null,
    onOpenReasoning: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onSelectModel: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenTools: () -> Unit = {},
    onQuickPhrase: () -> Unit = {},
    attachments: List<ChatViewModel.PendingAttachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    // 语音输入执行器（chat_input_bar.dart asrProvider 的系统分支）；null =
    // 不可用，麦克风按钮按 CIB:2542-2546 showVoiceInput 条件隐藏。
    voice: com.psyche.memo.ui.chat.VoiceInputController? = null,
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
                    // BasicTextField 复刻——横向零内边距；最小高 48dp =
                    // InputDecorator 的 kMinInteractiveDimension（非 dense 字段
                    // 的 minContainerHeight，input_decorator.dart L1120-1123），
                    // 单行时文本垂直居中（interactiveAdjustment 语义），多行时
                    // 内容撑高。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        contentAlignment = Alignment.CenterStart,
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            // CIB:934-938 _handleSend —— 语音会话中不触发发送。
                            keyboardActions = KeyboardActions(
                                onSend = { if (!streaming && !voiceActive) onSend() },
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
                                    InputIcon(
                                        Lucide.Boxes,
                                        stringResource(UiR.string.chat_input_bar_select_model_tooltip),
                                        onSelectModel,
                                        cs,
                                    )
                                    InputIcon(
                                        Lucide.Globe,
                                        stringResource(UiR.string.chat_input_bar_online_search_tooltip),
                                        onOpenSearch,
                                        cs,
                                    )
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
                                                    voiceFocusManager.clearFocus()
                                                    voice?.start()
                                                }
                                            },
                                            cs,
                                        )
                                    }
                                    // CIB:3264-3315 _CompactSendButton —— 32 圆（icon 18 +
                                    // pad 7）；可用/流式: primary 底 + onPrimary 图标；
                                    // 禁用: onSurface@0.12 底 + onSurface@0.38 图标。
                                    val canSend = input.isNotBlank()
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
                                        Icon(
                                            imageVector = if (streaming) Lucide.CircleStop else Lucide.ArrowUp,
                                            contentDescription = if (streaming) "Stop" else "Send",
                                            tint = sendFg,
                                            modifier = Modifier.size(ChatStyleSpec.SEND_ICON_DP.dp),
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
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        coil.compose.AsyncImage(
            model = asset,
            contentDescription = label,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                cs.onSurface.copy(
                    alpha = if (cs.surface.luminance() < 0.5f) ChatStyleSpec.COMPACT_ICON_ALPHA_DARK
                    else ChatStyleSpec.COMPACT_ICON_ALPHA_LIGHT,
                ),
            ),
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
