package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Provider detail — shell + config tab of kelivo provider_detail_page.dart:
 * AppBar (back / avatar+name / test · delete), a PageView keeping both tabs
 * alive, bottom Config/Models tab switch, and the config tab saving each
 * control immediately (no save button).
 */
private val detailJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
fun ProviderDetailScreen(
    container: AppContainerImpl,
    providerId: String,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dao = remember(container) {
        PayloadEntityDao(container.database.writableDatabase, "provider_rows", primaryKey = "provider_key")
    }

    var cfg by remember(providerId) {
        mutableStateOf(
            runCatching {
                dao.get(providerId)?.let { detailJson.decodeFromString(ProviderConfig.serializer(), it.payload) }
            }.getOrNull() ?: ProviderConfig(id = providerId, name = providerId),
        )
    }
    var showDelete by remember { mutableStateOf(false) }
    val deletedMessage = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_provider_deleted_snackbar)
    var showApiKey by remember { mutableStateOf(false) }

    // Immediate save (debounced 400ms) whenever the config changes.
    LaunchedEffect(providerId) {
        snapshotFlow { cfg }
            .debounce(400)
            .collect { latest ->
                dao.upsert(latest.id, detailJson.encodeToString(ProviderConfig.serializer(), latest), dao.get(latest.id)?.sortOrder ?: 0)
            }
    }

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    var tabIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { tabIndex = it }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- AppBar ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconActionButton(Lucide.ArrowLeft, cs.onSurface, "Back") { onBack() }
            Row(
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(24.dp)) {
                    ProviderAvatarSmall(
                        providerKey = providerId,
                        displayName = cfg.name.ifEmpty { providerId },
                        size = 24.dp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = cfg.name.ifEmpty { providerId },
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                    maxLines = 1,
                )
            }
            val testButtonLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_button)
            if (tabIndex == 0) {
                IconActionButton(Lucide.HeartPulse, cs.onSurface, testButtonLabel) {
                    Haptics.light(view)
                    // Connectivity test: covered with the models tab batch (#10).
                }
            } else {
                IconActionButton(Lucide.Trash2, cs.onSurface, stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_provider_title)) {
                    showDelete = true
                }
            }
        }

        // ---- Both tabs kept alive in the pager ----
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1,
        ) { page ->
            if (page == 0) {
                ConfigTab(
                    cfg = cfg,
                    onCfgChange = { cfg = it },
                )
            } else {
                ModelsTabPlaceholder(providerId = providerId)
            }
        }

        // ---- Bottom tab switch (_BottomTabs) ----
        BottomTabs(
            index = tabIndex,
            leftIcon = Lucide.Settings2,
            leftLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_config_tab),
            rightIcon = Lucide.Boxes,
            rightLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_models_tab),
            onSelect = { i ->
                tabIndex = i
                scope.launch { pagerState.animateScrollToPage(i) }
            },
        )
    }

    // ---- Delete confirmation: clear model refs, remove row, pop ----
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_provider_title)) },
            text = { Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_provider_content)) },
            confirmButton = {
                TextButton(onClick = {
                    dao.delete(providerId)
                    showDelete = false
                    SnackbarManager.show(
                        AppNotification(
                            message = deletedMessage,
                            type = NotificationType.SUCCESS,
                        ),
                    )
                    onBack()
                }) {
                    Text(
                        stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_button),
                        color = cs.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_cancel_button))
                }
            },
        )
    }
}

/** Config tab — settings card, credentials inputs; every edit saves instantly. */
@Composable
private fun ConfigTab(cfg: ProviderConfig, onCfgChange: (ProviderConfig) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var showApiKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Toggles card (kind-conditional rows in kelivo order).
        SettingsSectionCard {
            SettingsSwitchRow(
                icon = Lucide.Power,
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_enabled_title),
                value = cfg.enabled,
                onToggle = { onCfgChange(cfg.copy(enabled = it)) },
            )
            SettingsIosDivider()
            SettingsSwitchRow(
                icon = Lucide.KeyRound,
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_multi_key_mode_title),
                value = cfg.multiKeyEnabled == true,
                onToggle = { onCfgChange(cfg.copy(multiKeyEnabled = it)) },
            )
            if (cfg.classifiedKind() == "openai") {
                SettingsIosDivider()
                SettingsSwitchRow(
                    icon = Lucide.Zap,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_response_api_title),
                    value = cfg.useResponseApi == true,
                    onToggle = { onCfgChange(cfg.copy(useResponseApi = it)) },
                )
            }
            if (cfg.classifiedKind() == "gemini") {
                SettingsIosDivider()
                SettingsSwitchRow(
                    icon = Lucide.Cloud,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_vertex_ai_title),
                    value = cfg.vertexAI == true,
                    onToggle = { onCfgChange(cfg.copy(vertexAI = it)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Credentials.
        LabeledInput(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_name_label), cfg.name, false) {
            onCfgChange(cfg.copy(name = it))
        }
        Spacer(Modifier.height(12.dp))
        LabeledInput(
            label = "API Key",
            value = cfg.apiKey,
            obscure = !showApiKey,
            trailing = {
                Icon(
                    imageVector = if (showApiKey) Lucide.EyeOff else Lucide.Eye,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(40.dp)
                        .padding(8.dp)
                        .clickable { showApiKey = !showApiKey },
                )
            },
        ) {
            onCfgChange(cfg.copy(apiKey = it))
        }
        if (cfg.classifiedKind() != "gemini") {
            Spacer(Modifier.height(12.dp))
            LabeledInput(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_api_base_url_label), cfg.baseUrl, false) {
                onCfgChange(cfg.copy(baseUrl = it))
            }
        }
        if (cfg.classifiedKind() == "openai" && cfg.useResponseApi != true) {
            Spacer(Modifier.height(12.dp))
            LabeledInput(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_api_path_label), cfg.chatPath ?: "/chat/completions", false) {
                onCfgChange(cfg.copy(chatPath = it))
            }
        }
        if (cfg.classifiedKind() == "gemini" && cfg.vertexAI == true) {
            Spacer(Modifier.height(12.dp))
            LabeledInput("位置", cfg.location ?: "", false) {
                onCfgChange(cfg.copy(location = it))
            }
            Spacer(Modifier.height(12.dp))
            LabeledInput("项目ID", cfg.projectId ?: "", false) {
                onCfgChange(cfg.copy(projectId = it))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Labeled input with 13dp label + r12 filled field (provider_detail _inputRow). */
@Composable
private fun LabeledInput(
    label: String,
    value: String,
    obscure: Boolean,
    trailing: (@Composable () -> Unit)? = null,
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
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = trailing,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceCard,
                unfocusedContainerColor = semantic.surfaceCard,
                focusedIndicatorColor = cs.primary.copy(alpha = 0.5f),
                unfocusedIndicatorColor = cs.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Bottom config/models tab switch (provider_detail _BottomTabs). */
@Composable
private fun BottomTabs(
    index: Int,
    leftIcon: ImageVector,
    leftLabel: String,
    rightIcon: ImageVector,
    rightLabel: String,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .background(semantic.surfaceCard, RoundedCornerShape(16.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .navigationBarsPadding()
            .padding(4.dp),
    ) {
        listOf(leftIcon to leftLabel, rightIcon to rightLabel).forEachIndexed { i, (icon, label) ->
            val selected = index == i
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.6f),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/** Models tab placeholder — full model management lands with #10. */
@Composable
private fun ModelsTabPlaceholder(providerId: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = providerId,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
