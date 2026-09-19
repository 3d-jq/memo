package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/**
 * Provider sub-pages (#11) — 1:1 ports of provider_network_page.dart and
 * provider_custom_request_page.dart: each control saves immediately through a
 * debounced write-back into the **detail page's** config state (which owns the
 * single debounced DB writer), mirroring the original's
 * `settings.setProviderConfig` read-modify-write.
 */

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

/**
 * 供应商子页的标签输入行 —— 原版 `provider_network_page._inputRow` +
 * `_proxyInputDecoration`（L158-233）：13sp 标签 + 6dp + 字段（surfaceFill 底、
 * r10、hairline `outlineVariant@12%`、聚焦 `primary@35%`、内边距 12/10、
 * `isDense`、14sp 文本、提示 14sp@50%）。
 *
 * [obscure] 用于代理密码（原版 `obscureText: true`），[placeholder] 对应原版
 * 各字段的 hint（127.0.0.1 / 8080）。
 */
@Composable
internal fun SubPageInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    obscure: Boolean = false,
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
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 5,
            visualTransformation = if (obscure) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                    )
                }
            },
            shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceFill,
                unfocusedContainerColor = semantic.surfaceFill,
                focusedBorderColor = cs.primary.copy(alpha = 0.35f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.12f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 代理类型选择字段 —— 原版 `_ProxyTypeSheetField`（L235-328）：r10 卡片式选择框
 * （内边距 12/12、surfaceFill 底、hairline 边框、14sp@88% 文本、18dp 下拉箭头），
 * 点开还是我们统一过的那套卡片选项 sheet。
 */
@Composable
private fun ProxyTypeField(
    value: String,
    onOpenSheet: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .clickable(onClick = onOpenSheet)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (value == "socks5") com.psyche.memo.ui.R.string.network_proxy_type_socks5
                else com.psyche.memo.ui.R.string.network_proxy_type_http,
            ),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.88f)),
            modifier = Modifier.weight(1f),
        )
        Icon(
            Lucide.ChevronDown,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ------------------------------------------------------ network proxy page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderNetworkPage(
    container: AppContainerImpl,
    providerId: String,
    cfg: ProviderConfig,
    onCfgChange: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    var proxyEnabled by remember { mutableStateOf(cfg.proxyEnabled ?: false) }
    var proxyType by remember { mutableStateOf(if (cfg.proxyType == "socks5") "socks5" else "http") }
    var proxyTypeSheetVisible by remember { mutableStateOf(false) }
    var proxyHost by remember { mutableStateOf(cfg.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(cfg.proxyPort ?: "8080") }
    var proxyUsername by remember { mutableStateOf(cfg.proxyUsername ?: "") }
    var proxyPassword by remember { mutableStateOf(cfg.proxyPassword ?: "") }
    // 保存走**父页面**的 cfg（原版是 settings.setProviderConfig 的读-改-写）：
    // 子页此前直写 DB，而详情页手里还是旧 cfg，回去动一下任何一行就会把这里的
    // 代理设置整段覆盖回去（用户可见的数据丢失）。
    val latestCfg by rememberUpdatedState(cfg)
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(providerId) { loaded = true }

    // Debounced immediate save. `<Any?>` 显式指定 snapshotFlow 重整 T
    // 之外的元素类型（避免与 Boolean/String 共同父类回退）。
    @OptIn(FlowPreview::class)
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { arrayOf<Any?>(proxyEnabled, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword) }
            .debounce(400)
            .collect {
                onCfgChange(
                    latestCfg.copy(
                        proxyEnabled = proxyEnabled,
                        proxyType = proxyType,
                        proxyHost = proxyHost,
                        proxyPort = proxyPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword,
                    ),
                )
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
            // 原版是**裸的开关行**（`_switchRow` L145-156）：没有卡片、没有前置
            // 图标；15sp 标签 + 行尾 IosSwitch。
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_enable_proxy_title),
                    style = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f),
                )
                IosSwitch(value = proxyEnabled, onValueChanged = { proxyEnabled = it })
            }
            if (proxyEnabled) {
                Spacer(Modifier.height(12.dp))
                // 原版：代理类型是**带标签的选择框**（不是 chevron 导航行）。
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.network_proxy_type),
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    ),
                )
                Spacer(Modifier.height(6.dp))
                ProxyTypeField(value = proxyType, onOpenSheet = { proxyTypeSheetVisible = true })
            }
            if (proxyEnabled) {
                Spacer(Modifier.height(12.dp))
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_host_label),
                    value = proxyHost,
                    onValueChange = { proxyHost = it },
                    placeholder = "127.0.0.1",
                )
                Spacer(Modifier.height(12.dp))
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_port_label),
                    value = proxyPort,
                    onValueChange = { proxyPort = it.filter { ch -> ch.isDigit() } },
                    placeholder = "8080",
                )
                Spacer(Modifier.height(12.dp))
                // C12 — labels from ARB (provider_network_page.dart:121-136).
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_username_optional_label),
                    value = proxyUsername,
                    onValueChange = { proxyUsername = it },
                )
                Spacer(Modifier.height(12.dp))
                // 原版 `obscureText: true` —— 密码不要明文摆着。
                SubPageInput(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_password_optional_label),
                    value = proxyPassword,
                    onValueChange = { proxyPassword = it },
                    obscure = true,
                )
            }
        }
    }

    // provider_network_page.dart:235-282 — proxy type is a http/socks5
    // two-option bottom sheet (C12), not a free-text field.
    if (proxyTypeSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { proxyTypeSheetVisible = false }, dragHandle = null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                MemoSheetHandle(trailingGap = 0.dp)
                listOf(
                    "http" to com.psyche.memo.ui.R.string.network_proxy_type_http,
                    "socks5" to com.psyche.memo.ui.R.string.network_proxy_type_socks5,
                ).forEach { (value, labelRes) ->
                    MemoSheetOptionRow(
                        label = stringResource(labelRes),
                        selected = value == proxyType,
                        onClick = {
                            proxyType = value
                            proxyTypeSheetVisible = false
                        },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------- custom request page

@Composable
fun ProviderCustomRequestPage(
    container: AppContainerImpl,
    providerId: String,
    cfg: ProviderConfig,
    onCfgChange: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    var headers by remember { mutableStateOf(cfg.customHeaders) }
    var body by remember { mutableStateOf(cfg.customBody) }
    var loaded by remember { mutableStateOf(false) }
    // 同网络页：保存回写到父页面 cfg，避免详情页用旧 cfg 把这里的编辑覆盖掉。
    val latestCfg by rememberUpdatedState(cfg)

    LaunchedEffect(providerId) { loaded = true }

    LaunchedEffect(loaded, headers, body) {
        if (!loaded) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        onCfgChange(latestCfg.copy(customHeaders = headers, customBody = body))
    }

    SubPageScaffold(
        title = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_custom_request_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_custom_request_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.85.sp, // 原版 height: 1.45 × 13
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                ),
            )
            Spacer(Modifier.height(18.dp))
            // Headers section — 标题 13sp emphasis@80%（editor L166-176），
            // 添加钮是 IosTileButton（Plus + 13sp 标签，L177-184）。
            CustomRequestSection(
                title = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_custom_headers_title),
                addLabel = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_add_header),
                onAdd = { headers = headers + mapOf("name" to "", "value" to "") },
                rows = headers.size,
            ) {
                headers.forEachIndexed { i, row ->
                    RequestRow(
                        name = row["name"] ?: "",
                        value = row["value"] ?: "",
                        nameHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_header_key_hint),
                        valueHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_header_value_hint),
                        multilineValue = false,
                        onChange = { n, v ->
                            headers = headers.toMutableList().also { it[i] = mapOf("name" to n, "value" to v) }
                        },
                        onDelete = { headers = headers.toMutableList().also { it.removeAt(i) } },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            // Body overrides section（值是多行 JSON，原版 2..5 行）。
            CustomRequestSection(
                title = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_custom_body_title),
                addLabel = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_add_body),
                onAdd = { body = body + mapOf("key" to "", "value" to "") },
                rows = body.size,
            ) {
                body.forEachIndexed { i, row ->
                    RequestRow(
                        name = row["key"] ?: "",
                        value = row["value"] ?: "",
                        nameHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_body_key_hint),
                        valueHint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_body_json_hint),
                        multilineValue = true,
                        onChange = { n, v ->
                            body = body.toMutableList().also { it[i] = mapOf("key" to n, "value" to v) }
                        },
                        onDelete = { body = body.toMutableList().also { it.removeAt(i) } },
                    )
                }
            }
        }
    }
}

/**
 * 一个「键值对」编辑区 —— 原版 `provider_custom_request_editor` L160-200：
 * 标题 13sp emphasis@80% + 添加钮（IosTileButton：Plus + 13sp、内边距 12/9）；
 * 宽屏（≥440dp）时添加钮与标题同行，否则另起一行。
 */
@Composable
private fun CustomRequestSection(
    title: String,
    addLabel: String,
    onAdd: () -> Unit,
    rows: Int,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 440.dp
        Column {
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface.copy(alpha = 0.8f),
                        ),
                        modifier = Modifier.weight(1f).padding(start = 2.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    AddRowButton(label = addLabel, onClick = onAdd)
                }
            } else {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface.copy(alpha = 0.8f),
                    ),
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                AddRowButton(label = addLabel, onClick = onAdd)
            }
            if (rows > 0) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** 「添加 Header/Body」—— 原版 `IosTileButton(label, Lucide.Plus, 13sp, 12/9)`。 */
@Composable
private fun AddRowButton(label: String, onClick: () -> Unit) {
    IosTileButton(
        label = label,
        icon = Lucide.Plus,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        fontSize = 13.sp,
        onClick = onClick,
    )
}

/**
 * 一行键值对 —— 原版 `_CustomRequestField`（L280-395）：两个**裸字段**（hint 即
 * 占位、r10 hairline 底、14sp）+ 行尾 `Trash2` 图标钮（18dp、44dp 命中）；
 * ≥440dp 时名称/值按 4:6 并排，否则名称与删除钮一行、值另起一行；值可多行。
 */
@Composable
private fun RequestRow(
    name: String,
    value: String,
    nameHint: String,
    valueHint: String,
    multilineValue: Boolean,
    onChange: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 440.dp
            val deleteButton: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_button),
                        tint = cs.onSurface.copy(alpha = 0.62f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(modifier = Modifier.weight(4f)) {
                        BareField(value = name, hint = nameHint, singleLine = true) { onChange(it, value) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(6f)) {
                        BareField(value = value, hint = valueHint, singleLine = !multilineValue) { onChange(name, it) }
                    }
                    Spacer(Modifier.width(4.dp))
                    deleteButton()
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.weight(1f)) {
                        BareField(value = name, hint = nameHint, singleLine = true) { onChange(it, value) }
                    }
                    Spacer(Modifier.width(4.dp))
                    deleteButton()
                }
                Spacer(Modifier.height(8.dp))
                BareField(value = value, hint = valueHint, singleLine = !multilineValue) { onChange(name, it) }
            }
        }
    }
}

/** 裸字段（原版 `_decoration`）：r10、surfaceFill、hairline、聚焦 primary@35%。 */
@Composable
private fun BareField(
    value: String,
    hint: String,
    singleLine: Boolean,
    onValueChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 5,
        textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
        placeholder = {
            Text(
                text = hint,
                style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        shape = RoundedCornerShape(MemoRadius.SMALL_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceFill,
            unfocusedContainerColor = semantic.surfaceFill,
            focusedBorderColor = cs.primary.copy(alpha = 0.35f),
            unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.12f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
