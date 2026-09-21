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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.serialization.json.Json

private fun providerDao(container: AppContainerImpl) =
    PayloadEntityDao(container.database.readableDatabase, "provider_rows", primaryKey = "provider_key")

private val providerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun loadProviders(container: AppContainerImpl): List<Pair<String, ProviderConfig>> =
    providerDao(container).getAll().mapNotNull { row ->
        runCatching {
            ProviderConfig.fromJsonString(providerJson, row.payload)
        }.getOrNull()?.let { row.id to it }
    }

internal fun saveProvider(container: AppContainerImpl, id: String, config: ProviderConfig) {
    val dao = providerDao(container)
    // New rows take max+1; a truncated timestamp could go negative and violate
    // the schema CHECK(sort_order >= 0).
    val existing = dao.get(id)
    val sortOrder = existing?.sortOrder ?: dao.nextSortOrder()
    dao.upsert(
        id,
        providerJson.encodeToString(ProviderConfig.serializer(), config),
        sortOrder = sortOrder,
    )
}

@Composable
fun ProviderEditScreen(
    container: AppContainerImpl,
    providerId: String?,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isNew = providerId == null
    var id by remember { mutableStateOf(providerId ?: "provider_${System.currentTimeMillis()}") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var models by remember { mutableStateOf(listOf<String>()) }
    var newModel by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    // 删除某一行的动作只造一次（按 **下标** 删，与原来逐字一致：模型名可以重复，
    // 所以既不能用 name 当删除键，也不能给行加 name 的 key —— 会撞 key）。
    val onRemoveModel: (Int) -> Unit = remember {
        { index -> models = models.filterIndexed { j, _ -> j != index } }
    }

    LaunchedEffect(providerId) {
        if (providerId != null) {
            // 读库走 IO（原来是 LaunchedEffect 主线程体里直接查 provider_rows）。
            val cfg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadProviders(container).firstOrNull { it.first == providerId }?.second
            }
            if (cfg != null) {
                name = cfg.name
                baseUrl = cfg.baseUrl
                apiKey = cfg.apiKey
                enabled = cfg.enabled
                models = cfg.models
            }
        }
    }

    fun save() {
        if (name.isBlank()) return
        saveProvider(
            container,
            id,
            ProviderConfig(id = id, enabled = enabled, name = name.trim(), apiKey = apiKey.trim(), baseUrl = baseUrl.trim(), models = models),
        )
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
                MemoTopBar(
                    title = if (isNew) stringResource(UiR.string.providers_page_add_tooltip) else stringResource(UiR.string.settings_page_providers),
                    onBack = onBack,
                ) {
                    if (!isNew) {
                        IconButton(onClick = { showDelete = true }, modifier = Modifier.size(44.dp)) {
                            Icon(Lucide.Trash2, contentDescription = null, tint = cs.error, modifier = Modifier.size(22.dp))
                        }
                    }
                    Button(onClick = { save() }, modifier = Modifier.padding(end = 12.dp)) {
                        Text(text = stringResource(UiR.string.provider_detail_page_save_button))
                    }
                }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                SettingsSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(UiR.string.provider_detail_page_enabled_title),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                        )
                        IosSwitch(value = enabled, onValueChanged = { enabled = it })
                    }
                }
            }
            item {
                SettingsSectionCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        LabeledField(stringResource(UiR.string.provider_detail_page_name_label), name, { name = it })
                        LabeledField(stringResource(UiR.string.provider_detail_page_api_base_url_label), baseUrl, { baseUrl = it })
                        LabeledField(stringResource(UiR.string.multi_key_page_key), apiKey, { apiKey = it }, obscure = true)
                    }
                }
            }
            item {
                SettingsSectionCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = stringResource(UiR.string.provider_detail_page_models_title),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                        )
                        models.forEachIndexed { i, m ->
                            ProviderModelNameRow(index = i, name = m, onRemove = onRemoveModel)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newModel,
                                onValueChange = { newModel = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text(stringResource(UiR.string.auto_retry_add_hint), style = TextStyle(fontSize = 13.sp)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = cs.primary,
                                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.3f),
                                    focusedTextColor = cs.onSurface,
                                    unfocusedTextColor = cs.onSurface,
                                ),
                            )
                            IconButton(
                                onClick = {
                                    if (newModel.isNotBlank()) {
                                        models = models + newModel.trim()
                                        newModel = ""
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Lucide.Plus, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { showDelete = false },
            title = { Text(stringResource(UiR.string.provider_detail_page_delete_provider_title)) },
            text = { Text(stringResource(UiR.string.providers_page_delete_selected_confirm_content)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    providerDao(container).delete(id)
                    onBack()
                }) { Text(stringResource(UiR.string.chat_history_page_delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(UiR.string.chat_history_page_cancel))
                }
            },
        )
    }
}

/**
 * 模型列表里的一行（名字 + 删除钮）。
 *
 * 单独成一个 composable 是为了**可跳过**：三个实参全是稳定类型（Int/String 与一个
 * `remember` 住的删除动作）。以前这一行的 Row 直接摊在卡片里，`newModel` 输入框
 * 每敲一个字，整个 item 重跑 ⇒ 几十个模型行全重组合。
 */
@Composable
private fun ProviderModelNameRow(
    index: Int,
    name: String,
    onRemove: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
        )
        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.Trash2, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit, obscure: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.3f),
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
            ),
        )
    }
}
