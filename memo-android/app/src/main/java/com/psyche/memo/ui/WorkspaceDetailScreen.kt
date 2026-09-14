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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import com.psyche.memo.workspace.WorkspaceCommandResult
import com.psyche.memo.workspace.WorkspaceFileEntry
import com.psyche.memo.workspace.WorkspaceStorageArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区详情页 —— 照 RikkaHub `WorkspaceDetailPage` + `WorkspaceDetailVM` 的结构做：
 *
 *  - **两个存储区**（`FILES` = 沙箱里的 `/workspace`；`LINUX` = rootfs 本体），切区会
 *    重置路径（上游 `selectArea` 同款）；
 *  - 面包屑 + 上一级 + 目录/文件浏览，文件点开编辑、长按删；
 *  - **终端控制台**：输入命令跑在 rootfs 里，保留「命令 → 输出」历史（上游
 *    `executeTerminalCommand` 的 `WorkspaceTerminalEntry` 三种条目）。
 *
 * 与上游的差距（都记在 PORTING §4-46）：导入/导出文件、每工作区的工具审批覆盖、
 * 以及带 PTY 的交互式终端页（上游是 `WorkspaceTerminalPage` + session manager，
 * 需要 NDK 编 `termux_pty.cpp`）。这个控制台是上游详情页里那个**不需要 NDK** 的版本。
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

    var area by remember { mutableStateOf(WorkspaceStorageArea.FILES) }
    var path by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var editorText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    // 终端控制台状态（上游 WorkspaceTerminalState 的等价物）。
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<TerminalEntry>>(emptyList()) }

    LaunchedEffect(reload, path, area) {
        entries = withContext(Dispatchers.IO) {
            runCatching { repo.listFiles(workspaceId, area, path) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { runCatching { repo.touch(workspaceId) } }

    fun runCommand() {
        val trimmed = command.trim()
        // 上游用 getAndUpdate 原子地「查 running + 置 true」，避免连点跑出两条并发命令。
        if (trimmed.isEmpty() || running) return
        running = true
        command = ""
        history = history + TerminalEntry.Command(trimmed)
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { repo.executeCommand(workspaceId, trimmed) }
            }
            history = history + outcome.fold(
                onSuccess = { TerminalEntry.Output(it) },
                onFailure = { TerminalEntry.Error(it.message ?: "") },
            )
            running = false
        }
    }

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
                AreaToggle(
                    area = area,
                    onSelect = {
                        if (it != area) {
                            area = it
                            path = ""
                        }
                    },
                )
            }

            item {
                Text(
                    text = breadcrumb(workspace?.name.orEmpty(), path),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
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
                            Text("..", style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace))
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
                                                    repo.readTextForPreview(workspaceId, area, entry.path)
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

            item { TerminalSection(history = history, running = running) }

            item {
                CommandInput(
                    command = command,
                    running = running,
                    onCommandChange = { command = it },
                    onRun = { runCommand() },
                    onClear = { history = emptyList() },
                )
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
                        withContext(Dispatchers.IO) { repo.writeText(workspaceId, entry.path, text, overwrite = true) }
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
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_title)) },
            text = { Text(entry.path) },
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

/** 存储区切换（上游 `selectArea`）：两枚药丸，选中走主题色。 */
@Composable
private fun AreaToggle(area: WorkspaceStorageArea, onSelect: (WorkspaceStorageArea) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            WorkspaceStorageArea.FILES to R.string.workspace_area_files,
            WorkspaceStorageArea.LINUX to R.string.workspace_area_linux,
        ).forEach { (value, labelRes) ->
            val selected = value == area
            Text(
                text = stringResource(labelRes),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) cs.onPrimary else cs.onSurface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) cs.primary else LocalSemanticColors.current.surfaceCard,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
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

/** 终端条目（上游 `WorkspaceTerminalEntry`）。 */
internal sealed interface TerminalEntry {
    data class Command(val command: String) : TerminalEntry
    data class Output(val result: WorkspaceCommandResult) : TerminalEntry
    data class Error(val message: String) : TerminalEntry
}

/** 输出历史：命令用 `$` 前缀，输出按 stdout/stderr 分色，非零退出码单独标一行。 */
@Composable
private fun TerminalSection(history: List<TerminalEntry>, running: Boolean) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.workspace_terminal_title),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        if (history.isEmpty() && !running) {
            Text(
                text = stringResource(R.string.workspace_terminal_hint),
                style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                modifier = Modifier.padding(start = 4.dp),
            )
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            history.forEach { entry ->
                when (entry) {
                    is TerminalEntry.Command -> Text(
                        text = "$ ${entry.command}",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = cs.primary,
                        ),
                    )
                    is TerminalEntry.Output -> {
                        val result = entry.result
                        if (result.stdout.isNotBlank()) Text(
                            text = result.stdout.trimEnd(),
                            style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        )
                        if (result.stderr.isNotBlank()) Text(
                            text = result.stderr.trimEnd(),
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = cs.error,
                            ),
                        )
                        if (result.timedOut) Text(
                            text = "timeout",
                            style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = semantic.warning),
                        )
                        if (result.exitCode != 0 && !result.timedOut) Text(
                            text = "exit ${result.exitCode}",
                            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = semantic.warning),
                        )
                    }
                    is TerminalEntry.Error -> Text(
                        text = entry.message,
                        style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = cs.error),
                    )
                }
            }
            if (running) Text(
                text = stringResource(R.string.workspace_terminal_running),
                style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
            )
        }
    }
}

/** 命令输入行 + 运行 + 清空。 */
@Composable
private fun CommandInput(
    command: String,
    running: Boolean,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit,
    onClear: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .background(cs.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$",
                style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = cs.primary),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = command,
                onValueChange = onCommandChange,
                singleLine = false,
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(onClick = onRun, enabled = !running && command.isNotBlank()) {
            Icon(
                Lucide.Play,
                contentDescription = stringResource(R.string.workspace_terminal_title),
                tint = if (running || command.isBlank()) withAlpha(cs.onSurface, 0.3) else cs.primary,
            )
        }
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.workspace_terminal_clear))
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
