package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.provider.workspace.WorkspaceTerminalReadiness
import com.psyche.memo.provider.workspace.WorkspaceTerminalTab
import com.psyche.memo.provider.workspace.WorkspaceTerminalTabsState
import com.psyche.memo.provider.workspace.WorkspaceTerminalViewClient
import com.psyche.memo.provider.workspace.writeText
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.flowOf

/**
 * 交互式终端页 —— 上游 `WorkspaceTerminalPage.kt` 1:1 移植（外壳换成 Memo 件）。
 *
 * 与上游一致的地方：会话独立于页面生命周期（`WorkspaceTerminalSessionManager` 是容器级
 * 单例，退出页面 shell 还活着）、多 tab（+ 新建、× 关闭带确认）、附加键栏
 * （ESC/TAB/CTRL/ALT/符号/方向键/HOME/END）、点 URL 用浏览器打开、会话结束显示「进程已退出」。
 *
 * 与上游的两处外壳差异（都记在 PORTING §4-46）：
 *  1. 上游把整页强制深色（`RikkahubTheme(colorMode = DARK)`），Memo 用当前主题的顶栏与
 *     tab 条、只把终端视口做成黑底（终端页在浅色主题下也是「深色视口 + 浅色外壳」）；
 *  2. 上游用 `SecondaryScrollableTabRow`，Memo 用横向滚动的 tab 胶囊（与附加键栏同一套语言）。
 */
@Composable
fun WorkspaceTerminalScreen(
    container: AppContainerImpl,
    workspaceId: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val manager = container.workspaceTerminalSessions
    val version by container.workspaceRepository.version.collectAsState()
    val workspace = remember(version, workspaceId) { container.workspaceRepository.get(workspaceId) }
    val root = workspace?.root

    val stateFlow = remember(root, manager) {
        root?.let(manager::observeWorkspace) ?: flowOf(WorkspaceTerminalTabsState())
    }
    val state by stateFlow.collectAsState(initial = WorkspaceTerminalTabsState())
    var pendingCloseTabId by remember(root) { mutableStateOf<Long?>(null) }

    LaunchedEffect(root) {
        root?.let { manager.ensureSession(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding(),
    ) {
        MemoTopBar(
            title = workspace?.name?.let { stringResource(R.string.workspace_terminal_title_with_name, it) }
                ?: stringResource(R.string.workspace_terminal_title),
            onBack = onBack,
        ) {
            IconActionButton(
                Lucide.Plus,
                if (root != null && !state.isCreating) cs.onSurface else withAlpha(cs.onSurface, 0.3),
                stringResource(R.string.workspace_terminal_new_tab),
            ) {
                root?.let { manager.createTab(it) }
            }
        }

        TerminalContent(
            root = root,
            state = state,
            onSelectTab = { tabId -> root?.let { manager.selectTab(it, tabId) } },
            onCloseTab = { tabId -> pendingCloseTabId = tabId },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    val pendingCloseTab = state.tabs.firstOrNull { it.id == pendingCloseTabId }
    if (pendingCloseTab != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { pendingCloseTabId = null },
            title = {
                Text(stringResource(R.string.workspace_terminal_close_confirm_title, pendingCloseTab.number.toString()))
            },
            text = { Text(stringResource(R.string.workspace_terminal_close_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    root?.let { manager.closeTab(it, pendingCloseTab.id) }
                    pendingCloseTabId = null
                }) {
                    Text(stringResource(R.string.workspace_terminal_close), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseTabId = null }) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
            },
        )
    }
}

@Composable
private fun TerminalContent(
    root: String?,
    state: WorkspaceTerminalTabsState,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    if (root == null || state.tabs.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = when {
                    root == null || state.isCreating || state.readiness == WorkspaceTerminalReadiness.Loading ->
                        stringResource(R.string.workspace_terminal_loading)

                    state.readiness == WorkspaceTerminalReadiness.NotInstalled ->
                        stringResource(R.string.workspace_terminal_not_installed)

                    else -> stringResource(R.string.workspace_terminal_no_tabs)
                },
                style = TextStyle(fontSize = 15.sp, color = cs.onSurfaceVariant),
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    val selectedIndex = state.tabs.indexOfFirst { it.id == state.selectedTabId }.takeIf { it >= 0 } ?: 0
    val selectedTab = state.tabs[selectedIndex]

    Column(modifier = modifier.imePadding()) {
        TerminalTabStrip(
            tabs = state.tabs,
            selectedTabId = selectedTab.id,
            onSelectTab = onSelectTab,
            onCloseTab = onCloseTab,
        )
        TerminalTabContent(tab = selectedTab, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** tab 条：序号 + × 关闭；横向滚动（照上游 `SecondaryScrollableTabRow` 的意图）。 */
@Composable
private fun TerminalTabStrip(
    tabs: List<WorkspaceTerminalTab>,
    selectedTabId: Long,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            Row(
                modifier = Modifier
                    .background(
                        if (selected) cs.primary.copy(alpha = 0.12f) else semantic.surfaceFill,
                        RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                    )
                    .clickable { onSelectTab(tab.id) }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.number.toString(),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) cs.primary else withAlpha(cs.onSurface, 0.7),
                    ),
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { onCloseTab(tab.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(R.string.workspace_terminal_close_tab, tab.number.toString()),
                        modifier = Modifier.size(14.dp),
                        tint = withAlpha(cs.onSurface, 0.5),
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalTabContent(tab: WorkspaceTerminalTab, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    // 终端必须等宽。上游随包带了 JetBrains Mono（`R.font.jetbrains_mono`）；Memo 不额外
    // 塞字体资源，用系统等宽（Droid Sans Mono）—— `Typeface` 无法从 Memo 的可配置
    // 代码字体（Compose `FontFamily`）反解出来，所以这里也读不到用户的代码字体设置。
    val terminalTypeface = remember { Typeface.MONOSPACE }
    var controlDown by remember(tab.id) { mutableStateOf(false) }
    var altDown by remember(tab.id) { mutableStateOf(false) }
    val viewClient = remember(tab.id) { WorkspaceTerminalViewClient(context) }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    DisposableEffect(tab.id, viewClient) {
        onDispose {
            if (tab.client.terminalView === viewClient.terminalView) {
                tab.client.terminalView = null
            }
            viewClient.terminalView = null
        }
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    TerminalView(viewContext, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(terminalTextSizePx)
                        setTypeface(terminalTypeface)
                        setTerminalViewClient(viewClient)
                        attachSession(tab.session)
                        tab.client.terminalView = this
                        viewClient.terminalView = this
                        setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                viewClient.focusAndShowKeyboard()
                            }
                            false
                        }
                        post { viewClient.focusAndShowKeyboard() }
                    }
                },
                update = { terminalView ->
                    terminalView.isFocusable = true
                    terminalView.isFocusableInTouchMode = true
                    terminalView.setTextSize(terminalTextSizePx)
                    terminalView.setTypeface(terminalTypeface)
                    terminalView.setTerminalViewClient(viewClient)
                    tab.client.terminalView = terminalView
                    viewClient.terminalView = terminalView
                    terminalView.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            viewClient.focusAndShowKeyboard()
                        }
                        false
                    }
                    terminalView.attachSession(tab.session)
                    terminalView.onScreenUpdated()
                },
            )
            if (tab.finished) {
                Text(
                    text = stringResource(R.string.workspace_terminal_exited),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)),
                )
            }
        }
        TerminalExtraKeysBar(
            controlDown = controlDown,
            altDown = altDown,
            onControlToggle = { controlDown = !controlDown },
            onAltToggle = { altDown = !altDown },
            onSendText = { tab.session.writeText(it) },
        )
    }
}

/** 附加键栏（上游 `TerminalExtraKeysBar`）：手机键盘上没有的那些键。 */
@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = if (selected) cs.primary else withAlpha(cs.onSurface, 0.12),
                shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        color = if (selected) cs.onPrimary else withAlpha(cs.onSurface, 0.9),
    )
}
