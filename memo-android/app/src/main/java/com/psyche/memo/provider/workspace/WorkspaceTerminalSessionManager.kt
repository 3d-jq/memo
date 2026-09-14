package com.psyche.memo.provider.workspace

import android.util.Log
import com.psyche.memo.AppContainerImpl
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 工作区终端的会话管理 —— 上游 `WorkspaceTerminalSessionManager.kt` 1:1 移植。
 *
 * 会话不随终端页的生命周期销毁：页面只把选中的那个 tab 挂到一个 `TerminalView` 上，
 * 退出页面时每条 shell 与其屏幕缓冲都还在，只有显式关 tab（或 shell 自己退出）才结束。
 */
class WorkspaceTerminalSessionManager(
    private val container: AppContainerImpl,
    private val appScope: CoroutineScope,
) {
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()

    fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    fun ensureSession(root: String) {
        launchCreateTab(root = root, onlyIfEmpty = true)
    }

    fun createTab(root: String) {
        launchCreateTab(root = root, onlyIfEmpty = false)
    }

    fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabIdAfterClose(
                    tabs = state.tabs.map { it.id },
                    selectedTabId = state.selectedTabId,
                    closedTabId = tabId,
                ),
            )
        }

        // 先把会话从可观察状态里摘掉再结束它，免得结束回调把它重新塞回 UI，
        // 而那时选中的 TerminalView 正在被 dispose。
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    /** 工作区的 rootfs 要被替换或整个删除前，先停掉它名下所有会话。 */
    suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // 等 rootfs 准备从它的 IO 段里出来，调用方随后要删/换同一批文件。
        // CancellationException 由 createTab() 有意重抛。
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    private fun launchCreateTab(root: String, onlyIfEmpty: Boolean) {
        if (root in creationJobs) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(root = root, onlyIfEmpty = onlyIfEmpty)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(root: String, onlyIfEmpty: Boolean) = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
            true
        } else {
            try {
                withContext(Dispatchers.IO) {
                    if (!workspaceRootfsReady(container.workspaceManager, root)) {
                        false
                    } else {
                        prepareWorkspaceTerminalSession(container.appContext, root, container.workspaceManager)
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to prepare terminal for workspace $root", error)
                false
            }
        }

        if (!prepared) {
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext
        }

        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(container.appContext) {
            markFinished(root = root, tabId = tabId)
        }
        val session = runCatching {
            createWorkspaceTerminalSession(
                container = container,
                root = root,
                client = client,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (session == null) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }

        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
    }
}

/**
 * 关掉 [closedTabId] 之后该选哪个 tab（上游 `closeTab` 里那三元表达式）：
 * 优先「补上来的同位置那个」，其次「前一个」，都没有就 null（全关完了）。
 * 关掉不是当前选中的 tab 时选中项不变。
 */
internal fun selectedTabIdAfterClose(
    tabs: List<Long>,
    selectedTabId: Long?,
    closedTabId: Long,
): Long? {
    val closedIndex = tabs.indexOf(closedTabId)
    if (closedIndex < 0) return selectedTabId
    if (selectedTabId != closedTabId) return selectedTabId
    val remaining = tabs.filterNot { it == closedTabId }
    return remaining.getOrNull(closedIndex) ?: remaining.getOrNull(closedIndex - 1)
}

data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val finished: Boolean = false,
)

enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
