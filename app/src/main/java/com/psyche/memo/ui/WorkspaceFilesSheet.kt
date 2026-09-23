package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.workspace.WorkspaceStorageArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区文件预览 sheet —— 「+」面板 →「工作区」里点某个工作区的「查看文件」。
 *
 * 用户 2026-09-15「点击应该显示对应工作区的文件界面 sheet，这样就可以方便用户看到
 * 工作结果」：沙箱跑完的东西（生成的文件、脚本产物）就在工作区里，从对话界面点两下
 * 就能看到，不用先跳进「管理工作区」再翻。
 *
 * 外壳用 Memo 自己的 sheet 风格（[MemoSheetHandle] + 高度上限 + 卡片式文件行），
 * 文件列表复用详情页的 [FilesTab]，所以两处的行样式天然一致。
 *
 * 与详情页的分工：这里只**看**（浏览目录 + 打开文本文件预览），导出 / 分享 / 删除
 * 仍走「管理工作区」的详情页（[FilesTab] 的 `showFileActions = false`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFilesSheet(
    container: AppContainerImpl,
    workspaceId: String,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val repo = remember { container.workspaceRepository }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var area by remember { mutableStateOf(WorkspaceStorageArea.FILES) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<com.psyche.memo.workspace.WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<com.psyche.memo.workspace.WorkspaceFileEntry?>(null) }
    var previewText by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    // 与详情页同一套加载方式：listing 走 IO，错误只记消息（不吞异常）。
    LaunchedEffect(reload, path, area) {
        loading = true
        val outcome = withContext(Dispatchers.IO) {
            runCatching { repo.listFiles(workspaceId, area, path) }
        }
        entries = outcome.getOrDefault(emptyList())
        listError = outcome.exceptionOrNull()?.message
        loading = false
    }

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
        ) {
            // 用户 2026-09-15：「文件这个 sheet 不要 title」——把手下方直接就是文件列表。
            MemoSheetHandle(trailingGap = 8.dp)
            val shown = previewing
            if (shown != null) {
                // 预览**就地换掉列表**，不再套一层 sheet：本 sheet 本身已经叠在
                // WorkspaceSelectorSheet 之上，再套一层 ModalBottomSheet 就是三层窗口，
                // 预览不显示（用户 2026-09-22「md 点开没反应」）。
                FileEditorBody(
                    title = shown.name,
                    initial = previewText,
                    // rootfs 区只读；能编辑的那一份在详情页。
                    editable = false,
                    onDismiss = { previewing = null },
                    onSave = { previewing = null },
                )
                return@Column
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                FilesTab(
                    area = area,
                    path = path,
                    entries = entries,
                    loading = loading,
                    error = listError,
                    onSelectArea = { area = it; path = "" },
                    onGoUp = { path = parentOf(path) },
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> path = entry.path
                            entry.detectFileType() == WorkspaceFileType.TEXT -> {
                                previewing = entry
                                previewText = ""
                                scope.launch {
                                    previewText = withContext(Dispatchers.IO) {
                                        runCatching {
                                            repo.readTextForPreview(workspaceId, area, entry.path)
                                        }.getOrElse { it.message ?: "" }
                                    }
                                }
                            }
                            // 其余（图片、PPT/Word/PDF…）**交给系统打开**：原来这个 when 没有 else，
                            // 于是非文本文件点了完全没反应（用户 2026-09-22 实测：生成的 PPT/Word
                            // 在加号里点不开，而「管理工作区」详情页里同样的文件点得开 —— 详情页
                            // 走的就是这一支）。这里照详情页的做法：先导出到 cacheDir，再用
                            // FileProvider 交给系统（图片走相册/看图应用，PPT/Word 走 Office/WPS）。
                            else -> scope.launch {
                                val outcome = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val dir = java.io.File(context.cacheDir, "workspace").apply { mkdirs() }
                                        val file = java.io.File(dir, entry.name)
                                        file.outputStream().use {
                                            repo.exportFile(workspaceId, area, entry.path, it)
                                        }
                                        file
                                    }
                                }
                                outcome.fold(
                                    onSuccess = { file ->
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        val mime = android.webkit.MimeTypeMap.getSingleton()
                                            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mime)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(android.content.Intent.createChooser(intent, null))
                                        }.onFailure {
                                            com.psyche.memo.ui.snackbar.SnackbarManager.show(
                                                com.psyche.memo.ui.snackbar.AppNotification(
                                                    message = context.getString(R.string.workspace_open_failed),
                                                    type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
                                                ),
                                            )
                                        }
                                    },
                                    onFailure = { error ->
                                        com.psyche.memo.ui.snackbar.SnackbarManager.show(
                                            com.psyche.memo.ui.snackbar.AppNotification(
                                                message = error.message ?: "",
                                                type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    },
                    onDelete = {},
                    onExport = {},
                    onShare = {},
                    // 预览 sheet 不做重动作，菜单整颗不画（不会出现点了没反应的空菜单）。
                    showFileActions = false,
                )
            }
        }
    }
}
