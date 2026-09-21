package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Video
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.AssistantGenerationBinding
import com.psyche.memo.data.model.GenerationKind

/**
 * 输入栏「+」面板里的**生成模型选择**面板（自研功能）。
 *
 * 形态照 [WorkspaceSelectorSheet]（同一套 `MemoSheetHandle` + `MemoSheetOptionRow` +
 * 末尾「管理」出口）：一行「不使用」，然后是该类型的全部服务（名字 + 模型/地址），
 * 选中打勾，点一下**绑到当前助手**并收起。
 *
 * **这里只选模型、不生成**（用户 2026-09-17：「点击是选择对应的模型，不是点击使用呀
 * —— 这个生成图片和视频是大模型调用工具来」）。真正出图/出片由模型调
 * `generate_image` / `generate_video` 完成（助手绑了服务才会拿到那两个工具，
 * 见 [com.psyche.memo.provider.generation.GenerationTools.buildDefinitions]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationSelectorSheet(
    container: AppContainerImpl,
    kind: String,
    onDismiss: () -> Unit,
    onOpenManage: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isImage = kind == GenerationKind.IMAGE
    val icon = if (isImage) Lucide.Image else Lucide.Video
    val assistant = container.currentAssistant()
    // 服务列表随仓储版本号刷新（新建/删除/改名后立刻反映）。
    val version by container.generationServices.version.collectAsState()
    val services = remember(version, kind) { container.generationServices.list(kind) }

    val binding = remember(assistant, isImage) { assistant?.generationBinding(kind) }

    fun bind(serviceId: String?) {
        val current = container.currentAssistant() ?: return
        // 与助手编辑页同一条规则（AssistantGenerationBinding.select）：
        // 选中 = enabled + 服务 id；「不使用」 = enabled false 且 id 清空。
        container.assistantStore.update(
            current.withGenerationBinding(
                kind,
                AssistantGenerationBinding.select(current.generationBinding(kind), serviceId),
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
            Text(
                text = stringResource(
                    if (isImage) R.string.generation_services_page_image_title
                    else R.string.generation_services_page_video_title,
                ),
                style = TextStyle(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.generation_selector_hint),
                style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MemoSheetOptionRow(
                    label = stringResource(R.string.workspace_none),
                    selected = binding?.serviceId == null,
                    icon = icon,
                    onClick = {
                        bind(null)
                        onDismiss()
                    },
                )
                services.forEach { service ->
                    MemoSheetOptionRow(
                        label = service.displayName,
                        subtitle = service.model.ifEmpty { service.resolvedBaseUrl },
                        selected = binding?.serviceId == service.id,
                        icon = icon,
                        onClick = {
                            bind(service.id)
                            onDismiss()
                        },
                    )
                }
                MemoSheetOptionRow(
                    label = stringResource(R.string.generation_selector_manage),
                    selected = false,
                    icon = Lucide.Settings,
                    onClick = {
                        onDismiss()
                        onOpenManage()
                    },
                )
            }
            if (services.isEmpty()) {
                Text(
                    text = stringResource(
                        if (isImage) R.string.assistant_generation_no_services
                        else R.string.assistant_generation_no_services_video,
                    ),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.padding(start = 2.dp, top = 10.dp),
                )
            } else if (binding?.serviceId == null) {
                Text(
                    text = stringResource(R.string.assistant_generation_disabled_hint),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.padding(start = 2.dp, top = 10.dp),
                )
            }
        }
    }
}
