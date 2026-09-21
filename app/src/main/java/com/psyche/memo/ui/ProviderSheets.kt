package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.X
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.repo.ProviderRepository
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Provider sheets — 1:1 ports of kelivo add_provider_sheet.dart (3-tab add
 * form), share_provider_sheet.dart (ai-provider:v1 base64 payload + QR) and
 * provider_group_picker_sheet.dart (group list + create dialog).
 */

private val sheetJson = Json { ignoreUnknownKeys = true }

/** Mirrors kelivo encodeProviderConfig — ai-provider:v1:<base64(json)>. */
internal fun encodeProviderConfig(cfg: ProviderConfig): String {
    val kind = cfg.classifiedKind()
    val obj: JsonObject = buildJsonObject {
        // wire 值按 Flutter 的 ProviderKind.name（share_provider_sheet.dart L18-27）：
        // google / claude / openai —— 不是我们内部的 "gemini"（否则分享码在两端都导错）。
        put("type", if (kind == "gemini") "google" else kind)
        put("name", cfg.name)
        put("apiKey", cfg.apiKey)
        if (kind != "gemini") put("baseUrl", cfg.baseUrl)
    }
    val b64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "ai-provider:v1:$b64"
}

// ---------------------------------------------------------------- Add sheet

/** Unique provider key: "OpenAI" / "OpenAI - <name>" with (n) suffixes. */
internal fun uniqueKey(existing: Set<String>, prefix: String, display: String): String {
    if (display.lowercase() == prefix.lowercase()) {
        var i = 1
        var candidate = "$prefix - $i"
        while (candidate in existing) {
            i++
            candidate = "$prefix - $i"
        }
        return candidate
    }
    val base = "$prefix - $display"
    if (base !in existing) return base
    var i = 2
    var candidate = "$base ($i)"
    while (candidate in existing) {
        i++
        candidate = "$base ($i)"
    }
    return candidate
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderSheet(
    container: AppContainerImpl,
    onAdded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val tab = remember { mutableIntStateOf(0) }

    // OpenAI form state
    var openaiEnabled by remember { mutableStateOf(true) }
    var openaiName by remember { mutableStateOf("OpenAI") }
    var openaiKey by remember { mutableStateOf("") }
    var openaiBase by remember { mutableStateOf("https://api.openai.com/v1") }
    var openaiPath by remember { mutableStateOf("/chat/completions") }
    // Google form state
    var googleEnabled by remember { mutableStateOf(true) }
    var googleName by remember { mutableStateOf("Google") }
    var googleKey by remember { mutableStateOf("") }
    var googleBase by remember { mutableStateOf("https://generativelanguage.googleapis.com/v1beta") }
    var googleVertex by remember { mutableStateOf(false) }
    var googleLocation by remember { mutableStateOf("us-central1") }
    var googleProject by remember { mutableStateOf("") }
    var googleSaJson by remember { mutableStateOf("") }
    // Claude form state
    var claudeEnabled by remember { mutableStateOf(true) }
    var claudeName by remember { mutableStateOf("Claude") }
    var claudeKey by remember { mutableStateOf("") }
    var claudeBase by remember { mutableStateOf("https://api.anthropic.com/v1") }

    val addedMessage = stringResource(com.psyche.memo.ui.R.string.providers_page_provider_added_snackbar)

    fun onAdd() {
        val dao = com.psyche.memo.data.db.PayloadEntityDao(
            container.database.writableDatabase,
            "provider_rows",
            primaryKey = "provider_key",
        )
        val existing = dao.getAll().map { it.id }.toSet()
        val cfg: ProviderConfig
        val keyName: String
        when (tab.intValue) {
            0 -> {
                val display = openaiName.trim().ifEmpty { "OpenAI" }
                keyName = uniqueKey(existing, "OpenAI", display)
                val base = openaiBase.trim().ifEmpty { "https://api.openai.com/v1" }
                cfg = ProviderConfig(
                    id = keyName,
                    enabled = openaiEnabled,
                    name = display,
                    apiKey = openaiKey.trim(),
                    baseUrl = base,
                    providerType = "openai",
                    chatPath = openaiPath,
                    useResponseApi = useResponseApiFor(openaiPath),
                )
            }
            1 -> {
                val display = googleName.trim().ifEmpty { "Google" }
                keyName = uniqueKey(existing, "Google", display)
                cfg = ProviderConfig(
                    id = keyName,
                    enabled = googleEnabled,
                    name = display,
                    apiKey = googleKey.trim(),
                    // Vertex 用 aiplatform 主机；普通 Gemini 回落到默认地址
                    // （add_provider_sheet.dart L363-368：空值也走默认，别存 ""）。
                    baseUrl = if (googleVertex) {
                        "https://aiplatform.googleapis.com"
                    } else {
                        googleBase.trim().ifEmpty { "https://generativelanguage.googleapis.com/v1beta" }
                    },
                    providerType = "google",
                    vertexAI = if (googleVertex) true else null,
                    location = if (googleVertex) googleLocation.trim() else null,
                    projectId = if (googleVertex) googleProject.trim() else null,
                    serviceAccountJson = if (googleVertex && googleSaJson.isNotBlank()) googleSaJson else null,
                )
            }
            else -> {
                val display = claudeName.trim().ifEmpty { "Claude" }
                keyName = uniqueKey(existing, "Claude", display)
                cfg = ProviderConfig(
                    id = keyName,
                    enabled = claudeEnabled,
                    name = display,
                    apiKey = claudeKey.trim(),
                    baseUrl = claudeBase.trim().ifEmpty { "https://api.anthropic.com/v1" },
                    providerType = "claude",
                )
            }
        }
        val repo = ProviderRepository(container.database.writableDatabase, container.preferenceRepository)
        val sortOrder = dao.get(cfg.id)?.sortOrder ?: dao.nextSortOrder()
        dao.upsert(cfg.id, sheetJson.encodeToString(ProviderConfig.serializer(), cfg), sortOrder)
        // 新供应商插到顺序表**最前面**（add_provider_sheet.dart L424-429）。
        val order = (listOf(cfg.id) + repo.order()).distinct()
        repo.setOrder(order)
        onAdded()
        SnackbarManager.show(
            AppNotification(message = addedMessage, type = NotificationType.SUCCESS),
        )
        onDismiss()
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            // Header: X left + centered 18sp semibold title.
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(36.dp),
            ) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .size(40.dp)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = "Close", tint = cs.onSurface, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // Segmented tab bar: OpenAI / Google / Claude.
            SegTabBar(
                tabs = listOf("OpenAI", "Google", "Claude"),
                selected = tab.intValue,
                onSelect = { tab.intValue = it },
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab.intValue) {
                    0 -> {
                        SwitchRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_enabled_label), openaiEnabled) { openaiEnabled = it }
                        // 每个输入块之间 10dp（add_provider_sheet.dart 的 SizedBox(height: 10)）。
                        Spacer(Modifier.height(10.dp))
                        InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_name_label), openaiName) { openaiName = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow("API Key", openaiKey) { openaiKey = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow("API Base Url", openaiBase) { openaiBase = it }
                        // 「Response API」开关 + 手填路径合并成 combobox（与详情页一致）。
                        Spacer(Modifier.height(10.dp))
                        ApiPathField(
                            label = stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_api_path_label),
                            chatPath = openaiPath,
                            useResponseApi = useResponseApiFor(openaiPath),
                            onSelect = { openaiPath = it.path },
                        )
                    }
                    1 -> {
                        SwitchRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_enabled_label), googleEnabled) { googleEnabled = it }
                        SwitchRow("Vertex AI", googleVertex) { googleVertex = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_name_label), googleName) { googleName = it }
                        if (!googleVertex) {
                            Spacer(Modifier.height(10.dp))
                            InputRow("API Key", googleKey) { googleKey = it }
                            Spacer(Modifier.height(10.dp))
                            InputRow("API Base Url", googleBase) { googleBase = it }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_vertex_ai_location_label), googleLocation) { googleLocation = it }
                            Spacer(Modifier.height(10.dp))
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_vertex_ai_project_id_label), googleProject) { googleProject = it }
                            Spacer(Modifier.height(10.dp))
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_vertex_ai_service_account_json_label), googleSaJson, minLines = 3) { googleSaJson = it }
                        }
                    }
                    else -> {
                        SwitchRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_enabled_label), claudeEnabled) { claudeEnabled = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_name_label), claudeName) { claudeName = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow("API Key", claudeKey) { claudeKey = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow("API Base Url", claudeBase) { claudeBase = it }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Primary add button (full width, primary fill).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(cs.primary, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .clickable(onClick = ::onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_add_button),
                        style = MaterialTheme.typography.labelLarge.copy(color = cs.onPrimary, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/** Segmented tab bar (kelivo _SegTabBar): 44dp shell, 4dp inset, r18 pills. */
@Composable
internal fun SegTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(44.dp)
            .background(
                if (semantic.isDark) semantic.surfaceFill else semantic.surfaceCard,
                RoundedCornerShape(MemoRadius.CARD_DP.dp),
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = selected == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(
                        if (isSelected) cs.primary.copy(alpha = 0.14f) else Color.Transparent,
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = if (isSelected) cs.primary else cs.onSurface.copy(alpha = 0.82f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, value: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IosSwitch(value = value, onValueChanged = onChanged)
    }
}

@Composable
internal fun InputRow(
    label: String,
    value: String,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
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
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceCard,
                unfocusedContainerColor = semantic.surfaceCard,
                focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth().testTag(PROVIDER_INPUT_FIELD_TAG),
        )
    }
}

// -------------------------------------------------------------- Share sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareProviderSheet(
    providerKey: String,
    config: ProviderConfig,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val code = remember(config) { encodeProviderConfig(config) }
    val copiedMessage = stringResource(com.psyche.memo.ui.R.string.share_provider_sheet_copied_message)

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.share_provider_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(com.psyche.memo.ui.R.string.share_provider_sheet_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            // QR: white card for scannability (kelivo hardcodes white).
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(Color.White, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .border(1.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(10.dp),
            ) {
                QrCodeView(
                    data = code,
                    size = 180.dp,
                    darkColor = android.graphics.Color.BLACK,
                    lightColor = android.graphics.Color.WHITE,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IosTileButtonCompact(
                    icon = Lucide.Copy,
                    label = stringResource(com.psyche.memo.ui.R.string.share_provider_sheet_copy_button),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("provider", code))
                        SnackbarManager.show(
                            AppNotification(message = copiedMessage, type = NotificationType.SUCCESS),
                        )
                    },
                )
                IosTileButtonCompact(
                    icon = Lucide.Share2,
                    label = stringResource(com.psyche.memo.ui.R.string.share_provider_sheet_share_button),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, code)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                )
            }
        }
    }
}

/**
 * 多选导出面板 —— 1:1 移植 `_showMultiExportSheet`（providers_page.dart
 * L1528-1691）：标题带数量、选中 ≤4 个时给二维码（白卡保证可扫）、代码预览
 * 限高 128dp（7 行省略）、底部 复制 / 分享 两钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiProviderExportSheet(
    entries: List<Pair<String, String>>, // name to code
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val text = remember(entries) { entries.joinToString("\n") { it.second } }
    val copiedMessage = stringResource(com.psyche.memo.ui.R.string.providers_page_export_copied_snackbar)

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semanticOverlaySurface(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        com.psyche.memo.ui.R.string.providers_page_export_selected_title,
                        entries.size,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (entries.size <= 4) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .border(1.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .padding(10.dp),
                    ) {
                        QrCodeView(
                            data = text,
                            size = 180.dp,
                            darkColor = android.graphics.Color.BLACK,
                            lightColor = android.graphics.Color.WHITE,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Box(modifier = Modifier.fillMaxWidth().height(128.dp)) {
                Text(
                    text = text,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IosTileButtonCompact(
                    icon = Lucide.Copy,
                    label = stringResource(com.psyche.memo.ui.R.string.providers_page_export_copy_button),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("providers", text))
                        SnackbarManager.show(
                            AppNotification(message = copiedMessage, type = NotificationType.SUCCESS),
                        )
                    },
                )
                IosTileButtonCompact(
                    icon = Lucide.Share2,
                    label = stringResource(com.psyche.memo.ui.R.string.providers_page_export_share_button),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                )
            }
        }
    }
}

/** sheet 底色统一取 overlaySurface（本文件内多处复用）。 */
@Composable
private fun semanticOverlaySurface(): Color = LocalSemanticColors.current.overlaySurface(MaterialTheme.colorScheme)

/** ZXing BitMatrix drawn on a Canvas (kelivo uses pretty_qr; same output). */
@Composable
fun QrCodeView(data: String, size: Dp, darkColor: Int, lightColor: Int) {
    val matrix = remember(data) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
    val dark = androidx.compose.ui.graphics.Color(darkColor)
    val light = androidx.compose.ui.graphics.Color(lightColor)
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val n = matrix.width
        val cell = this.size.width / n
        drawRect(light)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = dark,
                        topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** Compact tile button used by the share sheet (kelivo IosTileButton). */
@Composable
internal fun IosTileButtonCompact(
    icon: ImageVectorAlias,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(
        modifier = modifier
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(color = cs.onSurface),
        )
    }
}

typealias ImageVectorAlias = androidx.compose.ui.graphics.vector.ImageVector
// -------------------------------------------------------------- API 路径 combobox

/**
 * 端点三选一（**用户 2026-09-12 点名**：把原来的「Response API 开关 + 手填
 * 服务器路径」合成一个选择项 —— 选了 Responses 就等于原来的开关打开）。
 * 标签沿用用户给的英文写法（与 MCP 传输选择的 "Streamable HTTP"/"SSE" 同例）。
 */
internal data class ApiPathOption(val label: String, val path: String)

internal val API_PATH_OPTIONS: List<ApiPathOption> = listOf(
    ApiPathOption("Anthropic Messages", "/v1/messages"),
    ApiPathOption("Chat Completions", "/chat/completions"),
    ApiPathOption("Responses", "/responses"),
)

/** 当前配置对应的选项；自定义路径（老数据/手填）回落成原样显示。 */
internal fun apiPathOptionFor(chatPath: String?, useResponseApi: Boolean?): ApiPathOption {
    val path = chatPath?.trim().orEmpty().ifEmpty {
        if (useResponseApi == true) "/responses" else "/chat/completions"
    }
    return API_PATH_OPTIONS.firstOrNull { it.path == path } ?: ApiPathOption(path, path)
}

/** `useResponseApi` 与路径保持同步（模型检测/导入都读这个标记）。 */
internal fun useResponseApiFor(path: String): Boolean? =
    if (path == "/responses") true else null

/** 只读字段外壳的测试标记（锁「与同屏输入框同高」）。 */
internal const val API_PATH_FIELD_TAG = "apiPathField"

/** 添加页输入框的测试标记（同上，用于比对两者几何一致）。 */
internal const val PROVIDER_INPUT_FIELD_TAG = "providerInputField"

/**
 * API 路径选择器（**用户 2026-09-12 二次点名**：「把这个 api 路径还原样式吧，
 * 把这个 sheet 改成点击出来那个 combobox 来选择」）：
 *
 * - 外观与同屏的「名称 / API Base Url」输入框**完全一致**（用户三次点名：
 *   「这个api这个输入框大小样式和名称和baseURL这个样式不一样大小了 统一下嘛」）
 *   —— 直接复用同一个 `OutlinedTextField` 外壳（56dp 高、r12、surfaceCard 底、
 *   同一组边框色），只把输入关掉（`readOnly`）+ 盖一层透明点击层；
 * - 交互改成**锚定下拉**（点击字段正下方弹出菜单，即「combobox」），不再用
 *   底部 sheet；菜单项是「选项名 + 路径」，**选中项靠变色表示、不打勾**（用户
 *   2026-09-12：「这个conbox这个选择变色就是 不打勾 不然里面的内容要换行」）。
 *
 * [textStyle] 由调用方传同屏字段的正文样式（详情页 `LabeledInput` = bodyLarge，
 * 添加页 `InputRow` = bodyMedium），这样字段高度与字号都和邻居一致。
 */
@Composable
internal fun ApiPathField(
    label: String,
    chatPath: String?,
    useResponseApi: Boolean?,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onSelect: (ApiPathOption) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val current = apiPathOptionFor(chatPath, useResponseApi)
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                color = cs.onSurface.copy(alpha = 0.8f),
            ),
        )
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedTextField(
                value = current.path,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = textStyle,
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceCard,
                    unfocusedContainerColor = semantic.surfaceCard,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth().testTag(API_PATH_FIELD_TAG),
            )
            // 箭头画在字段**外面**（不用 `trailingIcon`）：图标槽会把字段撑高到
            // 68dp，而旁边的名称/baseUrl 是 56dp —— 用户看到的「大小不一样」。
            Icon(
                Lucide.ChevronDown,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(18.dp),
            )
            // 只读字段自己不接点击（只会聚焦），盖一层透明层来开菜单。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true },
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                containerColor = semantic.surfaceCard,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
            ) {
                API_PATH_OPTIONS.forEach { option ->
                    val selected = option.path == current.path
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.background(
                            if (selected) cs.primary.copy(alpha = 0.08f) else Color.Transparent,
                        ),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = option.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.sp,
                                        color = if (selected) cs.primary else cs.onSurface,
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = option.path,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        color = if (selected) {
                                            cs.primary
                                        } else {
                                            cs.onSurface.copy(alpha = 0.55f)
                                        },
                                    ),
                                )
                            }
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

