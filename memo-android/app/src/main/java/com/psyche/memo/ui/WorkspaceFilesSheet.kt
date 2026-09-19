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

        previewing?.let { entry ->
            FileEditorSheet(
                title = entry.name,
                initial = previewText,
                // rootfs 区只读；能编辑的那一份在详情页。
                editable = false,
                onDismiss = { previewing = null },
                onSave = { previewing = null },
            )
        }
    }
}
