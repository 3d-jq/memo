package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.Json

/**
 * Provider sub-pages (#11) — 1:1 ports of provider_network_page.dart and
 * provider_custom_request_page.dart: each control saves immediately through
 * a debounced config writer (no save button), mirroring the detail page.
 */
private val subPageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Shared immediate-save helper: reloads the row, applies [change], persists. */
internal fun persistProviderConfig(
    container: AppContainerImpl,
    providerId: String,
    change: (ProviderConfig) -> ProviderConfig,
) {
    val dao = PayloadEntityDao(container.database.writableDatabase, "provider_rows", primaryKey = "provider_key")
    val current = dao.get(providerId)?.let {
        runCatching { subPageJson.decodeFromString(ProviderConfig.serializer(), it.payload) }.getOrNull()
    } ?: return
    val next = change(current)
    dao.upsert(providerId, subPageJson.encodeToString(ProviderConfig.serializer(), next), dao.get(providerId)?.sortOrder ?: 0)
}

/** Back-chevron + title app bar used by all provider sub-pages. */
@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Opaque surface: these pages render as full-screen overlays above the
    // detail screen, so without a background it shows through.
    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ChevronLeft, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        content()
    }
}

@Composable
internal fun SubPageInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                color = cs.onSurface.copy(alpha = 0.8f),
            ),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceCard,
                unfocusedContainerColor = semantic.surfaceCard,
                focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------ network proxy page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderNetworkPage(
    container: AppContainerImpl,
    providerId: String,
    onBack: () -> Unit,
) {
    var proxyEnabled by remember { mutableStateOf(false) }
    var proxyType by remember { mutableStateOf("http") }
    var proxyTypeSheetVisible by remember { mutableStateOf(false) }
    var proxyHost by remember { mutableStateOf("") }
    var proxyPort by remember { mutableStateOf("8080") }
    var proxyUsername by remember { mutableStateOf("") }
    var proxyPassword by remember { mutableStateOf("") }

    LaunchedEffect(providerId) {
        val dao = PayloadEntityDao(container.database.readableDatabase, "provider_rows", primaryKey = "provider_key")
        dao.get(providerId)?.let { row ->
            runCatching { subPageJson.decodeFromString(ProviderConfig.serializer(), row.payload) }.getOrNull()
        }?.let { cfg ->
            proxyEnabled = cfg.proxyEnabled ?: false
            proxyType = if (cfg.proxyType == "socks5") "socks5" else "http"
            proxyHost = cfg.proxyHost ?: ""
            proxyPort = cfg.proxyPort ?: "8080"
            proxyUsername = cfg.proxyUsername ?: ""
            proxyPassword = cfg.proxyPassword ?: ""
        }
    }

    // Debounced immediate save.
    LaunchedEffect(providerId) {
        snapshotFlow { arrayOf(proxyEnabled, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword) }
            .debounce(400)
            .collect {
                persistProviderConfig(container, providerId) { cfg ->
                    cfg.copy(
                        proxyEnabled = proxyEnabled,
                        proxyType = proxyType,
                        proxyHost = proxyHost,
                        proxyPort = proxyPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword,
                    )
                }
            }
    }

    SubPageScaffold(
        title = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_network_tab),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SettingsSectionCard {
                SettingsSwitchRow(
                    icon = Lucide.Globe,
                    // provider_network_page.dart:72 — enable proxy label.
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_enable_proxy_title),
                    value = proxyEnabled,
                    onToggle = { proxyEnabled = it },
                )
                if (proxyEnabled) {
                    SettingsIosDivider()
                    // provider_network_page.dart:81-91 — type picker row.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { proxyTypeSheetVisible = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(com.psyche.memo.ui.R.string.network_proxy_type),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                        )
                        Text(
                            text = stringResource(
                                if (proxyType == "socks5") com.psyche.memo.ui.R.string.network_proxy_type_socks5
                                else com.psyche.memo.ui.R.string.network_proxy_type_http,
                            ),
                            style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
                        )
                        Icon(
                            Lucide.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (proxyEnabled) {
                Spacer(Modifier.height(12.dp))
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_host_label),
                    value = proxyHost,
                    onValueChange = { proxyHost = it },
                )
                Spacer(Modifier.height(12.dp))
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_port_label),
                    value = proxyPort,
                    onValueChange = { proxyPort = it },
                )
                Spacer(Modifier.height(12.dp))
                // C12 — labels from ARB (provider_network_page.dart:121-136).
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_username_optional_label),
                    value = proxyUsername,
                    onValueChange = { proxyUsername = it },
                )
                Spacer(Modifier.height(12.dp))
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_password_optional_label),
                    value = proxyPassword,
                    onValueChange = { proxyPassword = it },
                )
            }
        }
    }

    // provider_network_page.dart:235-282 — proxy type is a http/socks5
    // two-option bottom sheet (C12), not a free-text field.
    if (proxyTypeSheetVisible) {
        ModalBottomSheet(onDismissRequest = { proxyTypeSheetVisible = false }) {
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                ProxyTypeOption(
                    labelRes = com.psyche.memo.ui.R.string.network_proxy_type_http,
                    value = "http",
                    current = proxyType,
                ) { v ->
                    proxyType = v
                    proxyTypeSheetVisible = false
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
                ProxyTypeOption(
                    labelRes = com.psyche.memo.ui.R.string.network_proxy_type_socks5,
                    value = "socks5",
                    current = proxyType,
                ) { v ->
                    proxyType = v
                    proxyTypeSheetVisible = false
                }
            }
        }
    }
}

/** provider_network_page.dart:235-282 — http / socks5 sheet option row. */
@Composable
private fun ProxyTypeOption(labelRes: Int, value: String, current: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val selected = value == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (selected) cs.primary else cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------- custom request page

@Composable
fun ProviderCustomRequestPage(
    container: AppContainerImpl,
    providerId: String,
    onBack: () -> Unit,
) {
    var headers by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var body by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(providerId) {
        val dao = PayloadEntityDao(container.database.readableDatabase, "provider_rows", primaryKey = "provider_key")
        dao.get(providerId)?.let { row ->
            runCatching { subPageJson.decodeFromString(ProviderConfig.serializer(), row.payload) }.getOrNull()
        }?.let { cfg ->
            headers = cfg.customHeaders
            body = cfg.customBody
        }
        loaded = true
    }

    LaunchedEffect(loaded, headers, body) {
        if (!loaded) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        persistProviderConfig(container, providerId) { cfg ->
            cfg.copy(customHeaders = headers, customBody = body)
        }
    }

    SubPageScaffold(
        title = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_custom_request_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_custom_request_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                ),
            )
            Spacer(Modifier.height(18.dp))
            // Headers section — ARB labels (provider_custom_request_editor.dart:99-113).
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_custom_headers_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            headers.forEachIndexed { i, row ->
                RequestRow(
                    name = row["name"] ?: "",
                    value = row["value"] ?: "",
                    nameHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_header_key_hint),
                    valueHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_header_value_hint),
                    onChange = { n, v ->
                        headers = headers.toMutableList().also { it[i] = mapOf("name" to n, "value" to v) }
                    },
                    onDelete = { headers = headers.toMutableList().also { it.removeAt(i) } },
                )
            }
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_add_header),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { headers = headers + mapOf("name" to "", "value" to "") }
                    .padding(8.dp),
            )
            Spacer(Modifier.height(18.dp))
            // Body overrides section.
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_custom_body_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            body.forEachIndexed { i, row ->
                RequestRow(
                    name = row["key"] ?: "",
                    value = row["value"] ?: "",
                    nameHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_body_key_hint),
                    valueHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_body_json_hint),
                    onChange = { n, v ->
                        body = body.toMutableList().also { it[i] = mapOf("key" to n, "value" to v) }
                    },
                    onDelete = { body = body.toMutableList().also { it.removeAt(i) } },
                )
            }
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_add_body),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { body = body + mapOf("key" to "", "value" to "") }
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun RequestRow(
    name: String,
    value: String,
    nameHint: String,
    valueHint: String,
    onChange: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(LocalSemanticColors.current.surfaceCard, RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        SubPageInput(label = nameHint, value = name, onValueChange = { onChange(it, value) })
        Spacer(Modifier.height(6.dp))
        SubPageInput(label = valueHint, value = value, onValueChange = { onChange(name, it) })
        Text(
            text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_button),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.End)
                .clickable(onClick = onDelete)
                .padding(6.dp),
        )
    }
}
