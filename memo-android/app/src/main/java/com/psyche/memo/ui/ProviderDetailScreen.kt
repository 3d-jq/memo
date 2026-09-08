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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Database
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
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    onOpenGroups: () -> Unit,
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
    var showCustomRequest by remember { mutableStateOf(false) }
    var showNetwork by remember { mutableStateOf(false) }
    var showMultiKey by remember { mutableStateOf(false) }
    var showBalance by remember { mutableStateOf(false) }
    var testModel by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val deletedMessage = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_provider_deleted_snackbar)
    val testOkTemplate = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_success_message)
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
                    val model = cfg.models.firstOrNull()
                    if (model == null) {
                        testResult = false to "No models configured"
                    } else {
                        scope.launch {
                            val client = container.clientFor(cfg.classifiedKind())
                            val ok = runCatching {
                                client.complete(
                                    com.psyche.memo.llm.client.LlmRequest(
                                        providerId = cfg.id,
                                        modelId = model,
                                        messages = listOf(com.psyche.memo.llm.client.LlmMessage(role = "user", content = "hi")),
                                        apiKey = cfg.apiKey,
                                        baseUrl = container.baseUrlFor(cfg.id),
                                        chatPath = cfg.chatPath,
                                    ),
                                )
                            }
                            testResult = if (ok.isSuccess) true to model
                            else false to (ok.exceptionOrNull()?.message ?: "error")
                        }
                    }
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
                    container = container,
                    providerId = providerId,
                    onCfgChange = { cfg = it },
                    onOpenCustomRequest = { showCustomRequest = true },
                    onOpenNetwork = { showNetwork = true },
                    onOpenMultiKey = { showMultiKey = true },
                    onOpenBalance = { showBalance = true },
                    onOpenGroups = onOpenGroups,
                )
            } else {
                ModelsTab(
                    cfg = cfg,
                    container = container,
                    onCfgChange = { cfg = it },
                    onTestModel = { testModel = it },
                    onReload = {
                        // Reload from provider_rows so a detail-sheet save
                        // (which writes the DB directly) is reflected here.
                        scope.launch(Dispatchers.IO) {
                            val fresh = dao.get(providerId)?.let {
                                detailJson.decodeFromString(ProviderConfig.serializer(), it.payload)
                            }
                            withContext(Dispatchers.Main) { if (fresh != null) cfg = fresh }
                        }
                    },
                )
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

    testResult?.let { (ok, detail) ->
        LaunchedEffect(ok, detail) {
            SnackbarManager.show(
                AppNotification(
                    message = if (ok) testOkTemplate + " ($detail)" else detail,
                    type = if (ok) NotificationType.SUCCESS else NotificationType.ERROR,
                ),
            )
            testResult = null
        }
    }

    // ---- #11 sub-pages ----
    if (showCustomRequest) {
        ProviderCustomRequestPage(
            container = container,
            providerId = providerId,
            onBack = { showCustomRequest = false },
        )
    }
    if (showNetwork) {
        ProviderNetworkPage(
            container = container,
            providerId = providerId,
            onBack = { showNetwork = false },
        )
    }
    if (showMultiKey) {
        MultiKeyManagerScreen(
            container = container,
            cfg = cfg,
            onCfgChange = { cfg = it },
            onBack = { showMultiKey = false },
        )
    }
    if (showBalance) {
        BalanceScreen(
            container = container,
            cfg = cfg,
            onCfgChange = { cfg = it },
            onBack = { showBalance = false },
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
private fun ConfigTab(
    cfg: ProviderConfig,
    container: AppContainerImpl,
    providerId: String,
    onCfgChange: (ProviderConfig) -> Unit,
    onOpenCustomRequest: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenMultiKey: () -> Unit,
    onOpenBalance: () -> Unit,
    onOpenGroups: () -> Unit,
) {
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
        val kind = cfg.classifiedKind()
        var showKindSheet by remember { mutableStateOf(false) }
        var showGroupSheet by remember { mutableStateOf(false) }
        SettingsSectionCard {
            // Provider kind row (Gemini/Claude/OpenAI) with selection sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showKindSheet = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_provider_type_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when (kind) {
                        "gemini" -> "Gemini"
                        "anthropic" -> "Claude"
                        else -> "OpenAI"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        color = cs.onSurface.copy(alpha = 0.6f),
                    ),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
            }
            SettingsIosDivider()
            // Group row (opens the group picker sheet).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showGroupSheet = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_groups_picker_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
            }
            SettingsIosDivider()
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
            if (cfg.multiKeyEnabled == true) {
                SettingsIosDivider()
                NavRow(label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_manage_keys_button)) {
                    onOpenMultiKey()
                }
            }
            if (cfg.classifiedKind() == "openai") {
                SettingsIosDivider()
                SettingsSwitchRow(
                    icon = Lucide.Zap,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_response_api_title),
                    value = cfg.useResponseApi == true,
                    onToggle = { onCfgChange(cfg.copy(useResponseApi = it)) },
                )
                SettingsIosDivider()
                // Balance row: label + live badge (max 108dp) when enabled +
                // chevron — provider_detail_page.dart L1850-1899.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBalance() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_balance_title),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (cfg.balanceEnabled == true) {
                        ProviderBalanceBadge(
                            cfg = cfg,
                            container = container,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = cs.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
                }
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
            if (cfg.classifiedKind() == "anthropic") {
                SettingsIosDivider()
                SettingsSwitchRow(
                    icon = Lucide.Database,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_title),
                    value = cfg.claudePromptCachingEnabled,
                    onToggle = { onCfgChange(cfg.copy(claudePromptCachingEnabled = it)) },
                )
                if (cfg.claudePromptCachingEnabled) {
                    SettingsIosDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCfgChange(
                                    cfg.copy(
                                        claudePromptCachingTtl = if (cfg.claudePromptCachingTtl == "1h") "5m" else "1h",
                                    ),
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_ttl_title),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = cfg.claudePromptCachingTtl,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                color = cs.onSurface.copy(alpha = 0.6f),
                            ),
                        )
                    }
                }
            }
            // Custom request + network proxy entries (#11 sub-pages).
            SettingsIosDivider()
            NavRow(label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_custom_request_title)) {
                onOpenCustomRequest()
            }
            SettingsIosDivider()
            NavRow(label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_network_tab)) {
                onOpenNetwork()
            }
        }

        // Kind selection sheet + group picker.
        if (showKindSheet) {
            ProviderKindSheet(
                current = kind,
                onSelect = { picked ->
                    onCfgChange(cfg.copy(providerType = picked))
                    showKindSheet = false
                },
                onDismiss = { showKindSheet = false },
            )
        }
        if (showGroupSheet) {
            ProviderGroupPickerSheet(
                container = container,
                providerKey = providerId,
                onDismiss = { showGroupSheet = false },
                onOpenManager = {
                    showGroupSheet = false
                    onOpenGroups()
                },
            )
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
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (obscure) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = trailing,
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

/** 48dp chevron nav row inside a settings card (kelivo _TactileRow variant). */
@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            modifier = Modifier.weight(1f),
        )
        Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
    }
}

/** Provider kind selection sheet (Gemini/Claude/OpenAI) - _showProviderKindSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderKindSheet(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
            )
            Spacer(Modifier.height(12.dp))
            listOf("Gemini" to "gemini", "Claude" to "anthropic", "OpenAI" to "openai").forEach { (label, kind) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(kind) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (current == kind) cs.primary else cs.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (current == kind) {
                        Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Models tab — list + fetch/add/delete/reorder + connection test (#10). */
@Composable
private fun ModelsTab(
    cfg: ProviderConfig,
    container: AppContainerImpl,
    onCfgChange: (ProviderConfig) -> Unit,
    onTestModel: (String) -> Unit,
    onReload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var showCreate by remember { mutableStateOf(false) }
    var detailModel by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    val models = cfg.models
    val modelDeletedMessage = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_model_deleted_snackbar)

    fun saveModels(next: List<String>) {
        onCfgChange(cfg.copy(models = next))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (models.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_no_models_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_no_models_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        } else {
            ReorderableColumn(
                items = models,
                keyOf = { it },
                onMove = { from, to ->
                    val next = models.toMutableList()
                    val moved = next.removeAt(from)
                    next.add(to, moved)
                    saveModels(next)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(semantic.surfaceCard, RoundedCornerShape(12.dp))
                    .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            ) { model, isDragging ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTestModel(model) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Lucide.Settings2,
                        contentDescription = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_edit_tooltip),
                        tint = cs.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(34.dp)
                            .padding(7.dp)
                            .clickable { detailModel = model },
                    )
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_model_button),
                        tint = cs.error,
                        modifier = Modifier
                            .size(34.dp)
                            .padding(7.dp)
                            .clickable {
                                saveModels(models - model)
                                SnackbarManager.show(
                                    AppNotification(message = modelDeletedMessage, type = NotificationType.SUCCESS),
                                )
                            },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(12.dp))
                    .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !fetching) { fetching = true },
                contentAlignment = Alignment.Center,
            ) {
                if (fetching) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_fetch_models_button),
                        style = MaterialTheme.typography.labelLarge.copy(color = cs.primary),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(cs.primary, RoundedCornerShape(12.dp))
                    .clickable { showCreate = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_add_new_model_button),
                    style = MaterialTheme.typography.labelLarge.copy(color = cs.onPrimary, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }

    // Fetch executes against the provider's /models endpoint (core:llm).
    LaunchedEffect(fetching) {
        if (!fetching) return@LaunchedEffect
        val client = container.clientFor(cfg.classifiedKind())
        val fetched = runCatching {
            client.listModels(container.baseUrlFor(cfg.id), cfg.apiKey)
        }.getOrNull()
        val ids = fetched?.map { it.id }.orEmpty()
        val merged = (models + ids.filter { it !in models }).distinct()
        if (merged.size > models.size) saveModels(merged)
        fetching = false
    }

    if (showCreate) {
        // provider_detail_page L2476-2483: add button -> showCreateModelSheet.
        ModelDetailSheet(
            container = container,
            providerKey = cfg.id,
            modelId = "",
            isNew = true,
            onDismiss = { saved ->
                showCreate = false
                if (saved) onReload()
            },
        )
    }
    detailModel?.let { modelId ->
        // provider_detail_page L4144-4152: edit button -> showModelDetailSheet.
        ModelDetailSheet(
            container = container,
            providerKey = cfg.id,
            modelId = modelId,
            onDismiss = { saved ->
                detailModel = null
                if (saved) onReload()
            },
        )
    }
}
