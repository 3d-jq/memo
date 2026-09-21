package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.llm.retry.AutoRetryOptions
import com.psyche.memo.ui.R as UiR
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 1:1 port of lib/features/settings/pages/auto_retry_page.dart —
 * AutoRetryOptions JSON ('auto_retry_options') with enabled master switch,
 * maxRetries / initialDelayMs / multiplier / maxDelayMs numeric rows, jitter /
 * retryOnNetworkError switches, and three editable chip sections
 * (status codes / retry keywords / stop keywords) + footer.
 *
 * Defaults mirror auto_retry_options.dart:41-52 — **enabled 例外**：Dart 是
 * `false`，Memo 默认 `true`（用户 2026-09-15 拍板出厂即开，有意偏离，见 PORTING
 * §5.11）。其余照 Dart：3 / 1000 / 2.0 / 30000、jitter on、状态码与两个词表逐字。
 * 三个默认值**直接引用 `AutoRetryOptions.DEFAULT_*`**（别再抄一份 —— 这里曾有自己的
 * 一份拷贝，于是「页面显示着正确的触发词、运行时出厂词表却是空的」没人发现，
 * 就是 2026-09-15「触发条件没做完」那个 bug）。
 * Clamps follow auto_retry_options.dart:119-127: maxRetries 0-10,
 * multiplier (0,100] else 2.0, delays >= 0.
 */
private val defaultRetryStatusCodes: List<String> =
    AutoRetryOptions.DEFAULT_RETRY_STATUS_CODES.map { it.toString() }

private val defaultRetryKeywords: List<String> = AutoRetryOptions.DEFAULT_RETRY_KEYWORDS

private val defaultStopKeywords: List<String> = AutoRetryOptions.DEFAULT_STOP_KEYWORDS

@Composable
fun AutoRetrySettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Committed values — the JSON is always written from these.
    var enabled by remember { mutableStateOf(true) }
    var maxRetries by remember { mutableIntStateOf(3) }
    var initialDelayMs by remember { mutableIntStateOf(1000) }
    var multiplier by remember { mutableDoubleStateOf(2.0) }
    var maxDelayMs by remember { mutableIntStateOf(30000) }
    var jitter by remember { mutableStateOf(true) }
    var retryOnNetworkError by remember { mutableStateOf(true) }
    var retryStatusCodes by remember { mutableStateOf(defaultRetryStatusCodes) }
    var retryKeywords by remember { mutableStateOf(defaultRetryKeywords) }
    var stopKeywords by remember { mutableStateOf(defaultStopKeywords) }

    // Text field drafts (committed on blur / IME done).
    var maxRetriesText by remember { mutableStateOf("3") }
    var initialDelayText by remember { mutableStateOf("1000") }
    var multiplierText by remember { mutableStateOf("2") }
    var maxDelayText by remember { mutableStateOf("30000") }

    LaunchedEffect(Unit) {
        // LaunchedEffect 体默认跑在组合线程上，里面的 readJson 是真会打 SQLite 的
        withContext(Dispatchers.IO) {
            val raw = container.preferenceRepository.readJson("auto_retry_options")
            if (raw.isNullOrEmpty()) return@withContext
            runCatching {
                val parsed = Json.parseToJsonElement(raw)
                val obj = parsed as? kotlinx.serialization.json.JsonObject ?: return@withContext
                fun boolOf(k: String, d: Boolean) = obj[k]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
                } ?: d
                fun intOf(k: String, d: Int) = obj[k]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                } ?: d
                fun dblOf(k: String, d: Double) = obj[k]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                } ?: d
                fun strList(k: String, d: List<String>) = obj[k]?.let { arr ->
                    (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }?.filter { it.isNotEmpty() }
                } ?: d
                fun intList(k: String, d: List<String>) = obj[k]?.let { arr ->
                    (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.toString()
                    }?.distinct() ?: d
                } ?: d
                enabled = boolOf("enabled", true)
                maxRetries = intOf("maxRetries", 3).coerceIn(0, 10)
                initialDelayMs = intOf("initialDelayMs", 1000).coerceAtLeast(0)
                multiplier = clampMultiplier(dblOf("multiplier", 2.0))
                maxDelayMs = intOf("maxDelayMs", 30000).coerceAtLeast(0)
                jitter = boolOf("jitter", true)
                retryOnNetworkError = boolOf("retryOnNetworkError", true)
                retryStatusCodes = intList("retryStatusCodes", defaultRetryStatusCodes).sorted()
                retryKeywords = strList("retryKeywords", defaultRetryKeywords)
                stopKeywords = strList("stopKeywords", defaultStopKeywords)
                maxRetriesText = maxRetries.toString()
                initialDelayText = initialDelayMs.toString()
                multiplierText = formatMultiplier(multiplier)
                maxDelayText = maxDelayMs.toString()
            }
        }
}

    fun save() {
        // buildJsonObject guarantees valid JSON — no string concatenation.
        val json = buildJsonObject {
            put("enabled", enabled)
            put("maxRetries", maxRetries)
            put("initialDelayMs", initialDelayMs)
            put("multiplier", multiplier)
            put("maxDelayMs", maxDelayMs)
            put("jitter", jitter)
            put("retryOnNetworkError", retryOnNetworkError)
            put("retryStatusCodes", buildJsonArray {
                retryStatusCodes.mapNotNull { it.toIntOrNull() }.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            put("retryKeywords", buildJsonArray { retryKeywords.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("stopKeywords", buildJsonArray { stopKeywords.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        container.preferenceRepository.writeJson("auto_retry_options", json.toString())
    }

    // auto_retry_page.dart:104-126 — parse failure falls back to the current
    // stored value, then clamps mirror AutoRetryOptions.
    fun commitMaxRetries(text: String) {
        val parsed = text.trim().toIntOrNull() ?: run { maxRetriesText = maxRetries.toString(); return }
        maxRetries = parsed.coerceIn(0, 10)
        maxRetriesText = maxRetries.toString()
        save()
    }

    fun commitInitialDelay(text: String) {
        val parsed = text.trim().toIntOrNull() ?: run { initialDelayText = initialDelayMs.toString(); return }
        initialDelayMs = parsed.coerceAtLeast(0)
        initialDelayText = initialDelayMs.toString()
        save()
    }

    fun commitMultiplier(text: String) {
        val parsed = text.trim().toDoubleOrNull() ?: run { multiplierText = formatMultiplier(multiplier); return }
        multiplier = clampMultiplier(parsed)
        multiplierText = formatMultiplier(multiplier)
        save()
    }

    fun commitMaxDelay(text: String) {
        val parsed = text.trim().toIntOrNull() ?: run { maxDelayText = maxDelayMs.toString(); return }
        maxDelayMs = parsed.coerceAtLeast(0)
        maxDelayText = maxDelayMs.toString()
        save()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.settings_page_auto_retry),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp,
            ),
        ) {
            // 分类重组（用户要求对齐设置主屏的分组卡片形态）：重试策略 /
            // 触发条件（状态码 / 关键词 / 停止词）。行内容与提交逻辑不动。
            item(key = "h_strategy") {
                SectionHeader(stringResource(UiR.string.auto_retry_section_strategy), first = true)
            }
            item(key = "c_strategy") {
                SettingsSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { enabled = !enabled; save() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(UiR.string.auto_retry_enable_label),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                        IosSwitch(value = enabled, onValueChanged = { enabled = it; save() })
                    }
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_max_retries),
                        value = maxRetriesText,
                        onCommit = ::commitMaxRetries,
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_initial_delay),
                        value = initialDelayText,
                        onCommit = ::commitInitialDelay,
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_multiplier),
                        value = multiplierText,
                        decimal = true,
                        onCommit = ::commitMultiplier,
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_max_delay),
                        value = maxDelayText,
                        onCommit = ::commitMaxDelay,
                    )
                    SettingsIosDivider()
                    // auto_retry_page.dart:237-243 — jitter is a bool switch.
                    SettingsSwitchRow(
                        Lucide.RefreshCw,
                        stringResource(UiR.string.auto_retry_jitter),
                        tip = stringResource(UiR.string.auto_retry_jitter_subtitle),
                        value = jitter,
                        onToggle = { jitter = it; save() },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Globe,
                        stringResource(UiR.string.auto_retry_on_network_error),
                        value = retryOnNetworkError,
                        onToggle = { retryOnNetworkError = it; save() },
                    )
                }
            }
            item(key = "gap_strategy") { Spacer(Modifier.size(12.dp)) }
            item(key = "h_triggers") {
                SectionHeader(stringResource(UiR.string.auto_retry_section_triggers))
            }
            item(key = "c_status") {
                Spacer(Modifier.size(0.dp))
                // auto_retry_page.dart:254-281 — status codes, 100-599, no restore.
                ChipSection(
                    title = stringResource(UiR.string.auto_retry_status_codes),
                    values = retryStatusCodes,
                    hint = stringResource(UiR.string.auto_retry_add_hint),
                    restoreLabel = null,
                    numericKeyboard = true,
                    onAdd = { raw ->
                        val code = raw.trim().toIntOrNull()
                        if (code != null && code in 100..599 && !retryStatusCodes.contains(code.toString())) {
                            retryStatusCodes = (retryStatusCodes + code.toString()).sorted()
                            save()
                        }
                    },
                    onRemove = { raw ->
                        retryStatusCodes = retryStatusCodes - raw
                        save()
                    },
                    onRestore = null,
                )
            }
            item(key = "c_keywords") {
                Spacer(Modifier.size(12.dp))
                ChipSection(
                    title = stringResource(UiR.string.auto_retry_keywords),
                    values = retryKeywords,
                    hint = stringResource(UiR.string.auto_retry_add_hint),
                    restoreLabel = stringResource(UiR.string.auto_retry_restore_defaults),
                    onAdd = { raw ->
                        val keyword = raw.trim()
                        if (keyword.isNotEmpty() && !retryKeywords.contains(keyword)) {
                            retryKeywords = retryKeywords + keyword
                            save()
                        }
                    },
                    onRemove = { raw ->
                        retryKeywords = retryKeywords - raw
                        save()
                    },
                    onRestore = {
                        retryKeywords = defaultRetryKeywords.toList()
                        save()
                    },
                )
            }
            item(key = "c_stop") {
                Spacer(Modifier.size(12.dp))
                ChipSection(
                    title = stringResource(UiR.string.auto_retry_stop_keywords),
                    values = stopKeywords,
                    hint = stringResource(UiR.string.auto_retry_add_hint),
                    restoreLabel = stringResource(UiR.string.auto_retry_restore_defaults),
                    onAdd = { raw ->
                        val keyword = raw.trim()
                        if (keyword.isNotEmpty() && !stopKeywords.contains(keyword)) {
                            stopKeywords = stopKeywords + keyword
                            save()
                        }
                    },
                    onRemove = { raw ->
                        stopKeywords = stopKeywords - raw
                        save()
                    },
                    onRestore = {
                        stopKeywords = defaultStopKeywords.toList()
                        save()
                    },
                )
            }
            item(key = "footer") {
                // auto_retry_page.dart:346-355 — footer.
                Text(
                    text = stringResource(UiR.string.auto_retry_footer),
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, end = 12.dp),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                )
            }
        }
    }
}

/** auto_retry_options.dart:124-128 — non-finite/<=0 falls back to 2.0, cap 100. */
private fun clampMultiplier(value: Double): Double {
    if (!value.isFinite() || value <= 0) return 2.0
    return value.coerceAtMost(100.0)
}

/** auto_retry_page.dart:99-102 — integral values render without decimals. */
private fun formatMultiplier(value: Double): String {
    if (value == value.roundToInt().toDouble()) return value.roundToInt().toString()
    return value.toString()
}

/**
 * auto_retry_page.dart:176-236 number rows — digits commit on blur / IME
 * done (C4), never on every keystroke.
 */
@Composable
internal fun NumberSettingsRow(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    decimal: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .width(110.dp)
                .onFocusChanged {
                    if (focused && !it.hasFocus) onCommit(text)
                    focused = it.hasFocus
                },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, textAlign = TextAlign.Center, color = cs.onSurface),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.18f),
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
    }
}

/** auto_retry_page.dart:427-562 — chip section with add field, removable chips and optional restore. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(
    title: String,
    values: List<String>,
    hint: String,
    restoreLabel: String?,
    numericKeyboard: Boolean = false,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRestore: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    var input by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    fun submit() {
        if (input.isBlank()) return
        onAdd(input)
        input = ""
        focusManager.clearFocus()
    }
    SettingsSectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            if (restoreLabel != null && onRestore != null) {
                Text(
                    text = restoreLabel,
                    modifier = Modifier
                        .clickable(onClick = onRestore)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = TextStyle(fontSize = 12.sp, color = cs.primary),
                )
            }
        }
        if (values.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                values.forEach { value ->
                    RemovableChip(label = value, isDark = isDark, onRemove = { onRemove(value) })
                }
            }
        } else {
            Spacer(Modifier.size(4.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.5f)))
                },
                textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary.copy(alpha = 0.45f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.12f),
                    focusedContainerColor = cs.surfaceCardColorCompat(),
                    unfocusedContainerColor = cs.surfaceCardColorCompat(),
                    focusedTextColor = cs.onSurface,
                    unfocusedTextColor = cs.onSurface,
                ),
                shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numericKeyboard) KeyboardType.Number else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { submit() }, modifier = Modifier.size(36.dp)) {
                Icon(Lucide.Plus, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** auto_retry_page.dart:513-562 — pill chip with trailing X. */
@Composable
private fun RemovableChip(label: String, isDark: Boolean, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(cs.primary.copy(alpha = if (isDark) 0.22f else 0.12f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .border(0.6.dp, cs.primary.copy(alpha = if (isDark) 0.36f else 0.26f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
            Icon(
                Lucide.X,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
