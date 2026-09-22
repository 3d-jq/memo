package com.psyche.memo.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.animateFloat
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
import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
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
import com.psyche.memo.ui.chat.ChatInterruptionPanel
import com.psyche.memo.ui.chat.ToolApprovalService
import com.psyche.memo.ui.chat.ToolUiPart
import com.psyche.memo.ui.chat.currentChatInterruption
import com.psyche.memo.ui.chat.ImePinTracker
import com.psyche.memo.ui.chat.checkpointPart
import com.psyche.memo.ui.chat.shouldPinTimelineOnImeRise
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
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
import com.psyche.memo.ui.chat.ChatContent
import com.psyche.memo.ui.chat.loadQuickPhrases

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
    onOpenSkills: () -> Unit = {},
    /**
     * 工作区管理入口。**故意不给默认值**：它是从「+」面板 →「工作区」→「管理工作区」
     * 一路抛上来的，曾经因为这里有 `= {}` 默认值、而 `home` 路由漏传 → 点了没反应
     *（用户 2026-09-15「加号里面的工作区里面的管理工作区点击没有反应呀」）。去掉默认值
     * 后调用方漏传会直接编译不过。
     */
    onOpenWorkspaces: () -> Unit,
    /** + 面板生成选择器末尾的「管理生成服务」出口（kind = image/video）。 */
    onOpenGenerationServices: (String) -> Unit,
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

    // 选中的会话**从容器恢复**：进设置页/其它路由时本页会被销毁，页面级 state 会丢，
    // 返回时就会落到「启动恢复」逻辑去开列表第一条（用户 2026-09-15「为什么点击设置回来
    // 不是我点开那个对话了 是第一个对话了呀」）。原版 controller 活在页面之上，不会丢。
    var selectedConversationId by remember {
        mutableStateOf(container.currentConversationId.value?.takeIf { it != Conversation.TEMPORARY_ID })
    }
    // Publish the open conversation so the assistant memory tab can organize it.
    LaunchedEffect(selectedConversationId) {
        com.psyche.memo.PerfProbe.mark("selection-applied")
        container.setCurrentConversation(selectedConversationId)
    }
    // 临时聊天也一起从容器恢复（否则进设置返回会掉出临时模式）。
    var temporaryActive by remember {
        mutableStateOf(container.currentConversationId.value == Conversation.TEMPORARY_ID)
    }

    // 顶栏标题刷新信号：抽屉改写了当前会话标题（重命名 / 重新生成标题）后自增，
    // ChatContent 观察到变化即让 ChatViewModel 重读库里的标题。对齐 Flutter 端
    // 共享 _conversationsCache + notifyListeners 的自动同步语义。
    var titleRefreshTick by remember { mutableStateOf(0) }

    // ---- 会话切换：淡出 → 后台备好 → 提交 → 落底 → 淡入 ----
    // 照原版 `home_page_controller.switchConversationAnimated`（L1077-1131）：
    // `_reverseConvoFade()` → 与淡出**并行** `prepareConversationSwitch(id)` →
    // 等淡完才 `commitConversationSwitch(prepared)` → `settleAtBottomBeforeReveal()` →
    // `_convoFadeController.forward()`。于是「取数 + 首帧渲染 + 落底」全在透明度 0 后面，
    // 抽屉关闭动画不再和重活抢帧（用户 2026-09-15「内容多的就会很卡」）。
    // 冷启动/空会话那条（原版 `isLoadingWindow`）由 ChatContent 的加载态负责。
    val convoFade = remember { androidx.compose.animation.core.Animatable(1f) }
    // 只有**冷启动那一次**窗口加载会露骨架（原版 _startupConversationPending）；+/点会话
    // 都走 fetch-then-commit，提交时数据已在手 ⇒ 不铺骨架。
    var startupWindowPending by remember { mutableStateOf(container.startupConversationPending) }
    fun switchConversation(target: String?) {
        if (target == null || target == selectedConversationId) return
        scope.launch {
            // ① **先备好，再切换**（原版 `prepareConversationSwitch` → `commitConversationSwitch`
            //    的 fetch-then-commit）。第一版写反了：先淡出再等预热，于是屏幕上有一段
            //    空白（用户 2026-09-15「会白一会 在显示」）。现在预热期间**旧会话内容照旧
            //    可见**，用户看不到任何空白。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.prepareConversationSwitch(target)
            }
            // ② 短淡出 → ③ 提交（首帧命中预热的 Markdown 缓存，很快）→ ④ 立刻淡入：
            //    屏幕上要么已经是新内容，要么是骨架（`showTimelineSkeleton`），不会是白屏。
            convoFade.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(CONVO_FADE_MS, easing = CONVO_FADE_EASING),
            )
            selectedConversationId = target
            startupWindowPending = false
            container.startupConversationPending = false
            convoFade.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(CONVO_FADE_MS, easing = CONVO_FADE_EASING),
            )
        }
    }

    val newChatTitle = stringResource(UiR.string.chat_service_default_conversation_title)

    // 这里原来有个 `currentIsEmpty()`：组合期实参里调 `container.messageDao.count(id)`
    // （整表 `SELECT COUNT(*)`）—— 点开一条长会话时它就是主线程上的一次全表扫描，也就是
    // 用户 2026-09-15「对话点击加载还是卡」的一个直接来源。现在改由 `ChatContent` 用
    // `ChatViewModel.tailLoaded + messages.isEmpty()` 判断（见 `newActionToggleable`），
    // 组合期不再有任何查库。

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
        startupWindowPending = false
        container.startupConversationPending = false
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
            startupWindowPending = false
            container.startupConversationPending = false
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
            startupWindowPending = false
            container.startupConversationPending = false
        } else {
            newConversation()
        }
    }

    val drawerHaptics = LocalHapticsSettings.current
    val drawerView = LocalView.current
    fun settleDrawer(open: Boolean, velocityPx: Float = 0f) {
        // 触觉**不在这里发**（见下方 LaunchedEffect）：手势收起时 onSettle 会先
        // 把 drawerOpen 翻成目标值、紧接着又触发这个 effect，两处都发就会连震两下
        //（用户 2026-09-15「侧边栏到主对话界面这个震动怎么是两下」）。
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
                com.psyche.memo.PerfProbe.mark("drawer-closed")
            }
        }
    }

    // 应用更新提示（用户 2026-09-22「为什么有新版本不是弹窗」）：容器启动时已静默查过一次
    // （`checkForAppUpdatesOnStartup`，受 display_show_app_updates_v1 控制），这里只管"有新版本
    // 且没被跳过"时弹一个对话框。「跳过此版本」写进偏好、同版本之后不再弹；点「稍后」/返回键
    // 只是本次不弹（下次启动还会提示）。
    val updateOutcome by container.updateOutcome.collectAsState()
    var updateDismissedThisSession by remember { mutableStateOf<String?>(null) }
    val skippedUpdateVersion = container.preferenceRepository.readJson("update_skipped_version_v1")
    val availableUpdate =
        (updateOutcome as? com.psyche.memo.update.UpdateService.Outcome.Available)?.info
    if (availableUpdate != null &&
        availableUpdate.version != skippedUpdateVersion &&
        availableUpdate.version != updateDismissedThisSession
    ) {
        val updateContext = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            onDismissRequest = { updateDismissedThisSession = availableUpdate.version },
            title = {
                Text(stringResource(UiR.string.side_drawer_update_title, availableUpdate.version))
            },
            text = {
                Text(
                    text = availableUpdate.notes.trim().lineSequence()
                        .filter { it.isNotBlank() }.joinToString(" ").take(600),
                    style = TextStyle(fontSize = 13.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDismissedThisSession = availableUpdate.version
                    runCatching {
                        updateContext.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(availableUpdate.downloadUrl),
                            ),
                        )
                    }
                }) { Text(stringResource(UiR.string.about_page_update_download)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    container.preferenceRepository.writeJson(
                        "update_skipped_version_v1",
                        availableUpdate.version,
                    )
                    updateDismissedThisSession = availableUpdate.version
                }) { Text(stringResource(UiR.string.about_page_update_skip_version)) }
            },
        )
    }

    // 抽屉触觉：home_page_controller.dart L2364-2382 `onDrawerValueChanged` ——
    // 越过 0.05 / 0.95 阈值时 pulse 一次，即「抽屉状态真的翻转」时给一次。
    // 首帧（冷启动 drawerOpen 初值）不发；旋转导致的 drawerWidthPx 变化也不发。
    var lastDrawerOpenForPulse by remember { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(drawerOpen, drawerWidthPx) {
        if (lastDrawerOpenForPulse != null &&
            lastDrawerOpenForPulse != drawerOpen &&
            drawerHaptics.onDrawer
        ) {
            Haptics.drawerPulse(drawerView)
        }
        lastDrawerOpenForPulse = drawerOpen
        settleDrawer(drawerOpen)
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    // A conversation picked in ChatHistoryScreen lands here: select it.
    androidx.compose.runtime.LaunchedEffect(pendingOpenConversation?.value) {
        val id = pendingOpenConversation?.value
        if (!id.isNullOrEmpty()) {
            selectedConversationId = id
            startupWindowPending = false
            container.startupConversationPending = false
            temporaryActive = false
            pendingOpenConversation.value = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 只有**真正冷启动**（容器里还没有当前会话）才跑启动恢复；页面重建（进设置返回）
        // 时上面的 remember 已经从容器把会话恢复回来了，不能再开一条。
        if (selectedConversationId == null && container.currentConversationId.value == null) {
            // `display_new_chat_on_launch_v1`（默认**开**，home_page_controller.dart:782-786
            // `initChat`）：开启时每次启动都新建会话；关闭时回到最近一条。两种情况下
            // 都没有历史就开一个 draft（不入库，发首条消息才落库）。
            // 两个读都在 IO 上：这是**冷启动路径**（挂首页的组合），读偏好 + 取最近一条
            // 会话原来直接在 LaunchedEffect 的主线程体里跑。
            val newChatOnLaunch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.preferenceRepository
                    .readJson("display_new_chat_on_launch_v1")
                    ?.let { it == "1" || it == "true" } ?: true
            }
            val latest = if (newChatOnLaunch) {
                null
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.conversationDao.getAll().firstOrNull()
                }
            }
            selectedConversationId = latest?.id
                ?: Conversation.create(
                    title = newChatTitle,
                    assistantId = container.currentAssistantId.value,
                ).id
            startupWindowPending = false
            container.startupConversationPending = false
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
                modifier = modifier,
                isTemporary = temporaryActive,
                onOpenConversation = { id ->
                    // 全局搜索结果 / 通知点开：同一条淡出→备好→提交→落底→淡入的路径。
                    com.psyche.memo.PerfProbe.begin("open-conversation")
                    switchConversation(id)
                    temporaryActive = false
                },
                onOpenSearchServices = onOpenSearchServices,
                onOpenWorldBookPage = onOpenWorldBookPage,
                onOpenSkills = onOpenSkills,
                onOpenWorkspaces = onOpenWorkspaces,
                onOpenGenerationServices = onOpenGenerationServices,
                titleRefreshTick = titleRefreshTick,
                injectPresets = pendingPresetInject,
                timelineAlpha = { convoFade.value },
                startupWindowPending = startupWindowPending,
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
                        // 诊断用（debug 构建）：侧边栏点会话 → 主界面这条链从这里开始。
                        com.psyche.memo.PerfProbe.begin("drawer-select")
                        // 先选中（淡出 + 后台备好），再关抽屉 —— 与原版
                        // `home_mobile_layout.dart:110-113` 同序（选中在前、close 在后）。
                        switchConversation(id)
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

// ---------------------------------------------------------------------------
/**
 * 会话切换的淡出/淡入时长与曲线 —— 逐字照原版 `home_page_controller.dart:378-386`：
 * `AnimationController(duration: 180ms)` + `CurvedAnimation(curve: Curves.easeOutCubic)`。
 * Compose 侧用等价的 cubic-bezier(0.215, 0.61, 0.355, 1)（CSS `ease-out` cubic）。
 */
private const val CONVO_FADE_MS = 180
private val CONVO_FADE_EASING =
    androidx.compose.animation.core.CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

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

