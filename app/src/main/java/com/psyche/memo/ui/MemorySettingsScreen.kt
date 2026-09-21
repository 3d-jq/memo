package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.X
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.runtime.key
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager

/**
 * memory_settings_page.dart 1:1 (mobile branches; the desktop dialog variants
 * of the prompt editor / pickers are unreachable on Android).
 *
 * Settings persist through PreferenceRepository using the original
 * settings_provider.dart storage keys (memory_legacy_mode_v1,
 * memory_prompt_lang_v1, memory_model_v1, …).
 */

internal object MemorySettingsKeys {
    const val MODEL = "memory_model_v1"
    const val MODEL_THINKING = "memory_model_thinking_enabled_v1"
    const val PROMPT_LANG = "memory_prompt_lang_v1"
    const val RULES_ZH = "memory_rules_prompt_zh_v1"
    const val RULES_EN = "memory_rules_prompt_en_v1"
    const val GATE_ZH = "memory_gate_prompt_zh_v1"
    const val GATE_EN = "memory_gate_prompt_en_v1"
    const val EXTRACT_ZH = "memory_extract_prompt_zh_v1"
    const val EXTRACT_EN = "memory_extract_prompt_en_v1"
    const val SMART_ADD_ZH = "memory_smart_add_prompt_zh_v1"
    const val SMART_ADD_EN = "memory_smart_add_prompt_en_v1"
    const val SMART_ADD_BATCH_ZH = "memory_smart_add_batch_prompt_zh_v1"
    const val SMART_ADD_BATCH_EN = "memory_smart_add_batch_prompt_en_v1"
    const val PROFILE_DISTILL_ZH = "memory_profile_distill_prompt_zh_v1"
    const val PROFILE_DISTILL_EN = "memory_profile_distill_prompt_en_v1"
    const val INJECTION_MAX_ITEMS = "memory_injection_max_items_v1"
}

/** settings_provider.dart defaults + accessors for the memory settings keys. */
internal class MemorySettingsState(private val container: AppContainerImpl) {
    private val prefs get() = container.preferenceRepository

    private fun readString(key: String, fallback: String): String =
        prefs.readJson(key)?.removeSurrounding("\"") ?: fallback

    private fun writeString(key: String, value: String) =
        prefs.writeJson(key, kotlinx.serialization.json.JsonPrimitive(value).toString())

    private fun readBool(key: String, fallback: Boolean): Boolean = prefs.readJson(key)
        ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonPrimitive.booleanOrNull }.getOrNull() }
        ?: fallback

    private fun writeBool(key: String, value: Boolean) =
        prefs.writeJson(key, kotlinx.serialization.json.JsonPrimitive(value).toString())

    fun readInt(key: String, fallback: Int): Int = prefs.readJson(key)
        ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonPrimitive.intOrNull }.getOrNull() }
        ?: fallback

    fun writeInt(key: String, value: Int) =
        prefs.writeJson(key, kotlinx.serialization.json.JsonPrimitive(value).toString())

    var promptLang: String
        get() = readString(MemorySettingsKeys.PROMPT_LANG, "auto")
        set(value) = writeString(MemorySettingsKeys.PROMPT_LANG, value)

    /** `provider::modelId` split like settings_provider.dart L977-984. */
    val memoryModelProvider: String?
        get() = readString(MemorySettingsKeys.MODEL, "").takeIf { it.isNotEmpty() }?.split("::")?.firstOrNull()
    val memoryModelId: String?
        get() = readString(MemorySettingsKeys.MODEL, "").takeIf { it.isNotEmpty() }
            ?.let { it.split("::", limit = 2).getOrNull(1) }

    val modelSet: Boolean get() = memoryModelProvider != null && memoryModelId != null

    fun setMemoryModel(providerKey: String, modelId: String) =
        writeString(MemorySettingsKeys.MODEL, "$providerKey::$modelId")

    var thinkingEnabled: Boolean
        get() = readBool(MemorySettingsKeys.MODEL_THINKING, false)
        set(value) = writeBool(MemorySettingsKeys.MODEL_THINKING, value)

    var injectionMaxItems: Int
        get() = readInt(MemorySettingsKeys.INJECTION_MAX_ITEMS, 10).coerceIn(1, 100)
        set(value) = writeInt(MemorySettingsKeys.INJECTION_MAX_ITEMS, value.coerceIn(1, 100))

    /** resolvedMemoryPromptLang (settings_provider.dart L4222-4232): auto → zh for zh interface. */
    fun resolvedPromptLang(): MemoryPromptLang {
        val stored = promptLang
        if (stored == "zh") return MemoryPromptLang.zh
        if (stored == "en") return MemoryPromptLang.en
        val locale = java.util.Locale.getDefault()
        return if (locale.language == "zh") MemoryPromptLang.zh else MemoryPromptLang.en
    }

    // Prompt get/set/reset — keys match settings_provider.dart L146-171.

    fun prompt(main: MemoryPromptKind, zh: Boolean): String = when (main) {
        MemoryPromptKind.RULES -> readString(if (zh) MemorySettingsKeys.RULES_ZH else MemorySettingsKeys.RULES_EN, if (zh) MemoryPrompts.rulesZh else MemoryPrompts.rulesEn)
        MemoryPromptKind.GATE -> readString(if (zh) MemorySettingsKeys.GATE_ZH else MemorySettingsKeys.GATE_EN, if (zh) MemoryPrompts.gateZh else MemoryPrompts.gateEn)
        MemoryPromptKind.EXTRACT -> readString(if (zh) MemorySettingsKeys.EXTRACT_ZH else MemorySettingsKeys.EXTRACT_EN, if (zh) MemoryPrompts.extractZh else MemoryPrompts.extractEn)
        MemoryPromptKind.SMART_ADD -> readString(if (zh) MemorySettingsKeys.SMART_ADD_ZH else MemorySettingsKeys.SMART_ADD_EN, if (zh) MemoryPrompts.smartAddZh else MemoryPrompts.smartAddEn)
        MemoryPromptKind.DISTILL -> readString(if (zh) MemorySettingsKeys.PROFILE_DISTILL_ZH else MemorySettingsKeys.PROFILE_DISTILL_EN, if (zh) MemoryPrompts.profileDistillZh else MemoryPrompts.profileDistillEn)
    }

    fun smartAddBatchPrompt(zh: Boolean): String = readString(
        if (zh) MemorySettingsKeys.SMART_ADD_BATCH_ZH else MemorySettingsKeys.SMART_ADD_BATCH_EN,
        if (zh) MemoryPrompts.smartAddBatchZh else MemoryPrompts.smartAddBatchEn,
    )

    fun setPrompt(main: MemoryPromptKind, zh: Boolean, text: String) {
        val key = when (main) {
            MemoryPromptKind.RULES -> if (zh) MemorySettingsKeys.RULES_ZH else MemorySettingsKeys.RULES_EN
            MemoryPromptKind.GATE -> if (zh) MemorySettingsKeys.GATE_ZH else MemorySettingsKeys.GATE_EN
            MemoryPromptKind.EXTRACT -> if (zh) MemorySettingsKeys.EXTRACT_ZH else MemorySettingsKeys.EXTRACT_EN
            MemoryPromptKind.SMART_ADD -> if (zh) MemorySettingsKeys.SMART_ADD_ZH else MemorySettingsKeys.SMART_ADD_EN
            MemoryPromptKind.DISTILL -> if (zh) MemorySettingsKeys.PROFILE_DISTILL_ZH else MemorySettingsKeys.PROFILE_DISTILL_EN
        }
        writeString(key, text)
    }

    fun setSmartAddBatchPrompt(zh: Boolean, text: String) =
        writeString(if (zh) MemorySettingsKeys.SMART_ADD_BATCH_ZH else MemorySettingsKeys.SMART_ADD_BATCH_EN, text)

    fun resetPrompt(main: MemoryPromptKind, zh: Boolean) {
        val default = when (main) {
            MemoryPromptKind.RULES -> if (zh) MemoryPrompts.rulesZh else MemoryPrompts.rulesEn
            MemoryPromptKind.GATE -> if (zh) MemoryPrompts.gateZh else MemoryPrompts.gateEn
            MemoryPromptKind.EXTRACT -> if (zh) MemoryPrompts.extractZh else MemoryPrompts.extractEn
            MemoryPromptKind.SMART_ADD -> if (zh) MemoryPrompts.smartAddZh else MemoryPrompts.smartAddEn
            MemoryPromptKind.DISTILL -> if (zh) MemoryPrompts.profileDistillZh else MemoryPrompts.profileDistillEn
        }
        setPrompt(main, zh, default)
        if (main == MemoryPromptKind.SMART_ADD) setSmartAddBatchPrompt(zh, if (zh) MemoryPrompts.smartAddBatchZh else MemoryPrompts.smartAddBatchEn)
    }
}

internal enum class MemoryPromptKind { RULES, GATE, EXTRACT, SMART_ADD, DISTILL }

internal data class PromptEntry(val titleRes: Int, val subtitleRes: Int, val kind: MemoryPromptKind)

/** memory_settings_page.dart L447-478 _promptEntries. */
private fun promptEntries() = listOf(
    PromptEntry(UiR.string.memory_prompt_edit_rules_title, UiR.string.memory_prompt_edit_rules_subtitle, MemoryPromptKind.RULES),
    PromptEntry(UiR.string.memory_prompt_edit_gate_title, UiR.string.memory_prompt_edit_gate_subtitle, MemoryPromptKind.GATE),
    PromptEntry(UiR.string.memory_prompt_edit_extract_title, UiR.string.memory_prompt_edit_extract_subtitle, MemoryPromptKind.EXTRACT),
    PromptEntry(UiR.string.memory_prompt_edit_smart_add_title, UiR.string.memory_prompt_edit_smart_add_subtitle, MemoryPromptKind.SMART_ADD),
    PromptEntry(UiR.string.memory_prompt_edit_distill_title, UiR.string.memory_prompt_edit_distill_subtitle, MemoryPromptKind.DISTILL),
)

private val INJECTION_CUSTOM_SENTINEL = -1
private val INJECTION_MAX_ITEM_OPTIONS = listOf(5, 10, 20, 30)

@Composable
fun MemorySettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenMemoryTrace: () -> Unit,
    onOpenMemoryAbout: () -> Unit,
    // L369-387: desktop dialogs only; on Android these push pages.
    onOpenMemoryEntries: () -> Unit,
    onOpenMemoryProfile: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    val state = remember { MemorySettingsState(container) }
    // version bump forces recomposition after each write (provider.notifyListeners).
    var rev by remember { mutableStateOf(0) }
    fun bump() { rev++ }

    var promptEditor by remember { mutableStateOf<PromptEntry?>(null) }
    var injectionPicker by remember { mutableStateOf(false) }
    var injectionCustomInput by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.memory_settings_page_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            key(rev) {
                // The V2 children (L131-234). The legacy-mode branch that used to
                // wrap them was dropped: it only served data written by the old
                // app, which a fresh Memo install never has.
                    if (!state.modelSet) {
                        MemoryInfoBanner(body = stringResource(UiR.string.memory_settings_model_tip))
                        Spacer(Modifier.height(12.dp))
                    }
                    SettingsSectionHeader(
                        title = stringResource(UiR.string.memory_settings_model_section),
                        trailing = {
                            if (state.modelSet) {
                                // _ModelTipInfoIcon L898-925.
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Lucide.BadgeInfo,
                                        contentDescription = stringResource(UiR.string.memory_settings_model_tip),
                                        modifier = Modifier.size(16.dp),
                                        tint = withAlpha(cs.onSurface, 0.45),
                                    )
                                }
                            }
                        },
                    )
                    SectionCard {
                        val providerName = state.memoryModelProvider?.let { container.providerConfig(it)?.name?.trim().takeUnless { n -> n.isNullOrEmpty() } ?: it }
                        val modelLabel = if (!state.modelSet) {
                            stringResource(UiR.string.memory_settings_model_unset)
                        } else {
                            "$providerName / ${state.memoryModelId}"
                        }
                        SettingsNavRowFull(
                            title = stringResource(UiR.string.memory_settings_model_title),
                            subtitle = modelLabel,
                            onTap = { modelSheet = true },
                        )
                        MemorySettingsSwitchRow(
                            title = stringResource(UiR.string.memory_settings_thinking_title),
                            subtitle = stringResource(UiR.string.memory_settings_thinking_subtitle),
                            checked = state.thinkingEnabled,
                            onCheckedChange = { state.thinkingEnabled = it; bump() },
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    SettingsSectionHeader(title = stringResource(UiR.string.memory_settings_injection_section))
                    SectionCard {
                        SettingsNavRowFull(
                            title = stringResource(UiR.string.memory_settings_injection_max_items_title),
                            // 原版：`tip:`（tooltip）而不是 `subtitle:`（memory_settings_page.dart L177）。
                            tip = stringResource(UiR.string.memory_settings_injection_max_items_subtitle),
                            detailText = state.injectionMaxItems.toString(),
                            onTap = { injectionPicker = true },
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    langSection(state) { bump() }
                    Spacer(Modifier.height(18.dp))
                    SettingsSectionHeader(title = stringResource(UiR.string.memory_settings_prompts_section))
                    SectionCard {
                        promptEntries().forEach { entry ->
                            SettingsNavRowFull(
                                title = stringResource(entry.titleRes),
                                subtitle = stringResource(entry.subtitleRes),
                                onTap = { promptEditor = entry },
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    SettingsSectionHeader(title = stringResource(UiR.string.memory_settings_entries_section))
                    SectionCard {
                        SettingsNavRowFull(
                            title = stringResource(UiR.string.memory_settings_entries_title),
                            subtitle = stringResource(UiR.string.memory_settings_entries_subtitle),
                            onTap = onOpenMemoryEntries,
                        )
                        SettingsNavRowFull(
                            title = stringResource(UiR.string.memory_settings_profile_title),
                            subtitle = stringResource(UiR.string.memory_settings_profile_subtitle),
                            onTap = onOpenMemoryProfile,
                        )
                        SettingsNavRowFull(
                            title = stringResource(UiR.string.memory_trace_settings_title),
                            subtitle = stringResource(UiR.string.memory_trace_settings_subtitle),
                            onTap = onOpenMemoryTrace,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    SectionCard {
                        MemoryNavRow(
                            title = stringResource(UiR.string.memory_settings_about_title),
                            tip = stringResource(UiR.string.memory_settings_about_subtitle),
                            onTap = onOpenMemoryAbout,
                        )
                }
            }
        }
    }

    // Prompt editor (L480-500 — mobile push variant).
    promptEditor?.let { entry ->
        MemoryPromptEditOverlay(
            container = container,
            entry = entry,
            onClose = { promptEditor = null },
        )
    }

    // Injection max-items picker (L250-277).
    if (injectionPicker) {
        val selected = state.injectionMaxItems
        val counts = (INJECTION_MAX_ITEM_OPTIONS + selected).distinct().sorted()
        MemoryOptionPickerSheet(
            selected = selected,
            options = counts.map { n ->
                MemoryPickerOption(n, stringResource(UiR.string.memory_settings_injection_max_items_option, n.toString()))
            } + MemoryPickerOption(
                INJECTION_CUSTOM_SENTINEL,
                stringResource(UiR.string.memory_settings_injection_max_items_custom_button),
            ),
            onDismiss = { injectionPicker = false },
            onSelected = { choice ->
                if (choice == INJECTION_CUSTOM_SENTINEL) {
                    injectionCustomInput = true
                } else {
                    state.injectionMaxItems = choice
                    bump()
                }
            },
        )
    }

    // Custom injection count dialog (L279-367 — mobile branch).
    if (injectionCustomInput) {
        var text by remember { mutableStateOf(state.injectionMaxItems.toString()) }
        val cs2 = MaterialTheme.colorScheme
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { injectionCustomInput = false },
            title = { Text(stringResource(UiR.string.memory_settings_injection_max_items_custom_title)) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        label = { Text(stringResource(UiR.string.memory_settings_injection_max_items_custom_label)) },
                        placeholder = { Text(stringResource(UiR.string.memory_settings_injection_max_items_custom_hint)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(UiR.string.memory_settings_injection_max_items_custom_description),
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = withAlpha(cs2.onSurface, 0.62)),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val parsed = text.toIntOrNull()
                    if (parsed == null || parsed < 1 || parsed > 100) {
                        SnackbarManager.show(
                            AppNotification(
                                message = context.getString(UiR.string.memory_settings_injection_max_items_custom_invalid),
                                type = NotificationType.ERROR,
                            ),
                        )
                        return@TextButton
                    }
                    state.injectionMaxItems = parsed
                    bump()
                    injectionCustomInput = false
                }) { Text(stringResource(UiR.string.user_profile_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { injectionCustomInput = false }) {
                    Text(stringResource(UiR.string.home_page_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }

    // Memory model select (L142-157 showModelSelector). provider_rows 整表 + 逐条解
    // JSON 不在组合期做（§5.13）—— sheet 打开时早就在 IO 上备好了。
    if (modelSheet) {
        val options = rememberLoaded(emptyList(), state.memoryModelProvider, state.memoryModelId) {
            loadModelOptions(container, state.memoryModelProvider, state.memoryModelId)
        }
        ModelSelectSheet(
            container = container,
            options = options,
            onSelect = { sel ->
                state.setMemoryModel(sel.providerId, sel.modelId)
                bump()
                modelSheet = false
            },
            onDismiss = { modelSheet = false },
        )
    }
}

/** L92-103 langSection shared by both modes. */
@Composable
private fun langSection(state: MemorySettingsState, bump: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionHeader(title = stringResource(UiR.string.memory_settings_prompt_lang_section))
    SectionCard {
        listOf("auto", "zh", "en").forEach { lang ->
            val title = stringResource(
                when (lang) {
                    "zh" -> UiR.string.memory_settings_prompt_lang_zh
                    "en" -> UiR.string.memory_settings_prompt_lang_en
                    else -> UiR.string.memory_settings_prompt_lang_auto
                },
            )
            val subtitle = stringResource(
                when (lang) {
                    "zh" -> UiR.string.memory_settings_prompt_lang_zh_subtitle
                    "en" -> UiR.string.memory_settings_prompt_lang_en_subtitle
                    else -> UiR.string.memory_settings_prompt_lang_auto_subtitle
                },
            )
            TactileRow(onTap = { state.promptLang = lang; bump() }) { pressed ->
                val bg = if (pressed) withAlpha(cs.onSurface, 0.05) else Color.Transparent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(start = 14.dp, top = 11.dp, end = 12.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)))
                        Spacer(Modifier.height(3.dp))
                        Text(subtitle, style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = withAlpha(cs.onSurface, 0.62)))
                    }
                    Spacer(Modifier.width(12.dp))
                    // AnimatedOpacity check (L1138-1142).
                    androidx.compose.animation.AnimatedVisibility(visible = state.promptLang == lang) {
                        Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
                    }
                }
            }
        }
    }
}

/** _SettingsSection header (L942-961) + optional trailing widget. */
@Composable
private fun SettingsSectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = settingsSectionHeaderColor(cs)),
            // ⓘ 紧跟标题文字（用户 2026-09-12 点名）：标题只占所需宽度，右侧不再顶到边。
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

/** _SettingsRow (L979-1005) — title + subtitle + trailing switch. */
@Composable
private fun MemorySettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)))
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = withAlpha(cs.onSurface, 0.62)))
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(value = checked, onValueChanged = onCheckedChange, semanticLabel = title)
    }
}

/** _NavRow full-width variant (title + subtitle/tip + chevron) with divider spacing handled by SectionCard rows. */
@Composable
private fun SettingsNavRowFull(
    title: String,
    subtitle: String? = null,
    tip: String? = null,
    detailText: String? = null,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    TactileRow(onTap = onTap) { pressed ->
        val bg = if (pressed) withAlpha(cs.onSurface, 0.05) else Color.Transparent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ⓘ 紧跟标题文字（用户 2026-09-12 规则，见 SettingsUi.TipHuggingLabel）。
                    // 原版「每类注入条数」就是 `title + tip + detailText`
                    // （memory_settings_page.dart L176-179），说明走 tooltip 而不是副标题。
                    TipHuggingLabel(
                        label = title,
                        tip = tip,
                        labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                    )
                }
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(subtitle, style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, color = withAlpha(cs.onSurface, 0.62)))
                }
            }
            if (detailText != null) {
                Spacer(Modifier.width(8.dp))
                Text(detailText, style = TextStyle(fontSize = 13.sp, color = withAlpha(cs.onSurface, 0.6)))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = withAlpha(cs.onSurface, 0.35))
        }
    }
}
