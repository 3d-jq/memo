package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.workspace.WorkspaceEntity

/**
 * 输入栏「+」面板里的工作区选择面板 —— 照 RikkaHub 的 `WorkspacePickerListItem`
 * (`FilesPicker.kt:141`) + `WorkspaceSelectSheet`：一行入口、点开选工作区、附「管理」出口。
 *
 * 用户 2026-09-14「这个输入框加号里面加一个工作区吧 你看看 rikkhub 都有」。
 *
 * 与上游的一处差异：RikkaHub 能同时绑助手与单次会话（`onUpdateAssistant` /
 * `onUpdateConversation`），Memo 的绑定是**每助手一份**（`assistant.workspaceId`，
 * 用户先前已确认的形态），所以这里写的是当前助手。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSelectorSheet(
    container: AppContainerImpl,
    onDismiss: () -> Unit,
    onOpenManage: () -> Unit,
) {
    val assistant = remember { container.currentAssistant() }
    val workspaces = remember { container.workspaceRepository.list() }
    // 点「查看文件」时打开哪个工作区的预览 sheet（null = 不显示）。
    var filesFor by remember { mutableStateOf<WorkspaceEntity?>(null) }

    fun bind(workspaceId: String?) {
        val current = container.currentAssistant() ?: return
        container.assistantStore.update(
            current.copy(
                workspaceId = workspaceId,
                // 解绑时把 cwd 一起清掉，免得留给下一个工作区。
                workspaceCwd = if (workspaceId == null) null else current.workspaceCwd,
            ),
        )
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
            MemoSheetHandle(trailingGap = 0.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MemoSheetOptionRow(
                    label = stringResource(R.string.workspace_none),
                    selected = assistant?.workspaceId == null,
                    icon = Lucide.HardDrive,
                    onClick = {
                        bind(null)
                        onDismiss()
                    },
                )
                workspaces.forEach { workspace ->
                    MemoSheetOptionRow(
                        label = workspace.name,
                        subtitle = workspace.shellStatus.toShellStatusLabel(),
                        selected = assistant?.workspaceId == workspace.id,
                        icon = Lucide.HardDrive,
                        onClick = {
                            bind(workspace.id)
                            onDismiss()
                        },
                        // 行尾「查看文件」：不用先绑成当前助手、也不用跳进管理工作区，
                        // 点一下就能看到这个沙箱跑出来的东西（用户 2026-09-15）。
                        trailing = {
                            IosIconButton(
                                icon = Lucide.FolderOpen,
                                onTap = { filesFor = workspace },
                                size = 20.dp,
                                contentPadding = 6.dp,
                                minSize = 36.dp,
                                semanticLabel = stringResource(R.string.workspace_view_files),
                            )
                        },
                    )
                }
                MemoSheetOptionRow(
                    label = stringResource(R.string.workspace_manage),
                    selected = false,
                    icon = Lucide.Settings,
                    onClick = {
                        onDismiss()
                        onOpenManage()
                    },
                )
            }
        }

        // 文件预览 sheet 叠在本面板之上（同 RikkaHub 的选择面板 → 详情的关系），
        // 关掉它就回到本面板。
        filesFor?.let { workspace ->
            WorkspaceFilesSheet(
                container = container,
                workspaceId = workspace.id,
                onDismiss = { filesFor = null },
            )
        }
    }
}
