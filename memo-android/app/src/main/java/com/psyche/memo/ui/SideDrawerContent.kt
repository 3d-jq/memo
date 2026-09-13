@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.psyche.memo.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.BotMessageSquare
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
    var query by remember { mutableStateOf("") }

    fun reload() {
        conversations = container.conversationDao.getAll()
    }

    // 抽屉改为常驻组合（避免开合时插拔节点导致布局抖动），所以不能靠"每次重建"
    // 刷新列表 —— 用 open 作为 key，每次展示时重新加载。
    androidx.compose.runtime.LaunchedEffect(selectedId, open) { reload() }

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
            assistantList = runCatching { container.assistantStore.getAll() }.getOrDefault(emptyList())
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
    val showChatListDate = remember {
        container.preferenceRepository
            .readJson("display_show_chat_list_date_v1") == "1"
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
    var moveTarget by remember { mutableStateOf<Conversation?>(null) }

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
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {},
                    textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = {
                        // Tapping the prefix toggles global search mode
                        // (side_drawer.dart L2270-2327: prefix is the toggle).
                        Box(
                            modifier = Modifier
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
                    },
                    trailingIcon = {
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
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = cs.surfaceVariant.copy(alpha = 0.6f),
                        unfocusedContainerColor = cs.surfaceVariant.copy(alpha = 0.6f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
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
            shape = RoundedCornerShape(16.dp),
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
                            shape = RoundedCornerShape(16.dp),
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
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
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
                        enabled = false,
                        onClick = {},
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
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = cs.surface,
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
                            .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
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
                    moveTarget = target
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
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_title)) },
            text = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_content, "1")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    val deletingCurrent = target.id == selectedId
                    container.conversationDao.delete(target.id)
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

    // Move-to-assistant sheet (memo showAssistantMoveSelector): lists every
    // assistant except the conversation's current one.
    moveTarget?.let { target ->
        ModalBottomSheet(
            sheetState = rememberMemoSheetState(),
            onDismissRequest = { moveTarget = null },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = cs.surface,
            dragHandle = null,
        ) {
            val assistants = remember {
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
                }.filter { it.id != target.assistantId }
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            ) {
                assistants.forEach { a ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable {
                                moveTarget = null
                                container.conversationDao.update(
                                    target.copy(assistantId = a.id, updatedAt = System.currentTimeMillis()),
                                )
                                reload()
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
            onDismissRequest = { multiDeleteConfirm = false },
            title = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_title)) },
            text = { Text(stringResource(UiR.string.side_drawer_selection_delete_confirm_content, selectedIds.size.toString())) },
            confirmButton = {
                TextButton(onClick = {
                    multiDeleteConfirm = false
                    val deletingCurrent = selectedId != null && selectedId in selectedIds
                    selectedIds.toList().forEach { container.conversationDao.delete(it) }
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
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = cs.surface,
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
                            .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
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
    // Color.alphaBlend equivalent: blend color@14% over onSurface@4%.
    val base = cs.onSurface.copy(alpha = 0.04f)
    val f = 0.14f
    val bg = Color(
        red = color.red * f + base.red * (1f - f),
        green = color.green * f + base.green * (1f - f),
        blue = color.blue * f + base.blue * (1f - f),
        alpha = 1f,
    )
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg, RoundedCornerShape(14.dp))
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
            .background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
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
            onResults(container.messageDao.searchGlobal(needle), true)
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
                        .background(cs.primary.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
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
