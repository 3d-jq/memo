package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.psyche.memo.DefaultModelPrefs
import com.psyche.memo.common.CompressText
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.IosSheetButton
import kotlinx.serialization.json.jsonPrimitive
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * Port of home_page.dart _CompressContextOptionsDialog: compress-model picker,
 * the four-part mode segmented control (start / recent / unlimited / keep N),
 * the character or keep-count field and the keep-recent token estimate.
 */
@Composable
fun CompressContextDialog(
    container: AppContainerImpl,
    messages: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (mode: CompressText.Mode, maxChars: Int?, keepUserMessages: Int?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current

    val userMessageCount = remember(messages) { CompressText.countUserMessages(messages) }
    var mode by remember { mutableStateOf(readMode(container)) }
    var maxCharsText by remember { mutableStateOf(readIntPref(container, "compress_max_chars_v1", CompressText.DEFAULT_MAX_CHARS).toString()) }
    var keepCountText by remember {
        mutableStateOf(
            readIntPref(
                container,
                "compress_keep_user_messages_v1",
                CompressText.defaultKeepUserMessageCountFor(userMessageCount),
            ).toString(),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }

    val keepCount = keepCountText.trim().toIntOrNull()
    val keepCoversAll = userMessageCount == 0 || (keepCount ?: 0) >= userMessageCount
    val totalText = remember(messages) { CompressText.buildConversationText(messages) }

    fun persistSelections() {
        container.preferenceRepository.writeJson(
            "compress_limit_mode_v1",
            kotlinx.serialization.json.JsonPrimitive(mode.name.lowercase()).toString(),
        )
        if (mode == CompressText.Mode.KEEP_RECENT) {
            keepCount?.takeIf { it > 0 }?.let {
                container.preferenceRepository.writeJson("compress_keep_user_messages_v1", kotlinx.serialization.json.JsonPrimitive(it).toString())
            }
        } else {
            maxCharsText.trim().toIntOrNull()?.takeIf { it > 0 }?.let {
                container.preferenceRepository.writeJson("compress_max_chars_v1", kotlinx.serialization.json.JsonPrimitive(it).toString())
            }
        }
    }

    fun submit() {
        if (mode == CompressText.Mode.KEEP_RECENT) {
            if (keepCount == null || keepCount <= 0) {
                error = container.appContext.getString(UiR.string.compress_context_invalid_limit)
                return
            }
            if (keepCoversAll) return
            persistSelections()
            onConfirm(mode, null, keepCount)
            return
        }
        if (mode == CompressText.Mode.START || mode == CompressText.Mode.RECENT) {
            val maxChars = maxCharsText.trim().toIntOrNull()
            if (maxChars == null || maxChars <= 0) {
                error = container.appContext.getString(UiR.string.compress_context_invalid_limit)
                return
            }
            persistSelections()
            onConfirm(mode, maxChars, null)
            return
        }
        persistSelections()
        onConfirm(mode, null, null)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = cs.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .heightIn(max = 560.dp)
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
                            .background(semantic.surfaceFill, RoundedCornerShape(12.dp))
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
                    // Mode segmented control
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeSegment(stringResource(UiR.string.compress_context_keep_start), mode == CompressText.Mode.START, true) {
                            mode = CompressText.Mode.START
                            error = null
                        }
                        ModeSegment(stringResource(UiR.string.compress_context_keep_recent), mode == CompressText.Mode.RECENT, true) {
                            mode = CompressText.Mode.RECENT
                            error = null
                        }
                        ModeSegment(stringResource(UiR.string.compress_context_unlimited), mode == CompressText.Mode.UNLIMITED, true) {
                            mode = CompressText.Mode.UNLIMITED
                            error = null
                        }
                        ModeSegment(
                            stringResource(UiR.string.compress_context_keep_recent_messages),
                            mode == CompressText.Mode.KEEP_RECENT,
                            userMessageCount > 1,
                        ) {
                            mode = CompressText.Mode.KEEP_RECENT
                            error = null
                        }
                    }
                    if (mode == CompressText.Mode.START || mode == CompressText.Mode.RECENT) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = maxCharsText,
                            onValueChange = {
                                maxCharsText = it.filter(Char::isDigit)
                                error = null
                            },
                            label = { Text(stringResource(UiR.string.compress_context_max_chars_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (mode == CompressText.Mode.KEEP_RECENT) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = keepCountText,
                            onValueChange = {
                                keepCountText = it.filter(Char::isDigit)
                                error = null
                            },
                            label = { Text(stringResource(UiR.string.compress_context_keep_count_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (keepCoversAll) {
                                stringResource(UiR.string.compress_context_keep_all_messages)
                            } else {
                                val kept = CompressText.buildConversationText(
                                    CompressText.selectKeepRecentMessages(messages, keepCount ?: 0),
                                )
                                val summarized = (totalText.length - kept.length).coerceAtLeast(0)
                                val est = CompressText.estimateCompressionTokens(totalText, kept)
                                stringResource(
                                    UiR.string.compress_context_estimate_preview,
                                    summarized.toString(),
                                    kept.length.toString(),
                                    est.minResultTokens.toString(),
                                    est.maxResultTokens.toString(),
                                    est.totalTokens.toString(),
                                )
                            },
                            style = TextStyle(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = if (keepCoversAll) cs.error else cs.onSurface.copy(alpha = 0.62f),
                            ),
                        )
                    }
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
                        onTap = { submit() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showModelSheet) {
        val options = remember(container) { loadCompressModelOptions(container) }
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

/** _CompressModeSegmented option. */
@Composable
private fun ModeSegment(label: String, selected: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(
                if (selected) cs.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (selected) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    !enabled -> cs.onSurface.copy(alpha = 0.35f)
                    selected -> cs.primary
                    else -> cs.onSurface.copy(alpha = 0.8f)
                },
            ),
        )
    }
}

/** LoadingDialogCard — modal spinner with a label. */
@Composable
fun CompressLoadingDialog() {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(UiR.string.compressing_context),
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                )
            }
        },
    )
}

private fun readMode(container: AppContainerImpl): CompressText.Mode {
    val raw = container.preferenceRepository.readJson("compress_limit_mode_v1")
        ?.removeSurrounding("\"")?.lowercase()
    return CompressText.Mode.entries.firstOrNull { it.name.lowercase() == raw } ?: CompressText.Mode.START
}

private fun readIntPref(container: AppContainerImpl, key: String, default: Int): Int {
    val raw = container.preferenceRepository.readJson(key) ?: return default
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
    }.getOrNull() ?: default
}

/** _compressModelDisplayName — compress → summary → title → assistant → current. */
private fun compressModelDisplay(container: AppContainerImpl): String? {
    val assistant = container.currentAssistant()
    val model = CompressText.resolveCompressModel(
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
