package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Video
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.data.model.GenerationTestState
import com.psyche.memo.provider.generation.GenerationServiceTester
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

/**
 * 生成服务列表（自研功能）—— 设置 →「生成图片」/「生成视频」。
 *
 * 形态照 Memo 自己的服务列表页（[SearchServicesScreen] / [WorkspaceScreen]）：
 * `MemoTopBar` + 一张 `SectionCard` 里堆行 + `DividerRow`，长按出 [ActionSheet]
 * （测试连接 / 删除），点行进编辑页。图片与视频共用这一个页面，靠 [kind] 区分 ——
 * 两个入口、两套文案，代码一份。
 */
@Composable
fun GenerationServicesScreen(
    container: AppContainerImpl,
    kind: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val scope = rememberCoroutineScope()
    val repo = container.generationServices
    val version by repo.version.collectAsState()
    val services = remember(version, kind) { repo.list(kind) }
    val isImage = kind == GenerationKind.IMAGE

    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var editing by remember { mutableStateOf<GenerationService?>(null) }
    var adding by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<GenerationService?>(null) }
    var deleteTarget by remember { mutableStateOf<GenerationService?>(null) }

    val deleteMessageFmt = stringResource(R.string.generation_services_delete_message)
    val noModelsMsg = stringResource(R.string.generation_service_editor_test_no_models)

    fun testService(service: GenerationService) {
        testing[service.id] = true
        scope.launch {
            val result = GenerationServiceTester.test(service, container.httpClient)
            testing[service.id] = false
            // 三态落库：可达（没有 /models）不再被写成「连接失败」（见 tester 注释）。
            repo.setTestState(service.id, result.state)
            when (result) {
                is GenerationServiceTester.Result.Ok -> SnackbarManager.show(
                    AppNotification(
                        message = "✓ ${service.displayName}",
                        type = NotificationType.SUCCESS,
                    ),
                )
                is GenerationServiceTester.Result.ReachableWithoutModels -> SnackbarManager.show(
                    AppNotification(message = noModelsMsg, type = NotificationType.INFO),
                )
                is GenerationServiceTester.Result.Failed -> SnackbarManager.show(
                    AppNotification(message = result.message, type = NotificationType.ERROR),
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(
                if (isImage) R.string.generation_services_page_image_title
                else R.string.generation_services_page_video_title,
            ),
            onBack = onBack,
        ) {
            IconActionButton(
                Lucide.Plus,
                cs.onSurface,
                stringResource(R.string.generation_services_page_add),
            ) { adding = true }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (services.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (isImage) R.string.generation_services_page_empty
                            else R.string.generation_services_page_empty_video,
                        ),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            } else {
                item {
                    SectionCard {
                        services.forEachIndexed { index, service ->
                            GenerationServiceRow(
                                service = service,
                                testing = testing[service.id] == true,
                                onTap = { editing = service },
                                onLongPress = { actionsFor = service },
                            )
                            if (index != services.lastIndex) DividerRow()
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        GenerationServiceEditorScreen(
            container = container,
            kind = kind,
            serviceId = null,
            onClose = { adding = false },
        )
    }
    editing?.let { service ->
        GenerationServiceEditorScreen(
            container = container,
            kind = kind,
            serviceId = service.id,
            onClose = { editing = null },
        )
    }

    actionsFor?.let { service ->
        ActionSheet(
            onDismiss = { actionsFor = null },
            actions = listOf(
                SheetAction(
                    icon = Lucide.Activity,
                    label = stringResource(R.string.generation_services_page_test_tooltip),
                ) {
                    actionsFor = null
                    testService(service)
                },
                SheetAction(
                    icon = Lucide.Trash2,
                    label = stringResource(R.string.provider_detail_page_delete_button),
                    destructive = true,
                ) {
                    actionsFor = null
                    deleteTarget = service
                },
            ),
        )
    }

    deleteTarget?.let { service ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.generation_services_delete_title)) },
            text = { Text(deleteMessageFmt.format(service.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = service
                    deleteTarget = null
                    scope.launch { repo.delete(target.id) }
                }) {
                    Text(stringResource(R.string.provider_detail_page_delete_button), color = cs.error)
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

/** 一列：品牌图标槽 + 名称/模型 + 状态胶囊（未测试 / 可用 / 连接失败）。 */
@Composable
private fun GenerationServiceRow(
    service: GenerationService,
    testing: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val statusText: String
    val statusBg: androidx.compose.ui.graphics.Color
    val statusFg: androidx.compose.ui.graphics.Color
    when {
        testing -> {
            statusText = stringResource(R.string.generation_services_page_testing)
            statusBg = cs.primary.copy(alpha = 0.12f)
            statusFg = cs.primary
        }
        service.lastTestState == GenerationTestState.OK -> {
            statusText = stringResource(R.string.generation_services_status_ok)
            statusBg = semantic.success.copy(alpha = 0.12f)
            statusFg = semantic.success
        }
        // 「可达」= 地址/key 通、但该中转没有 /models 接口（生成类服务的常态）。
        // 之前这里被并进「连接失败」，用户 2026-09-17 报的就是这条。
        service.lastTestState == GenerationTestState.REACHABLE -> {
            statusText = stringResource(R.string.generation_services_status_reachable)
            statusBg = cs.primary.copy(alpha = 0.12f)
            statusFg = cs.primary
        }
        service.lastTestState == GenerationTestState.FAILED -> {
            statusText = stringResource(R.string.generation_services_status_failed)
            statusBg = cs.error.copy(alpha = 0.12f)
            statusFg = cs.error
        }
        else -> {
            statusText = stringResource(R.string.generation_services_status_untested)
            statusBg = cs.onSurface.copy(alpha = 0.06f)
            statusFg = cs.onSurface.copy(alpha = 0.7f)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(
                if (service.isImage) Lucide.Image else Lucide.Video,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = service.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
            )
            Text(
                text = service.model.ifEmpty { service.resolvedBaseUrl },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            style = TextStyle(fontSize = 11.sp, color = statusFg),
            maxLines = 1,
            modifier = Modifier
                .background(statusBg, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
