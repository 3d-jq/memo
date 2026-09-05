package com.psyche.memo.ui

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
    providerDao(container).upsert(
        id,
        providerJson.encodeToString(ProviderConfig.serializer(), config),
        sortOrder = System.currentTimeMillis().toInt(),
    )
}

@Composable
fun ProvidersScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenProvider: (String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = LocalSemanticColors.current.isDark
    var providers by remember { mutableStateOf(loadProviders(container)) }
    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // ensureProviderConfig semantics: create each built-in provider
        // config if it doesn't exist yet (settings_provider.dart L1556-1567).
        val builtIn = listOf(
            "openai" to ("OpenAI" to "https://api.openai.com/v1"),
            "siliconflow" to ("SiliconFlow" to "https://api.siliconflow.cn/v1"),
            "gemini" to ("Gemini" to "https://generativelanguage.googleapis.com/v1beta"),
            "openrouter" to ("OpenRouter" to "https://openrouter.ai/api/v1"),
            "memoin" to ("MemoIN" to "https://text.pollinations.ai/openai"),
            "tensdaq" to ("Tensdaq" to "https://tensdaq-api.x-aio.com/v1"),
            "deepseek" to ("DeepSeek" to "https://api.deepseek.com/v1"),
            "aihubmix" to ("AIhubmix" to "https://aihubmix.com/v1"),
            "随想ai中转站" to ("随想AI中转站" to "https://sui-xiang.com/v1"),
            "marucode" to ("MaruCode" to "https://api.muteki.site/v1"),
            "aliyun" to ("Aliyun" to "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            "zhipu ai" to ("Zhipu AI" to "https://open.bigmodel.cn/api/paas/v4"),
            "claude" to ("Claude" to "https://api.anthropic.com/v1"),
            "grok" to ("Grok" to "https://api.x.ai/v1"),
            "bytedance" to ("ByteDance" to "https://ark.cn-beijing.volces.com/api/v3"),
        )
        val existingKeys = loadProviders(container).map { it.first }.toSet()
        builtIn.forEach { (key, pair) ->
            if (key !in existingKeys) {
                saveProvider(container, key, ProviderConfig(id = key, enabled = true, name = pair.first, baseUrl = pair.second))
            }
        }
        providers = loadProviders(container)
    }

    val filtered = if (searchQuery.isBlank()) providers
    else providers.filter { it.second.name.lowercase().contains(searchQuery.lowercase()) }

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
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = {
                Text(
                    text = stringResource(UiR.string.providers_page_search_hint),
                    style = TextStyle(fontSize = 13.5.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                Icon(Lucide.Search, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                        Icon(Lucide.X, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
                cursorColor = cs.primary,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(cs.surfaceCardColorCompat(), RoundedCornerShape(12.dp))
                .border(1.dp, cs.outlineVariant.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                filtered.forEachIndexed { i, (id, config) ->
                    val isEnabled = config.enabled
                    val pillBg = if (isEnabled) Color(0xFF4CAF50).copy(alpha = 0.12f) else Color(0xFFFF9800).copy(alpha = 0.15f)
                    val pillFg = if (isEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    val iconModel = BrandAssets.assetForName(config.name.ifEmpty { id })
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenProvider(id) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (iconModel != null) {
                                AsyncImage(
                                    model = iconModel,
                                    contentDescription = null,
                                    // provider_avatar.dart L169-186: monochrome logos are
                                    // tinted onSurface in dark theme, else they vanish.
                                    colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(iconModel)) {
                                        ColorFilter.tint(cs.onSurface, BlendMode.SrcIn)
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.size(22.dp),
                                )
                            } else {
                                Text(
                                    text = (config.name.ifEmpty { id }).firstOrNull()?.toString() ?: "?",
                                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = config.name.ifEmpty { id },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(pillBg, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (isEnabled) UiR.string.providers_page_enabled_status
                                    else UiR.string.providers_page_disabled_status,
                                ),
                                style = TextStyle(fontSize = 11.sp, color = pillFg),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                    }
                    if (i != filtered.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 54.dp, end = 12.dp)
                                .height(0.6.dp)
                                .background(cs.outlineVariant.copy(alpha = 0.18f)),
                        )
                    }
                }
            }
        }
    }
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

    LaunchedEffect(providerId) {
        if (providerId != null) {
            loadProviders(container).firstOrNull { it.first == providerId }?.second?.let { cfg ->
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = if (isNew) stringResource(UiR.string.providers_page_add_tooltip)
                else stringResource(UiR.string.settings_page_providers),
                modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = m,
                                    modifier = Modifier.weight(1f),
                                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                                )
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
