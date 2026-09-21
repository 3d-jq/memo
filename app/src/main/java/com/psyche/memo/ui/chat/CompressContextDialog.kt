package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ContextCompactionPrefs
import com.psyche.memo.DefaultModelPrefs
import com.psyche.memo.common.CompressModel
import com.psyche.memo.common.Haptics
import com.psyche.memo.common.SessionCompaction
import com.psyche.memo.ui.IosSheetButton
import com.psyche.memo.ui.IosSwitch
import kotlinx.serialization.json.jsonPrimitive
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * 压缩上下文对话框 —— 改成 opencode 的阈值机制：自动压缩开关、原样保留的最近
 * tokens、预留缓冲 tokens、上下文窗口默认值，加一个「开始压缩」按钮立刻压一次
 * （opencode `/compact` 不看阈值）。旧的「起始/最近/无限制 + 字符数 + 保留 N 条」
 * 选项随机制一起删掉。
 */
@Composable
fun CompressContextDialog(
    container: AppContainerImpl,
    messages: List<Pair<String, String>>,
    /** 当前会话的聊天模型 —— 压缩阈值的基准取自它的「上下文长度」。 */
    providerId: String,
    modelId: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current

    // 模型 override 里填了「上下文长度」就以它为准（与自动压缩的判定同源）；
    // 没填时用设置里的全局默认值。
    val modelWindow = remember(providerId, modelId) {
        ContextCompactionPrefs.modelContextWindow(container, providerId, modelId)
    }
    val initial = remember(providerId, modelId) {
        ContextCompactionPrefs.read(container, providerId, modelId)
    }
    // 当前对话的估算 tokens（opencode estimate = 字符数 / 4）。
    val usedTokens = remember(messages) {
        SessionCompaction.estimate(
            messages.mapIndexedNotNull { _, (role, text) ->
                if (text.isBlank()) null
                else SessionCompaction.serialize(
                    SessionCompaction.Entry(role, listOf(SessionCompaction.Part.Text(text))),
                )
            }.joinToString("\n\n"),
        )
    }
    var auto by remember { mutableStateOf(initial.auto) }
    var keepText by remember { mutableStateOf(initial.keepTokens.toString()) }
    var bufferText by remember { mutableStateOf(initial.buffer.toString()) }
    var windowText by remember(providerId, modelId) { mutableStateOf(initial.contextWindow.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }

    val keep = keepText.trim().toIntOrNull()
    val buffer = bufferText.trim().toIntOrNull()
    val window = if (modelWindow != null) modelWindow else windowText.trim().toIntOrNull()
    val threshold = if (buffer != null && window != null && window > 0 && buffer > 0) {
        (window - buffer).coerceAtLeast(0)
    } else {
        0
    }

    fun persist() {
        if (keep == null || keep < 0 || buffer == null || buffer <= 0) {
            error = container.appContext.getString(UiR.string.compress_context_invalid_limit)
            return
        }
        if (modelWindow == null && (window == null || window <= 0)) {
            error = container.appContext.getString(UiR.string.compress_context_invalid_limit)
            return
        }
        ContextCompactionPrefs.writeSettings(container, auto = auto, keepTokens = keep, buffer = buffer)
        // 模型自己填了上下文长度时不写全局默认值（写了也不会被用到，徒增困惑）。
        if (modelWindow == null && window != null) ContextCompactionPrefs.writeDefaultWindow(container, window)
        error = null
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = cs.surfaceContainerHigh,
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .heightIn(max = 600.dp)
                    .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 14.dp),
            ) {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Package2, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(UiR.string.compress_context_options_title),
                            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(UiR.string.compress_context_options_desc),
                        style = TextStyle(fontSize = 13.sp, lineHeight = 17.5.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                    )
                    Spacer(Modifier.height(16.dp))
                    // Model picker row
                    Text(
                        text = stringResource(UiR.string.compress_context_model_label),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.85f)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .clickable {
                                Haptics.light(view)
                                showModelSheet = true
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = compressModelDisplay(container) ?: stringResource(UiR.string.compress_context_model_unset),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.92f)),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Lucide.ChevronRight,
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(UiR.string.compress_context_auto_title),
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(UiR.string.compress_context_auto_subtitle),
                                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                            )
                        }
                        IosSwitch(
                            value = auto,
                            onValueChanged = {
                                auto = it
                                error = null
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    NumberField(
                        value = keepText,
                        onValueChange = { keepText = it; error = null },
                        label = stringResource(UiR.string.compress_context_keep_tokens_label),
                    )
                    Spacer(Modifier.height(10.dp))
                    NumberField(
                        value = bufferText,
                        onValueChange = { bufferText = it; error = null },
                        label = stringResource(UiR.string.compress_context_buffer_label),
                    )
                    Spacer(Modifier.height(10.dp))
                    NumberField(
                        value = windowText,
                        onValueChange = { windowText = it; error = null },
                        label = stringResource(UiR.string.compress_context_window_label),
                        enabled = modelWindow == null,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (modelWindow != null) {
                            stringResource(UiR.string.compress_context_window_from_model, modelWindow.toString())
                        } else {
                            stringResource(UiR.string.compress_context_window_description)
                        },
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            UiR.string.compress_context_estimate_line,
                            usedTokens.toString(),
                            threshold.toString(),
                        ),
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                    )
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = TextStyle(fontSize = 12.sp, color = cs.error, fontWeight = FontWeight.Medium))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row {
                    IosSheetButton(
                        label = stringResource(UiR.string.home_page_cancel),
                        onTap = onDismiss,
                        modifier = Modifier.weight(1f),
                        useSurfaceFill = true,
                    )
                    Spacer(Modifier.width(10.dp))
                    IosSheetButton(
                        label = stringResource(UiR.string.compress_context_start_button),
                        filled = true,
                        onTap = {
                            persist()
                            if (error == null) onConfirm()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showModelSheet) {
        // 读库走 IO（原 `remember { loadCompressModelOptions(...) }` 是组合期同步查）。
        val options = com.psyche.memo.ui.rememberLoaded(emptyList(), container) {
            loadCompressModelOptions(container)
        }
        com.psyche.memo.ui.ModelSelectSheet(
            container = container,
            options = options,
            onSelect = { option ->
                showModelSheet = false
                container.preferenceRepository.writeJson(
                    "compress_model_v1",
                    kotlinx.serialization.json.JsonPrimitive(
                        DefaultModelPrefs.encodeModelSelection(option.providerId, option.modelId),
                    ).toString(),
                )
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

/** 一个正整数设置行（保留 tokens / 缓冲 / 上下文窗口）。 */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** _compressModelDisplayName — compress → summary → title → assistant → current. */
private fun compressModelDisplay(container: AppContainerImpl): String? {
    val assistant = container.currentAssistant()
    val model = CompressModel.resolve(
        DefaultModelPrefs.parseModelSelection(readString(container, "compress_model_v1")),
        DefaultModelPrefs.parseModelSelection(readString(container, "summary_model_v1")),
        DefaultModelPrefs.parseModelSelection(readString(container, "title_model_v1")),
        assistant?.chatModelProvider?.let { p -> assistant.chatModelId?.let { m -> p to m } },
        readString(container, "selected_model_v1")?.let { DefaultModelPrefs.parseModelSelection(it) },
    ) ?: return null
    val config = container.providerConfig(model.first)
    val providerName = config?.name?.takeIf { it.isNotEmpty() } ?: model.first
    return "${model.second} ($providerName)"
}

private fun readString(container: AppContainerImpl, key: String): String? {
    val raw = container.preferenceRepository.readJson(key) ?: return null
    val value = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.content
    }.getOrDefault(raw)
    return value.takeIf { it.isNotBlank() }
}

/** loadModelOptions for the compress picker. */
private fun loadCompressModelOptions(container: AppContainerImpl): List<com.psyche.memo.ui.ModelOption> {
    val selected = DefaultModelPrefs.parseModelSelection(readString(container, "compress_model_v1"))
    return com.psyche.memo.ui.loadModelOptions(container, selected?.first, selected?.second)
}
