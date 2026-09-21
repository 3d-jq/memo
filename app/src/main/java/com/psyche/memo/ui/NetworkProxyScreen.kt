package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1:1 port of lib/features/settings/pages/network_proxy_page.dart — the
 * GLOBAL proxy settings page (not the provider-detail network tab). Fields
 * persist on focus loss into the global_proxy_*_v1 preference keys
 * (settings_provider.dart L383-391); the connection-test card performs a real
 * request through the configured proxy (L289-350).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkProxyScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val scope = rememberCoroutineScope()

    // PREFERENCE keys — settings_provider.dart L383-391.
    fun readStr(key: String, default: String): String =
        container.preferenceRepository.readJson(key)?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
            ?: default

    var enabled by remember { mutableStateOf(readStr("global_proxy_enabled_v1", "false") == "true") }
    var type by remember { mutableStateOf(readStr("global_proxy_type_v1", "http")) }
    var host by remember { mutableStateOf(readStr("global_proxy_host_v1", "")) }
    var port by remember { mutableStateOf(readStr("global_proxy_port_v1", "8080")) }
    var username by remember { mutableStateOf(readStr("global_proxy_username_v1", "")) }
    var password by remember { mutableStateOf(readStr("global_proxy_password_v1", "")) }
    var bypass by remember { mutableStateOf(readStr("global_proxy_bypass_v1", "localhost,127.0.0.1,::1")) }

    fun persist(key: String, value: String) {
        container.preferenceRepository.writeJson(key, "\"$value\"")
    }
    fun persistPort(value: String) {
        // setGlobalProxyPort L1614-1618 — trim only.
        val v = value.trim()
        port = v
        container.preferenceRepository.writeJson("global_proxy_port_v1", "\"$v\"")
    }

    var typeSheetVisible by remember { mutableStateOf(false) }
    var testUrl by remember { mutableStateOf("https://www.google.com") }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testErr by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.settings_page_network_proxy),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item { SectionHeader(stringResource(UiR.string.network_proxy_section_proxy), first = true) }
            item {
                SettingsSectionCard {
                    // L111-140 — enable switch row.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(UiR.string.network_proxy_enable_label),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(
                            value = enabled,
                            onValueChanged = {
                                enabled = it
                                container.preferenceRepository.writeJson("global_proxy_enabled_v1", it.toString())
                            },
                        )
                    }
                    // L141-154 — proxy type sheet field.
                    ProxyLabeledField(stringResource(UiR.string.network_proxy_type)) {
                        ProxyTypeSheetField(
                            value = type,
                            onSelect = { v ->
                                type = v
                                persist("global_proxy_type_v1", v)
                                typeSheetVisible = false
                            },
                            onOpen = { typeSheetVisible = true },
                        )
                    }
                    // L155-165 — host.
                    ProxyLabeledField(stringResource(UiR.string.network_proxy_server_host)) {
                        ProxyTextField(
                            value = host,
                            onValueChange = { host = it },
                            onCommit = { persist("global_proxy_host_v1", it.trim()) },
                            hint = "127.0.0.1",
                        )
                    }
                    // L166-177 — port.
                    ProxyLabeledField(stringResource(UiR.string.network_proxy_port)) {
                        ProxyTextField(
                            value = port,
                            onValueChange = { port = it },
                            onCommit = { persistPort(it) },
                            hint = "8080",
                            number = true,
                        )
                    }
                    // L178-188 — username.
                    ProxyLabeledField(stringResource(UiR.string.network_proxy_username)) {
                        ProxyTextField(
                            value = username,
                            onValueChange = { username = it },
                            onCommit = { persist("global_proxy_username_v1", it.trim()) },
                            hint = stringResource(UiR.string.network_proxy_optional_hint),
                        )
                    }
                    // L189-200 — password.
                    ProxyLabeledField(stringResource(UiR.string.network_proxy_password)) {
                        ProxyTextField(
                            value = password,
                            onValueChange = { password = it },
                            onCommit = {
                                // L1628-1635 — empty password removes the key.
                                val v = it
                                password = v
                                if (v.isEmpty()) {
                                    container.preferenceRepository.remove("global_proxy_password_v1")
                                } else {
                                    container.preferenceRepository.writeJson("global_proxy_password_v1", "\"$v\"")
                                }
                            },
                            hint = stringResource(UiR.string.network_proxy_optional_hint),
                            obscure = true,
                        )
                    }
                    // L201-213 — bypass.
                    ProxyLabeledField(stringResource(UiR.string.network_proxy_bypass_label)) {
                        ProxyTextField(
                            value = bypass,
                            onValueChange = { bypass = it },
                            onCommit = { persist("global_proxy_bypass_v1", it.trim()) },
                            hint = stringResource(UiR.string.network_proxy_bypass_hint),
                            minLines = 1,
                            maxLines = 3,
                        )
                    }
                    // L214-223 — priority note.
                    Text(
                        text = stringResource(UiR.string.network_proxy_priority_note),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                // L228-237 — test header; use the unified SectionHeader form.
                SectionHeader(stringResource(UiR.string.network_proxy_test_header))
            }
            item {
                SettingsSectionCard {
                    // L238-264 — test URL field + test button.
                    Box(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)) {
                        ProxyTextField(
                            value = testUrl,
                            onValueChange = { testUrl = it },
                            onCommit = {},
                            hint = stringResource(UiR.string.network_proxy_test_url_hint),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 10.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    ) {
                        ProxyTestButton(
                            label = stringResource(if (testing) UiR.string.network_proxy_testing else UiR.string.network_proxy_test_button),
                            enabled = !testing,
                            onTap = {
                                // _onTest L289-350 — request through the proxy.
                                val url = testUrl.trim()
                                if (url.isEmpty()) {
                                    testOk = false
                                    testErr = "no_url"
                                    return@ProxyTestButton
                                }
                                testing = true
                                testOk = null
                                testErr = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runProxyTest(
                                            type = type,
                                            host = host.trim(),
                                            port = port.trim().toIntOrNull() ?: 8080,
                                            username = username.trim(),
                                            password = password,
                                            url = url,
                                        )
                                    }
                                    testing = false
                                    testOk = result.first
                                    testErr = result.second
                                }
                            },
                        )
                    }
                }
            }
            if (testOk == true) {
                item {
                    Text(
                        text = stringResource(UiR.string.network_proxy_test_success),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = semantic.success),
                    )
                }
            }
            if (testOk == false) {
                item {
                    Text(
                        text = stringResource(
                            UiR.string.network_proxy_test_failed,
                            if (testErr == "no_url") stringResource(UiR.string.network_proxy_no_url) else (testErr ?: ""),
                        ),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                        style = TextStyle(fontSize = 14.sp, color = cs.error),
                    )
                }
            }
        }
    }

    // L377-418 — proxy type bottom sheet: http / https / socks5.
    if (typeSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { typeSheetVisible = false }, dragHandle = null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                MemoSheetHandle(trailingGap = 0.dp)
                listOf(
                    "http" to UiR.string.network_proxy_type_http,
                    "https" to UiR.string.network_proxy_type_https,
                    "socks5" to UiR.string.network_proxy_type_socks5,
                ).forEach { (value, labelRes) ->
                    MemoSheetOptionRow(
                        label = stringResource(labelRes),
                        selected = value == type,
                        onClick = {
                            type = value
                            persist("global_proxy_type_v1", value)
                            typeSheetVisible = false
                        },
                    )
                }
            }
        }
    }
}

/** L540-565 — _labeledField. */
@Composable
private fun ProxyLabeledField(label: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(bottom = 6.dp),
            style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.7f)),
        )
        content()
    }
}

/**
 * L568-601 — _deskInputDecoration: filled surfaceCardFill, 10dp radius,
 * hairline border, 0.5-alpha 14sp hints.
 */
@Composable
private fun ProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    hint: String,
    obscure: Boolean = false,
    number: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (obscure || number) 1 else 5,
) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.5f))) },
        visualTransformation = if (obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                obscure -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        minLines = minLines,
        maxLines = maxLines,
        textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
        shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cs.surfaceCardColorCompat(),
            unfocusedContainerColor = cs.surfaceCardColorCompat(),
            focusedBorderColor = cs.primary.copy(alpha = 0.35f),
            unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.12f),
        ),
    )
    // Persist on typing idle — stand-in for the Dart focus-loss listeners
    // (initState L58-72).
    androidx.compose.runtime.LaunchedEffect(value) {
        kotlinx.coroutines.delay(600)
        onCommit(value)
    }
}

/** L354-455 — _ProxyTypeSheetField closed control. */
@Composable
private fun ProxyTypeSheetField(
    value: String,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val label = when (value) {
        "https" -> stringResource(UiR.string.network_proxy_type_https)
        "socks5" -> stringResource(UiR.string.network_proxy_type_socks5)
        else -> stringResource(UiR.string.network_proxy_type_http)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface.copy(alpha = 0.04f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.88f)),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Icon(
            Lucide.ChevronDown,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** L603-666 — _DeskIosButton (dense, unfilled). */
@Composable
private fun ProxyTestButton(
    label: String,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val isDark = lum < 0.5f
    Row(
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(onClick = onTap) else Modifier)
            .background(cs.onSurface.copy(alpha = if (isDark) 0.06f else 0.05f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = if (isDark) 0.22f else 0.18f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
        )
    }
}

/**
 * network_proxy_page.dart _onTest L304-349 — performs a GET through the
 * configured proxy; 8s timeout; success = 2xx/3xx. HTTP proxy auth goes
 * through OkHttp's proxyAuthenticator; SOCKS5 auth uses java.net.Authenticator.
 */
private fun runProxyTest(
    type: String,
    host: String,
    port: Int,
    username: String,
    password: String,
    url: String,
): Pair<Boolean, String?> {
    return try {
        if (type == "socks5" && username.isNotEmpty()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication =
                    java.net.PasswordAuthentication(username, password.toCharArray())
            })
        }
        val proxy = java.net.Proxy(
            if (type == "socks5") java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress(host, port),
        )
        val client = okhttp3.OkHttpClient.Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    val credential = okhttp3.Credentials.basic(username, password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { res ->
            val ok = res.code in 200..399
            if (ok) Pair(true, null) else Pair(false, "HTTP ${res.code}")
        }
    } catch (e: Exception) {
        Pair(false, e.message ?: e.toString())
    }
}
