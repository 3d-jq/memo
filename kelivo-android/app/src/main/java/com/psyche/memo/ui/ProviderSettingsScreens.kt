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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.R as UiR
import kotlinx.serialization.json.Json

/** provider_rows 读写（AppContainer 的 providerConfig 只读单个，这里补列表/写入）。 */
private fun dao(container: AppContainerImpl) =
    PayloadEntityDao(container.database.readableDatabase, "provider_rows", primaryKey = "provider_key")

private val providerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun loadProviders(container: AppContainerImpl): List<Pair<String, ProviderConfig>> =
    dao(container).getAll().mapNotNull { row ->
        runCatching {
            ProviderConfig.fromJsonString(providerJson, row.payload)
        }.getOrNull()?.let { row.id to it }
    }

internal fun saveProvider(container: AppContainerImpl, id: String, config: ProviderConfig) {
    dao(container).upsert(
        id,
        providerJson.encodeToString(ProviderConfig.serializer(), config),
        sortOrder = System.currentTimeMillis().toInt(),
    )
}

/** 供应商列表（providers_page.dart 核心：卡片 + 添加）。 */
@Composable
fun ProvidersScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenProvider: (String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var providers by remember { mutableStateOf(loadProviders(container)) }
    LaunchedEffect(Unit) { providers = loadProviders(container) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(UiR.string.settings_page_providers),
                modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            IconButton(onClick = { onOpenProvider(null) }, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.Plus, contentDescription = stringResource(UiR.string.providers_page_add_tooltip), tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(providers.size) { i ->
                val (id, config) = providers[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProvider(id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(cs.primary.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = config.name.firstOrNull()?.toString() ?: "?",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = config.name.ifEmpty { id },
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                        Text(
                            text = "${config.models.size} models · ${config.baseUrl}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                        )
                    }
                    Text(
                        text = "${i + 1}",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.4f)),
                    )
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 供应商编辑页（provider_detail_page.dart 核心：名称/Host/Key/模型列表）。 */
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
    var models by remember { mutableStateOf(listOf<String>()) }
    var newModel by remember { mutableStateOf("") }

    LaunchedEffect(providerId) {
        if (providerId != null) {
            val cfg = loadProviders(container).firstOrNull { it.first == providerId }?.second ?: return@LaunchedEffect
            name = cfg.name
            baseUrl = cfg.baseUrl
            apiKey = cfg.apiKey
            models = cfg.models
        }
    }

    fun save() {
        if (name.isBlank()) return
        saveProvider(
            container,
            id,
            ProviderConfig(id = id, enabled = true, name = name.trim(), apiKey = apiKey.trim(), baseUrl = baseUrl.trim(), models = models),
        )
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = if (isNew) stringResource(UiR.string.providers_page_add_tooltip)
                else stringResource(UiR.string.settings_page_providers),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { save() }, modifier = Modifier.padding(end = 12.dp)) {
                Text(text = stringResource(UiR.string.settings_page_save))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                LabeledField(stringResource(UiR.string.providers_page_name_label), name, { name = it })
                LabeledField(stringResource(UiR.string.providers_page_host_label), baseUrl, { baseUrl = it })
                LabeledField(stringResource(UiR.string.providers_page_api_key_label), apiKey, { apiKey = it }, obscure = true)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(UiR.string.default_model_page_models_section_title),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                    )
                }
                models.forEachIndexed { i, m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = m, modifier = Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, color = cs.onSurface))
                        IconButton(onClick = { models = models.filterIndexed { j, _ -> j != i } }, modifier = Modifier.size(32.dp)) {
                            Icon(Lucide.Trash2, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
                        }
                    }
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
                        ),
                    )
                    IconButton(onClick = {
                        if (newModel.isNotBlank()) {
                            models = models + newModel.trim()
                            newModel = ""
                        }
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Lucide.Plus, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
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
            visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.3f),
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
            ),
        )
    }
}
