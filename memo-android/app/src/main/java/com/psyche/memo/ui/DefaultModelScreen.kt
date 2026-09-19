package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.NotebookTabs
import com.composables.icons.lucide.Package2
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Settings
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.DefaultModelPrefs
import com.psyche.memo.OcrModelCapability
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

private enum class PickerSlot { CURRENT, TITLE, SUMMARY, SUGGESTION, COMPRESS, TRANSLATE, OCR }
private enum class PromptTask { TITLE, SUMMARY, SUGGESTION, COMPRESS, TRANSLATE, OCR }

/**
 * Default Model page — default_model_page.dart 1:1. Seven model cards
 * (chat/title/summary/suggestion/compress/translate/ocr), each with reset /
 * disable / prompt-config actions, opening the shared ModelSelectSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultModelScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    val backTooltip = stringResource(UiR.string.default_model_page_back_tooltip)
    val resetDefaultText = stringResource(UiR.string.default_model_page_reset_default)
    val useCurrentModelText = stringResource(UiR.string.default_model_page_use_current_model)
    val notEnabledText = stringResource(UiR.string.default_model_page_not_enabled)
    val ocrRequiresImageInputText = stringResource(UiR.string.default_model_page_ocr_model_requires_image_input)

    // ------------------------------------------------------------------
    // Preference read/write — NetworkProxyScreen pattern; values stored as
    // JSON text with the exact Flutter keys (settings_provider.dart L99-141).
    // ------------------------------------------------------------------
    fun readStoredString(key: String): String? =
        container.preferenceRepository.readJson(key)?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
        }?.takeIf { it.isNotBlank() }

    fun readStoredBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readJson(key)?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonPrimitive.booleanOrNull }.getOrNull()
        } ?: default

    fun writeString(key: String, value: String) {
        container.preferenceRepository.writeJson(key, JsonPrimitive(value).toString())
    }

    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, value.toString())
    }

    fun removeKey(key: String) {
        container.preferenceRepository.remove(key)
    }

    fun readSelection(key: String) = DefaultModelPrefs.parseModelSelection(readStoredString(key))

    fun persistSelection(key: String, sel: Pair<String, String>?) {
        if (sel == null) removeKey(key)
        else writeString(key, DefaultModelPrefs.encodeModelSelection(sel.first, sel.second))
    }

    // --- model slots (load mirrors settings_provider.dart L845-950) ---
    var currentSel by remember { mutableStateOf(readSelection(DefaultModelPrefs.SELECTED_MODEL_V1)) }

    var titleSel by remember { mutableStateOf(readSelection(DefaultModelPrefs.TITLE_MODEL_V1)) }
    var titleEnabled by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.TITLE_GENERATION_ENABLED_V1, true)) }
    var titleThinking by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.TITLE_GENERATION_THINKING_ENABLED_V1, false)) }
    var titlePrompt by remember { mutableStateOf(readStoredString(DefaultModelPrefs.TITLE_PROMPT_V1) ?: DefaultModelPrefs.DEFAULT_TITLE_PROMPT) }

    var summarySel by remember { mutableStateOf(readSelection(DefaultModelPrefs.SUMMARY_MODEL_V1)) }
    var summaryThinking by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.SUMMARY_GENERATION_THINKING_ENABLED_V1, false)) }
    var summaryPrompt by remember { mutableStateOf(readStoredString(DefaultModelPrefs.SUMMARY_PROMPT_V1) ?: DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT) }

    var suggestionSel by remember { mutableStateOf(readSelection(DefaultModelPrefs.SUGGESTION_MODEL_V1)) }
    // L925: enabled defaults to "a suggestion model was ever set".
    var suggestionEnabled by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.SUGGESTION_GENERATION_ENABLED_V1, suggestionSel != null)) }
    var suggestionThinking by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.SUGGESTION_GENERATION_THINKING_ENABLED_V1, false)) }
    var suggestionPrompt by remember { mutableStateOf(readStoredString(DefaultModelPrefs.SUGGESTION_PROMPT_V1) ?: DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT) }

    var compressSel by remember { mutableStateOf(readSelection(DefaultModelPrefs.COMPRESS_MODEL_V1)) }
    var compressThinking by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.COMPRESS_GENERATION_THINKING_ENABLED_V1, false)) }
    var compressPrompt by remember { mutableStateOf(readStoredString(DefaultModelPrefs.COMPRESS_PROMPT_V1) ?: DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT) }

    var translateSel by remember { mutableStateOf(readSelection(DefaultModelPrefs.TRANSLATE_MODEL_V1)) }
    var translateThinking by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.TRANSLATE_GENERATION_THINKING_ENABLED_V1, false)) }
    var translatePrompt by remember { mutableStateOf(readStoredString(DefaultModelPrefs.TRANSLATE_PROMPT_V1) ?: DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT) }

    var ocrSel by remember { mutableStateOf(readSelection(DefaultModelPrefs.OCR_MODEL_V1)) }
    var ocrThinking by remember { mutableStateOf(readStoredBool(DefaultModelPrefs.OCR_GENERATION_THINKING_ENABLED_V1, false)) }
    var ocrPrompt by remember { mutableStateOf(readStoredString(DefaultModelPrefs.OCR_PROMPT_V1) ?: DefaultModelPrefs.DEFAULT_OCR_PROMPT) }

    // ------------------------------------------------------------------
    // Display resolution — _ModelCard L875-896 + model_display_helper:
    // override name > apiModelId > raw model id; provider cfg name first.
    // ------------------------------------------------------------------
    fun modelDisplayOf(providerKey: String, modelId: String): String {
        val ov = container.providerConfig(providerKey)?.modelOverrides?.get(modelId) as? JsonObject
        if (ov != null) {
            val overrideName = (ov["name"] as? JsonPrimitive)?.content?.trim()
            if (!overrideName.isNullOrEmpty()) return overrideName
            val apiId = ((ov["apiModelId"] ?: ov["api_model_id"]) as? JsonPrimitive)?.content?.trim()
            if (!apiId.isNullOrEmpty()) return apiId
        }
        return modelId
    }

    fun slotDisplayText(sel: Pair<String, String>?, disabledWhenUnset: Boolean): String {
        // usingFallback — _ModelCard L869/899-903.
        if (sel == null) return if (disabledWhenUnset) notEnabledText else useCurrentModelText
        return modelDisplayOf(sel.first, sel.second)
    }

    // ------------------------------------------------------------------
    // Reset actions — settings_provider.dart L3607-3674, L3927+.
    // ------------------------------------------------------------------
    fun resetCurrentModel() { // L3607-3613
        currentSel = null
        removeKey(DefaultModelPrefs.SELECTED_MODEL_V1)
    }

    fun resetTitleModel() { // L3654-3663
        titleSel = null
        titleEnabled = true
        removeKey(DefaultModelPrefs.TITLE_MODEL_V1)
        writeBool(DefaultModelPrefs.TITLE_GENERATION_ENABLED_V1, true)
    }

    fun disableTitleGeneration() { // L3664-3674
        titleSel = null
        titleEnabled = false
        removeKey(DefaultModelPrefs.TITLE_MODEL_V1)
        writeBool(DefaultModelPrefs.TITLE_GENERATION_ENABLED_V1, false)
    }

    fun resetSummaryModel() {
        summarySel = null
        removeKey(DefaultModelPrefs.SUMMARY_MODEL_V1)
    }

    fun resetSuggestionModel() { // suggestion: mirror resetTitleModel
        suggestionSel = null
        suggestionEnabled = true
        removeKey(DefaultModelPrefs.SUGGESTION_MODEL_V1)
        writeBool(DefaultModelPrefs.SUGGESTION_GENERATION_ENABLED_V1, true)
    }

    fun disableSuggestionGeneration() { // L3927+
        suggestionSel = null
        suggestionEnabled = false
        removeKey(DefaultModelPrefs.SUGGESTION_MODEL_V1)
        writeBool(DefaultModelPrefs.SUGGESTION_GENERATION_ENABLED_V1, false)
    }

    fun resetCompressModel() {
        compressSel = null
        removeKey(DefaultModelPrefs.COMPRESS_MODEL_V1)
    }

    fun resetTranslateModel() {
        translateSel = null
        removeKey(DefaultModelPrefs.TRANSLATE_MODEL_V1)
    }

    fun resetOcrModel() {
        ocrSel = null
        removeKey(DefaultModelPrefs.OCR_MODEL_V1)
    }

    // ------------------------------------------------------------------
    // Pick handling — setTitleModel semantics (sets generation enabled =
    // true) plus the OCR image-input validation (default_model_page.dart
    // L222-243).
    // ------------------------------------------------------------------
    fun applyPick(slot: PickerSlot, option: ModelOption) {
        val sel = option.providerId to option.modelId
        when (slot) {
            PickerSlot.CURRENT -> {
                currentSel = sel
                persistSelection(DefaultModelPrefs.SELECTED_MODEL_V1, sel)
            }
            PickerSlot.TITLE -> {
                titleSel = sel
                titleEnabled = true
                persistSelection(DefaultModelPrefs.TITLE_MODEL_V1, sel)
                writeBool(DefaultModelPrefs.TITLE_GENERATION_ENABLED_V1, true)
            }
            PickerSlot.SUMMARY -> {
                summarySel = sel
                persistSelection(DefaultModelPrefs.SUMMARY_MODEL_V1, sel)
            }
            PickerSlot.SUGGESTION -> {
                suggestionSel = sel
                suggestionEnabled = true
                persistSelection(DefaultModelPrefs.SUGGESTION_MODEL_V1, sel)
                writeBool(DefaultModelPrefs.SUGGESTION_GENERATION_ENABLED_V1, true)
            }
            PickerSlot.COMPRESS -> {
                compressSel = sel
                persistSelection(DefaultModelPrefs.COMPRESS_MODEL_V1, sel)
            }
            PickerSlot.TRANSLATE -> {
                translateSel = sel
                persistSelection(DefaultModelPrefs.TRANSLATE_MODEL_V1, sel)
            }
            PickerSlot.OCR -> {
                val ov = container.providerConfig(option.providerId)?.modelOverrides?.get(option.modelId) as? JsonObject
                if (!OcrModelCapability.supportsImageInput(option.modelId, ov)) {
                    SnackbarManager.show(
                        AppNotification(
                            message = ocrRequiresImageInputText,
                            type = NotificationType.ERROR,
                        ),
                    )
                    return
                }
                ocrSel = sel
                persistSelection(DefaultModelPrefs.OCR_MODEL_V1, sel)
            }
        }
    }

    var pickerSlot by remember { mutableStateOf<PickerSlot?>(null) }
    var promptSheet by remember { mutableStateOf<PromptTask?>(null) }
    // 选择器里长按模型 → 模型详情页保存后要重读模型列表（model_select_sheet.dart
    // L1387-1391：`await showModelDetailSheet(...)` 之后 `_loadModelsAsync()`）。
    // 不重读的话，刚改完「输入模式」的模型在列表里的能力胶囊还是旧的 —— 用户就会
    // 以为改了没生效（OCR 那边读的是最新配置，会拒绝一个看起来"已经勾了图片"的模型）。
    var modelOptionsVersion by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
                MemoTopBar(
                    title = stringResource(UiR.string.default_model_page_title),
                    onBack = onBack,
                ) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp)) // actions: SizedBox(width: 12)
                }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // L54-72 — chat model.
            ModelCard(
                icon = Lucide.MessageCircle,
                title = stringResource(UiR.string.default_model_page_chat_model_title),
                subtitle = stringResource(UiR.string.default_model_page_chat_model_subtitle),
                displayText = slotDisplayText(currentSel, disabledWhenUnset = false),
                showReset = currentSel != null,
                resetDescription = resetDefaultText,
                onReset = { resetCurrentModel() },
                onDisable = null,
                onConfig = null,
                onPick = { pickerSlot = PickerSlot.CURRENT },
            )
            CardGap()
            // L74-103 — title model (fallback: current; disabled when title
            // generation off; reset tooltip "use current model" when unset).
            ModelCard(
                icon = Lucide.NotebookTabs,
                title = stringResource(UiR.string.default_model_page_title_model_title),
                subtitle = stringResource(UiR.string.default_model_page_title_model_subtitle),
                displayText = slotDisplayText(titleSel, disabledWhenUnset = !titleEnabled),
                showReset = titleSel != null || !titleEnabled,
                resetDescription = useCurrentModelText,
                onReset = { resetTitleModel() },
                onDisable = if (titleEnabled) {
                    { disableTitleGeneration() }
                } else {
                    null
                },
                onConfig = { promptSheet = PromptTask.TITLE },
                onPick = { pickerSlot = PickerSlot.TITLE },
            )
            CardGap()
            // L105-127 — summary model (fallback: title ?: current).
            ModelCard(
                icon = Lucide.FileText,
                title = stringResource(UiR.string.default_model_page_summary_model_title),
                subtitle = stringResource(UiR.string.default_model_page_summary_model_subtitle),
                displayText = slotDisplayText(summarySel, disabledWhenUnset = false),
                showReset = summarySel != null,
                resetDescription = resetDefaultText,
                onReset = { resetSummaryModel() },
                onDisable = null,
                onConfig = { promptSheet = PromptTask.SUMMARY },
                onPick = { pickerSlot = PickerSlot.SUMMARY },
            )
            CardGap()
            // L129-158 — suggestion model (fallback: current; disabled when
            // suggestion generation off).
            ModelCard(
                icon = Lucide.MessagesSquare,
                title = stringResource(UiR.string.default_model_page_suggestion_model_title),
                subtitle = stringResource(UiR.string.default_model_page_suggestion_model_subtitle),
                displayText = slotDisplayText(suggestionSel, disabledWhenUnset = !suggestionEnabled),
                showReset = suggestionSel != null || !suggestionEnabled,
                resetDescription = useCurrentModelText,
                onReset = { resetSuggestionModel() },
                onDisable = if (suggestionEnabled) {
                    { disableSuggestionGeneration() }
                } else {
                    null
                },
                onConfig = { promptSheet = PromptTask.SUGGESTION },
                onPick = { pickerSlot = PickerSlot.SUGGESTION },
            )
            CardGap()
            // L160-187 — compress model (fallback: summary ?: title ?: current).
            ModelCard(
                icon = Lucide.Package2,
                title = stringResource(UiR.string.default_model_page_compress_model_title),
                subtitle = stringResource(UiR.string.default_model_page_compress_model_subtitle),
                displayText = slotDisplayText(compressSel, disabledWhenUnset = false),
                showReset = compressSel != null,
                resetDescription = resetDefaultText,
                onReset = { resetCompressModel() },
                onDisable = null,
                onConfig = { promptSheet = PromptTask.COMPRESS },
                onPick = { pickerSlot = PickerSlot.COMPRESS },
            )
            CardGap()
            // L189-210 — translate model (fallback: current).
            ModelCard(
                icon = Lucide.Languages,
                title = stringResource(UiR.string.default_model_page_translate_model_title),
                subtitle = stringResource(UiR.string.default_model_page_translate_model_subtitle),
                displayText = slotDisplayText(translateSel, disabledWhenUnset = false),
                showReset = translateSel != null,
                resetDescription = resetDefaultText,
                onReset = { resetTranslateModel() },
                onDisable = null,
                onConfig = { promptSheet = PromptTask.TRANSLATE },
                onPick = { pickerSlot = PickerSlot.TRANSLATE },
            )
            CardGap()
            // L212-245 — OCR model (disabled display when unset; pick
            // validates image-input support).
            ModelCard(
                icon = Lucide.Eye,
                title = stringResource(UiR.string.default_model_page_ocr_model_title),
                subtitle = stringResource(UiR.string.default_model_page_ocr_model_subtitle),
                displayText = slotDisplayText(ocrSel, disabledWhenUnset = true),
                showReset = ocrSel != null,
                resetDescription = resetDefaultText,
                onReset = { resetOcrModel() },
                onDisable = null,
                onConfig = { promptSheet = PromptTask.OCR },
                onPick = { pickerSlot = PickerSlot.OCR },
            )
        }
    }

    if (pickerSlot != null) {
        val slot = pickerSlot ?: return
        val selForSlot = when (slot) {
            PickerSlot.CURRENT -> currentSel
            PickerSlot.TITLE -> titleSel
            PickerSlot.SUMMARY -> summarySel
            PickerSlot.SUGGESTION -> suggestionSel
            PickerSlot.COMPRESS -> compressSel
            PickerSlot.TRANSLATE -> translateSel
            PickerSlot.OCR -> ocrSel
        }
        // provider_rows 整表 + 逐条解 JSON 不在组合期做（§5.13）。
        val options = rememberLoaded(emptyList(), slot, selForSlot, modelOptionsVersion) {
            loadModelOptions(container, selForSlot?.first, selForSlot?.second)
        }
        ModelSelectSheet(
            container = container,
            options = options,
            onSelect = { option ->
                pickerSlot = null
                applyPick(slot, option)
            },
            onOptionsInvalidated = { modelOptionsVersion++ },
            onDismiss = { pickerSlot = null },
        )
    }

    when (promptSheet) {
        PromptTask.TITLE -> TaskPromptSheet(
            labelSize = 15, // _showTitlePromptSheet L296 uses 15, others 16.
            maxLines = 6,
            hint = stringResource(UiR.string.default_model_page_title_prompt_hint),
            varsText = stringResource(UiR.string.default_model_page_title_vars, "{content}", "{locale}"),
            varsAfterButtons = false,
            initialPrompt = titlePrompt,
            resetPrompt = DefaultModelPrefs.DEFAULT_TITLE_PROMPT,
            thinkingEnabled = titleThinking,
            onThinkingChange = { v ->
                titleThinking = v
                writeBool(DefaultModelPrefs.TITLE_GENERATION_THINKING_ENABLED_V1, v)
            },
            onReset = { // resetTitlePrompt + resetTitleGenerationThinkingEnabled
                titlePrompt = DefaultModelPrefs.DEFAULT_TITLE_PROMPT
                writeString(DefaultModelPrefs.TITLE_PROMPT_V1, DefaultModelPrefs.DEFAULT_TITLE_PROMPT)
                titleThinking = false
                writeBool(DefaultModelPrefs.TITLE_GENERATION_THINKING_ENABLED_V1, false)
            },
            onSave = { input ->
                val v = DefaultModelPrefs.normalizePrompt(input, DefaultModelPrefs.DEFAULT_TITLE_PROMPT)
                titlePrompt = v
                writeString(DefaultModelPrefs.TITLE_PROMPT_V1, v)
            },
            onDismiss = { promptSheet = null },
        )
        PromptTask.SUMMARY -> TaskPromptSheet(
            labelSize = 16,
            maxLines = 8,
            hint = stringResource(UiR.string.default_model_page_summary_prompt_hint),
            varsText = stringResource(UiR.string.default_model_page_summary_vars, "{previous_summary}", "{user_messages}"),
            varsAfterButtons = true,
            initialPrompt = summaryPrompt,
            resetPrompt = DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT,
            thinkingEnabled = summaryThinking,
            onThinkingChange = { v ->
                summaryThinking = v
                writeBool(DefaultModelPrefs.SUMMARY_GENERATION_THINKING_ENABLED_V1, v)
            },
            onReset = {
                summaryPrompt = DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT
                writeString(DefaultModelPrefs.SUMMARY_PROMPT_V1, DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT)
                summaryThinking = false
                writeBool(DefaultModelPrefs.SUMMARY_GENERATION_THINKING_ENABLED_V1, false)
            },
            onSave = { input ->
                val v = DefaultModelPrefs.normalizePrompt(input, DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT)
                summaryPrompt = v
                writeString(DefaultModelPrefs.SUMMARY_PROMPT_V1, v)
            },
            onDismiss = { promptSheet = null },
        )
        PromptTask.SUGGESTION -> TaskPromptSheet(
            labelSize = 16,
            maxLines = 8,
            hint = stringResource(UiR.string.default_model_page_suggestion_prompt_hint),
            varsText = stringResource(UiR.string.default_model_page_suggestion_vars, "{content}", "{locale}"),
            varsAfterButtons = true,
            initialPrompt = suggestionPrompt,
            resetPrompt = DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT,
            thinkingEnabled = suggestionThinking,
            onThinkingChange = { v ->
                suggestionThinking = v
                writeBool(DefaultModelPrefs.SUGGESTION_GENERATION_THINKING_ENABLED_V1, v)
            },
            onReset = {
                suggestionPrompt = DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT
                writeString(DefaultModelPrefs.SUGGESTION_PROMPT_V1, DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT)
                suggestionThinking = false
                writeBool(DefaultModelPrefs.SUGGESTION_GENERATION_THINKING_ENABLED_V1, false)
            },
            onSave = { input ->
                val v = DefaultModelPrefs.normalizePrompt(input, DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT)
                suggestionPrompt = v
                writeString(DefaultModelPrefs.SUGGESTION_PROMPT_V1, v)
            },
            onDismiss = { promptSheet = null },
        )
        PromptTask.COMPRESS -> TaskPromptSheet(
            labelSize = 16,
            maxLines = 8,
            hint = stringResource(UiR.string.default_model_page_compress_prompt_hint),
            varsText = stringResource(UiR.string.default_model_page_compress_vars, "{content}", "{locale}"),
            varsAfterButtons = true,
            initialPrompt = compressPrompt,
            resetPrompt = DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT,
            thinkingEnabled = compressThinking,
            onThinkingChange = { v ->
                compressThinking = v
                writeBool(DefaultModelPrefs.COMPRESS_GENERATION_THINKING_ENABLED_V1, v)
            },
            onReset = {
                compressPrompt = DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT
                writeString(DefaultModelPrefs.COMPRESS_PROMPT_V1, DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT)
                compressThinking = false
                writeBool(DefaultModelPrefs.COMPRESS_GENERATION_THINKING_ENABLED_V1, false)
            },
            onSave = { input ->
                val v = DefaultModelPrefs.normalizePrompt(input, DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT)
                compressPrompt = v
                writeString(DefaultModelPrefs.COMPRESS_PROMPT_V1, v)
            },
            onDismiss = { promptSheet = null },
        )
        PromptTask.TRANSLATE -> TaskPromptSheet(
            labelSize = 16,
            maxLines = 8,
            hint = stringResource(UiR.string.default_model_page_translate_prompt_hint),
            varsText = stringResource(UiR.string.default_model_page_translate_vars, "{source_text}", "{target_lang}"),
            varsAfterButtons = true,
            initialPrompt = translatePrompt,
            resetPrompt = DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT,
            thinkingEnabled = translateThinking,
            onThinkingChange = { v ->
                translateThinking = v
                writeBool(DefaultModelPrefs.TRANSLATE_GENERATION_THINKING_ENABLED_V1, v)
            },
            onReset = {
                translatePrompt = DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT
                writeString(DefaultModelPrefs.TRANSLATE_PROMPT_V1, DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT)
                translateThinking = false
                writeBool(DefaultModelPrefs.TRANSLATE_GENERATION_THINKING_ENABLED_V1, false)
            },
            onSave = { input ->
                val v = DefaultModelPrefs.normalizePrompt(input, DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT)
                translatePrompt = v
                writeString(DefaultModelPrefs.TRANSLATE_PROMPT_V1, v)
            },
            onDismiss = { promptSheet = null },
        )
        PromptTask.OCR -> TaskPromptSheet( // ocr_prompt_sheet.dart 1:1
            labelSize = 16,
            maxLines = 8,
            hint = stringResource(UiR.string.default_model_page_ocr_prompt_hint),
            varsText = null,
            varsAfterButtons = true,
            initialPrompt = ocrPrompt,
            resetPrompt = DefaultModelPrefs.DEFAULT_OCR_PROMPT,
            thinkingEnabled = ocrThinking,
            onThinkingChange = { v ->
                ocrThinking = v
                writeBool(DefaultModelPrefs.OCR_GENERATION_THINKING_ENABLED_V1, v)
            },
            onReset = {
                ocrPrompt = DefaultModelPrefs.DEFAULT_OCR_PROMPT
                writeString(DefaultModelPrefs.OCR_PROMPT_V1, DefaultModelPrefs.DEFAULT_OCR_PROMPT)
                ocrThinking = false
                writeBool(DefaultModelPrefs.OCR_GENERATION_THINKING_ENABLED_V1, false)
            },
            onSave = { input ->
                val v = DefaultModelPrefs.normalizePrompt(input, DefaultModelPrefs.DEFAULT_OCR_PROMPT)
                ocrPrompt = v
                writeString(DefaultModelPrefs.OCR_PROMPT_V1, v)
            },
            onDismiss = { promptSheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun CardGap() {
    Spacer(Modifier.height(16.dp))
}

/**
 * _ModelCard — default_model_page.dart L828-1023: surfaceCard container r16
 * hairline 0.6 border, padding 14; title row icon 18 + title 15 semibold +
 * trailing actions 20; subtitle 12 α0.7; pick row surfaceFill r12 h12 v10
 * with 24dp brand avatar + 14 semibold display text, press scale 0.98.
 */
@Composable
private fun ModelCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    displayText: String,
    showReset: Boolean,
    resetDescription: String,
    onReset: () -> Unit,
    onDisable: (() -> Unit)?,
    onConfig: (() -> Unit)?,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            if (showReset) {
                CardActionButton(icon = Lucide.RotateCcw, description = resetDescription, onTap = onReset)
            }
            if (onDisable != null) {
                CardActionButton(icon = Lucide.Ban, description = stringResource(UiR.string.default_model_page_disable), onTap = onDisable)
            }
            if (onConfig != null) {
                CardActionButton(icon = Lucide.Settings, description = stringResource(UiR.string.default_model_page_prompt_label), onTap = onConfig)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.height(8.dp))
        TactileRow(modifier = Modifier.fillMaxWidth(), onTap = onPick) { pressed ->
            val bg = semantic.surfaceFill
            val overlayAlpha = if (semantic.isDark) 0.06f else 0.05f
            val pressedBg = cs.onSurface.copy(alpha = overlayAlpha).compositeOver(bg)
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.98f else 1f,
                animationSpec = tween(durationMillis = 110, easing = EaseOutCubic),
                label = "pickScale",
            )
            val color by animateColorAsState(
                targetValue = if (pressed) pressedBg else bg,
                animationSpec = tween(durationMillis = 160, easing = EaseOutCubic),
                label = "pickColor",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scale)
                    .background(color, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardAvatar(name = displayText, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = displayText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            }
        }
    }
}

/** _TactileIconButton — icon 20 with h6/v6 padding in a 32dp touch target. */
@Composable
private fun CardActionButton(icon: ImageVector, description: String, onTap: () -> Unit) {
    IconButton(onClick = onTap, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

/**
 * _BrandAvatar — default_model_page.dart L1025-1078: primary-a circle
 * (0.18 dark / 0.1 light), brand asset at 0.62x (dark mono logos tinted
 * onSurface), else the uppercased first character.
 */
@Composable
private fun CardAvatar(name: String, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val asset = remember(name) { BrandAssets.assetForName(name) }
    Box(
        modifier = Modifier
            .size(size)
            .background(cs.primary.copy(alpha = if (semantic.isDark) 0.18f else 0.1f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (asset != null) {
            val mono = semantic.isDark && BrandAssets.assetNeedsDarkInvert(asset)
            AsyncImage(
                model = asset,
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f),
                colorFilter = if (mono) ColorFilter.tint(cs.onSurface) else null,
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                style = TextStyle(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * Shared prompt sheet for title/summary/suggestion/compress/translate tasks
 * (_showTitlePromptSheet etc.) and the OCR sheet (ocr_prompt_sheet.dart):
 * top-r16 overlaySurface sheet, self-drawn 40x4 handle, thinking switch
 * row, prompt label, outlined textarea (surfaceFill fill, outlineVariant
 * α0.4 border, primary α0.5 focused), reset + save buttons; the variables
 * hint sits before the buttons on the title sheet and after them elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPromptSheet(
    labelSize: Int,
    maxLines: Int,
    hint: String,
    varsText: String?,
    varsAfterButtons: Boolean,
    initialPrompt: String,
    resetPrompt: String,
    thinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf(initialPrompt) }
    val thinkingTitle = stringResource(UiR.string.title_model_thinking_title)
    val promptLabel = stringResource(UiR.string.default_model_page_prompt_label)
    val resetText = stringResource(UiR.string.default_model_page_reset_default)
    val saveText = stringResource(UiR.string.default_model_page_save)

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null, // 原版自绘 40x4 拖柄，禁用 Material 默认 handle
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = semantic.overlaySurface(cs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(14.dp))
            // _ThinkingSwitchRow — 16sp medium α0.92 + IosSwitch, v4 padding.
            TactileRow(modifier = Modifier.fillMaxWidth(), onTap = { onThinkingChange(!thinkingEnabled) }) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = thinkingTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface.copy(alpha = 0.92f)),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    IosSwitch(value = thinkingEnabled, onValueChanged = onThinkingChange)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = promptLabel,
                style = TextStyle(fontSize = labelSize.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                maxLines = maxLines,
                placeholder = {
                    Text(
                        text = hint,
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                    cursorColor = cs.primary,
                    focusedTextColor = cs.onSurface,
                    unfocusedTextColor = cs.onSurface,
                ),
            )
            if (!varsAfterButtons && varsText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = varsText,
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onReset()
                    text = resetPrompt
                }) {
                    Text(text = resetText)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    onSave(text)
                    onDismiss()
                }) {
                    Text(text = saveText)
                }
            }
            if (varsAfterButtons && varsText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = varsText,
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                )
            }
        }
    }
}
