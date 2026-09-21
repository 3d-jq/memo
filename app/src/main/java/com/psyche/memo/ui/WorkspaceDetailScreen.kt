package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CornerLeftUp
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.workspace.WorkspaceEntity
import com.psyche.memo.provider.workspace.WorkspaceTools
import com.psyche.memo.ui.chat.ImageViewerOverlay
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import com.psyche.memo.workspace.RootfsInstallProgress
import com.psyche.memo.workspace.RootfsInstallStage
import com.psyche.memo.workspace.WorkspaceFileEntry
import com.psyche.memo.workspace.WorkspaceShellStatus
import com.psyche.memo.workspace.WorkspaceStorageArea
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区详情页 —— 1:1 对齐 RikkaHub `WorkspaceDetailPage` 的结构，外壳用 Memo 既有件：
 *
 *  - 顶栏（`MemoTopBar`）：标题 = 工作区名；动作 =［文件 tab］导入文件 · 刷新 ·［Shell 未禁用］终端；
 *  - 内容两页：「基本」= 工作区信息卡（名称 / Shell 状态）→ 启用 Shell 卡（说明 + 整宽按钮 + 进度）
 *    → 工具审批卡（四项：人类可读名 + 工具名 + 开关），「文件」= 存储区段控 + 路径栏 →
 *    错误卡 / 空目录态 / 每个条目一张卡（两行内容 + 溢出菜单：导出·分享·删除）；
 *  - 底部两个 tab（基本 / 文件），照上游 `NavigationBar`；文件 tab 且路径非空时返回键上跳一级；
 *  - 点文件按扩展名分流：文本 → 应用内编辑 sheet（上游是独立页）、图片 → 全屏查看器、
 *    其它 → 导出到 cache 交给系统应用打开；
 *  - 终端是**独立路由页**（`WorkspaceTerminalScreen`，1:1 上游的 `WorkspaceTerminalPage`）。
 *
 * 见 PORTING §4-46。
 */
@Composable
fun WorkspaceDetailScreen(
    container: AppContainerImpl,
    workspaceId: String,
    onBack: () -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.workspaceRepository
    val version by repo.version.collectAsState()

    val workspace = remember(version, workspaceId) { repo.get(workspaceId) }

    var tab by remember { mutableIntStateOf(0) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<RootfsInstallProgress?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var previewImage by remember { mutableStateOf<String?>(null) }

    // ---- 文件 tab 状态 ----
    var area by remember { mutableStateOf(WorkspaceStorageArea.FILES) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var editorText by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name

    fun refresh() {
        reload++
        scope.launch { repo.checkIntegrity() }
    }

    fun exportToCache(entry: WorkspaceFileEntry, onReady: (File) -> Unit) {
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "workspace").apply { mkdirs() }
                    val file = File(dir, entry.name)
                    file.outputStream().use { repo.exportFile(workspaceId, area, entry.path, it) }
                    file
                }
            }
            outcome.fold(
                onSuccess = onReady,
                onFailure = { error ->
                    SnackbarManager.show(
                        AppNotification(message = error.message ?: "", type = NotificationType.ERROR),
                    )
                },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.displayName(uri)
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(context.getString(R.string.workspace_open_failed))
                    input.use { repo.importFile(workspaceId, area, path, fileName, it) }
                }
            }
            outcome.onFailure { error ->
                SnackbarManager.show(
                    AppNotification(message = error.message ?: "", type = NotificationType.ERROR),
                )
            }
            reload++
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error(context.getString(R.string.workspace_open_failed))
                    out.use { repo.exportFile(workspaceId, area, entry.path, it) }
                }
            }
            outcome.onFailure { error ->
                SnackbarManager.show(
                    AppNotification(message = error.message ?: "", type = NotificationType.ERROR),
                )
            }
        }
    }

    BackHandler(enabled = tab == 1 && path.isNotBlank()) { path = parentOf(path) }

    LaunchedEffect(reload, path, area, version) {
        loading = true
        val outcome = withContext(Dispatchers.IO) {
            runCatching { repo.listFiles(workspaceId, area, path) }
        }
        entries = outcome.getOrDefault(emptyList())
        listError = outcome.exceptionOrNull()?.message
        loading = false
    }
    LaunchedEffect(Unit) { runCatching { repo.touch(workspaceId) } }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = workspace?.name ?: stringResource(R.string.workspace_page_title),
            onBack = onBack,
        ) {
            if (tab == 1) {
                IconActionButton(
                    Lucide.FileUp,
                    cs.onSurface,
                    stringResource(R.string.workspace_import_file),
                ) { importLauncher.launch(arrayOf("*/*")) }
            }
            IconActionButton(Lucide.RefreshCw, cs.onSurface, stringResource(R.string.workspace_refresh)) {
                refresh()
            }
            if (shellStatus != WorkspaceShellStatus.DISABLED.name) {
                IconActionButton(
                    Lucide.Terminal,
                    cs.onSurface,
                    stringResource(R.string.workspace_open_terminal),
                ) { onOpenTerminal(workspaceId) }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (tab == 0) {
                BasicTab(
                    workspace = workspace,
                    container = container,
                    workspaceId = workspaceId,
                    installing = installing,
                    rootfsReady = rootfsReady,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onSetApproval = { tool, value ->
                        scope.launch {
                            withContext(Dispatchers.IO) { repo.setToolApproval(workspaceId, tool, value) }
                        }
                        // 关掉审批开关的那一刻，屏上可能还挂着这个工具**之前**建出来的
                        // 审批面板（用户 2026-09-16「我关闭了确认 为什么还有确认呀」）。
                        // 用户的意图就是「这个工具以后不用问我」→ 把待审批的直接放行，
                        // 别让它继续卡住这一轮生成。
                        if (!value) container.toolApprovalService.approvePendingForTool(tool)
                    },
                )
            } else {
                FilesTab(
                    area = area,
                    path = path,
                    entries = entries,
                    loading = loading,
                    error = listError,
                    onSelectArea = {
                        if (it != area) {
                            area = it
                            path = ""
                        }
                    },
                    onGoUp = { path = parentOf(path) },
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> path = entry.path
                            entry.detectFileType() == WorkspaceFileType.TEXT -> {
                                editing = entry
                                editorText = ""
                                scope.launch {
                                    editorText = withContext(Dispatchers.IO) {
                                        runCatching { repo.readTextForPreview(workspaceId, area, entry.path) }
                                            .getOrElse { it.message ?: "" }
                                    }
                                }
                            }

                            entry.detectFileType() == WorkspaceFileType.IMAGE -> {
                                exportToCache(entry) { file -> previewImage = file.absolutePath }
                            }

                            else -> exportToCache(entry) { file ->
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val mime = MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(Intent.createChooser(intent, null)) }
                                    .onFailure {
                                        SnackbarManager.show(
                                            AppNotification(
                                                message = context.getString(R.string.workspace_open_failed),
                                                type = NotificationType.ERROR,
                                            ),
                                        )
                                    }
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        exportToCache(entry) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, null)) }
                                .onFailure {
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = context.getString(R.string.workspace_open_failed),
                                            type = NotificationType.ERROR,
                                        ),
                                    )
                                }
                        }
                    },
                )
            }
        }

        WorkspaceBottomTabs(tab = tab, onSelect = { tab = it })
    }

    if (showInstallDialog) {
        InstallRootfsDialog(
            workspaceName = workspace?.name.orEmpty(),
            onDismiss = { showInstallDialog = false },
            onConfirm = { url ->
                showInstallDialog = false
                installError = null
                installProgress = RootfsInstallProgress(RootfsInstallStage.DOWNLOADING)
                scope.launch {
                    // 换 rootfs 之前先停掉这个工作区的所有终端会话（上游
                    // WorkspaceDetailVM.installRootfs 同样先 closeWorkspace），
                    // 否则交互式 shell 会在被替换的目录上继续跑。
                    workspace?.root?.let { container.workspaceTerminalSessions.closeWorkspace(it) }
                    val outcome = runCatching {
                        repo.installRootfs(workspaceId, url) { progress -> installProgress = progress }
                    }
                    installProgress = null
                    outcome.onFailure { error -> installError = error.message ?: error.toString() }
                }
            },
        )
    }

    installError?.let { message ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { installError = null },
            title = { Text(stringResource(R.string.workspace_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { installError = null }) {
                    Text(stringResource(R.string.side_drawer_o_k))
                }
            },
        )
    }

    previewImage?.let { image ->
        ImageViewerOverlay(
            images = listOf(image),
            initialIndex = 0,
            onClose = { previewImage = null },
        )
    }

    editing?.let { entry ->
        FileEditorSheet(
            title = entry.name,
            initial = editorText,
            // 上游 `WorkspaceFileEditorPage`：只有 FILES 区可保存，rootfs 区只读预览。
            editable = area == WorkspaceStorageArea.FILES,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            repo.writeText(workspaceId, entry.path, text, overwrite = true)
                        }
                    }.onFailure { error ->
                        SnackbarManager.show(
                            AppNotification(message = error.message ?: "", type = NotificationType.ERROR),
                        )
                    }
                    reload++
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    stringResource(
                        if (entry.isDirectory) R.string.workspace_delete_directory
                        else R.string.workspace_delete_file,
                    ),
                )
            },
            text = { Text(stringResource(R.string.workspace_will_delete, entry.path)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            repo.deleteFile(workspaceId, area, entry.path, recursive = entry.isDirectory)
                        }
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
}

/**
 * 「基本」页 —— 上游 `WorkspaceBasicPage`：三张卡（工作区信息 / 启用 Shell / 工具审批）。
 * 上游把卡片标题放在卡里；Memo 的设置页约定是 `SectionHeader` 在卡上方，这里照 Memo。
 */
@Composable
private fun BasicTab(
    workspace: WorkspaceEntity?,
    installing: Boolean,
    rootfsReady: Boolean,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onSetApproval: (String, Boolean) -> Unit,
    container: AppContainerImpl,
    workspaceId: String,
) {
    val cs = MaterialTheme.colorScheme
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            SectionHeader(stringResource(R.string.workspace_info_title), first = true)
            SectionCard {
                InfoRow(
                    label = stringResource(R.string.workspace_field_name),
                    value = workspace?.name ?: stringResource(R.string.workspace_loading),
                )
                DividerRow()
                InfoRow(
                    label = stringResource(R.string.workspace_field_shell_status),
                    value = workspace?.shellStatus?.toShellStatusLabel() ?: "-",
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.workspace_enable_shell_title))
            SectionCard {
                Text(
                    text = stringResource(R.string.workspace_enable_shell_desc),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
                DividerRow()
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    IosButton(
                        label = when {
                            installing -> stringResource(R.string.workspace_status_installing)
                            rootfsReady -> stringResource(R.string.workspace_reinstall_rootfs)
                            else -> stringResource(R.string.workspace_install_rootfs)
                        },
                        onTap = onInstallRootfs,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Lucide.FolderOpen,
                        filled = true,
                        neutral = false,
                    )
                }
                installProgress?.let { progress ->
                    DividerRow()
                    RootfsProgress(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.workspace_tool_approvals))
            SectionCard {
                Text(
                    text = stringResource(R.string.workspace_tool_approvals_desc),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
                toolApprovalItems().forEach { (toolName, label) ->
                    DividerRow()
                    ToolApprovalRow(
                        toolName = toolName,
                        label = label,
                        needsApproval = WorkspaceTools.resolveApproval(toolName, overrides),
                        enabled = workspace != null,
                        onChange = { onSetApproval(toolName, it) },
                    )
                }
            }
        }
    }
}

/** 上游 `workspaceToolApprovalItems()`：固定四项、固定顺序、人类可读名 + 工具名。 */
@Composable
private fun toolApprovalItems(): List<Pair<String, String>> = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_tool_shell),
)

/** 上游 `WorkspaceToolApprovalCard` 的一行：两行文案（标签 / 工具名）+ 开关。 */
@Composable
private fun ToolApprovalRow(
    toolName: String,
    label: String,
    needsApproval: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = label, style = TextStyle(fontSize = 15.sp))
            Text(
                text = toolName,
                style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IosSwitch(
            value = needsApproval,
            onValueChanged = if (enabled) onChange else null,
            semanticLabel = toolName,
        )
    }
}

/** 上游 `WorkspaceInfoRow`：label 占 0.35、value 占 0.65。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = TextStyle(fontSize = 14.sp, color = cs.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = TextStyle(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 「启用 Shell」卡里的安装进度（上游 `RootfsProgress`）。 */
@Composable
private fun RootfsProgress(progress: RootfsInstallProgress, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(
                        R.string.workspace_downloading,
                        progress.bytesRead.fileSizeToString(),
                        total,
                    )
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(
                        R.string.workspace_extracting,
                        progress.entriesExtracted.toString(),
                        entry,
                    )
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_install_complete)
            },
            style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 上游 `InstallRootfsDialog`：URL 预填默认 rootfs。 */
@Composable
private fun InstallRootfsDialog(
    workspaceName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var url by remember(workspaceName) { mutableStateOf(defaultRootfsUrl()) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_enable_shell_desc),
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurfaceVariant),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim()) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.workspace_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.custom_theme_cancel))
            }
        },
    )
}

/** 「文件」页 —— 上游 `WorkspaceFilesPage`：存储区段控 → 路径栏 → 错误/空态/条目卡。 */
@Composable
internal fun FilesTab(
    area: WorkspaceStorageArea,
    path: String,
    entries: List<WorkspaceFileEntry>,
    loading: Boolean,
    error: String?,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
    /**
     * 文件卡右侧的操作菜单（导出/分享/删除）。工作区预览 sheet 里关掉 —— 那里只是
     * 「快速看工作结果」，重动作仍走「管理工作区」的详情页，避免同一个菜单在两处
     * 各挂一套 SAF / 分享 / 删除确认。
     */
    showFileActions: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { AreaSelector(area = area, onSelect = onSelectArea) }

        item { PathBar(path = path, canGoUp = path.isNotBlank(), onGoUp = onGoUp) }

        error?.let { message ->
            item {
                SectionCard {
                    Text(
                        text = message,
                        style = TextStyle(fontSize = 14.sp, color = cs.error),
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
            }
        }

        if (!loading && entries.isEmpty() && error == null) {
            item { EmptyDirectoryState() }
        }

        items(
            count = entries.size,
            key = { index -> "${area.name}:${entries[index].path}" },
        ) { index ->
            val entry = entries[index]
            WorkspaceFileCard(
                entry = entry,
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
                showActions = showFileActions,
            )
        }
    }
}

/** 存储区段控（上游 `WorkspaceAreaSelector`，整宽两段）。 */
@Composable
private fun AreaSelector(area: WorkspaceStorageArea, onSelect: (WorkspaceStorageArea) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.15f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(3.dp),
    ) {
        listOf(
            WorkspaceStorageArea.FILES to R.string.workspace_area_files,
            WorkspaceStorageArea.LINUX to R.string.workspace_area_linux,
        ).forEach { (value, labelRes) ->
            val selected = value == area
            Text(
                text = stringResource(labelRes),
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) cs.primary else withAlpha(cs.onSurface, 0.6),
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent,
                        RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** 路径栏（上游 `WorkspacePathBar`）：后退按钮 + 当前路径。根目录显示 `/`。 */
@Composable
private fun PathBar(path: String, canGoUp: Boolean, onGoUp: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onGoUp, enabled = canGoUp, modifier = Modifier.size(36.dp)) {
            Icon(
                Lucide.CornerLeftUp,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (canGoUp) cs.primary else withAlpha(cs.onSurface, 0.3),
            )
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一个条目一张卡（上游 `WorkspaceFileCard`）：图标 + 两行（名字 / 路径·大小）+
 * 溢出菜单（导出·分享·删除）。上游用 `DropdownMenu`，Memo 的长按操作面板在这里
 * 不适用（详情页条目要能一次看到三个动作），所以照上游用锚定菜单。
 */
@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    showActions: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(start = 14.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (entry.isDirectory) Lucide.Folder else Lucide.FileText,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (entry.isDirectory) cs.primary else cs.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = entry.name,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (entry.isDirectory) entry.path
                        else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.isDirectory) {
                    Icon(
                        Lucide.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = withAlpha(cs.onSurface, 0.35),
                    )
                }
            }
            if (showActions) Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Lucide.EllipsisVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                        tint = cs.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_export)) },
                            leadingIcon = { Icon(Lucide.FileUp, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_share)) },
                            leadingIcon = { Icon(Lucide.Share2, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.custom_theme_delete), color = cs.error) },
                        leadingIcon = { Icon(Lucide.Trash2, contentDescription = null, tint = cs.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** 空目录态（上游 `EmptyDirectoryState`）：48dp 图标 + 文案，上下留白 48。 */
@Composable
private fun EmptyDirectoryState() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Lucide.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = cs.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_empty_directory),
            style = TextStyle(fontSize = 16.sp, color = cs.onSurfaceVariant),
        )
    }
}

/** 底部两个 tab（上游 `NavigationBar`；视觉照 Memo 供应商详情页的 `BottomTabs`）。 */
@Composable
private fun WorkspaceBottomTabs(tab: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.15f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(4.dp),
    ) {
        listOf(
            Lucide.Settings to R.string.workspace_tab_basic,
            Lucide.FileText to R.string.workspace_area_files,
        ).forEachIndexed { index, (icon, labelRes) ->
            val selected = tab == index
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent,
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) cs.primary else withAlpha(cs.onSurface, 0.6),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(labelRes),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) cs.primary else withAlpha(cs.onSurface, 0.6),
                    ),
                )
            }
        }
    }
}


/** 文本编辑器（等宽、统一样式 sheet）。`editable=false` 时是只读预览（rootfs 区）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileEditorSheet(
    title: String,
    initial: String,
    editable: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 内容在 sheet 打开后才异步读到，所以 key 里带上 initial：读到就填进去。
    var text by remember(title, initial) { mutableStateOf(initial) }
    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 10,
                maxLines = 18,
                readOnly = !editable,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
                if (editable) {
                    TextButton(onClick = { onSave(text) }) {
                        Text(stringResource(R.string.custom_theme_save))
                    }
                }
            }
        }
    }
}

/** 工作区文件按扩展名的粗略分类（1:1 上游 `WorkspaceFileType`）。 */
internal enum class WorkspaceFileType { TEXT, IMAGE, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)

internal fun WorkspaceFileEntry.detectFileType(): WorkspaceFileType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext.isEmpty() -> WorkspaceFileType.OTHER
        ext in IMAGE_EXTENSIONS -> WorkspaceFileType.IMAGE
        ext in TEXT_EXTENSIONS -> WorkspaceFileType.TEXT
        else -> WorkspaceFileType.OTHER
    }
}

/** Shell 状态 → 文案（上游 `toShellStatusLabel`）。 */
@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_status_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_status_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_status_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_status_broken)
    else -> lowercase()
}

/** 1:1 上游 `Long.fileSizeToString()`：1024 进制，按量级选 0/1/2 位小数。 */
internal fun Long.fileSizeToString(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val precision = when {
        value >= 100 -> 0
        value >= 10 -> 1
        else -> 2
    }
    return String.format(Locale.US, "%.${precision}f %s", value, units[unitIndex])
}

/** 上一级路径（根返回空串）。 */
internal fun parentOf(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")

/** SAF 文档的显示名（拿不到就退回最后一段路径）。 */
private fun Context.displayName(uri: Uri): String {
    val fromCursor = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
    return fromCursor ?: uri.lastPathSegment ?: "imported_file"
}
