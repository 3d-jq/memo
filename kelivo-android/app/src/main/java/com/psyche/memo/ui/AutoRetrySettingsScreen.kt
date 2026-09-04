package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of lib/features/settings/pages/auto_retry_page.dart —
 * AutoRetryOptions JSON ('auto_retry_options') with enabled master switch,
 * maxRetries / initialDelayMs / multiplier / maxDelayMs / jitter number rows
 * and retryOnNetworkError switch. Status-code & keyword list editors are
 * displayed read-only in this pass.
 */
@Composable
fun AutoRetrySettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    var enabled by remember { mutableStateOf(true) }
    var maxRetries by remember { mutableStateOf("3") }
    var initialDelayMs by remember { mutableStateOf("500") }
    var multiplier by remember { mutableStateOf("2.0") }
    var maxDelayMs by remember { mutableStateOf("8000") }
    var jitter by remember { mutableStateOf("0.2") }
    var retryOnNetworkError by remember { mutableStateOf(true) }
    var retryStatusCodes by remember { mutableStateOf(listOf<String>()) }
    var retryKeywords by remember { mutableStateOf(listOf<String>()) }
    var stopKeywords by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        val raw = container.preferenceRepository.readJson("auto_retry_options")
        if (raw.isNullOrEmpty()) return@LaunchedEffect
        runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            fun b(k: String, d: Boolean) = obj[k]?.jsonPrimitive?.booleanOrNull ?: d
            fun i(k: String, d: Int) = obj[k]?.jsonPrimitive?.intOrNull ?: d
            fun dbl(k: String, d: Double) = obj[k]?.jsonPrimitive?.doubleOrNull ?: d
            enabled = b("enabled", true)
            maxRetries = i("maxRetries", 3).toString()
            initialDelayMs = i("initialDelayMs", 500).toString()
            multiplier = dbl("multiplier", 2.0).toString()
            maxDelayMs = i("maxDelayMs", 8000).toString()
            jitter = dbl("jitter", 0.2).toString()
            retryOnNetworkError = b("retryOnNetworkError", true)
            retryStatusCodes = obj["retryStatusCodes"]?.jsonArray
                ?.map { it.jsonPrimitive.intOrNull?.toString() ?: "" }?.filter { it.isNotEmpty() } ?: emptyList()
            retryKeywords = obj["retryKeywords"]?.jsonArray
                ?.map { it.jsonPrimitive.contentOrNull ?: "" }?.filter { it.isNotEmpty() } ?: emptyList()
            stopKeywords = obj["stopKeywords"]?.jsonArray
                ?.map { it.jsonPrimitive.contentOrNull ?: "" }?.filter { it.isNotEmpty() } ?: emptyList()
        }
    }

    fun save() {
        val json = buildString {
            append("{")
            append("\"enabled\":$enabled,")
            append("\"maxRetries\":$maxRetries,")
            append("\"initialDelayMs\":$initialDelayMs,")
            append("\"multiplier\":$multiplier,")
            append("\"maxDelayMs\":$maxDelayMs,")
            append("\"jitter\":$jitter,")
            append("\"retryOnNetworkError\":$retryOnNetworkError,")
            append("\"retryStatusCodes\":[${retryStatusCodes.joinToString(",")}],")
            append("\"retryKeywords\":[${retryKeywords.joinToString(",") { "\"$it\"" }}],")
            append("\"stopKeywords\":[${stopKeywords.joinToString(",") { "\"$it\"" }}]")
            append("}")
        }
        container.preferenceRepository.writeJson("auto_retry_options", json)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(UiR.string.settings_page_back_button),
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(UiR.string.settings_page_auto_retry),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            item {
                SettingsSectionCard {
                    // Master switch: label from ARB autoRetryEnabledTitle.
                    SettingsSwitchRow(
                        Lucide.RefreshCw,
                        stringResource(UiR.string.settings_page_auto_retry),
                        value = enabled,
                        onToggle = { enabled = it; save() },
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_max_retries),
                        value = maxRetries,
                        onCommit = { maxRetries = it; save() },
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_initial_delay),
                        value = initialDelayMs,
                        onCommit = { initialDelayMs = it; save() },
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_multiplier),
                        value = multiplier,
                        onCommit = { multiplier = it; save() },
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_max_delay),
                        value = maxDelayMs,
                        onCommit = { maxDelayMs = it; save() },
                    )
                    SettingsIosDivider()
                    NumberSettingsRow(
                        label = stringResource(UiR.string.auto_retry_jitter),
                        value = jitter,
                        onCommit = { jitter = it; save() },
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
            item {
                SettingsSectionCard {
                    ReadOnlyListRow(
                        label = stringResource(UiR.string.auto_retry_status_codes),
                        items = retryStatusCodes,
                    )
                    SettingsIosDivider()
                    ReadOnlyListRow(
                        label = stringResource(UiR.string.auto_retry_keywords),
                        items = retryKeywords,
                    )
                    SettingsIosDivider()
                    ReadOnlyListRow(
                        label = stringResource(UiR.string.auto_retry_stop_keywords),
                        items = stopKeywords,
                    )
                }
            }
        }
    }
}

/** Numeric settings row (auto_retry_page.dart number rows). */
@Composable
internal fun NumberSettingsRow(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var text by remember(value) { mutableStateOf(value) }
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
            modifier = Modifier.width(110.dp),
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = cs.onSurface),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.18f),
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        // Commit on blur is Flutter's behaviour; here commit on each change.
        LaunchedEffect(text) { onCommit(text) }
    }
}

/** Read-only list row for status codes / keywords (editors come later). */
@Composable
private fun ReadOnlyListRow(label: String, items: List<String>) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 15.sp, color = cs.onSurface))
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (items.isEmpty()) "—" else items.joinToString(", "),
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
        )
    }
}
