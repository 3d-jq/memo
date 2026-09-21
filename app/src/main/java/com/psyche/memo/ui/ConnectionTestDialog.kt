package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

/**
 * 测试连接对话框 —— 1:1 移植 `_ConnectionTestDialog`
 * （provider_detail_page.dart L4200-4516）：选模型（复用全局模型选择 sheet，
 * 只列出本供应商的模型）+「使用流式」开关 + 测试/加载/成功/失败四态。
 *
 * 四态与原版一致：idle 显示模型行与流式开关；loading 显示进度条 + 「正在测试…」；
 * success/failure 只显示模型行 + 结果文案（原版 `_buildResult`）。
 */
private enum class ConnectionTestState { IDLE, LOADING, SUCCESS, ERROR }

@Composable
internal fun ConnectionTestDialog(
    cfg: ProviderConfig,
    container: AppContainerImpl,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val scope = rememberCoroutineScope()

    var selectedModelId by remember(cfg.id) { mutableStateOf(cfg.models.firstOrNull()) }
    var state by remember(cfg.id) { mutableStateOf(ConnectionTestState.IDLE) }
    var errorMessage by remember(cfg.id) { mutableStateOf("") }
    var useStream by remember(cfg.id) { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    val testing = state == ConnectionTestState.LOADING
    val canTest = selectedModelId != null && !testing

    fun runTest() {
        val modelId = selectedModelId ?: return
        state = ConnectionTestState.LOADING
        errorMessage = ""
        scope.launch {
            val (ok, message) = runConnectionCheck(container, cfg, modelId, useStream)
            if (ok) {
                state = ConnectionTestState.SUCCESS
            } else {
                state = ConnectionTestState.ERROR
                errorMessage = message
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        title = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_connection_title),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    ConnectionTestState.IDLE -> {
                        if (selectedModelId == null) {
                            TextButton(onClick = { showPicker = true }) {
                                Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_select_model_button))
                            }
                        } else {
                            ModelChip(
                                modelId = selectedModelId!!,
                                showChange = true,
                                onClick = { showPicker = true },
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_use_streaming_label),
                                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.9f)),
                                )
                                Spacer(Modifier.width(8.dp))
                                IosSwitch(value = useStream, onValueChanged = { useStream = it })
                            }
                        }
                    }

                    ConnectionTestState.LOADING -> {
                        selectedModelId?.let { ModelChip(modelId = it, showChange = false, onClick = {}) }
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_testing_message),
                            style = TextStyle(color = cs.onSurface.copy(alpha = 0.7f), fontSize = 14.sp),
                        )
                    }

                    ConnectionTestState.SUCCESS -> ResultBody(
                        modelId = selectedModelId,
                        message = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_success_message),
                        color = semantic.success,
                        onClickModel = { showPicker = true },
                    )

                    ConnectionTestState.ERROR -> ResultBody(
                        modelId = selectedModelId,
                        message = errorMessage,
                        color = cs.error,
                        onClickModel = { showPicker = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::runTest, enabled = canTest) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_button),
                    color = if (canTest) cs.primary else cs.onSurface.copy(alpha = 0.4f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_cancel_button))
            }
        },
    )

    if (showPicker) {
        // `showModelPickerForTest` → showModelSelector(limitProviderKey: 本供应商)。
        ModelSelectSheet(
            container = container,
            // 读库走 IO（原 `remember { loadModelOptions(...) }` 是组合期同步查）。
            options = rememberLoaded(emptyList(), cfg.id, cfg.models) {
                loadModelOptions(container, cfg.id, selectedModelId).filter { it.providerId == cfg.id }
            },
            onSelect = { option ->
                selectedModelId = option.modelId
                state = ConnectionTestState.IDLE
                errorMessage = ""
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 结果态与加载态共用的模型行（原版 `_buildResult` 顶部那颗 24dp 头像行）。 */
@Composable
private fun ResultBody(
    modelId: String?,
    message: String,
    color: androidx.compose.ui.graphics.Color,
    onClickModel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        modelId?.let {
            ModelChip(modelId = it, showChange = true, onClick = onClickModel)
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** 模型行：24dp 品牌头像 + 名称（+ 可换时右侧 chevron），点按重新选模型。 */
@Composable
private fun ModelChip(
    modelId: String,
    showChange: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showChange) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(24.dp)) {
            ProviderAvatarSmall(providerKey = modelId, displayName = modelId, size = 24.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = modelId,
            style = TextStyle(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (showChange) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = cs.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
