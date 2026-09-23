package com.psyche.memo.ui.chat

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Glasses
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Map
import com.composables.icons.lucide.MessageCircleDashed
import com.composables.icons.lucide.MessageCirclePlus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.User
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ChatViewModel
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.ui.chat.AskUserInteractionService
import com.psyche.memo.ui.chat.ChatInterruptionPanel
import com.psyche.memo.ui.chat.ToolApprovalService
import com.psyche.memo.ui.chat.currentChatInterruption
import com.psyche.memo.ui.chat.ImePinTracker
import com.psyche.memo.ui.chat.checkpointPart
import com.psyche.memo.ui.chat.shouldPinTimelineOnImeRise
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.psyche.memo.ui.chat.isToolModel
import com.psyche.memo.ui.chat.isReasoningModel
import com.psyche.memo.ui.chat.mcpButtonActive
import com.psyche.memo.ui.chat.quickPhraseButtonVisible
import com.psyche.memo.ui.chat.MOBILE_NAV_NEVER
import com.psyche.memo.ui.chat.MOBILE_NAV_SCROLL
import com.psyche.memo.ui.chat.MOBILE_NAV_ALWAYS
import com.psyche.memo.ui.chat.HISTORY_LOAD_TRIGGER_DP
import com.psyche.memo.ui.chat.selectedCloudAsrService
import com.psyche.memo.ui.chat.CHAT_TIMELINE_TAG
import com.psyche.memo.ui.chat.SCROLL_BOTTOM_ITEM_KEY
import com.psyche.memo.ui.chat.newActionToggleable
import com.psyche.memo.ui.chat.TimelineSkeleton
import com.psyche.memo.ui.chat.showTimelineSkeleton
import com.psyche.memo.ui.chat.headerAssistantId
import com.psyche.memo.ui.chat.loadQuickPhrases
import com.psyche.memo.ui.MiniMapSheet
import com.psyche.memo.ui.SearchSettingsSheet
import com.psyche.memo.ui.OcrPromptSheet
import com.psyche.memo.ui.SkillSelectorSheet
import com.psyche.memo.ui.WorkspaceSelectorSheet
import com.psyche.memo.ui.BottomToolsSheet
import com.psyche.memo.ui.ModelSelectSheet
import com.psyche.memo.ui.rememberLoaded
import com.psyche.memo.ui.ProviderAvatarSource
import com.psyche.memo.ui.providerAvatarSource
import com.psyche.memo.ui.BrandAssets
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.UIStrings
import com.psyche.memo.ui.AssistantListAvatar
import com.psyche.memo.ui.IosCheckbox
import com.psyche.memo.ui.loadModelOptions
import com.psyche.memo.ui.LocalHapticsSettings

/**
 * 键盘抬起「把对话内容一起顶上去」的钉底副作用（原版
 * `scroll_controller.dart:312-321 pinBottomDuringViewportResizeIfNeeded`）。
 *
 * **为什么必须是一个独立 composable**：`WindowInsets.ime` 的每个类型都挂在
 * 快照状态上，在组合体里读 `getBottom()` 等于**订阅**了 IME inset 的变化 ——
 * 键盘动画期间 inset 每帧都在变，读它的那个 composable 就会每帧重组。
 * 原先这段直接写在 `ChatContent` 里，而 `ChatContent` 是个约 1700 行的组合
 * （顶栏 + 整条时间线 + 输入栏接线 + 所有 sheet/dialog 挂载），于是「点输入框、
 * 键盘抬起」的每一次动画都带动它整体重组约 60 次 —— 用户 2026-09-17 报的
 * 「输入框抬起一顿一顿」就是这个。
 *
 * 抽成独立函数后，按帧重组的只剩这个**不渲染任何东西**的函数；`ChatContent`
 * 自身不再订阅 inset，键盘动画期间不再重组。语义与抽出前逐字一致：
 * - inset 的读取时机不变（composition 期，位置与原先相同）
 * - `tailNearBottom` / `pointerDown` 仍在组合期读（由调用方以 lambda 传入，
 *   读取点依旧是组合期，`derivedStateOf` 的「只在新视口布局之前取值」语义不变）
 * - `record` 在组合期、`consume` 在 `LaunchedEffect` 里，与原先一致
 * - `ImePinTracker` 的初值仍是「本 composable 首次组合时观察到的 inset」，
 *   对齐 `home_page.dart:739-742` 的 post-frame 播种
 */
@Composable
private fun ImeRisePinEffect(
    tailNearBottom: () -> Boolean,
    pointerDown: () -> Boolean,
    scrollTimelineToBottom: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    // 初值取当前 inset（对齐 home_page.dart:739-742 的 post-frame 播种）：否则「启动时
    // 键盘已经开着」会被当成一次抬起。
    val imePin = remember { ImePinTracker(imeBottomPx) }
    imePin.record(
        imeBottomPx = imeBottomPx,
        shouldPin = shouldPinTimelineOnImeRise(
            previousImeBottomPx = imePin.previousImeBottomPx,
            nextImeBottomPx = imeBottomPx,
            pointerDown = pointerDown(),
            tailNearBottom = tailNearBottom(),
        ),
    )
    androidx.compose.runtime.LaunchedEffect(imeBottomPx) {
        if (imePin.consume(imeBottomPx)) scrollTimelineToBottom()
    }
}

/**
 * 聊天会话页主体 —— 第 3 步从 `ui/HomeScreen.kt` 摘出（纯搬运）：VM 取数与状态接线、
 * 顶栏、时间线（LazyColumn + 末尾哨兵 + 骨架 + 导航面板）、输入栏接线、以及各个
 * sheet/dialog 的挂载。时间线/顶栏的进一步拆分留作第 3 步的后半段（那时才需要把状态
 * 作为参数显式传进去）。
 */

@Composable
fun ChatContent(
    container: AppContainerImpl,
    conversationId: String,
    onOpenDrawer: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    isTemporary: Boolean = false,
    onOpenConversation: (String) -> Unit = {},
    onOpenSearchServices: () -> Unit = {},
    onOpenWorldBookPage: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenWorkspaces: () -> Unit,
    /**
     * 生成服务的「管理」出口（+ 面板选择器末尾那行；kind = image/video）。
     * **不给默认值**：漏传的话这一行会被静默吞掉（见 onOpenWorkspaces 的教训）。
     */
    onOpenGenerationServices: (String) -> Unit,
    titleRefreshTick: Int = 0,
    injectPresets: Boolean = false,
    /**
     * 时间线透明度（会话切换的交叉淡入，原版 `_convoFadeController`）。传 lambda 是
     * 为了让每帧的 alpha 变化只脏 `graphicsLayer` 的绘制阶段，不重组合整页。
     */
    timelineAlpha: () -> Float = { 1f },
    /** 是否处于「冷启动那一次窗口加载」（只有它会露骨架，见 showTimelineSkeleton）。 */
    startupWindowPending: Boolean = false,
) {
    val vm: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(container, conversationId, injectPresets = injectPresets),
    )
    // 抽屉改写了本会话标题 → 让 vm 重读库里的标题刷新顶栏（首次 tick=0 不触发）。
    androidx.compose.runtime.LaunchedEffect(titleRefreshTick) {
        if (titleRefreshTick > 0) vm.refreshTitle()
    }
    // 会话页 VM 的回收登记：`viewModel(key = conversationId)` 不会因为 key 变化释放旧 VM，
    // 由容器在切会话时把不忙的旧 VM 清成空壳（见 AppContainerImpl.registerChatViewModel）。
    androidx.compose.runtime.LaunchedEffect(vm) {
        // 登记进容器的会话页 VM 表（它会把**上一个不忙的** VM 清成空壳）。
        // **不要在页面销毁时回收**：进设置页/其它路由时本页会被拿掉，回收会把当前会话的
        // 窗口清空，返回时又要整窗重读 + 重渲染（用户 2026-09-15「点击设置 返回 又会加载
        // 对话 又会卡一下」）。原版 controller 活在页面之上（内存缓存），返回是瞬时的。
        container.registerChatViewModel(vm)
    }
    // 首次进入本会话、或被回收后切回来 → 补读首屏窗口（回收时 tailLoaded 被清掉）。
    androidx.compose.runtime.LaunchedEffect(conversationId) {
        com.psyche.memo.PerfProbe.mark("page-composed")
        vm.ensureLoaded()
    }
    val messages by vm.messages.collectAsState()
    // 顶栏 `+` 的三态（home_page.dart:1504-1516 / MLV 的「空会话换成临时聊天开关」）：
    // **组合期不再查库** —— 用 VM 的「首屏已读」+ 窗口是否为空判断（见
    // `newActionToggleable` 的注释）。
    val tailLoaded by vm.tailLoaded.collectAsState()
    // 首屏消息第一次进状态（诊断用；PerfProbe 只在 debuggable 构建输出）。
    androidx.compose.runtime.LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty()) com.psyche.memo.PerfProbe.mark("tail-in-state")
    }
    val newActionToggleable = newActionToggleable(
        isTemporary = isTemporary,
        tailLoaded = tailLoaded,
        messagesEmpty = messages.isEmpty(),
    )
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
    // 底部打断面板的数据源（问询 / 审批）——见 ChatInterruptionPanel.kt。
    val approvalPending by approvalService.pendingRequests.collectAsState()
    val askUserPending by askUserService.pendingRequests.collectAsState()
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
    // home_view_model.dart:185/423/495 —— 「消息生成」触觉由 ViewModel 在生成开跑前
    // 触发（发送/回车发送/重新生成/建议气泡全覆盖），页面只注入：有 View 才能发的
    // 触觉 + 显示设置里的 `haptics on generate` 开关。
    androidx.compose.runtime.SideEffect {
        vm.onHapticFeedback = { if (chatHaptics.onGenerate) Haptics.light(chatView) }
    }

    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var showModelSheet by remember { mutableStateOf(false) }
    var showReasoningSheet by remember { mutableStateOf(false) }
    var reasoningBudget by remember { mutableStateOf(com.psyche.memo.ui.chat.readBudget(container)) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    /** 生成图片 / 生成视频面板（自研功能）；null = 关着，否则是 image / video。 */
    var generationKind by remember { mutableStateOf<String?>(null) }
    var quickPhrases by remember { mutableStateOf<List<com.psyche.memo.data.model.QuickPhrase>?>(null) }
    var showInstructionSheet by remember { mutableStateOf(false) }
    var showWorldBookSheet by remember { mutableStateOf(false) }
    var showSkillSelector by remember { mutableStateOf(false) }
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

    /**
     * 导出的输入：会话标题（要查库，所以只在点击/开 sheet 那一次算，不进组合 §5.13）
     * + 选中的消息。顺序是**会话时间序**而不是点选顺序（上游 `_selectedCollapsedMessages`
     * home_page_controller.dart:2064-2081），压缩检查点不参与导出。
     */
    fun exportSelection(): Pair<String, List<com.psyche.memo.ui.chat.MessageExport.ExportMessage>> {
        val title = (container.conversationDao.get(conversationId)?.title ?: "")
            .ifBlank { container.appContext.getString(UiR.string.message_export_sheet_default_title) }
        val picked = messages
            .filter { it.id in selectedIds && it.checkpointPart() == null }
            .map {
                com.psyche.memo.ui.chat.MessageExport.ExportMessage(
                    role = it.role,
                    parts = it.parts,
                    timestamp = it.timestamp,
                    modelName = it.model.takeIf { name -> name.isNotBlank() },
                )
            }
        return title to picked
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
    val timeOf: (Long) -> String = { millis -> com.psyche.memo.ui.chat.timeStr(millis) }

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
                // 系统解不动的图片（HEIC 在 API 26/27）会被整张丢掉：原字节发出去厂商
                // 直接 400，整条消息都发不出去，所以宁可在这里说清楚。
                val dropped = uris.size - imported.size
                if (dropped > 0) {
                    com.psyche.memo.ui.snackbar.SnackbarManager.show(
                        com.psyche.memo.ui.snackbar.AppNotification(
                            message = context.getString(UiR.string.chat_attachment_unreadable_skipped, dropped.toString()),
                            type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
                        ),
                    )
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
                // 系统解不动的图片（HEIC 在 API 26/27）会被整张丢掉：原字节发出去厂商
                // 直接 400，整条消息都发不出去，所以宁可在这里说清楚。
                val dropped = uris.size - imported.size
                if (dropped > 0) {
                    com.psyche.memo.ui.snackbar.SnackbarManager.show(
                        com.psyche.memo.ui.snackbar.AppNotification(
                            message = context.getString(UiR.string.chat_attachment_unreadable_skipped, dropped.toString()),
                            type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
                        ),
                    )
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

    // 定位工具（get_current_location）要运行时权限，而只有界面手里有
    // ActivityResultRegistry ⇒ 执行器挂起等 LocationPermissionService，这里看到 pending
    // 就去弹系统框、结果回填。工具侧带超时，没人回答也不会把生成挂死。
    val locationPermissionService = container.locationPermissionService
    val locationPermissionPending by locationPermissionService.pending.collectAsState()
    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> locationPermissionService.resolve(granted) }
    androidx.compose.runtime.LaunchedEffect(locationPermissionPending) {
        if (locationPermissionPending) {
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

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

    // ---- 滚动导航 + 流式跟随 ----
    // 两边取长：**位置判据**取自 RikkaHub（ChatList.kt:236-243 / 278-290 —— 跟随的前提是
    // 「尾巴就在视口底部」，所以用户上滑后任何流式增量都不会把视口拉回去），旗标/容差/
    // 空闲计时取自原版（scroll_controller.dart:216-223 / 332-392 / 518-564）。
    // 硬保证：**手指在屏上（pointerDown）时绝不做程序化滚动** —— 不依赖
    // isScrollInProgress / interactionSource 这类状态观察是否及时（前两版都栽在这上面）。
    var navVisible by remember { mutableStateOf(false) }
    /** `_autoStickToBottom`：还要不要跟着新内容贴底。只由「用户接管」与「回到/停在底部」改写。 */
    var following by remember { mutableStateOf(true) }
    /** `_isUserScrolling` 的硬版本：手指是否还按在列表上。 */
    var pointerDown by remember { mutableStateOf(false) }
    var navHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var idleStickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scrollDensity = LocalDensity.current
    /**
     * RikkaHub ChatList.kt:236-243 `isAtBottom()` —— 最后一条**可见** item 的底边是否
     * 落到视口底部附近（我们列表的底边就是输入栏顶边，不必再减 IME 高度）。注意它不要求
     * 这条正好是列表最后一项：流式内容长高时，旧判据（maxScrollExtent − pixels）会被
     * 自己的增长打断，这个不会。
     */
    fun tailBottomGapPx(): Float {
        val info = timelineListState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return Float.MAX_VALUE
        return (last.offset + last.size - info.viewportEndOffset).toFloat()
    }
    fun tailAtBottom(withinDp: Int): Boolean =
        tailBottomGapPx() <= with(scrollDensity) { withinDp.dp.toPx() }
    /**
     * 到底。**坑（2026-09-13 日志实证）**：`requestScrollToItem(index)` 是把该 item 对齐
     * 到**视口顶部**（Compose KDoc：正的 `scrollOffset` 表示 item 滚到视口上方），所以传
     * 「末条下标」**不等于**到底 —— 长消息会跳到这条消息的开头，看起来就是「视口往上跑」
     * （日志：`initialJump(lastIndex)` 之后 `firstIdx=16 firstOff=0 tailGap=1282px`）。
     *
     * 真到底用**越界下标**：LazyColumn 先把它夹到末条、再夹到 maxScrollExtent，结果就是
     * 底部。上游 RikkaHub 用的是同一招（`ChatList.kt:284` 的 `lastIndex + 10`、
     * `ChatPage.kt:179` 的 `size + 5`）。以前这里写 `(lastIndex, Int.MAX_VALUE)`：
     * 2026-09-15 真机日志显示那个 MAX_VALUE 会**原样留在滚动位置里**
     * （`firstVisible=3 offset=2147483647`）再被夹一次，多一次 Int.MAX_VALUE 参与的
     * 位置运算 —— 换成有界下标后语义一样、位置算术不再碰边界值。
     */
    /**
     * 列表末尾**哨兵项**的下标（RikkaHub `ChatList.kt:374` 的 `ScrollBottomKey` 同款）：
     * 它是列表最后一项、高度约 0，「滚到它」= 精确等于 `maxScrollExtent`（视口会把
     * 它的顶对齐视口顶 → 越界部分被夹成最大滚动量）。有了它，到底就**不需要**越界下标
     * （`size + 5`）那一招，也不用碰 `Int.MAX_VALUE`（用户 2026-09-15 报的「点到底部
     * 会闪」就是那个越界偏移造成的，见 §5.14）。
     */
    val bottomAnchorIndex = messages.size +
        if (compacting && streamingMessageId == null) 1 else 0

    fun scrollTimelineToBottom() {
        if (messages.isEmpty()) return
        timelineListState.requestScrollToItem(bottomAnchorIndex)
    }
    /** scroll_controller.dart `_navButtonsHideDelayMs = 2000`。 */
    fun armNavHideTimer() {
        navHideJob?.cancel()
        navHideJob = coroutineScope.launch {
            kotlinx.coroutines.delay(2000)
            navVisible = false
        }
    }
    /**
     * scroll_controller.dart:384-392 / 332-344 —— 收手 `autoScrollIdleSeconds` 秒后再按
     * 56 容差判一次贴底（收手那刻惯性还没滑完，或用户又挪回底部一点）。
     *
     * 恢复跟随的条件照原版 `refreshAutoStickToBottom()`：贴底 ∧ 不在滚动 ∧
     * `enabled || _autoStickToBottom`。少了开关那一项的话，「自动回到底部」关掉之后
     * 这条路径仍会把 `following` 翻回 true（恢复成上游 `:338-341` 之前的行为）。
     */
    fun armIdleStickTimer() {
        idleStickJob?.cancel()
        idleStickJob = coroutineScope.launch {
            kotlinx.coroutines.delay(autoScrollIdleSeconds.coerceAtLeast(1) * 1000L)
            if (!pointerDown && !timelineListState.isScrollInProgress && tailAtBottom(56) &&
                (autoScrollEnabled || following)
            ) {
                following = true
            }
        }
    }
    // 位置判据（scroll_controller.dart:346-362「滚回底部立刻恢复」+ 24 容差）：只有
    // 「手指不在屏上 + 没在滚动 + 尾巴就在底部」才恢复跟随。用户往上滑走之后这个条件不
    // 成立，跟随就一直关着 —— 按**位置**判定的关键：流式增长不会把它关掉，用户也不会被
    // 拽回去。
    androidx.compose.runtime.LaunchedEffect(timelineListState, autoScrollEnabled) {
        snapshotFlow { tailBottomGapPx() }
            .distinctUntilChanged()
            .collect { gap ->
                if (gap != Float.MAX_VALUE && !pointerDown &&
                    !timelineListState.isScrollInProgress && tailAtBottom(24)
                ) {
                    idleStickJob?.cancel()
                    if (autoScrollEnabled || following) following = true
                }
            }
    }
    // 这里原先有一段「后台预热最近 60 条 Markdown 解析缓存」。**已按原版删除**：
    // Flutter 原项目没有任何预热（对 `lib/` 搜 `preloadMarkdown` 零命中），只对可见项
    // 惰性解析（`richtext/Markdown.kt:240-252` 的 `mapLatest + flowOn(Default)`）；
    // 而一次性解 60 条 CommonMark 是几百 ms 的 CPU + 一堆临时对象，正好和「打开会话的
    // 首帧」抢 CPU —— 2026-09-15 实测打开长会话有两帧各 ~330ms，主线程 SQL 只占 6ms，
    // 热点就在这一带（用户：「对话还是卡到爆」「原项目也没有这个问题」）。若日后滚动到
    // 未解析行反而变卡，再按**可见窗口大小**做有界预热，不要回到 60 条一次性全解。

    // 进入会话先落到最新一条 —— RikkaHub ChatPage.kt:170-183 同款：首次拿到
    // 非空消息时滚到底（一次性守卫，之后不再触发，免得抢用户的滚动）。
    //
    // 落到列表底部的哨兵项（`SCROLL_BOTTOM_ITEM_KEY`）：`requestScrollToItem(i)` 是把第 i
    // 项对齐到视口**顶部**，所以「传末条下标」并不等于到底（长消息会停在它的开头）；
    // 滚到那个零高哨兵项时，视口把它的顶对齐视口顶 → 越界部分被夹成 maxScrollExtent，
    // 正好是真底部。RikkaHub 同款（`ChatList.kt:374 ScrollBottomKey`，
    // 另有 `ChatPage.kt:179` / `ChatList.kt:284` 的 `size + 5` / `lastIndex + 10` 变体）。
    //
    // 以前这里传的是 `(lastIndex, Int.MAX_VALUE)`：真机日志显示那个 MAX_VALUE 会**原样
    // 留在滚动位置里**（`layout total=4 firstVisible=3 offset=2147483647`）再被夹一次，
    // 用户看到的就是「点一下到底部整个界面闪一下」（2026-09-15，见 §5.14）。
    var listInitialized by remember(conversationId) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(messages) {
        if (!listInitialized && messages.isNotEmpty()) {
            scrollTimelineToBottom()
            listInitialized = true
        }
    }
    // 流式期间贴底跟随（scroll_controller.dart:116-155 —— 布局期贴底，不做动画）。
    // 三个前提缺一不可：跟随开着（following）∧ 手指不在屏上（pointerDown）∧ 此刻没在滚动。
    // 用 requestScrollToItem 而不是 animateScrollToItem：后者每个增量都重启一次动画，
    // 会把用户正在进行的拖动/惯性顶掉。
    androidx.compose.runtime.LaunchedEffect(
        messages,
        streaming,
        following,
        pointerDown,
        autoScrollEnabled,
        timelineListState.isScrollInProgress,
    ) {
        if (streaming && following && !pointerDown && autoScrollEnabled &&
            !timelineListState.isScrollInProgress && messages.isNotEmpty()
        ) {
            scrollTimelineToBottom()
        }
    }
    // scroll_controller.dart:518-564 stickToBottomAfterGeneration：生成结束那一刻尾部
    // 还会长高（操作行/Token 统计出现、思考卡收起），跟随条件里的 streaming 已经翻假，
    // 需要在同一个窗口（450ms）里再贴一次底。用户接管过（following=false）就不抢。
    var wasStreaming by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(
        streaming,
        following,
        pointerDown,
        autoScrollEnabled,
        messages.size,
    ) {
        val justFinished = wasStreaming && !streaming
        wasStreaming = streaming
        if (justFinished && following && !pointerDown && autoScrollEnabled &&
            !timelineListState.isScrollInProgress && messages.isNotEmpty()
        ) {
            scrollTimelineToBottom()
            kotlinx.coroutines.delay(450)
            if (following && !pointerDown && !timelineListState.isScrollInProgress) {
                scrollTimelineToBottom()
            }
        }
    }
    // home_page.dart:763-771 didChangeMetrics →
    // scroll_controller.dart:312-321 pinBottomDuringViewportResizeIfNeeded ——
    // **软件键盘抬起时把对话内容一起顶上去**。输入栏吃 IME inset
    // （ChatInputBar 的 windowInsetsPadding）+ 列表吃剩余高度（weight(1f)），
    // 所以视口会随键盘缩小；但滚动位置是按像素记的，视口一矮 maxScrollExtent
    // 就变大，不钉的话用户正在看的最新一条会被压到输入栏后面。
    // 收起键盘不用管：视口长回去后 LazyList 自己把 pixels 夹回新的
    // maxScrollExtent，仍然是贴底的。
    //
    // **判据必须在「新视口布局之前」取**（原版 didChangeMetrics 就是那个时机：
    // `isNearBottom(24)`）。组合期读 layoutInfo 拿到的还是上一帧（键盘抬起前）
    // 的几何，正好是原版的语义；放进 effect 里读就晚了——那时可能已按新几何
    // 布局，判据恒假、功能静默失效。所以用 derivedStateOf 缓存「上一帧布局的贴底
    // 判定」（它只在布尔结果翻转时才让调用方重组，不会每帧都带动 ChatContent）。
    // 早先版本图省事用流式跟随的 `following` 当判据：它**不会被程序化跳转清掉**
    // （点引用跳转 / 上一条下一条 / 切会话），于是用户在历史里翻看时开键盘也会被
    // 拽到底——用户 2026-09-13 实测指出「判定太多了，应该是只有在最新一条才抬起」。
    val tailNearBottomForIme = remember(timelineListState, scrollDensity) {
        androidx.compose.runtime.derivedStateOf {
            tailBottomGapPx() <= with(scrollDensity) { 24.dp.toPx() }
        }
    }
    // 键盘抬起钉底 —— 交给独立 composable 承接，避免 ChatContent 按 IME 帧重组。
    // 详见 `ImeRisePinEffect` 的注释（用户 2026-09-17「输入框抬起一顿一顿」的根因）。
    // **注意：这里绝不能出现 `WindowInsets.ime.getBottom(...)`** —— 在 ChatContent
    // 的组合体里读它会让这个约 1700 行的组合按帧重组，正是本 bug 的根因。
    ImeRisePinEffect(
        tailNearBottom = { tailNearBottomForIme.value },
        pointerDown = { pointerDown },
        scrollTimelineToBottom = { scrollTimelineToBottom() },
    )
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
    // 组合期不许读库：`loadModelOptions` 要把 provider_rows 整表读出来逐条解 JSON，
    // 原来是 `remember { loadModelOptions(...) }`（同步、卡首帧）。改走 IO，
    // 消费方只有用户点开的模型 sheet，点开时早已就位。
    val modelOptions = rememberLoaded(emptyList(), container, optionsVersion, providerId, modelId) {
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

    // 消息头「模型名」（可选 `| 供应商`）的原料 —— `_resolveModelDisplayName`
    // (chat_message_widget.dart:1355-1407)。历史消息可能来自别的供应商，所以按**会话里
    // 出现过的 providerId** 异步读一次投影（组合期读库是禁止的，见 PORTING §5.13）。
    val messageProviderIds = remember(messages) {
        messages.mapNotNull { it.providerId?.takeIf { p -> p.isNotEmpty() } }.distinct()
    }
    val modelLabelSources = rememberLoaded(
        emptyMap<String, com.psyche.memo.ui.chat.MessageModelLabelSource>(),
        container,
        messageProviderIds,
    ) {
        messageProviderIds.associateWith { pid ->
            val cfg = container.providerConfig(pid)
            com.psyche.memo.ui.chat.MessageModelLabelSource(
                providerName = cfg?.name?.trim()?.takeIf { it.isNotEmpty() },
                overrides = buildMap {
                    cfg?.modelOverrides?.forEach { (mid, element) ->
                        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.trim()?.takeIf { it.isNotEmpty() }
                        val apiId = ((obj["apiModelId"] ?: obj["api_model_id"])
                            as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.trim()?.takeIf { it.isNotEmpty() }
                        // CMW:1376-1389 —— 覆盖名优先，其次 apiModelId。
                        (name ?: apiId)?.let { put(mid, it) }
                    }
                },
            )
        }
    }

    // chat_assistant_background.dart —— 当前助手壁纸 + surface 遮罩渐变（0.20→0.50 × 强度）。
    val bgAssistantId by container.currentAssistantId.collectAsState()
    val currentAssistant = remember(bgAssistantId) { container.currentAssistant() }
    val chatBackground = currentAssistant?.background
    // display_use_new_assistant_avatar_ux_v1（默认 false，settings_provider.dart:1114-1115）
    // —— 顶栏标题行前面加当前助手头像（home_mobile_layout.dart:185-227
    // `_buildAssistantTitleAvatar`，28dp）。用户 2026-09-15 要求把「聊天项显示」里
    // 这几个失效开关真正接上（此前 6 个键一个都没被消费）。
    val useNewAssistantAvatarUx = remember {
        container.preferenceRepository.readJson("display_use_new_assistant_avatar_ux_v1") == "1"
    }
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
            if (useNewAssistantAvatarUx && currentAssistant != null) {
                // home_mobile_layout.dart:188-189 / 306-329 —— 头像 + 10dp 间距；
                // 点它开抽屉（原版 `onTap = onDismissKeyboard + onToggleDrawer`）。
                Box(modifier = Modifier.clickable { onOpenDrawer() }) {
                    AssistantListAvatar(currentAssistant, 28.dp)
                }
                Spacer(Modifier.width(10.dp))
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
                    modifier = Modifier
                        .fillMaxSize()
                        // 交叉淡入：alpha 只在绘制阶段读（lambda 里的 State），
                        // 不触发重组（原版 `_convoFadeController` 包在消息列表外）。
                        .graphicsLayer { alpha = timelineAlpha() }
                        .testTag(CHAT_TIMELINE_TAG)                        // scroll_controller.dart:374-425 handleUserScrollIntent —— 原版把
                        // 「用户接管」记在 `message_list_view` 的 `Listener.onPointerDown`
                        // （1711-1721）上，**程序化滚动绝不触发**。这里同样只旁听不消费：
                        // 手指一按下就 `pointerDown=true; following=false`（跟随立即让位，
                        // 且整个按住期间程序化滚动都被禁掉）；抬手后按空闲计时再定论。
                        .pointerInput(Unit) {
                            try {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                        )
                                        pointerDown = true
                                        following = false
                                        navVisible = true
                                        navHideJob?.cancel()
                                        idleStickJob?.cancel()
                                        waitForUpOrCancellation(
                                            pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                        )
                                        pointerDown = false
                                        // 抬指时已在底部（多半只是点一下）→ 立刻恢复跟随；
                                        // 否则等 autoScrollIdleSeconds 后再按 56 容差判一次。
                                        if (tailAtBottom(24)) {
                                            if (autoScrollEnabled || following) following = true
                                        } else {
                                            armIdleStickTimer()
                                        }
                                        armNavHideTimer()
                                    }
                                }
                            } finally {
                                // 协程被取消（重组/离屏）也不能把「手指还按着」留在真值上，
                                // 否则跟随会被永久关掉。
                                pointerDown = false
                            }
                        },
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
                        com.psyche.memo.ui.chat.MessageRow(
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
                            // CMW:2807-2813 —— 助手没开「使用助手名字」时名字行显示模型名
                            // （模型名后按 display_show_provider_in_chat_message_v1 拼供应商）。
                            modelLabel = com.psyche.memo.ui.chat.messageModelDisplayName(
                                msg.model,
                                modelLabelSources[msg.providerId],
                                timelineSettings.showProviderInChatMessage,
                            ),
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
                    // 流式等待提示（扫光文字）：**列表末尾独立一项**，整个生成期间都在。
                    //
                    // 结构照 RikkaHub `ChatList.kt:381-402` 的 `item(LoadingIndicatorKey)`
                    // （那边是 28dp 动画图标 + 可选状态文字，我们按用户点名用文字扫光）。
                    // 用户 2026-09-23「你看看 rikkhub…在工具调用这个有点跳动」：这行原来渲染在
                    // **助手消息内部**，消息 parts 一变它就得跟着整条消息的块布局重排 —— 工具卡
                    // 是一次性长出/换态的，那一行看起来就是在跳。挪成独立 item 之后，它不参与
                    // 消息内部的布局，位置只由「消息列表有多长」决定。
                    val streamingMessage = messages.lastOrNull { it.isStreaming }
                    if (streamingMessage != null) {
                        item(key = STREAMING_INDICATOR_ITEM_KEY) {
                            // 1:1 照 RikkaHub `ChatList.kt:381-402` 的那一项：
                            // 28dp 的自家 app 图标（会动）+ 可选状态文字，横向一行、无底板。
                            // 用户 2026-09-23「人家一直是那样的，直接一比一改成他那样」。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 左边仍与助手气泡对齐（`ASSISTANT_MESSAGE_HORIZONTAL_DP`），
                                    // 这是用户同一天单独提过的要求。
                                    .padding(
                                        start = ChatStyleSpec.ASSISTANT_MESSAGE_HORIZONTAL_DP.dp,
                                        end = ChatStyleSpec.ASSISTANT_MESSAGE_HORIZONTAL_DP.dp,
                                        top = 6.dp,
                                        bottom = 6.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (timelineSettings.thinkingIndicator.style ==
                                    com.psyche.memo.ui.chat.ThinkingIndicatorStyle.SHIMMER
                                ) {
                                    // 文字扫光（用户 2026-09-12 点名那版；样式/字号/颜色/提示词
                                    // 由「显示设置 → 渲染 → 流式等待提示」决定）。
                                    com.psyche.memo.ui.chat.ThinkingShimmerText(
                                        phrases = timelineSettings.thinkingIndicator.phrases,
                                        fontSize = timelineSettings.thinkingIndicator.fontSizeSp.sp,
                                        colorArgb = timelineSettings.thinkingIndicator.colorArgb,
                                    )
                                } else {
                                    // 出厂形态：28dp 自家 app 图标（照 RikkaHub）。
                                    com.psyche.memo.ui.chat.MemoLoadingIndicator(
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                // RikkaHub 那行文字只在 processingStatus 非空时出现；我们对应的
                                // 是自动重试倒计时（原版 kelivo 的等待气泡也是「指示器 + 倒计时」）。
                                val retry = streamingMessage.retryStatus
                                if (retry != null &&
                                    com.psyche.memo.ui.chat.shouldShowRetryCountdown(retry, true)
                                ) {
                                    com.psyche.memo.ui.chat.RetryCountdownHint(status = retry)
                                }
                            }
                        }
                    }
                    // RikkaHub `ChatList.kt:374 ScrollBottomKey` —— 末尾哨兵项：让「到底」
                    // 有一个**合法下标**可以滚（`bottomAnchorIndex`），位置恰好是
                    // maxScrollExtent。高度 1dp 只为让 LazyColumn 收下它。
                    item(key = SCROLL_BOTTOM_ITEM_KEY) {
                        Spacer(Modifier.height(1.dp))
                    }
                }
                // 首屏窗口还没读回来 → 铺骨架，而不是让这一块空着（用户 2026-09-15
                // 「会白一会 在显示」）。判据同原版 `message_list_view.dart:1739`：
                // `rendered.isEmpty && isLoadingWindow`。
                if (showTimelineSkeleton(startupWindowPending, tailLoaded, messages.isEmpty())) {
                    TimelineSkeleton(modifier = Modifier.fillMaxSize())
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
                                // 这里曾经是 `animateScrollToItem(size - 1, Int.MAX_VALUE)`：
                                // 越界偏移会被**原样写进滚动位置**（本文件 985-990 行的真机日志
                                // `firstVisible=3 offset=2147483647`），下一帧 LazyColumn 再夹
                                // 一次 —— 用户看到的就是「点一下到底部，整个对话界面闪一下」
                                // （2026-09-15）。改成与「进入会话」「流式跟随」同一条**有界**
                                // 写法（`size + 5`，上游 ChatPage.kt:179 同款）。
                                //
                                // **有意与 Flutter 的一处差异**：原版
                                // `scroll_controller.dart:625-744 _animateToBottom` 在这里
                                // 走 450ms easeOutCubic 动画（目标是真实的 `maxScrollExtent`）。
                                // Compose 的 `animateScrollToItem` 只接受「下标 + 偏移」，
                                // 想要「动画到 maxScrollExtent」得先组合出尾部再算距离；而且
                                // 动画期间流式跟随（同一个目标）会再插一次瞬移，反而容易看出抖动。
                                // 所以到底按钮走瞬移 —— 与上面三条路径同一套语义。
                                scrollTimelineToBottom()
                                // scroll_controller.dart:488-496 forceScrollToBottom ——
                                // 主动到底：恢复跟随。
                                idleStickJob?.cancel()
                                following = true
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
        // 供应商自己配了头像（内置图标 / emoji / 图片 / LobeHub）时优先用它 ——
        // 品牌匹配只看名字，第三方供应商 key 里带「OpenAI」就会被认成 GPT。
        val modelAvatarSource = remember(providerId) {
            val cfg = providerId.takeIf { it.isNotEmpty() }
                ?.let { runCatching { container.providerConfig(it) }.getOrNull() }
            when (val src = providerAvatarSource(cfg?.avatarType, cfg?.avatarValue)) {
                ProviderAvatarSource.Brand -> null
                is ProviderAvatarSource.File -> src.takeIf { java.io.File(it.path).exists() }
                else -> src
            }
        }
        // 搜索按钮（CIB:1782-1863）：当前助手启用搜索 → 所选搜索服务的品牌
        // 图标；未启用 → Globe。内置搜索（builtinSearchActive）未移植，恒 false。
        val assistantForSearch = container.currentAssistant()
        val searchActive = assistantForSearch?.searchEnabled == true
        // 输入栏按钮的选中态（chat_input_section.dart:177-199）：
        // 推理没关 → reasoningActive；已连接的已选 MCP 存在 → mcpActive；
        // 快捷短语（全局 + 本助手）为空 → 整颗按钮不显示。
        val reasoningActive = com.psyche.memo.llm.client.ReasoningBudget.isReasoningEnabled(
            assistantForSearch?.thinkingBudget ?: com.psyche.memo.ui.chat.readBudget(container),
        )
        val mcpStates by container.mcpConnections.states.collectAsState()
        val mcpActive = remember(assistantForSearch?.id, mcpStates) {
            mcpButtonActive(
                selectedIds = assistantForSearch?.mcpServerIds.orEmpty(),
                connectedIds = mcpStates.filterValues { it.status == com.psyche.memo.provider.mcp.McpConnectionManager.Status.connected }.keys,
            )
        }
        // 快捷短语（全局 + 本助手）是否非空 —— 只用来决定那颗按钮显不显示。
        // **不能**在组合期直接 `loadQuickPhrases(container)`（它一进去就是两条 SQL +
        // 拿 writableDatabase），那等于每次重组都在主线程查库；改走 `rememberLoaded`（IO）。
        val quickPhraseAvailable = rememberLoaded(false, providerId, modelId, assistantForSearch?.id) {
            quickPhraseButtonVisible(loadQuickPhrases(container).size, 0)
        }
        // CIS:187 supportsReasoning / CIS:197+283-293 showMcpButton —— 能力门控：
        // 模型没有推理能力 → Brain 整颗不显示；没有工具能力或没有任何启用的
        // MCP 服务器 → Hammer 不显示。判定走 isReasoningModel/isToolModel
        //（override abilities 优先，否则名称推断）。
        val currentModelCfg = remember(providerId) {
            providerId.takeIf { it.isNotEmpty() }
                ?.let { runCatching { container.providerConfig(it) }.getOrNull() }
        }
        val supportsReasoning = isReasoningModel(currentModelCfg, modelId)
        // `McpRepository(...).enabledServers()` 是一条 SQL —— 同样挪出组合期（IO）。
        val showMcpButton = rememberLoaded(false, providerId, modelId, mcpStates) {
            isToolModel(currentModelCfg, modelId) && runCatching {
                com.psyche.memo.data.repo.McpRepository(container.database.readableDatabase)
                    .enabledServers().isNotEmpty()
            }.getOrDefault(false)
        }
        // CIS:248-281 `_enforceModelCapabilities` —— 能力不足时把助手设置归零
        //（工具能力没有 → 清空 mcpServerIds；推理能力没有 → thinkingBudget=0），
        // 防止发请求时带着模型根本不支持的能力参数。
        val capAssistant = assistantForSearch
        if (capAssistant != null && providerId.isNotEmpty() && modelId.isNotEmpty()) {
            androidx.compose.runtime.LaunchedEffect(capAssistant.id, providerId, modelId) {
                // 这段是「模型不支持工具/推理就把助手上对应的绑定清掉」，读写都是真库操作
                // （AssistantStore.get/update），主线程体里跑会看见掉帧 ⇒ 整段挪 IO。
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val aa = container.currentAssistant()
                    if (aa != null) {
                        if (!isToolModel(currentModelCfg, modelId) && aa.mcpServerIds.isNotEmpty()) {
                            container.assistantStore.update(aa.copy(mcpServerIds = emptyList()))
                        }
                        if (!isReasoningModel(currentModelCfg, modelId) &&
                            com.psyche.memo.llm.client.ReasoningBudget.isReasoningEnabled(aa.thinkingBudget)
                        ) {
                            container.assistantStore.update(aa.copy(thinkingBudget = 0))
                        }
                    }
                }
            }
        }
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
        // 待答问询 / 待审批时，底部换成对应面板、聊天输入栏暂时藏起来
        //（用户 2026-09-14「这个应该出现在输入框那个位置，体验更加友好」）。
        val interrupting = currentChatInterruption(
            askUser = askUserPending.values,
            approval = approvalPending,
            conversationId = conversationId,
        )
        if (interrupting != null) {
            ChatInterruptionPanel(
                interruption = interrupting,
                askUser = askUserService,
                approval = approvalService,
                conversationId = conversationId,
            )
        } else {
        ChatInputBar(
            input = input,
            enterToSend = remember {
                container.preferenceRepository.readJson("display_enter_to_send_on_mobile_v1")
                    ?.let { it == "1" } ?: false
            },
            streaming = streaming,
            onInputChange = vm::updateInput,
            onSend = {
                // 触觉移进 ViewModel（home_view_model.dart:423）—— 只挂在这个按钮上
                // 会漏掉回车发送/建议气泡等入口，见 vm.onHapticFeedback。
                vm.send()
                // home_page_controller.dart L526-531 发送路径：resetUserScrolling() +
                // scrollToBottom —— 自己发消息一律回到最新，即便此前上滑过。
                idleStickJob?.cancel()
                following = true
                scrollTimelineToBottom()
            },
            onStop = vm::stop,
            onSelectModel = { showModelSheet = true },
            onOpenSearch = { showSearchSheet = true },
            modelIconAsset = modelIconAsset,
            modelIconInitial = modelIconInitial,
            modelAvatarSource = modelAvatarSource,
            searchActive = searchActive,
            searchIconAsset = searchIconAsset,
            reasoningActive = reasoningActive,
            supportsReasoning = supportsReasoning,
            showMcpButton = showMcpButton,
            mcpActive = mcpActive,
            quickPhraseAvailable = quickPhraseAvailable,
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
    }

    if (showReasoningSheet) {
        // home_page.dart:1571-1602 —— 输入栏的思考强度选择在移动端**种入当前助手的
        // thinkingBudget**（不是全局），sheet 关闭时才写回助手；sheet 自己会把全局
        // thinking_budget_v1 一并写掉（我们由 onSelect 承担）。seed 用 initialBudget
        // 而不是先写全局，是因为同步 notify 会在 sheet 入场动画期间重建整个首页。
        var chosenBudget by remember { mutableStateOf<Int?>(null) }
        val reasoningAssistant = remember { container.currentAssistant() }
        com.psyche.memo.ui.chat.ReasoningBudgetSheet(
            initialBudget = reasoningAssistant?.thinkingBudget,
            onSelect = { v ->
                chosenBudget = v
                container.preferenceRepository.writeJson(
                    "thinking_budget_v1",
                    kotlinx.serialization.json.JsonPrimitive(v).toString(),
                )
            },
            modelId = modelId,
            onDismiss = {
                showReasoningSheet = false
                val chosen = chosenBudget
                if (chosen != null && chosen != reasoningAssistant?.thinkingBudget) {
                    reasoningAssistant?.let {
                        container.assistantStore.update(it.copy(thinkingBudget = chosen))
                    }
                }
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
            onOpenSkills = {
                showToolsSheet = false
                showSkillSelector = true
            },
            onOpenMcp = {
                showToolsSheet = false
                showMcpSheet = true
            },
            onOpenWorkspace = {
                showToolsSheet = false
                showWorkspaceSheet = true
            },
            onOpenImageGeneration = {
                showToolsSheet = false
                generationKind = com.psyche.memo.data.model.GenerationKind.IMAGE
            },
            onOpenVideoGeneration = {
                showToolsSheet = false
                generationKind = com.psyche.memo.data.model.GenerationKind.VIDEO
            },
            onOpenContextManagement = {
                showToolsSheet = false
                showContextSheet = true
            },
        )
    }

    if (showWorkspaceSheet) {
        // 照 RikkaHub 的「+」面板工作区入口：选工作区（绑当前助手）+ 管理出口。
        WorkspaceSelectorSheet(
            container = container,
            onDismiss = { showWorkspaceSheet = false },
            onOpenManage = { showWorkspaceSheet = false; onOpenWorkspaces() },
        )
    }

    // 生成图片 / 生成视频（自研功能）：+ 面板里**只选模型**（绑当前助手），
    // 真正生成由模型调 `generate_image` / `generate_video` 工具完成
    //（用户 2026-09-17「点击是选择对应的模型，不是点击使用」）。
    generationKind?.let { kind ->
        com.psyche.memo.ui.GenerationSelectorSheet(
            container = container,
            kind = kind,
            onDismiss = { generationKind = null },
            onOpenManage = {
                generationKind = null
                onOpenGenerationServices(kind)
            },
        )
    }

    if (showContextSheet) {
        // 模型编辑页可能刚改过「上下文长度」——打开面板时重算一次占用。
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshContextUsageNow() }
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
        val (exportTitle, selectedMessages) = exportSelection()
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

    if (showSkillSelector) {
        SkillSelectorSheet(
            container = container,
            onDismiss = { showSkillSelector = false },
            // 「管理技能」出口 —— 就是设置→技能 那个页面。
            onManage = onOpenSkills,
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
                // 触觉由 vm.regenerate() 自己发（同上传送路径），别在这里重复一次。
                vm.regenerate(target.id)
            },
        )
    }
}
