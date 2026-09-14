package com.psyche.memo.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CornerLeftUp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.withAlpha
import com.psyche.memo.workspace.WorkspaceFileEntry
import com.psyche.memo.workspace.WorkspaceStorageArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区详情页 —— 浏览器式的文件浏览与编辑。
 *
 * 用户 2026-09-14：「这个创建了工作区 点击 会有一个详细界面呀」，对应上游 RikkaHub 的
 * `WorkspaceDetailPage`（那里还带文件树、编辑器与终端页）。这一版做的是**工作区文件区**
 * （`files/`，也就是沙箱里的 `/workspace`）：
 *
 *  - 面包屑 + 返回上一级；
 *  - 目录点进去、文件点开编辑（等宽字体编辑器，走统一样式 sheet）；
 *  - 长按出操作面板（删除）。
 *
 * **没做的**：rootfs 内部（`linux/` 存储区）的浏览 —— 那是 Linux 根文件系统，几万个文件，
 * 浏览体验完全不同于工作区文件，上游也是单独一套（带忽略规则与补全）。想看 rootfs 里的
 * 东西目前用 `workspace_shell`（例如 `ls /skills`）。见 PORTING §4-46。
 */
@Composable
fun WorkspaceDetailScreen(
    container: AppContainerImpl,
    workspaceId: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val repo = container.workspaceRepository
    val workspace = remember(workspaceId) { repo.get(workspaceId) }

    var path by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var editorText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    // 进目录 / 改完 / 删完都重读（listFiles 会 ensureWorkspace，所以空工作区也能列）。
    LaunchedEffect(reload, path) {
        entries = withContext(Dispatchers.IO) {
            runCatching { repo.listFiles(workspaceId, WorkspaceStorageArea.FILES, path) }
                .getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) { runCatching { repo.touch(workspaceId) } }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = workspace?.name ?: stringResource(R.string.workspace_page_title),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                // 面包屑：工作区名 / a / b —— 点它一律回根，逐级返回用下面的「上一级」。
                Text(
                    text = breadcrumb(workspace?.name.orEmpty(), path),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            item {
                SectionCard {
                    if (path.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { path = parentOf(path) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Lucide.CornerLeftUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = cs.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "..",
                                style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                            )
                        }
                        DividerRow()
                    }

                    if (entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.workspace_empty),
                            style = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                    } else {
                        entries.forEachIndexed { index, entry ->
                            EntryRow(
                                entry = entry,
                                onOpen = {
                                    if (entry.isDirectory) {
                                        path = entry.path
                                    } else {
                                        editing = entry
                                        editorText = ""
                                        scope.launch {
                                            editorText = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    repo.readTextForPreview(
                                                        workspaceId,
                                                        WorkspaceStorageArea.FILES,
                                                        entry.path,
                                                    )
                                                }.getOrElse { it.message ?: "" }
                                            }
                                        }
                                    }
                                },
                                onDelete = { deleteTarget = entry },
                            )
                            if (index != entries.lastIndex) DividerRow()
                        }
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        FileEditorSheet(
            title = entry.name,
            initial = editorText,
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
                            AppNotification(
                                message = error.message ?: "",
                                type = NotificationType.ERROR,
                            ),
                        )
                    }
                    reload++
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_title)) },
            text = { Text(entry.path) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            repo.deleteFile(
                                workspaceId,
                                WorkspaceStorageArea.FILES,
                                entry.path,
                                recursive = entry.isDirectory,
                            )
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

/** 一行：目录（Folder/Chevron）或文件（FileText + 字节数）。长按删。 */
@Composable
private fun EntryRow(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Lucide.Folder else Lucide.FileText,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (entry.isDirectory) cs.tertiary else cs.primary,
        )
        Text(
            text = entry.name,
            style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        if (entry.isDirectory) {
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = withAlpha(cs.onSurface, 0.35),
            )
        } else {
            Text(
                text = "${entry.sizeBytes} B",
                style = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.workspace_delete_title),
                    modifier = Modifier.size(15.dp),
                    tint = withAlpha(cs.onSurface, 0.55),
                )
            }
        }
    }
}

/** 文本编辑器（等宽、统一样式 sheet）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorSheet(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 内容在 sheet 打开后才异步读到，所以 key 里带上 initial：读到就填进去。
    var text by remember(title, initial) { mutableStateOf(initial) }
    ModalBottomSheet(
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
                style = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 10,
                maxLines = 18,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
                TextButton(onClick = { onSave(text) }) {
                    Text(stringResource(R.string.custom_theme_save))
                }
            }
        }
    }
}

/** `""` → 工作区名；`a/b` → `工作区名 / a / b`。 */
internal fun breadcrumb(workspaceName: String, path: String): String =
    if (path.isBlank()) workspaceName else "$workspaceName / " + path.split('/').joinToString(" / ")

/** 上一级路径（根返回空串）。 */
internal fun parentOf(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
