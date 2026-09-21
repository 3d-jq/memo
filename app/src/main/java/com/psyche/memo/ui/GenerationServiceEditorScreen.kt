package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.data.model.GenerationTestState
import com.psyche.memo.provider.generation.GenerationServiceTester
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import kotlinx.coroutines.launch

/**
 * 生成服务编辑页（自研功能）—— 新增 / 编辑一个「OpenAI 兼容」的图片或视频生成服务。
 *
 * 视觉照 Memo 自己的编辑页：`MemoTopBar`（返回 + 保存 + 删除）+ `SectionCard` 分区
 * （服务 / 默认参数 / 连接），字段用搜索服务编辑器那套 `OutlinedTextField`
 * （surfaceFill 底 + 聚焦 primary 描边 + 圆角 12）。**没有**照搬 RikkaHub 的
 * 页面结构（用户要求 UI 按我们自己的来）。
 */
@Composable
fun GenerationServiceEditorScreen(
    container: AppContainerImpl,
    kind: String,
    serviceId: String?,
    onClose: (saved: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val repo = container.generationServices
    val isImage = kind == GenerationKind.IMAGE
    val isAdding = serviceId == null

    val initial = remember(serviceId, kind) {
        repo.get(serviceId ?: "") ?: GenerationService(id = "", kind = kind)
    }

    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var size by remember { mutableStateOf(initial.size) }
    var count by remember { mutableIntStateOf(initial.count) }
    var seconds by remember { mutableIntStateOf(initial.durationSeconds) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    // 测试结果三态（[GenerationTestState]）：可达 ≠ 失败（见 tester 注释）。
    var testState by remember { mutableStateOf(initial.lastTestState) }
    var confirmDelete by remember { mutableStateOf(false) }

    val requiredMsg = stringResource(R.string.generation_service_editor_required)
    val savedMsg = stringResource(R.string.generation_service_editor_saved)
    val testOkFmt = stringResource(R.string.generation_service_editor_test_ok)
    val testNoModelsMsg = stringResource(R.string.generation_service_editor_test_no_models)
    val testFailedFmt = stringResource(R.string.generation_service_editor_test_failed)
    val deleteMessageFmt = stringResource(R.string.generation_services_delete_message)

    fun current(): GenerationService = initial.copy(
        kind = kind,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        size = size,
        count = count,
        durationSeconds = seconds,
        // 带上去：新增时也能把刚测出来的结果一起存进来（否则列表一直显示「未测试」）。
        lastTestState = testState,
    )

    fun save() {
        val service = current()
        if (!service.isConfigured()) {
            SnackbarManager.show(
                AppNotification(message = requiredMsg, type = NotificationType.ERROR),
            )
            return
        }
        scope.launch {
            if (isAdding) repo.create(service) else repo.update(service)
            SnackbarManager.show(AppNotification(message = savedMsg, type = NotificationType.SUCCESS))
            onClose(true)
        }
    }

    fun runTest() {
        val service = current()
        if (!service.isConfigured()) {
            SnackbarManager.show(
                AppNotification(message = requiredMsg, type = NotificationType.ERROR),
            )
            return
        }
        testing = true
        testMessage = null
        testState = null
        scope.launch {
            val result = GenerationServiceTester.test(service, container.httpClient)
            testing = false
            // 三态落库：可达（该中转没有 /models）不再被写成「连接失败」。
            testState = result.state
            testMessage = when (result) {
                is GenerationServiceTester.Result.Ok -> testOkFmt.format(result.modelCount)
                is GenerationServiceTester.Result.ReachableWithoutModels -> testNoModelsMsg
                is GenerationServiceTester.Result.Failed -> testFailedFmt.format(result.message)
            }
            if (!isAdding) repo.setTestState(service.id, testState!!)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(
                if (isAdding) R.string.generation_service_editor_add_title
                else R.string.generation_service_editor_edit_title,
            ),
            onBack = { onClose(false) },
        ) {
            if (!isAdding) {
                IconActionButton(
                    Lucide.Trash2,
                    cs.error,
                    stringResource(R.string.generation_services_delete_title),
                ) { confirmDelete = true }
                Spacer(Modifier.width(4.dp))
            }
            IconActionButton(Lucide.Check, cs.onSurface, savedMsg) { save() }
            Spacer(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.generation_service_editor_basic_section), first = true)
            SectionCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    SettingsTextField(
                        label = stringResource(R.string.generation_service_editor_name_label),
                        value = name,
                        onValueChange = { name = it },
                        hint = stringResource(R.string.generation_service_editor_name_hint),
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingsTextField(
                        label = stringResource(R.string.generation_service_editor_base_url_label),
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        hint = GenerationService.DEFAULT_BASE_URL,
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingsTextField(
                        label = stringResource(R.string.generation_service_editor_api_key_label),
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        hint = "sk-…",
                        obscure = !showKey,
                        trailing = {
                            // 明文/密文切换：用 Lucide 的 Eye / EyeOff（项目统一图标），
                            // 别用 emoji（2026-09-16 用户点名）。
                            Icon(
                                if (showKey) Lucide.EyeOff else Lucide.Eye,
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clickable { showKey = !showKey }
                                    .padding(horizontal = 4.dp)
                                    .size(20.dp),
                            )
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingsTextField(
                        label = stringResource(R.string.generation_service_editor_model_label),
                        value = model,
                        onValueChange = { model = it },
                        hint = stringResource(
                            if (isImage) R.string.generation_service_editor_model_hint
                            else R.string.generation_service_editor_model_hint_video,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.generation_service_editor_params_section))
            SectionCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    SettingsTextField(
                        label = stringResource(R.string.generation_service_editor_size_label),
                        value = size,
                        onValueChange = { size = it },
                        hint = stringResource(R.string.generation_service_editor_size_hint),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (isImage) {
                        StepperField(
                            label = stringResource(R.string.generation_service_editor_count_label),
                            value = count.toString(),
                            onMinus = { if (count > GenerationService.MIN_IMAGE_COUNT) count-- },
                            onPlus = { if (count < GenerationService.MAX_IMAGE_COUNT) count++ },
                        )
                    } else {
                        StepperField(
                            label = stringResource(R.string.generation_service_editor_seconds_label),
                            value = if (seconds == 0) "—" else stringResource(
                                R.string.generation_service_editor_seconds_value,
                                seconds.toString(),
                            ),
                            onMinus = { if (seconds > 0) seconds = (seconds - 2).coerceAtLeast(0) },
                            onPlus = { if (seconds < GenerationService.MAX_VIDEO_SECONDS) seconds += 2 },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.generation_service_editor_test_section))
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Activity, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = testMessage ?: stringResource(R.string.generation_services_status_untested),
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = when (testState) {
                                    GenerationTestState.OK -> cs.primary
                                    // 可达 = 中性（接口通，只是没 /models）；只有真失败才红。
                                    GenerationTestState.REACHABLE -> cs.onSurface.copy(alpha = 0.66f)
                                    GenerationTestState.FAILED -> cs.error
                                    else -> cs.onSurface.copy(alpha = 0.66f)
                                },
                            ),
                        )
                    }
                    TextButton(onClick = { if (!testing) runTest() }) {
                        Text(
                            text = stringResource(
                                if (testing) R.string.generation_services_page_testing
                                else R.string.generation_service_editor_test_action,
                            ),
                            color = cs.primary,
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.generation_services_delete_title)) },
            text = { Text(deleteMessageFmt.format(initial.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.delete(initial.id)
                        onClose(true)
                    }
                }) {
                    Text(stringResource(R.string.provider_detail_page_delete_button), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.custom_theme_cancel))
                }
            },
        )
    }
}

/** 数值项：标签 + − 值 +（样式同搜索服务的 StepperRow）。 */
@Composable
private fun StepperField(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            modifier = Modifier.weight(1f),
        )
        StepperIcon(Lucide.Minus, onMinus)
        Spacer(Modifier.width(8.dp))
        Text(value, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.8f)))
        Spacer(Modifier.width(8.dp))
        StepperIcon(Lucide.Plus, onPlus)
    }
}
