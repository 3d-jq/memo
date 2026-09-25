@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.selectionChipColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.BotMessageSquare
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eraser

import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Shuffle
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.X
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Side drawer mirroring Memo SideDrawer (mobile):
 * search field + assistant card + conversation list (grouped by date) +
 * bottom user bar (avatar + name + translate + settings).
 */
@Composable
fun SideDrawerContent(
    container: AppContainerImpl,
    selectedId: String?,
    /** side_drawer.dart `closeDrawer: !keepSidebarOpenOnTopicTap`。 */
    onSelect: (id: String, closeDrawer: Boolean) -> Unit,
    onNew: (closeDrawer: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit = {},
    onOpenHistory: () -> Unit,
    onCurrentDeleted: () -> Unit,
    onOpenTranslate: () -> Unit = {},
    onEditAssistant: (String) -> Unit = {},
    onManageTags: (String) -> Unit = {},
    /** 会话标题被本抽屉改写（重命名 / 重新生成标题）后回调其 id，供聊天页
     * 刷新顶栏——Flutter 端靠共享 _conversationsCache + notifyListeners 自动
     * 同步，Android 端需手动通知。 */
    onConversationTitleChanged: (String) -> Unit = {},
    assistantName: String? = null,
    /** 抽屉当前是否展开 —— 常驻组合后用它驱动"每次展示重新加载"。 */
    open: Boolean = true,
    forceSelectionMode: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    /** 列表是否已读过一次（决定要不要铺 tile 骨架）。 */
    var listLoaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun reload() {
        conversations = container.conversationDao.getAll()
    }

    // 只在抽屉**打开**时重读一次列表。
    //
    // 原版是内存里的 `_conversationsCache` + `notifyListeners`，RikkaHub 是 Room 的
    // `Flow<List<Conversation>>` —— 两边都**不会**在「选中一条会话」时重查整张表。
    // 我们这里曾经的 key 是 `(selectedId, open)`：`selectedId` 一变（= 每次点会话）就
    // `conversationDao.getAll()` 整表读 + 逐条解 payload JSON，而 `open` 由 `presenting`
    // 驱动、**抽屉收起时再触发一次** —— 正好压在「侧边栏 → 主界面」那一下，历史越多越慢
    // （用户 2026-09-15「点击对话历史…内容多的就会很卡」）。
    // 抽屉内部的增删改（置顶/重命名/删除/移动/复制）本来就都显式调了 `reload()`，所以去掉
    // 这两个 key 不会让列表变旧；每次重新打开抽屉也一定会刷新。读放 IO（`getAll()` 是同步的）。
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reload() }
            listLoaded = true
        }
    }

    // 全局当前助手（assistant_provider.currentAssistantId）：抽屉助手卡显示它，
    // 会话列表按它过滤；切换即 setCurrentAssistant。
    val currentAssistantId by container.currentAssistantId.collectAsState()
    // 用户资料（user_provider.dart）：用户栏头像/名字与两个编辑入口。
    val userProfile by container.userProfileStore.profile
    var userAvatarEditRequested by remember { mutableStateOf(false) }
    var userNicknameEditRequested by remember { mutableStateOf(false) }

    // 助手卡头像用（AssistantAvatar 四态：http / 本地文件 / emoji / 首字母）。
    val currentAssistant = remember(currentAssistantId) {
        currentAssistantId?.let { id ->
            runCatching { container.assistantStore.get(id) }.getOrNull()
        }
    }
    val currentAssistantName = remember(currentAssistantId) {
        currentAssistantId?.let { id ->
            runCatching { container.assistantStore.get(id)?.name }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }
    val assistantLabel = currentAssistantName
        ?: assistantName?.takeIf { it.isNotBlank() }
        ?: stringResource(UiR.string.home_page_default_assistant)
    var assistantsExpanded by remember { mutableStateOf(false) }
    var assistantList by remember { mutableStateOf<List<Assistant>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(assistantsExpanded) {
        if (assistantsExpanded) {
            // getAll() 是整表读 + 每行解 JSON，冷启动后第一次展开最容易卡；
            // LaunchedEffect 体默认在主线程 ⇒ 挪到 IO（PORTING §5.13）。
            assistantList = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { container.assistantStore.getAll() }.getOrDefault(emptyList())
            }
        }
    }
    // side_drawer.dart:4182-4190（话题）/ :3014（助手）：点一下是否关抽屉看设置。
    val keepSidebarOnTopicTap = remember {
        container.preferenceRepository
            .readJson("display_keep_sidebar_open_on_topic_tap_v1") == "1"
    }
    val keepSidebarOnAssistantTap = remember {
        container.preferenceRepository
            .readJson("display_keep_sidebar_open_on_assistant_tap_v1") == "1"
    }
    // display_show_chat_list_date_v1（默认关）：会话列表是否显示日期分组头。
    // 走 DisplayPrefs 的唯一入口 —— 原先这里只认 `"1"`，而旧键是裸布尔 `true`，
    // 于是开关写进去也读不出来（用户 2026-09-23「侧边栏怎么没有对话时间显示了」）。
    val showChatListDate = remember(DisplayPrefs.revision) {
        DisplayPrefs.showChatListDate(container)
    }

    fun switchAssistant(a: Assistant) {
        container.setCurrentAssistant(a.id)
        // side_drawer.dart:3012-3018 `_handleSelectAssistant`：closeDrawer 由
        // display_keep_sidebar_open_on_assistant_tap_v1 决定，且关闭时才收起助手列表。
        val closeDrawer = !keepSidebarOnAssistantTap
        if (closeDrawer) assistantsExpanded = false
        // _handleSelectAssistant 3034-3058：设置开启“切换助手后新建会话”时总是
        // 新建；否则有该助手的会话就跳到最近一条，没有才新建。
        val forceNewChat = container.preferenceRepository
            .readJson("display_new_chat_on_assistant_switch_v1") == "1"
        if (forceNewChat) {
            onNew(closeDrawer)
            return
        }
        val recent = conversations
            .filter { it.assistantId == a.id }
            .maxByOrNull { it.updatedAt }
        Haptics.light(view)
        if (recent != null) onSelect(recent.id, closeDrawer) else onNew(closeDrawer)
    }
    // 用户栏名字来自 UserProvider（user_name），未设置过时用本地化默认名 ——
    // 原版 side_drawer 的 widget.userName 也是从 UserProvider 透传进来的。
    val userLabel = userProfile.name.ifEmpty {
        stringResource(UiR.string.user_provider_default_user_name)
    }
    val searchHint = stringResource(UiR.string.side_drawer_search_hint)
    val historyCd = stringResource(UiR.string.side_drawer_history)
    val settingsCd = stringResource(UiR.string.side_drawer_settings)

    // 会话列表按当前助手过滤（side_drawer.dart _sidebarRowsFor assistantId）。
    val scopedConversations = remember(conversations, currentAssistantId) {
        if (currentAssistantId != null) {
            conversations.filter { it.assistantId == currentAssistantId }
        } else {
            conversations
        }
    }
    val filtered = remember(scopedConversations, query) { filterConversations(scopedConversations, query) }
    val sections = remember(filtered) { groupedRows(filtered) }
    val isFilteredEmpty = sections.isEmpty()
    val streamingIds by container.streamingConversationIds.collectAsState()
    var internalSelectionMode by remember { mutableStateOf(false) }
    val selectionMode = forceSelectionMode || internalSelectionMode
    val selectedIds = remember { mutableStateListOf<String>() }
    val allSelected = filtered.isNotEmpty() && filtered.all { it.id in selectedIds }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var assistantMenuFor by remember { mutableStateOf<Assistant?>(null) }
    var assistantDeleteTarget by remember { mutableStateOf<Assistant?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var multiDeleteConfirm by remember { mutableStateOf(false) }
    // Global search mode (side_drawer.dart): the search field prefix toggles
    // it, a >=18px horizontal swipe on the field also toggles, and the list
    // area is replaced by cross-conversation results.
    var globalSearchMode by remember { mutableStateOf(false) }
    var globalResults by remember { mutableStateOf<List<com.psyche.memo.data.db.MessageDao.GlobalHit>>(emptyList()) }
    var globalHasRun by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    /** 移动到助手（单条传 excludeAssistantId、批量不传，照 side_drawer.dart:345 与 :766）。 */
    var moveRequest by remember { mutableStateOf<MoveRequest?>(null) }
    /** 批量移动完成后的「已移动 N 个话题」（条数只在事件回调里知道，所以走状态回灌）。 */
    var moveSnackbarCount by remember { mutableStateOf<Int?>(null) }
    moveSnackbarCount?.let { moved ->
        val text = stringResource(UiR.string.side_drawer_move_selected_snackbar, moved.toString())
        LaunchedEffect(moved) {
            moveSnackbarCount = null
            com.psyche.memo.ui.snackbar.SnackbarManager.show(
                com.psyche.memo.ui.snackbar.AppNotification(
                    message = text,
                    type = com.psyche.memo.ui.snackbar.NotificationType.SUCCESS,
                    durationMs = 3000,
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(cs.surface)
            // Side drawer extends edge-to-edge; SafeArea equivalent —
            // status bar on top, navigation bar gestures below the user bar.
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        if (selectionMode) {
            // SidebarSelectionHeader (mobile): cancel + count + select all.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { internalSelectionMode = false; selectedIds.clear() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(UiR.string.side_drawer_cancel),
                        modifier = Modifier.size(22.dp),
                        tint = cs.onSurface,
                    )
                }
                Text(
                    text = stringResource(UiR.string.side_drawer_selection_title, selectedIds.size.toString()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Row(
                    modifier = Modifier.clickable {
                        if (allSelected) selectedIds.clear() else selectedIds.addAll(filtered.map { it.id })
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosCheckbox(
                        value = allSelected,
                        onValueChanged = {},
                        size = 18.dp,
                        hitTestSize = 32.dp,
                        enableHaptics = false,
                        interactive = false,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(
                            if (allSelected) UiR.string.side_drawer_selection_deselect_all
                            else UiR.string.side_drawer_selection_select_all,
                        ),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                }
            }
        } else {
        // 0. Backup reminder banner (side_drawer.dart `_buildBackupReminderBanner`
        //    L1520-1612, placed above the search box in the fixed header): due
        //    only, tap goes to the backup page, X snoozes for the session.
        com.psyche.memo.ui.backup.BackupReminderBanner(
            container = container,
            onOpenBackup = onOpenBackup,
        )
        // 1. Search field (memo mobile: filled rounded TextField with a
        //    centered hint overlay when empty; leading search icon; trailing
        //    clear button when text is non-empty; history button is a separate
        //    44dp circular icon button on the right of the row).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        // Side_drawer.dart L2185-2227: swipe >= 18 toggles.
                        var accumulated = 0f
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            accumulated = 0f
                            var handled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.changedToUp()) break
                                if (change.isConsumed) break
                                accumulated += change.positionChange().x
                                if (kotlin.math.abs(accumulated) >= 18f && !handled) {
                                    handled = true
                                    globalSearchMode = !globalSearchMode
                                    globalHasRun = false
                                    globalResults = emptyList()
                                    change.consume()
                                } else if (handled) {
                                    change.consume()
                                }
                            }
                        }
                    }
            ) {
                // 原版胶囊 = `isCollapsed: true`（L1940）+ `contentPadding(h14, v11)`
                // （L2115-2118）+ 14sp 文字 ⇒ 高约 42dp，圆角 14（L2122）。Material3 1.3.2
                // 的 TextField 没有 contentPadding 形参、内置上下各 16dp，会鼓出一截，
                // 所以按原版几何自己拼这颗胶囊。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SEARCH_FIELD_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.6f)),
                ) {
                    Row(
                        modifier = Modifier.matchParentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 前缀图标位：原版 padding left 10 / right 4、图标 16
                        // （side_drawer.dart L1941-1949）；点击切换话题/全局搜索模式。
                        Box(
                            modifier = Modifier
                                .padding(start = 10.dp, end = 4.dp)
                                .clickable {
                                    globalSearchMode = !globalSearchMode
                                    globalHasRun = false
                                    globalResults = emptyList()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (globalSearchMode) Lucide.Database else Lucide.BotMessageSquare,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = cs.onSurface.copy(alpha = 0.72f),
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp),
                        )
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Lucide.X,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = cs.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
                if (query.isEmpty()) {
                    Box(
                        modifier = Modifier.matchParentSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (globalSearchMode) stringResource(UiR.string.side_drawer_global_search_hint) else searchHint,
                            modifier = Modifier.padding(horizontal = 40.dp),
                            fontSize = 14.sp,
                            color = cs.onSurface.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // History button (44dp circular, no ripple — ChatHistoryPage is
            // not ported yet so the tap is a no-op).
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { Haptics.light(view); onOpenHistory() },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Lucide.History,
                        contentDescription = historyCd,
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface,
                    )
                }
            }
        }
        } // end !selectionMode

        Spacer(Modifier.height(6.dp))

        // 2. Assistant card (memo mobile L2560-2647): IosCardPress padding
        //    (4,6,12,6) r16 on surface; AssistantAvatar 32dp = initial letter
        //    on primary-15% circle with a 0.5dp onSurface-12% border; name
        //    15sp medium; ChevronDown 18dp at 70%.
        Surface(
            shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
            color = cs.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .combinedClickable(
                    onClick = { assistantsExpanded = !assistantsExpanded },
                    // assistant_entry_actions.dart —— 长按助手卡弹上下文菜单。
                    onLongClick = { assistantMenuFor = container.currentAssistant() },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // side_drawer.dart:2610 —— AssistantAvatar(assistant, size: 32)；
                // 原版只画 primary-15% 圆底（无描边），这里同样交给共享组件。
                if (currentAssistant != null) {
                    AssistantListAvatar(currentAssistant, 32.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(cs.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = assistantLabel.firstOrNull()?.toString() ?: "?",
                            style = TextStyle(
                                fontSize = 13.4.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.primary,
                            ),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = assistantLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    ),
                )
                Icon(
                    if (assistantsExpanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = cs.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        // 展开的助手列表（side_drawer.dart _buildAssistantsList 移动端子集）：
        // 点击条目切换当前助手。
        if (assistantsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                assistantList.forEach { a ->
                    val selected = a.id == currentAssistantId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { switchAssistant(a) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // side_drawer.dart:3875 —— AssistantAvatar(a, size: 28)。
                        AssistantListAvatar(a, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = a.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface,
                            ),
                        )
                        if (selected) {
                            Icon(
                                Lucide.CircleCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = cs.primary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3. Conversation list grouped by date (Pinned / Today / Yesterday /
        //    MMM d, yyyy). Section gap 8dp, tile gap 4dp, tile padding matches
        //    memo's _ChatTile (fontSize 15, weight regular).
        if (globalSearchMode) {
            Box(modifier = Modifier.weight(1f)) {
            GlobalSearchResults(
                container = container,
                query = query,
                results = globalResults,
                onResults = { r, ran ->
                    globalResults = r
                    globalHasRun = ran
                },
                onOpenConversation = { id ->
                    Haptics.light(view)
                    onSelect(id, !keepSidebarOnTopicTap)
                },
            )
            }
        } else {
            // 列表还没读回来 → 铺原版那个 **tile 骨架**（`side_drawer.dart:4949`
            // `_ConversationListSkeleton`：药丸条，不是气泡块）。渲染在列表上层，
            // 列表此刻是空的，所以视觉上就是骨架。
            if (!listLoaded) {
                ConversationListSkeleton()
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    // side_drawer.dart:2702-2704 —— 隐藏日期头时列表顶部留 10（显示时 4）。
                    top = if (showChatListDate) 4.dp else 10.dp,
                    end = 10.dp,
                    bottom = 16.dp,
                ),
            ) {
            if (isFilteredEmpty) {
                item(key = "empty") {
                    val emptyText = if (query.isNotEmpty()) {
                        stringResource(UiR.string.side_drawer_global_search_no_results)
                    } else {
                        stringResource(UiR.string.chat_history_page_no_conversations)
                    }
                    Text(
                        text = emptyText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            } else {
                sections.forEachIndexed { sIdx, section ->
                    // side_drawer.dart:4098-4105 —— display_show_chat_list_date_v1
                    // 关掉时只过滤掉**按日期的分组头**（Today/Yesterday/日期），
                    // Pinned 头保留。
                    if (showsSectionHeader(section, showChatListDate)) {
                        item(key = "h_${section.key}") {
                            // Mirrors the mobile _SidebarHeaderRow render
                            // (side_drawer.dart L4140): plain text, 14sp semibold,
                            // primary color, padding (14, 6, 0, 6) — no chevron.
                            val label = section.labelTextResId?.let { stringResource(it) }
                                ?: formatSectionDate(section.bucket!!)
                            Text(
                                text = label,
                                modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 0.dp, bottom = 6.dp),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.primary,
                                ),
                            )
                        }
                    }
                    itemsIndexed(section.items, key = { _, c -> c.id }) { idx, conv ->
                        val isCurrent = conv.id == selectedId
                        val isChecked = conv.id in selectedIds
                        val isLastInSection = idx == section.items.lastIndex
                        // IosCardPress pressed look: darken while held.
                        val tileInteraction = remember { MutableInteractionSource() }
                        val tilePressed by tileInteraction.collectIsPressedAsState()
                        Surface(
                            // memo's _ChatTile: current row = primary 12%
                            // rounded block; in selection mode checked = 16%.
                            color = when {
                                tilePressed -> cs.onSurface.copy(alpha = 0.06f)
                                selectionMode && isChecked -> cs.primary.copy(alpha = 0.16f)
                                isCurrent -> cs.primary.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            },
                            shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (isLastInSection) 0.dp else 4.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        interactionSource = tileInteraction,
                                        indication = null,
                                        onClick = {
                                            if (selectionMode) {
                                                if (isChecked) selectedIds.remove(conv.id) else selectedIds.add(conv.id)
                                            } else {
                                                Haptics.light(view)
                                                // side_drawer.dart:4182-4190 话题行 onTap。
                                                onSelect(conv.id, !keepSidebarOnTopicTap)
                                            }
                                        },
                                        onLongClick = { if (!selectionMode) menuFor = conv },
                                    )
                                    .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selectionMode) {
                                    Box(
                                        modifier = Modifier.width(28.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        IosCheckbox(
                                            value = isChecked,
                                            onValueChanged = {},
                                            size = 20.dp,
                                            hitTestSize = 20.dp,
                                            enableHaptics = false,
                                            interactive = false,
                                        )
                                    }
                                }
                                Text(
                                    text = conv.title,
                                    modifier = Modifier.weight(1f),
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = cs.onSurface,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (conv.id in streamingIds) {
                                    Spacer(Modifier.width(8.dp))
                                    LoadingDot()
                                }
                            }
                        }
                    }
                    if (sIdx < sections.lastIndex) {
                        item(key = "secgap_$sIdx") {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
        }

        if (selectionMode) {
            // SidebarSelectionActionBar (mobile): translucent surface, top
            // corners 18dp; pin / move(disabled) / delete.
            val allSelectedPinned = selectedIds.isNotEmpty() &&
                conversations.filter { it.id in selectedIds }.all { it.isPinned }
            Surface(
                color = cs.surface.copy(alpha = 0.78f),
                shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp,
                    ),
                ) {
                    SelectionAction(
                        modifier = Modifier.weight(1f),
                        icon = if (allSelectedPinned) Lucide.PinOff else Lucide.Pin,
                        label = stringResource(
                            if (allSelectedPinned) UiR.string.side_drawer_selection_unpin
                            else UiR.string.side_drawer_selection_pin,
                        ),
                        color = cs.onSurface,
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            val target = !allSelectedPinned
                            selectedIds.forEach { container.conversationDao.updatePinned(it, target) }
                            reload()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    SelectionAction(
                        modifier = Modifier.weight(1f),
                        icon = Lucide.Shuffle,
                        label = stringResource(UiR.string.side_drawer_selection_move),
                        color = cs.primary,
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            // side_drawer.dart:767-770 `_moveSelected`：正在生成的会话不参与。
                            val picked = conversations.filter {
                                it.id in selectedIds && it.id !in streamingIds
                            }
                            if (picked.isNotEmpty()) moveRequest = MoveRequest(picked, null, batch = true)
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    SelectionAction(
                        modifier = Modifier.weight(1f),
                        icon = Lucide.Trash2,
                        label = stringResource(UiR.string.side_drawer_selection_delete),
                        color = cs.error,
                        enabled = selectedIds.isNotEmpty(),
                        onClick = { multiDeleteConfirm = true },
                    )
                }
            }
        } else {
        // Bottom user bar (memo mobile L2799): padding (16,10,16,12),
        // leading 6dp spacer, 40dp initial avatar, 20dp gap, name, buttons.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(6.dp))
            // 用户头像：四态（emoji/url/file/空回退首字母）来自 UserProvider，
            // 空态是名字首字母（side_drawer.dart:1670-1765 avatarWidget）；
            // 点击打开头像 sheet（`_editAvatar`）。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { userAvatarEditRequested = true },
                contentAlignment = Alignment.Center,
            ) {
                UserAvatar(
                    profile = userProfile,
                    name = userLabel,
                    size = 40.dp,
                    fallback = UserAvatarFallback.Initial,
                )
            }
            Spacer(Modifier.width(20.dp))
            Text(
                text = userLabel,
                modifier = Modifier
                    .weight(1f)
                    .clickable { userNicknameEditRequested = true },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.size(45.dp), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { Haptics.light(view); onOpenTranslate() },
                    modifier = Modifier.size(45.dp),
                ) {
                    Icon(
                        Lucide.Languages,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = cs.onSurface,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.size(45.dp), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { Haptics.light(view); onOpenSettings() },
                    modifier = Modifier.size(45.dp),
                ) {
                    Icon(
                        Lucide.Settings,
                        contentDescription = settingsCd,
                        modifier = Modifier.size(22.dp),
                        tint = cs.onSurface,
                    )
                }
            }
        }
        } // end !selectionMode bottom bar
    }

    // 用户头像 sheet（`_editAvatar`）与昵称对话框（`_editUserName`）。
    // UserAvatarEditor 必须常驻组合（内部用 open/step 决定渲染），不能用
    // if 包住：点「选图」会先关 sheet 卸载组件，相册 launcher 一起被注销。
    UserAvatarEditor(
        store = container.userProfileStore,
        open = userAvatarEditRequested,
        onDismiss = { userAvatarEditRequested = false },
    )
    if (userNicknameEditRequested) {
        NicknameDialog(
            initial = userProfile.name,
            onDismiss = { userNicknameEditRequested = false },
            onConfirm = { name ->
                container.userProfileStore.setName(name)
                userNicknameEditRequested = false
            },
        )
    }

    // Long-press conversation menu (memo _showChatMenu mobile branch):
    // bottom sheet with a grab handle and 48dp rows.
    menuFor?.let { target ->
        ModalBottomSheet(
            sheetState = rememberMemoSheetState(),
            onDismissRequest = { menuFor = null },
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
            containerColor = cs.overlaySurfaceColor(),
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MenuRow(
                    icon = Lucide.ListChecks,
                    label = stringResource(UiR.string.side_drawer_menu_select),
                    color = cs.onSurface,
                ) {
                    menuFor = null
                    internalSelectionMode = true
                    selectedIds.clear()
                    selectedIds.add(target.id)
                }
                MenuRow(
                    icon = Lucide.Pencil,
                    label = stringResource(UiR.string.side_drawer_menu_rename),
                    color = cs.onSurface,
                ) {
                    menuFor = null
                    renameTarget = target
                }
                MenuRow(
                    icon = Lucide.RefreshCw,
                    label = stringResource(UiR.string.side_drawer_menu_regenerate_title),
                    color = cs.onSurface,
                ) {
                    menuFor = null
                    scope.launch {
                        runCatching {
                            com.psyche.memo.TitleSummaryGenerator.generateTitle(container, target.id, force = true)
                        }.onFailure { e ->
                            // side_drawer.dart:972-975 —— 标题生成失败留一条应用日志。
                            com.psyche.memo.common.logging.FlutterLogger.log(
                                "[SideDrawer] Regenerate title failed: $e",
                                tag = "SideDrawer",
                            )
                        }
                        reload()
                        onConversationTitleChanged(target.id)
                    }
                }
                MenuRow(
                    icon = Lucide.Pin,
                    label = stringResource(
                        if (target.isPinned) UiR.string.side_drawer_menu_unpin
                        else UiR.string.side_drawer_menu_pin,
                    ),
                    color = cs.onSurface,
                ) {
                    menuFor = null
                    container.conversationDao.updatePinned(target.id, !target.isPinned)
                    reload()
                }
                MenuRow(
                    icon = Lucide.Copy,
                    label = stringResource(UiR.string.side_drawer_menu_copy),
                    color = cs.onSurface,
                ) {
                    menuFor = null
                    // duplicateConversation: copy the row and its messages.
                    // Reads + the bulk insert run off the main thread in one
                    // transaction (no getTail(Int.MAX_VALUE/2) row-by-row copy).
                    scope.launch {
                        val dup = Conversation.create(
                            title = target.title,
                            assistantId = target.assistantId,
                        )
                        withContext(Dispatchers.IO) {
                            container.conversationDao.insert(dup)
                            val messages =
                                container.messageDao.getAllForConversation(target.id)
                            container.messageDao.insertAllInTransaction(messages.map { m ->
                                ChatMessage(
                                    id = ChatMessage.newId(),
                                    role = m.role,
                                    parts = m.parts,
                                    timestamp = m.timestamp,
                                    modelId = m.modelId,
                                    providerId = m.providerId,
                                    conversationId = dup.id,
                                    reasoningSegmentsJson = m.reasoningSegmentsJson,
                                    translation = m.translation,
                                    reasoningStartAt = m.reasoningStartAt,
                                    reasoningFinishedAt = m.reasoningFinishedAt,
                                    groupId = m.groupId,
                                    promptTokens = m.promptTokens,
                                    completionTokens = m.completionTokens,
                                    cachedTokens = m.cachedTokens,
                                    durationMs = m.durationMs,
                                    updatedAt = m.updatedAt,
                                    messageOrder = m.messageOrder,
                                )
                            })
                        }
                        reload()
                    }
                }
                MenuRow(
                    icon = Lucide.Shuffle,
                    label = stringResource(UiR.string.side_drawer_menu_move_to),
                    color = cs.onSurface,
                ) {
                    menuFor = null
                    moveRequest = MoveRequest(listOf(target), target.assistantId, batch = false)
                }
                MenuRow(
                    icon = Lucide.Trash2,
                    label = stringResource(UiR.string.side_drawer_menu_delete),
                    color = cs.error,
                ) {
                    menuFor = null
                    deleteTarget = target
                }
            }
        }
    }

    // Single delete confirmation (memo _confirmDeleteConversation).
    deleteTarget?.let { target ->
        // 源码 side_drawer.dart:389 —— Deleted "title"（回调里取不到
        // stringResource，先在组合作用域内求值）。
        val deleteDoneText = stringResource(UiR.string.side_drawer_delete_snackbar, target.title)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_title)) },
            text = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_content, "1")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    val deletingCurrent = target.id == selectedId
                    container.deleteConversation(target.id)
                    reload()
                    // 源码 side_drawer.dart:387-392 —— 删除成功 snackbar。
                    com.psyche.memo.ui.snackbar.SnackbarManager.show(
                        com.psyche.memo.ui.snackbar.AppNotification(
                            message = deleteDoneText,
                            type = com.psyche.memo.ui.snackbar.NotificationType.SUCCESS,
                            durationMs = 3000,
                        ),
                    )
                    if (deletingCurrent) onCurrentDeleted()
                }) { Text(stringResource(UiR.string.side_drawer_menu_delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(UiR.string.side_drawer_cancel))
                }
            },
        )
    }

    // Rename dialog (memo _renameChat).
    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(UiR.string.side_drawer_menu_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = null
                    if (name.isNotBlank()) {
                        container.conversationDao.updateTitle(target.id, name.trim())
                        reload()
                        onConversationTitleChanged(target.id)
                    }
                }) { Text(stringResource(UiR.string.side_drawer_menu_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(UiR.string.side_drawer_cancel))
                }
            },
        )
    }

    // Move-to-assistant sheet (showAssistantMoveSelector): 单条排除会话当前所在
    // 助手，批量列出全部助手（side_drawer.dart:345-348 与 :766-810 的差别）。
    moveRequest?.let { request ->
        ModalBottomSheet(
            sheetState = rememberMemoSheetState(),
            onDismissRequest = { moveRequest = null },
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
            containerColor = cs.overlaySurfaceColor(),
            dragHandle = null,
        ) {
            // assistant_rows 整表 + 逐条解 JSON 不在组合期做（§5.13）。
            val assistants = rememberLoaded(emptyList(), request.excludeAssistantId) {
                com.psyche.memo.data.db.PayloadEntityDao(
                    container.database.readableDatabase,
                    "assistant_rows",
                    primaryKey = "id",
                ).getAll().mapNotNull { row ->
                    runCatching {
                        com.psyche.memo.data.model.Assistant.fromJsonString(
                            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                            row.payload,
                        )
                    }.getOrNull()
                }.filter { it.id != request.excludeAssistantId }
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            ) {
                assistants.forEach { a ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .clickable {
                                moveRequest = null
                                // 移动前算「下一条」（side_drawer.dart:777 + :824-840）：
                                // 当前助手作用域内、排除本次被移动的会话，按更新时间取最近一条。
                                val batchIds = request.conversations.map { it.id }.toSet()
                                val nextId = if (currentAssistantId == null) {
                                    null
                                } else {
                                    scopedConversations
                                        .filter { it.id !in batchIds }
                                        .maxByOrNull { it.updatedAt }?.id
                                }
                                // chat_service.dart:4231-4256：去重、跳过已在目标助手的，
                                // 只有真移动的计数；:4220 移动时清 injectedMemoryHash。
                                val now = System.currentTimeMillis()
                                val seen = HashSet<String>()
                                val movedIds = HashSet<String>()
                                request.conversations.forEach { conv ->
                                    if (conv.id.isEmpty() || !seen.add(conv.id)) return@forEach
                                    if (conv.assistantId == a.id) return@forEach
                                    container.conversationDao.update(
                                        conv.copy(
                                            assistantId = a.id,
                                            updatedAt = now,
                                            injectedMemoryHash = null,
                                        ),
                                    )
                                    movedIds.add(conv.id)
                                }
                                reload()
                                if (movedIds.isEmpty() || !request.batch) return@clickable
                                moveSnackbarCount = movedIds.size
                                selectedIds.clear()
                                internalSelectionMode = false
                                // 当前会话被移走（或本来没有当前会话）→ 选下一条 / 新建
                                // （side_drawer.dart:799-808，closeDrawer 跟随「点话题保持侧栏」）。
                                val currentMoved =
                                    selectedId == null || selectedId in movedIds
                                if (currentMoved) {
                                    val closeDrawer = !keepSidebarOnTopicTap
                                    if (nextId != null) onSelect(nextId, closeDrawer) else onNew(closeDrawer)
                                }
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .border(0.5.dp, cs.onSurface.copy(alpha = 0.12f), CircleShape)
                                .background(cs.primary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = a.name.firstOrNull()?.toString() ?: "?",
                                style = TextStyle(
                                    fontSize = 13.4.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.primary,
                                ),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = a.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // Multi-delete confirmation (memo _deleteSelected).
    if (multiDeleteConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { multiDeleteConfirm = false },
            title = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_title)) },
            text = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_content, selectedIds.size.toString())) },
            confirmButton = {
                TextButton(onClick = {
                    multiDeleteConfirm = false
                    val deletingCurrent = selectedId != null && selectedId in selectedIds
                    selectedIds.toList().forEach { container.deleteConversation(it) }
                    selectedIds.clear()
                    internalSelectionMode = false
                    reload()
                    if (deletingCurrent) onCurrentDeleted()
                }) { Text(stringResource(UiR.string.side_drawer_menu_delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { multiDeleteConfirm = false }) {
                    Text(stringResource(UiR.string.side_drawer_cancel))
                }
            },
        )
    }

    // Assistant context menu (assistant_entry_actions.dart
    // _showAssistantItemMenuMobile): edit / copy / clear tag / manage tags /
    // delete rows, 48dp tiles on the sheet background.
    assistantMenuFor?.let { target ->
        val tagsRepo = remember(container) {
            com.psyche.memo.data.repo.TagRepository(container.database.writableDatabase, container.preferenceRepository)
        }
        ModalBottomSheet(
            sheetState = rememberMemoSheetState(),
            onDismissRequest = { assistantMenuFor = null },
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
            containerColor = cs.overlaySurfaceColor(),
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MenuRow(Lucide.Pencil, stringResource(UiR.string.assistant_tags_context_menu_edit_assistant), cs.onSurface) {
                    assistantMenuFor = null
                    onEditAssistant(target.id)
                }
                MenuRow(Lucide.Copy, stringResource(UiR.string.assistant_settings_copy_button), cs.onSurface) {
                    assistantMenuFor = null
                    val store = com.psyche.memo.data.assistant.AssistantStore(container.database.writableDatabase)
                    val copyName = buildCopyName(
                        existing = store.getAll().map { it.name },
                        sourceName = target.name,
                        suffix = container.appContext.getString(UiR.string.assistant_settings_copy_suffix).trim(),
                        fallback = container.appContext.getString(UiR.string.assistant_provider_new_assistant_name),
                    )
                    if (store.duplicate(
                            id = target.id,
                            copyName = copyName,
                            copyLocalFile = { path, dupId, isAvatar ->
                                duplicateAssistantLocalFile(
                                    container.appContext, path, dupId, isAvatar,
                                )
                            },
                        ) != null
                    ) {
                        com.psyche.memo.ui.snackbar.SnackbarManager.show(
                            com.psyche.memo.ui.snackbar.AppNotification(
                                message = container.appContext.getString(UiR.string.assistant_settings_copy_success),
                                type = com.psyche.memo.ui.snackbar.NotificationType.SUCCESS,
                            ),
                        )
                    }
                }
                if (tagsRepo.tagOfAssistant(target.id) != null) {
                    MenuRow(Lucide.Eraser, stringResource(UiR.string.assistant_tags_clear_tag), cs.onSurface) {
                        assistantMenuFor = null
                        tagsRepo.assignAssistant(target.id, null)
                    }
                }
                MenuRow(Lucide.Bookmark, stringResource(UiR.string.assistant_tags_context_menu_manage_tags), cs.onSurface) {
                    assistantMenuFor = null
                    onManageTags(target.id)
                }
                MenuRow(Lucide.Trash2, stringResource(UiR.string.assistant_tags_context_menu_delete_assistant), cs.error) {
                    assistantMenuFor = null
                    assistantDeleteTarget = target
                }
            }
        }
    }

    assistantDeleteTarget?.let { target ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { assistantDeleteTarget = null },
            title = { Text(stringResource(UiR.string.assistant_settings_delete_dialog_title)) },
            text = { Text(stringResource(UiR.string.assistant_settings_delete_dialog_content)) },
            confirmButton = {
                TextButton(onClick = {
                    assistantDeleteTarget = null
                    Haptics.light(view)
                    val store = com.psyche.memo.data.assistant.AssistantStore(container.database.writableDatabase)
                    val success = store.delete(target.id)
                    if (container.currentAssistant()?.id == target.id) {
                        container.setCurrentAssistant("")
                        container.refreshCurrentAssistant()
                    }
                    reload()
                    if (!success) {
                        com.psyche.memo.ui.snackbar.SnackbarManager.show(
                            com.psyche.memo.ui.snackbar.AppNotification(
                                message = container.appContext.getString(UiR.string.assistant_settings_at_least_one_assistant_required),
                                type = com.psyche.memo.ui.snackbar.NotificationType.WARNING,
                            ),
                        )
                    }
                }) {
                    Text(
                        text = stringResource(UiR.string.assistant_settings_delete_dialog_confirm),
                        color = cs.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { assistantDeleteTarget = null }) {
                    Text(stringResource(UiR.string.assistant_settings_delete_dialog_cancel))
                }
            },
        )
    }

}

/**
 * Local title substring filter for the search box; case-insensitive, trimmed.
 */
internal fun filterConversations(
    conversations: List<Conversation>,
    query: String,
): List<Conversation> {
    val q = query.trim()
    if (q.isEmpty()) return conversations
    return conversations.filter { it.title.contains(q, ignoreCase = true) }
}

/**
 * Section descriptor for the grouped conversation list. Either carries a
 * stable string-resource id (Pinned / Today / Yesterday) or a Date bucket to
 * be formatted with the locale-aware short/full date pattern at render time.
 */
internal data class ConversationSection(
    val key: String,
    val labelTextResId: Int?,
    val bucket: Date?,
    val items: List<Conversation>,
)

/**
 * side_drawer.dart:4098-4105 —— `display_show_chat_list_date_v1` 关掉时，
 * `visibleRows` 会把按日期分组的表头整批滤掉，Pinned 表头保留（分组本身不变）。
 */
internal fun showsSectionHeader(section: ConversationSection, showChatListDate: Boolean): Boolean =
    showChatListDate || section.key == "pinned"

internal fun groupedRows(items: List<Conversation>): List<ConversationSection> {
    val now = Calendar.getInstance()
    val today = startOfToday(now)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
    val todayMs = today.timeInMillis

    val pinned = items.asSequence().filter { it.isPinned }
        .sortedByDescending { it.updatedAt }
        .toList()
    val rest = items.asSequence().filter { !it.isPinned }
        .sortedByDescending { it.updatedAt }
        .toList()

    fun bucketKey(time: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val ms = c.timeInMillis
        return when {
            ms >= todayMs -> "today"
            ms >= yesterday -> "yesterday"
            else -> SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(time))
        }
    }

    val grouped = LinkedHashMap<String, MutableList<Conversation>>()
    for (c in rest) {
        grouped.getOrPut(bucketKey(c.updatedAt)) { ArrayList() }.add(c)
    }

    val bucketToResId = linkedMapOf(
        "today" to UiR.string.side_drawer_date_today,
        "yesterday" to UiR.string.side_drawer_date_yesterday,
    )

    val sections = ArrayList<ConversationSection>()
    if (pinned.isNotEmpty()) {
        sections.add(
            ConversationSection(
                key = "pinned",
                labelTextResId = UiR.string.side_drawer_pinned_label,
                bucket = null,
                items = pinned,
            ),
        )
    }
    for ((key, list) in grouped) {
        val bucketDate = list.maxOfOrNull { it.updatedAt }?.let { Date(it) }
        sections.add(
            ConversationSection(
                key = key,
                labelTextResId = bucketToResId[key],
                bucket = bucketDate,
                items = list,
            ),
        )
    }
    return sections
}

private fun startOfToday(now: Calendar): Calendar {
    val c = now.clone() as Calendar
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c
}

@Composable
private fun formatSectionDate(date: Date): String {
    val nowYear = Calendar.getInstance().get(Calendar.YEAR)
    val dateYear = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    val pattern = if (dateYear == nowYear) {
        stringResource(UiR.string.side_drawer_date_short_pattern)
    } else {
        stringResource(UiR.string.side_drawer_date_full_pattern)
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/** 9dp primary pulsing dot (memo _LoadingDot: 900ms fade, reverse repeat). */
@Composable
private fun LoadingDot() {
    val transition = rememberInfiniteTransition(label = "loadingDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                CircleShape,
            ),
    )
}

/** Selection action bar button (memo _SidebarSelectionActionButton). */
@Composable
private fun SelectionAction(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = selectionChipColor(
        onSurface = cs.onSurface,
        color = color,
        isDark = LocalSemanticColors.current.isDark,
    )
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = color),
            maxLines = 1,
        )
    }
}

/** Bottom-sheet menu row (memo mobile row(): 48dp, r14, icon20 + 15sp medium). */
@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(48.dp)
            .background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/**
 * 全局搜索结果区（side_drawer.dart L1188-1366 的移植）：
 * 计数行 + 结果行（r14、标题 14sp medium + 摘要 12.5sp@65% 3 行、
 * 命中高亮背景、tap 打开会话）。
 */
@Composable
private fun GlobalSearchResults(
    container: AppContainerImpl,
    query: String,
    results: List<com.psyche.memo.data.db.MessageDao.GlobalHit>,
    onResults: (List<com.psyche.memo.data.db.MessageDao.GlobalHit>, Boolean) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var searching by remember { mutableStateOf(false) }
    val needle = query.trim()

    LaunchedEffect(needle) {
        if (needle.isNotEmpty()) {
            // 全库消息搜索（不是本会话）：真机上是几十毫秒级，不能占主线程。
            val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.messageDao.searchGlobal(needle)
            }
            onResults(hits, true)
        }
    }

    if (needle.isEmpty()) {
        // Pre-search: nothing (mobile branch, L1202-1205).
        return
    }
    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 0.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = stringResource(UiR.string.side_drawer_global_search_no_results),
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.45f)),
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Result count (L1246-1256)
        Text(
            text = stringResource(UiR.string.side_drawer_global_search_result_count, results.size),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 6.dp),
            style = TextStyle(
                fontSize = 12.sp,
                color = cs.onSurface.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium,
            ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
        ) {
            items(results, key = { it.conversationId }) { result ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                        .background(cs.primary.copy(alpha = 0.10f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .clickable { onOpenConversation(result.conversationId) }
                        .padding(start = 14.dp, top = 9.dp, end = 14.dp, bottom = 9.dp),
                ) {
                    Text(
                        text = result.conversationTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                    )
                    if (result.snippet.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = result.snippet,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                        )
                    }
                }
            }
        }
    }
}

/** assistant_provider.dart _buildCopyName L155-170. */
private fun buildCopyName(
    existing: List<String>,
    sourceName: String,
    suffix: String,
    fallback: String,
): String {
    val baseName = sourceName.trim().ifEmpty { fallback }
    var candidate = if (suffix.isEmpty()) baseName else "$baseName $suffix"
    var counter = 2
    while (candidate in existing) {
        val counterSuffix = if (suffix.isEmpty()) "$counter" else "$suffix $counter"
        candidate = "$baseName $counterSuffix"
        counter++
    }
    return candidate
}

/**
 * 抽屉会话列表的加载骨架 —— 逐字照原版 `side_drawer.dart:4949-5016`
 * `_ConversationListSkeleton`：
 * - 每个 tile = `bar(标题, 高 14)` + 8 间隔 + `bar(元信息, 高 10)`，圆角 = **高/2**（药丸）；
 * - 颜色 `onSurface@8%`（`:4975`）；tile 内边距 `horizontal 6 / vertical 10`（`:4992`）；
 * - 5 个 tile，宽度比 `0.72/0.56/0.66/0.50/0.62`、副行 `0.42/0.34/0.48/0.30/0.38`（`:5009-5013`）；
 * - 整列 `opacity 0.45→1.0`、900ms 往复（`:4961-4964` / `:5005`）。
 *
 * 只在「列表还没读到第一批数据」时显示（原版同条件：ChatService 初始化期间）。
 */
@Composable
private fun ConversationListSkeleton(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val barColor = cs.onSurface.copy(alpha = 0.08f)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "conversationListSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "conversationListSkeletonPulse",
    )
    val tiles = listOf(
        0.72f to 0.42f,
        0.56f to 0.34f,
        0.66f to 0.48f,
        0.50f to 0.30f,
        0.62f to 0.38f,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 4.dp, end = 10.dp)
            .alpha(pulse),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        tiles.forEach { (titleFactor, metaFactor) ->
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                SkeletonBar(titleFactor, 14.dp, barColor)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(metaFactor, 10.dp, barColor)
            }
        }
    }
}

/** 骨架里的一根药丸条（圆角 = 高/2，原版 `side_drawer.dart:4977-4988`）。 */
@Composable
private fun SkeletonBar(widthFactor: Float, height: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFactor)
            .height(height)
            .background(color, androidx.compose.foundation.shape.RoundedCornerShape(height / 2)),
    )
}

/**
 * 一次「移动到助手」请求：要移动的会话 + 选择器里排除的助手
 * （[excludeAssistantId] 只有单条路径给）+ 是否批量（批量才有 snackbar、
 * 才退多选并处理「当前会话被移走」）。
 */
/** 原版搜索胶囊高：contentPadding 上下 11 + 14sp 行高 ≈ 42dp（side_drawer.dart L2115-2118）。 */
private const val SEARCH_FIELD_HEIGHT_DP = 42

private data class MoveRequest(
    val conversations: List<Conversation>,
    val excludeAssistantId: String?,
    val batch: Boolean,
)