package com.psyche.memo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.workspace.WorkspaceEntity
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import com.psyche.memo.workspace.WorkspaceShellStatus

/**
 * 助手编辑页的「工作区」tab —— **按助手各选各的**沙箱工作区。
 *
 * 用户 2026-09-14：「这个绑定助手有问题吧怎么只能绑定当前助手呀…这个可以在助手界面加一个
 * 工作区 tap 这样就可以在助手里面选择工作区了」——原先只能在「设置→工作区」里把工作区绑到
 * **当前**助手，没法给别的助手各配一个。所以绑定搬到这里，「设置→工作区」那一页只管
 * 新建 / 装 Rootfs / 删除。
 *
 * 「不使用」永远排第一，选它即解绑（`workspaceId`/`workspaceCwd` 一起清）。
 */
@Composable
fun AssistantEditWorkspaceTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var reload by remember { mutableIntStateOf(0) }
    val workspaces = remember(reload) { container.workspaceRepository.list() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item {
            SectionCard {
                WorkspaceChoiceRow(
                    label = stringResource(R.string.workspace_none),
                    status = null,
                    selected = assistant.workspaceId == null,
                    onClick = { onEdit { it.copy(workspaceId = null, workspaceCwd = null) } },
                )
                workspaces.forEach { workspace ->
                    DividerRow()
                    WorkspaceChoiceRow(
                        label = workspace.name,
                        status = workspace.shellStatus,
                        selected = assistant.workspaceId == workspace.id,
                        onClick = { onEdit { it.copy(workspaceId = workspace.id) } },
                    )
                }
            }
        }
        if (workspaces.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.workspace_empty),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp),
                )
            }
        }
    }
}

/** 单选项行：图标 + 名字 +（状态）+ 选中打勾。 */
@Composable
private fun WorkspaceChoiceRow(
    label: String,
    status: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.HardDrive,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) cs.primary else cs.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) cs.primary else cs.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                val (statusRes, color) = when (status) {
                    WorkspaceShellStatus.READY.name -> R.string.workspace_status_ready to semantic.success
                    WorkspaceShellStatus.INSTALLING.name -> R.string.workspace_status_installing to cs.primary
                    WorkspaceShellStatus.BROKEN.name -> R.string.workspace_status_broken to cs.error
                    else -> R.string.workspace_status_disabled to cs.onSurfaceVariant
                }
                Text(
                    text = stringResource(statusRes),
                    style = TextStyle(fontSize = 11.sp, color = color),
                )
            }
        }
        if (selected) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
