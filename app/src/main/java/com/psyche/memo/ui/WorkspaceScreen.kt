package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.workspace.WorkspaceEntity
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import com.psyche.memo.workspace.RootfsInstallProgress
import com.psyche.memo.workspace.RootfsInstallStage
import com.psyche.memo.workspace.WorkspaceShellStatus
import kotlinx.coroutines.launch

/**
 * 沙箱工作区管理页 —— 让工作区**真的能用起来**的那一屏：新建 / 安装 Rootfs（带进度）/
 * 绑定到当前助手 / 删除。
 *
 * 上游 RikkaHub 拆成 `WorkspacePage` + `WorkspaceDetailPage`（详情页里还有文件浏览、
 * 文件编辑器、终端页）。这里先做**一条完整可用的最小链路**（建 → 装 → 绑），
 * 因为在那之前沙箱在界面上根本没有入口；文件浏览与终端随后补。
 *
 * 外壳按 Memo 风格：`MemoTopBar` + `SectionCard` + 统一 `ActionSheet`。
 */
@Composable
fun WorkspaceScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val repo = container.workspaceRepository

    var reload by remember { mutableIntStateOf(0) }
    val workspaces = remember(reload) { repo.list() }

    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var installing by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var progress by remember { mutableStateOf<RootfsInstallProgress?>(null) }

    // 进页面先对一次账（目录丢了标 BROKEN、rootfs 没了把 READY 重置回 DISABLED）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repo.checkIntegrity()
        reload++
    }

    val installFailedFmt = stringResource(R.string.workspace_install_failed)

    fun install(workspace: WorkspaceEntity) {
        installing = workspace
        progress = null
        scope.launch {
            val outcome = runCatching {
                repo.installRootfs(workspace.id, defaultRootfsUrl()) { update ->
                    progress = update
                }
            }
            installing = null
            progress = null
            reload++
            outcome.onFailure { error ->
                SnackbarManager.show(
                    AppNotification(
                        message = installFailedFmt.format(error.message ?: ""),
                        type = NotificationType.ERROR,
                    ),
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(R.string.workspace_page_title),
            onBack = onBack,
        ) {
            IconActionButton(
                Lucide.Plus,
                cs.onSurface,
                stringResource(R.string.workspace_new),
            ) { creating = true }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.workspace_empty),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            } else {
                item {
                    SectionCard {
                        workspaces.forEachIndexed { index, workspace ->
                            WorkspaceRow(
                                workspace = workspace,
                                onTap = { onOpenDetail(workspace.id) },
                                onLongPress = { actionsFor = workspace },
                            )
                            if (index != workspaces.lastIndex) DividerRow()
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.workspace_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workspace_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    scope.launch {
                        runCatching { repo.create(name) }
                            .onFailure { error ->
                                SnackbarManager.show(
                                    AppNotification(
                                        message = error.message ?: "",
                                        type = NotificationType.ERROR,
                                    ),
                                )
                            }
                        reload++
                    }
                }) {
                    Text(stringResource(R.string.custom_theme_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
            },
        )
    }

    actionsFor?.let { workspace ->
        ActionSheet(
            onDismiss = { actionsFor = null },
            actions = listOf(
                SheetAction(
                    icon = Lucide.Download,
                    label = stringResource(R.string.workspace_install_rootfs),
                ) {
                    actionsFor = null
                    install(workspace)
                },
                SheetAction(
                    icon = Lucide.Trash2,
                    label = stringResource(R.string.workspace_delete_title),
                    destructive = true,
                ) {
                    actionsFor = null
                    deleteTarget = workspace
                },
            ),
        )
    }

    deleteTarget?.let { workspace ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_title)) },
            text = { Text(stringResource(R.string.workspace_delete_message, workspace.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        // 删工作区之前先停掉它的终端会话（上游 WorkspaceVM.delete 同样先
                        // closeWorkspace）：否则交互式 shell 会在正被删掉的目录上继续跑。
                        container.workspaceTerminalSessions.closeWorkspace(workspace.root)
                        repo.delete(workspace.id)
                        reload++
                    }
                }) {
                    Text(stringResource(R.string.custom_theme_delete), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
            },
        )
    }

    installing?.let { workspace ->
        InstallProgressDialog(workspaceName = workspace.name, progress = progress)
    }
}

/** 一行工作区：图标 + 名字 + 状态徽标；点开操作面板。 */
@Composable
private fun WorkspaceRow(
    workspace: WorkspaceEntity,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.HardDrive,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = cs.primary,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = workspace.name,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(workspace.shellStatus)
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = withAlpha(cs.onSurface, 0.35),
        )
    }
}

/** 状态徽标：未安装（灰）/ 安装中（主题色）/ 就绪（绿）/ 已损坏（红）。 */
@Composable
private fun StatusBadge(status: String) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val (labelRes, color) = when (status) {
        WorkspaceShellStatus.READY.name -> R.string.workspace_status_ready to semantic.success
        WorkspaceShellStatus.INSTALLING.name -> R.string.workspace_status_installing to cs.primary
        WorkspaceShellStatus.BROKEN.name -> R.string.workspace_status_broken to cs.error
        else -> R.string.workspace_status_disabled to cs.onSurfaceVariant
    }
    Text(
        text = stringResource(labelRes),
        style = TextStyle(fontSize = 11.sp, color = color),
    )
}

/** 安装进度：下载看百分比，解压看条目数。 */
@Composable
private fun InstallProgressDialog(
    workspaceName: String,
    progress: RootfsInstallProgress?,
) {
    val cs = MaterialTheme.colorScheme
    val total = progress?.totalBytes ?: 0L
    val fraction = if (total > 0) (progress?.bytesRead ?: 0L).toFloat() / total.toFloat() else null
    AlertDialog(
        // 安装中不给关：关掉不会取消协程，只会让用户以为停了。
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = {},
        title = { Text(stringResource(R.string.workspace_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = workspaceName,
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (progress?.stage) {
                            // 数字/文件名是语言中立的，只有那句状态词走 l10n。
                            RootfsInstallStage.EXTRACTING -> listOfNotNull(
                                stringResource(R.string.workspace_status_installing),
                                progress.currentEntry,
                            ).joinToString(" ")
                            RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_status_ready)
                            else -> listOfNotNull(
                                stringResource(R.string.workspace_status_installing),
                                fraction?.let { "${(it * 100).toInt()}%" },
                            ).joinToString(" ")
                        },
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
            }
        },
        confirmButton = {},
    )
}

/**
 * 默认 Rootfs 源。上游 RikkaHub 写死 arm64；这里按设备 ABI 选 —— 我们的 proot 二进制
 * 同时提供 arm64-v8a 与 x86_64，模拟器上得拿 amd64 的那份。
 */
internal fun defaultRootfsUrl(): String =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
        "ubuntu-base-24.04.3-base-${rootfsArchFor(Build.SUPPORTED_ABIS.firstOrNull())}.tar.gz"

/** 供单测用：ABI → rootfs 架构名。 */
internal fun rootfsArchFor(abi: String?): String =
    if (abi?.startsWith("x86") == true) "amd64" else "arm64"
