package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext as LocalContextAlias
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.repo.ProviderRepository
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import io.github.g00fy2.quickie.ScanQRCode
import io.github.g00fy2.quickie.content.QRContent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Import provider sheet — 1:1 port of kelivo import_provider_sheet.dart:
 * paste area accepting multiple `ai-provider:v1:` lines or a ChatBox JSON
 * export, camera QR scan (quickie = CameraX + MLKit bundled) and gallery
 * image decode (zxing). Imported providers are inserted at the top of the
 * saved order and confirmed with a count snackbar.
 */
private val importJson = Json { ignoreUnknownKeys = true }

internal data class ImportResult(val key: String, val cfg: ProviderConfig)

/** Decode one `ai-provider:v1:<b64>` string (kelivo _decodeSingle). */
internal fun decodeAiProvider(existing: Set<String>, s: String): ImportResult {
    val trimmed = s.trim()
    require(trimmed.startsWith("ai-provider:v1:")) { "Invalid prefix" }
    val jsonStr = String(
        android.util.Base64.decode(trimmed.removePrefix("ai-provider:v1:"), android.util.Base64.DEFAULT),
        Charsets.UTF_8,
    )
    val obj = importJson.parseToJsonElement(jsonStr).jsonObject
    fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull ?: ""
    val type = str("type")
    val name = str("name")
    val apiKey = str("apiKey")
    val baseUrl = str("baseUrl")
    return when (type) {
        // Flutter 的 wire 值就是 'google'（share_provider_sheet.dart L18-27）；
        // 早期版本我们误写过 "gemini"，解码一并接受，避免老分享码导入成 OpenAI。
        "google", "gemini" -> {
            val key = uniqueKey(existing, "Google", name.ifEmpty { "Google" })
            ImportResult(
                key,
                ProviderConfig(
                    id = key,
                    enabled = true,
                    name = name.ifEmpty { "Google" },
                    apiKey = apiKey,
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    providerType = "google",
                    vertexAI = false,
                    location = "",
                    projectId = "",
                ),
            )
        }
        "claude" -> {
            val key = uniqueKey(existing, "Claude", name.ifEmpty { "Claude" })
            ImportResult(
                key,
                ProviderConfig(
                    id = key,
                    enabled = true,
                    name = name.ifEmpty { "Claude" },
                    apiKey = apiKey,
                    baseUrl = if (baseUrl.isNotEmpty()) baseUrl else "https://api.anthropic.com/v1",
                    providerType = "claude",
                ),
            )
        }
        else -> {
            val key = uniqueKey(existing, "OpenAI", name.ifEmpty { "OpenAI" })
            ImportResult(
                key,
                ProviderConfig(
                    id = key,
                    enabled = true,
                    name = name.ifEmpty { "OpenAI" },
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifEmpty { "https://api.openai.com/v1" },
                    providerType = "openai",
                    chatPath = "/chat/completions",
                    useResponseApi = false,
                ),
            )
        }
    }
}

/** Decode a ChatBox JSON export (kelivo _decodeChatBoxJson: openai/google/claude). */
internal fun decodeChatBoxJson(existing: Set<String>, s: String): List<ImportResult> {
    val obj = importJson.parseToJsonElement(s).jsonObject
    val providers = (obj["providers"] as? JsonObject) ?: return emptyList()
    val out = ArrayList<ImportResult>()
    fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""

    (providers["openai"] as? JsonObject)?.let { o ->
        val apiKey = str(o, "apiKey")
        if (apiKey.isNotBlank()) {
            val name = str(o, "name").ifEmpty { "OpenAI" }
            val key = uniqueKey(existing + out.map { it.key }, "OpenAI", name)
            out.add(
                ImportResult(
                    key,
                    ProviderConfig(
                        id = key, enabled = true, name = name, apiKey = apiKey,
                        baseUrl = str(o, "baseUrl").ifEmpty { "https://api.openai.com/v1" },
                        providerType = "openai", chatPath = "/chat/completions", useResponseApi = false,
                    ),
                ),
            )
        }
    }
    // ChatBox 的 JSON 里 Gemini 那一段的键是 'gemini'（import_provider_sheet.dart L110）；
    // 兼容 'google' 以免漏掉别的导出器。
    ((providers["gemini"] ?: providers["google"]) as? JsonObject)?.let { o ->
        val apiKey = str(o, "apiKey")
        if (apiKey.isNotBlank()) {
            val name = str(o, "name").ifEmpty { "Google" }
            val key = uniqueKey(existing + out.map { it.key }, "Google", name)
            out.add(
                ImportResult(
                    key,
                    ProviderConfig(
                        id = key, enabled = true, name = name, apiKey = apiKey,
                        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                        providerType = "google", vertexAI = false, location = "", projectId = "",
                    ),
                ),
            )
        }
    }
    (providers["claude"] as? JsonObject)?.let { o ->
        val apiKey = str(o, "apiKey")
        if (apiKey.isNotBlank()) {
            val name = str(o, "name").ifEmpty { "Claude" }
            val key = uniqueKey(existing + out.map { it.key }, "Claude", name)
            out.add(
                ImportResult(
                    key,
                    ProviderConfig(
                        id = key, enabled = true, name = name, apiKey = apiKey,
                        baseUrl = str(o, "baseUrl").ifEmpty { "https://api.anthropic.com/v1" },
                        providerType = "claude",
                    ),
                ),
            )
        }
    }
    return out
}

/** Parse raw paste/scan text into import results (multi-line aware). */
internal fun decodeImportPayload(existing: Set<String>, raw: String): List<ImportResult> {
    val parts = raw.split(Regex("\r?\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    val out = ArrayList<ImportResult>()
    for (p in parts) {
        runCatching {
            if (p.startsWith("ai-provider:v1:")) {
                out.add(decodeAiProvider(existing + out.map { it.key }, p))
            } else if (p.startsWith("{")) {
                out.addAll(decodeChatBoxJson(existing + out.map { it.key }, p))
            }
        }
    }
    require(out.isNotEmpty()) { "No valid entries" }
    return out
}

/** Decode a QR from a gallery image via zxing (kelivo uses mobile_scanner.analyzeImage). */
internal fun decodeQrFromImage(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return@runCatching null
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(bitmap).text
    }.getOrNull()

/** Persists decoded providers and bumps each to the top of the order; returns count. */
internal fun performImport(container: AppContainerImpl, raw: String): Int {
    val repo = ProviderRepository(container.database.writableDatabase, container.preferenceRepository)
    val dao = com.psyche.memo.data.db.PayloadEntityDao(
        container.database.writableDatabase,
        "provider_rows",
        primaryKey = "provider_key",
    )
    val results = decodeImportPayload(dao.getAll().map { it.id }.toSet(), raw)
    for (r in results) {
        val sortOrder = dao.get(r.key)?.sortOrder ?: dao.nextSortOrder()
        dao.upsert(r.key, importJson.encodeToString(ProviderConfig.serializer(), r.cfg), sortOrder)
        // Insert at the top of the saved order (kelivo order.remove+insert(0)).
        val order = repo.order().filterNot { it == r.key }.toMutableList()
        order.add(0, r.key)
        repo.setOrder(order)
    }
    return results.size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportProviderSheet(
    container: AppContainerImpl,
    onImported: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var paste by remember { mutableStateOf("") }
    val importSuccessTemplate = stringResource(com.psyche.memo.ui.R.string.import_provider_sheet_import_success_message)
    val importFailedTemplate = stringResource(com.psyche.memo.ui.R.string.import_provider_sheet_import_failed_message)

    fun applyImport(raw: String) {
        val imported = try {
            performImport(container, raw)
        } catch (e: Exception) {
            SnackbarManager.show(
                AppNotification(
                    message = importFailedTemplate.format(e.message ?: "error"),
                    type = NotificationType.ERROR,
                ),
            )
            return
        }
        onImported()
        SnackbarManager.show(
            AppNotification(message = importSuccessTemplate.format(imported), type = NotificationType.SUCCESS),
        )
        onDismiss()
    }

    // Camera scan (quickie = CameraX + MLKit bundled, permission handled).
    val scanLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        val code = (result as? io.github.g00fy2.quickie.QRResult.QRSuccess)?.content?.rawValue
        if (!code.isNullOrBlank()) applyImport(code)
    }
    // Gallery pick + zxing decode.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val code = decodeQrFromImage(context, uri)
            if (code.isNullOrBlank()) {
                SnackbarManager.show(
                    AppNotification(
                        // 原版走同一条失败提示模板（import_provider_sheet.dart L431-434）。
                        message = importFailedTemplate.format("QR not detected"),
                        type = NotificationType.ERROR,
                    ),
                )
            } else {
                applyImport(code)
            }
        }
    }

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
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            // Header: camera (left) + centered title + gallery (right).
            Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .clickable { scanLauncher.launch(null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Camera,
                        contentDescription = stringResource(
                            com.psyche.memo.ui.R.string.import_provider_sheet_scan_qr_tooltip,
                        ),
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.import_provider_sheet_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .clickable {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Image,
                        contentDescription = stringResource(
                            com.psyche.memo.ui.R.string.import_provider_sheet_from_gallery_tooltip,
                        ),
                        tint = cs.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = paste,
                onValueChange = { paste = it },
                minLines = 4,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp),
                placeholder = {
                    // 原版把这段说明只当输入框 hint 用（import_provider_sheet.dart
                    // L507-544），不另起一行正文。
                    Text(
                        stringResource(com.psyche.memo.ui.R.string.import_provider_sheet_description),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.5.sp,
                            color = cs.onSurface.copy(alpha = 0.4f),
                        ),
                    )
                },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LocalSemanticColors.current.surfaceCard,
                    unfocusedContainerColor = LocalSemanticColors.current.surfaceCard,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IosTileButtonCompact(
                    icon = Lucide.X,
                    label = stringResource(com.psyche.memo.ui.R.string.import_provider_sheet_cancel_button),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                IosTileButtonCompact(
                    icon = Lucide.Import,
                    label = stringResource(com.psyche.memo.ui.R.string.import_provider_sheet_import_button),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val raw = paste.trim()
                        if (raw.isNotEmpty()) applyImport(raw)
                    },
                )
            }
        }
    }
}
