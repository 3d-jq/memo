package com.psyche.memo.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Check
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
import com.psyche.memo.data.model.ProviderGroup
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
        put("type", kind)
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
    var openaiUseResponse by remember { mutableStateOf(false) }
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
                    chatPath = if (openaiUseResponse) null
                    else openaiPath.trim().ifEmpty { "/chat/completions" },
                    useResponseApi = if (openaiUseResponse) true else null,
                )
            }
            1 -> {
                val display = googleName.trim().ifEmpty { "Google" }
                keyName = uniqueKey(existing, "Gemini", display)
                cfg = ProviderConfig(
                    id = keyName,
                    enabled = googleEnabled,
                    name = display,
                    apiKey = googleKey.trim(),
                    baseUrl = googleBase.trim(),
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
                    baseUrl = claudeBase.trim(),
                    providerType = "claude",
                )
            }
        }
        val repo = ProviderRepository(container.database.writableDatabase, container.preferenceRepository)
        val sortOrder = dao.get(cfg.id)?.sortOrder ?: dao.nextSortOrder()
        dao.upsert(cfg.id, sheetJson.encodeToString(ProviderConfig.serializer(), cfg), sortOrder)
        // Register in the saved order so it slots after the builtins.
        val order = (repo.order() + listOf(cfg.id)).distinct()
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
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            // Header: X left + centered 18sp semibold title.
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(36.dp),
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
                        SwitchRow("Response API", openaiUseResponse) { openaiUseResponse = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_name_label), openaiName) { openaiName = it }
                        InputRow("API Key", openaiKey) { openaiKey = it }
                        InputRow("API Base Url", openaiBase) { openaiBase = it }
                        if (!openaiUseResponse) {
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_api_path_label), openaiPath) { openaiPath = it }
                        }
                    }
                    1 -> {
                        SwitchRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_enabled_label), googleEnabled) { googleEnabled = it }
                        SwitchRow("Vertex AI", googleVertex) { googleVertex = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_name_label), googleName) { googleName = it }
                        if (!googleVertex) {
                            InputRow("API Key", googleKey) { googleKey = it }
                            InputRow("API Base Url", googleBase) { googleBase = it }
                        } else {
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_vertex_ai_location_label), googleLocation) { googleLocation = it }
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_vertex_ai_project_id_label), googleProject) { googleProject = it }
                            InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_vertex_ai_service_account_json_label), googleSaJson, minLines = 3) { googleSaJson = it }
                        }
                    }
                    else -> {
                        SwitchRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_enabled_label), claudeEnabled) { claudeEnabled = it }
                        Spacer(Modifier.height(10.dp))
                        InputRow(stringResource(com.psyche.memo.ui.R.string.add_provider_sheet_name_label), claudeName) { claudeName = it }
                        InputRow("API Key", claudeKey) { claudeKey = it }
                        InputRow("API Base Url", claudeBase) { claudeBase = it }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Primary add button (full width, primary fill).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(cs.primary, RoundedCornerShape(12.dp))
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
                RoundedCornerShape(18.dp),
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
                        RoundedCornerShape(14.dp),
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
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
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
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
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
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
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
            .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
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

// -------------------------------------------------------- Group picker sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderGroupPickerSheet(
    container: AppContainerImpl,
    providerKey: String,
    onDismiss: () -> Unit,
    onOpenManager: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val repo = remember(container) {
        ProviderRepository(container.database.writableDatabase, container.preferenceRepository)
    }
    var groups by remember { mutableStateOf(repo.groups()) }
    var current by remember { mutableStateOf(repo.groupFor(providerKey)) }
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_groups_picker_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { showCreate = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Plus, contentDescription = "New group", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onOpenManager() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Settings, contentDescription = "Manage groups", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // Ungrouped option + group rows (48dp, selected primary + check).
            GroupPickerTile(
                title = stringResource(com.psyche.memo.ui.R.string.provider_groups_other_ungrouped_option),
                selected = current == null,
                onClick = {
                    repo.setGroupFor(providerKey, null)
                    onDismiss()
                },
            )
            groups.forEach { g ->
                GroupPickerTile(
                    title = g.name,
                    selected = current == g.id,
                    onClick = {
                        repo.setGroupFor(providerKey, g.id)
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCreate) {
        CreateGroupDialog(
            onCreate = { name ->
                val id = "grp_${System.currentTimeMillis()}"
                repo.saveGroup(ProviderGroup(id = id, name = name, createdAt = System.currentTimeMillis()))
                repo.setGroupFor(providerKey, id)
                groups = repo.groups()
                current = id
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
}

@Composable
private fun GroupPickerTile(title: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(48.dp)
            .background(
                if (selected) cs.primary.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) cs.primary else cs.onSurface,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
    }
}

// -------------------------------------------------------------- API 路径 sheet

/**
 * 端点三选一（**用户 2026-09-12 点名**：把原来的「Response API 开关 + 手填
 * 服务器路径」合成一个选择面板 —— 选了 Responses 就等于原来的开关打开）。
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApiPathSheet(
    chatPath: String?,
    useResponseApi: Boolean?,
    onSelect: (ApiPathOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val current = apiPathOptionFor(chatPath, useResponseApi)
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            API_PATH_OPTIONS.forEach { option ->
                MemoSheetOptionRow(
                    label = option.label,
                    detail = option.path,
                    selected = option.path == current.path,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun CreateGroupDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.psyche.memo.ui.R.string.provider_groups_create_dialog_title)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(com.psyche.memo.ui.R.string.provider_groups_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) {
                Text(stringResource(com.psyche.memo.ui.R.string.provider_groups_create_dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.psyche.memo.ui.R.string.provider_groups_create_dialog_cancel))
            }
        },
    )
}
