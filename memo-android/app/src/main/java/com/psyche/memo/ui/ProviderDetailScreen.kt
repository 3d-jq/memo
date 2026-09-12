package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.BadgePercent
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.X
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

@OptIn(kotlinx.coroutines.FlowPreview::class)
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
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // Model selection mode, hoisted so the AppBar can drive it (L208-233).
    var modelSelectMode by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    // 供应商头像编辑（provider_detail_page L346-452 的五选一 sheet + 三个子弹窗）。
    var showAvatarSheet by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showLobehubDialog by remember { mutableStateOf(false) }
    var showAvatarUrlDialog by remember { mutableStateOf(false) }
    val avatarContext = androidx.compose.ui.platform.LocalContext.current
    val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            copyProviderAvatarFile(avatarContext, providerId, uri)?.let { path ->
                cfg = cfg.copy(avatarType = "file", avatarValue = path)
            }
        }
    }
    val selectedModels = remember { mutableStateListOf<String>() }
    val deletedMessage = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_provider_deleted_snackbar)
    val testOkTemplate = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_success_message)
    val shareTooltip = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_share_tooltip)
    val deleteTooltip = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_provider_tooltip)
    val noModelsMessage = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_no_models_title)
    // 原版 `isUserAdded`（L134-153）：内置供应商不给删。
    val userAdded = providerId !in com.psyche.memo.data.repo.ProviderRepository.BUILTIN_KEYS

    // Immediate save (debounced 400ms) whenever the config changes.
    LaunchedEffect(providerId) {
        snapshotFlow { cfg }
            .debounce(400)
            .collect { latest ->
                runCatching {
                    dao.upsert(latest.id, detailJson.encodeToString(ProviderConfig.serializer(), latest), dao.get(latest.id)?.sortOrder ?: 0)
                }.onFailure { e ->
                    // provider_detail_page.dart:3106-3111 —— 保存失败留一条应用日志
                    // （写入抛错此前会让这个 collect 直接崩掉，连提示都没有）。
                    com.psyche.memo.common.logging.FlutterLogger.log(
                        "[ProviderDetail] save failed: $e\n${e.stackTraceToString()}",
                        tag = "Provider",
                    )
                }
            }
    }

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    var tabIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { tabIndex = it }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- AppBar: 24dp brand avatar before the name (custom title) ----
        val testButtonLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_test_button)
        MemoTopBarContent(
            onBack = onBack,
            title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderAvatar(
                    providerKey = providerId,
                    displayName = cfg.name.ifEmpty { providerId },
                    size = 24.dp,
                    onTap = { showAvatarSheet = true },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = cfg.name.ifEmpty { providerId },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            }
            },
            actions = {
            if (tabIndex == 0) {
                IconActionButton(Lucide.HeartPulse, cs.onSurface, testButtonLabel) {
                    Haptics.light(view)
                    val model = cfg.models.firstOrNull()
                    if (model == null) {
                        testResult = false to noModelsMessage
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
                                        useResponseApi = cfg.useResponseApi == true,
                                    ),
                                )
                            }
                            testResult = if (ok.isSuccess) true to model
                            else false to (ok.exceptionOrNull()?.message ?: "error")
                        }
                    }
                }
            } else {
                // Multi-select entry: CheckCheck to enter, X to leave
                // (provider_detail_page L208-233).
                IconActionButton(
                    if (modelSelectMode) Lucide.X else Lucide.CheckCheck,
                    cs.onSurface,
                    if (modelSelectMode) {
                        stringResource(com.psyche.memo.ui.R.string.provider_detail_page_cancel_button)
                    } else {
                        stringResource(com.psyche.memo.ui.R.string.provider_detail_page_multi_select_button)
                    },
                ) {
                    Haptics.light(view)
                    modelSelectMode = !modelSelectMode
                    if (!modelSelectMode) selectedModels.clear()
                }
            }
            // 分享（两个 tab 都有，provider_detail_page L234-245）——复用已有的
            // ShareProviderSheet（供应商列表页的行内分享用的同一个）。
            IconActionButton(Lucide.Share2, cs.onSurface, shareTooltip) {
                Haptics.light(view)
                showShare = true
            }
            // 删除：**只有用户自建的供应商**能删（原版 `isUserAdded`，L246），
            // 颜色用 error、tooltip 是 delete_provider_tooltip。
            if (userAdded) {
                IconActionButton(Lucide.Trash2, cs.error, deleteTooltip) {
                    Haptics.light(view)
                    showDelete = true
                }
            }
            Spacer(Modifier.width(12.dp))
            },
        )

        if (showShare) {
            ShareProviderSheet(providerKey = providerId, config = cfg, onDismiss = { showShare = false })
        }

        // ---- 供应商头像编辑（五选一 + 内置图标网格 + LobeHub 名 + 链接）----
        if (showAvatarSheet) {
            ProviderAvatarSheet(
                onPickBuiltInIcon = { showIconPicker = true },
                onPickLobehubIcon = { showLobehubDialog = true },
                onPickGallery = {
                    galleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onEnterLink = { showAvatarUrlDialog = true },
                onReset = { cfg = cfg.copy(avatarType = null, avatarValue = null) },
                onDismiss = { showAvatarSheet = false },
            )
        }
        if (showIconPicker) {
            ProviderIconPickerDialog(
                onPick = { assetFile ->
                    cfg = cfg.copy(avatarType = "icon", avatarValue = assetFile)
                    showIconPicker = false
                },
                onDismiss = { showIconPicker = false },
            )
        }
        if (showLobehubDialog) {
            ProviderAvatarTextDialog(
                title = stringResource(com.psyche.memo.ui.R.string.provider_avatar_lobehub_dialog_title),
                hint = stringResource(com.psyche.memo.ui.R.string.provider_avatar_lobehub_dialog_hint),
                initial = cfg.avatarValue?.takeIf { cfg.avatarType == "lobehub" } ?: "",
                valid = { it.isNotBlank() },
                onConfirm = {
                    cfg = cfg.copy(avatarType = "lobehub", avatarValue = it)
                    showLobehubDialog = false
                },
                onDismiss = { showLobehubDialog = false },
            )
        }
        if (showAvatarUrlDialog) {
            ProviderAvatarTextDialog(
                title = stringResource(com.psyche.memo.ui.R.string.side_drawer_image_url_dialog_title),
                hint = stringResource(com.psyche.memo.ui.R.string.side_drawer_image_url_dialog_hint),
                initial = cfg.avatarValue?.takeIf { cfg.avatarType == "url" } ?: "",
                valid = { it.startsWith("http://") || it.startsWith("https://") },
                onConfirm = {
                    cfg = cfg.copy(avatarType = "url", avatarValue = it)
                    showAvatarUrlDialog = false
                },
                onDismiss = { showAvatarUrlDialog = false },
            )
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
                    selectMode = modelSelectMode,
                    selected = selectedModels,
                    onSelectModeChange = { modelSelectMode = it },
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
                    // provider_detail_page L282-299 —— 删供应商前先清掉引用它的
                    // 助手模型选择、会话级模型 pin 与收藏（否则留下悬空引用）。
                    runCatching { container.clearProviderReferences(providerId) }
                        .onFailure { e ->
                            com.psyche.memo.common.logging.FlutterLogger.log(
                                "[ProviderDetail] clear refs failed: $e\n${e.stackTraceToString()}",
                                tag = "Provider",
                            )
                        }
                    runCatching { dao.delete(providerId) }.onFailure { e ->
                        com.psyche.memo.common.logging.FlutterLogger.log(
                            "[ProviderDetail] delete failed: $e\n${e.stackTraceToString()}",
                            tag = "Provider",
                        )
                    }
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
        // 顶部管理分组标题（provider_detail_page L1082-1093）。
        Text(
            text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_manage_section_title),
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.height(6.dp))
        // Toggles card (kind-conditional rows in kelivo order).
        val kind = cfg.classifiedKind()
        val keyLower = cfg.id.lowercase()
        val baseLower = cfg.baseUrl.lowercase()
        // provider_detail_page L2003-2018：AIHubMix 按 key/baseUrl 判定；
        // Claude 提示缓存对 claude **以及 openrouter 的 openai 端点**都可用。
        val isAihubmix = keyLower.contains("aihubmix") || baseLower.contains("aihubmix.com")
        val isOpenRouter = keyLower.contains("openrouter") || baseLower.contains("openrouter")
        val supportsClaudePromptCaching = kind == "anthropic" || (kind == "openai" && isOpenRouter)
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
            SettingsSwitchRow(
                icon = Lucide.Power,
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_enabled_title),
                value = cfg.enabled,
                onToggle = { onCfgChange(cfg.copy(enabled = it)) },
            )
            SettingsSwitchRow(
                icon = Lucide.KeyRound,
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_multi_key_mode_title),
                value = cfg.multiKeyEnabled == true,
                onToggle = { onCfgChange(cfg.copy(multiKeyEnabled = it)) },
            )
            if (cfg.multiKeyEnabled == true) {
                    NavRow(label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_manage_keys_button)) {
                    onOpenMultiKey()
                }
            }
            if (cfg.classifiedKind() == "openai") {
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
                    SettingsSwitchRow(
                    icon = Lucide.Cloud,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_vertex_ai_title),
                    value = cfg.vertexAI == true,
                    onToggle = { onCfgChange(cfg.copy(vertexAI = it)) },
                )
            }
            if (isAihubmix) {
                    // provider_detail_page L1197-1209 —— AIhubmix 专属：APP-Code 开关 +
                // 行尾 ⓘ（帮助文案）。
                SettingsSwitchRow(
                    icon = Lucide.BadgePercent,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_aihubmix_app_code_label),
                    tip = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_aihubmix_app_code_help),
                    value = cfg.aihubmixAppCodeEnabled == true,
                    onToggle = { onCfgChange(cfg.copy(aihubmixAppCodeEnabled = it)) },
                )
            }
            if (supportsClaudePromptCaching) {
                    SettingsSwitchRow(
                    icon = Lucide.Database,
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_title),
                    tip = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_help),
                    value = cfg.claudePromptCachingEnabled,
                    onToggle = { onCfgChange(cfg.copy(claudePromptCachingEnabled = it)) },
                )
                if (cfg.claudePromptCachingEnabled) {
                            // TTL 行（L1225-1242）：标题 + 行尾 ⓘ + 两段式分段控件。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TipHuggingLabel(
                            label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_ttl_title),
                            tip = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_ttl_help),
                            labelStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                        )
                        PromptCachingTtlSegmented(
                            value = cfg.claudePromptCachingTtl,
                            fiveMinuteLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_ttl5m),
                            oneHourLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_claude_prompt_caching_ttl1h),
                            onChanged = { onCfgChange(cfg.copy(claudePromptCachingTtl = it)) },
                        )
                    }
                }
            }
            // Custom request + network proxy entries (#11 sub-pages).
            NavRow(label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_custom_request_title)) {
                onOpenCustomRequest()
            }
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
        // Credentials（provider_detail_page L1334-1429）：名称 →（非 Vertex 时）API Key +
        // 服务器地址 → 路径 →（Vertex 时）区域/项目 ID/服务账号 JSON。
        LabeledInput(
            label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_name_label),
            value = cfg.name,
            obscure = false,
            hint = providerId,
        ) {
            onCfgChange(cfg.copy(name = it))
        }
        // Vertex 的 Google 端点用服务账号 JSON，不显示 API Key / 服务器地址。
        val vertexGoogle = kind == "gemini" && cfg.vertexAI == true
        if (!vertexGoogle) {
            // 多密钥模式下密钥走 MultiKey 子页（provider_detail_page L1344-1345）。
            if (cfg.multiKeyEnabled != true) {
                Spacer(Modifier.height(12.dp))
                LabeledInput(
                    label = stringResource(com.psyche.memo.ui.R.string.multi_key_page_key),
                    value = cfg.apiKey,
                    obscure = !showApiKey,
                    hint = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_api_key_hint),
                    trailing = {
                        Icon(
                            imageVector = if (showApiKey) Lucide.EyeOff else Lucide.Eye,
                            contentDescription = stringResource(
                                if (showApiKey) {
                                    com.psyche.memo.ui.R.string.provider_detail_page_hide_tooltip
                                } else {
                                    com.psyche.memo.ui.R.string.provider_detail_page_show_tooltip
                                },
                            ),
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
            }
            Spacer(Modifier.height(12.dp))
            LabeledInput(stringResource(com.psyche.memo.ui.R.string.provider_detail_page_api_base_url_label), cfg.baseUrl, false) {
                onCfgChange(cfg.copy(baseUrl = it))
            }
        }
        // 服务器路径（provider_detail_page L1379-1393 的 _inputRow 位置）——
        // 用户 2026-09-12 二次点名：外观还原成原版字段，点一下出 combobox 三选一。
        if (kind == "openai") {
            Spacer(Modifier.height(12.dp))
            ApiPathField(
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_api_path_label),
                chatPath = cfg.chatPath,
                useResponseApi = cfg.useResponseApi,
                onSelect = {
                    onCfgChange(
                        cfg.copy(
                            chatPath = it.path,
                            useResponseApi = useResponseApiFor(it.path),
                        ),
                    )
                },
            )
        }
        if (vertexGoogle) {
            Spacer(Modifier.height(12.dp))
            LabeledInput(
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_location_label),
                value = cfg.location ?: "",
                obscure = false,
                hint = "us-central1",
            ) {
                onCfgChange(cfg.copy(location = it))
            }
            Spacer(Modifier.height(12.dp))
            LabeledInput(
                label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_project_id_label),
                value = cfg.projectId ?: "",
                obscure = false,
                hint = "my-project-id",
            ) {
                onCfgChange(cfg.copy(projectId = it))
            }
            Spacer(Modifier.height(12.dp))
            ServiceAccountJsonInput(
                value = cfg.serviceAccountJson ?: "",
                onValueChange = { onCfgChange(cfg.copy(serviceAccountJson = it)) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** `_multilineRow` + 导入 JSON（provider_detail_page L1414-1427）：多行 JSON 输入，
 *  下方左对齐一个带 Upload 图标的「导入 JSON」（SAF 选文件读文本）。 */
@Composable
private fun ServiceAccountJsonInput(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val importLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_import_json_button)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (!text.isNullOrEmpty()) withContext(Dispatchers.Main) { onValueChange(text) }
        }
    }
    Column {
        Text(
            text = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_service_account_json_label),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = 4,
            maxLines = 10,
            placeholder = {
                Text(
                    "{\n  \"type\": \"service_account\", ...\n}",
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                )
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceCard,
                unfocusedContainerColor = semantic.surfaceCard,
                focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        IosTileButton(
            label = importLabel,
            icon = Lucide.Upload,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            onClick = { launcher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
    }
}

/**
 * `_PromptCachingTtlSegmentedControl` (provider_detail_page L4832-4893)：两段式
 * 胶囊（外壳 padding 2 / onSurface@8%(暗)·5%(亮) / r11；段 padding h10 v6 / r9，
 * 选中 primary 底 + onPrimary 字，未选中透明 + onSurface@0.7）。
 */
@Composable
private fun PromptCachingTtlSegmented(
    value: String,
    fiveMinuteLabel: String,
    oneHourLabel: String,
    onChanged: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .background(cs.onSurface.copy(alpha = if (semantic.isDark) 0.08f else 0.05f), RoundedCornerShape(11.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("5m" to fiveMinuteLabel, "1h" to oneHourLabel).forEach { (key, label) ->
            val selected = value == key
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) cs.onPrimary else cs.onSurface.copy(alpha = 0.7f),
                ),
                modifier = Modifier
                    .background(if (selected) cs.primary else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { onChanged(key) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Labeled input with 13dp label + r12 filled field (provider_detail _inputRow). */
@Composable
private fun LabeledInput(
    label: String,
    value: String,
    obscure: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    hint: String? = null,
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
            placeholder = hint?.let {
                { Text(it, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f))) }
            },
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

/** Bottom config/models tab switch (provider_detail _BottomTabs)：原版是
 *  `SafeArea(top: false) + Padding(12, 6, 12, 10)` —— **系统手势条留白在卡片外面**，
 *  所以卡片底边离小白条还有 10dp；之前把 `navigationBarsPadding()` 放在背景之内，
 *  卡片被撑高一大截、底边直接贴到手势条。 */
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
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .background(semantic.surfaceCard, RoundedCornerShape(16.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
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
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            listOf("Gemini" to "gemini", "Claude" to "anthropic", "OpenAI" to "openai").forEach { (label, kind) ->
                MemoSheetOptionRow(
                    label = label,
                    selected = current == kind,
                    onClick = { onSelect(kind) },
                )
            }
        }
    }
}

/** Models tab — list + fetch/add/delete/reorder + connection test (#10). */
@Composable
private fun ModelsTab(
    cfg: ProviderConfig,
    container: AppContainerImpl,
    onCfgChange: (ProviderConfig) -> Unit,
    selectMode: Boolean,
    selected: MutableList<String>,
    onSelectModeChange: (Boolean) -> Unit,
    onReload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var detailModel by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    // Connection-check state per model id (_detectionResults / _pendingModels /
    // _currentDetectingModel collapsed into one map).
    val checks = remember { mutableStateMapOf<String, ModelCheckResult>() }
    var detecting by remember { mutableStateOf(false) }
    var deleteAllConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }

    val models = cfg.models
    val modelDeletedMessage = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_model_deleted_snackbar)
    val undoLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_undo_button)
    val confirmTitle = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_confirm_delete_title)
    val confirmContent = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_confirm_delete_content)
    val cancelLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_cancel_button)
    val deleteLabel = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_button)
    val deleteAllTooltip = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_all_models_tooltip)
    val deleteAllWarning = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_all_models_warning)

    fun saveModels(next: List<String>) {
        onCfgChange(cfg.copy(models = next))
    }

    /**
     * Deletes [ids] and shows the undo snackbar. Mirrors `_confirmDeleteModels`
     * (L3148-3214) + the slidable action's delete body (L1623-1708): the models,
     * their overrides and every reference to them (assistant chat model,
     * conversation pin, pinned favourites) all go, and undo restores the models
     * at their original indices.
     */
    fun deleteModels(ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val previousOverrides = ids.mapNotNull { id -> cfg.modelOverrides[id]?.let { id to it } }.toMap()
        val indexed = ids.mapNotNull { id -> models.indexOf(id).takeIf { it >= 0 }?.let { it to id } }
        saveModels(models.filterNot { it in idSet })
        container.clearModelReferences(cfg.id, ids)
        SnackbarManager.show(
            AppNotification(
                message = modelDeletedMessage,
                type = NotificationType.INFO,
                actionLabel = undoLabel,
                onAction = {
                    // Restore at the original positions (ascending, so an
                    // earlier insert never shifts a later target index).
                    val restored = models.toMutableList()
                    indexed.sortedBy { it.first }.forEach { (index, id) ->
                        restored.add(index.coerceAtMost(restored.size), id)
                    }
                    onCfgChange(
                        cfg.copy(
                            models = restored,
                            modelOverrides = cfg.modelOverrides + previousOverrides,
                        ),
                    )
                },
            ),
        )
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
                reorderEnabled = !selectMode,
                onMove = { from, to ->
                    // Disabled in selection mode, matching onReorderItem's own
                    // `if (_isSelectionMode) return;` guard (L1494).
                    val next = if (selectMode) null else applyModelMove(models, from, to)
                    if (next != null) saveModels(next)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(semantic.surfaceCard, RoundedCornerShape(12.dp))
                    .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            ) { model, _ ->
                ModelRowWithSwipe(
                    modelId = model,
                    cfg = cfg,
                    selectMode = selectMode,
                    selected = selected.contains(model),
                    check = checks[model],
                    onToggleSelect = {
                        if (selected.contains(model)) selected.remove(model) else selected.add(model)
                    },
                    onEdit = { detailModel = model },
                    onRequestDelete = { pendingDelete = listOf(model) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ModelActionToolbar(
            selectMode = selectMode,
            fetching = fetching,
            detecting = detecting,
            hasModels = models.isNotEmpty(),
            allSelected = selected.size == models.size && models.isNotEmpty(),
            selectionCount = selected.size,
            hasFailed = models.any { checks[it]?.state == ModelCheckState.FAILURE },
            onFetch = { fetching = true },
            onAddNew = { showCreate = true },
            onDeleteAll = { deleteAllConfirm = true },
            onToggleSelectAll = {
                Haptics.light(view)
                if (selected.size == models.size) {
                    selected.clear()
                } else {
                    selected.clear()
                    selected.addAll(models)
                }
            },
            onDetect = {
                scope.launch {
                    detecting = true
                    val targets = selected.toList()
                    checks.clear()
                    targets.forEach { checks[it] = ModelCheckResult(ModelCheckState.PENDING) }
                    // Serial, 500ms apart (_startDetection L3216-3274).
                    targets.forEach { id ->
                        checks[id] = ModelCheckResult(ModelCheckState.RUNNING)
                        val (ok, message) = runConnectionCheck(container, cfg, id)
                        checks[id] = if (ok) {
                            ModelCheckResult(ModelCheckState.SUCCESS)
                        } else {
                            ModelCheckResult(ModelCheckState.FAILURE, message)
                        }
                        kotlinx.coroutines.delay(500)
                    }
                    detecting = false
                }
            },
            onDeleteFailed = {
                val failed = models.filter { checks[it]?.state == ModelCheckState.FAILURE }
                if (failed.isNotEmpty()) pendingDelete = failed
            },
            onDeleteSelected = {
                if (selected.isNotEmpty()) pendingDelete = selected.toList()
            },
        )
    }

    // ---- Fetch executes against the provider's /models endpoint (core:llm). ----
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

    // ---- Delete confirmation (single row / failed set / selection). ----
    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(confirmTitle) },
            text = { Text(confirmContent) },
            confirmButton = {
                TextButton(onClick = {
                    deleteModels(ids)
                    pendingDelete = null
                    if (selectMode) {
                        selected.clear()
                        onSelectModeChange(false)
                    }
                }) {
                    Text(deleteLabel, color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(cancelLabel) }
            },
        )
    }

    if (deleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { deleteAllConfirm = false },
            title = { Text(deleteAllTooltip) },
            text = { Text(deleteAllWarning) },
            confirmButton = {
                TextButton(onClick = {
                    deleteAllConfirm = false
                    container.clearModelReferences(cfg.id, models)
                    saveModels(emptyList())
                }) {
                    Text(deleteLabel, color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllConfirm = false }) { Text(cancelLabel) }
            },
        )
    }
}

/**
 * One reorder step applied to a model list — the same
 * `add(to, removeAt(from))` move the providers list uses, shared so both have
 * identical stale-index behaviour (reject, never clamp).
 */
internal fun applyModelMove(models: List<String>, from: Int, to: Int): List<String>? {
    if (from !in models.indices || to !in models.indices || from == to) return null
    return models.toMutableList().apply { add(to, removeAt(from)) }
}

/** Runs one non-streaming probe against a single model. Returns ok to message. */
private suspend fun runConnectionCheck(
    container: AppContainerImpl,
    cfg: ProviderConfig,
    modelId: String,
): Pair<Boolean, String> {
    val client = container.clientFor(cfg.classifiedKind())
    val result = runCatching {
        client.complete(
            com.psyche.memo.llm.client.LlmRequest(
                providerId = cfg.id,
                modelId = modelId,
                messages = listOf(com.psyche.memo.llm.client.LlmMessage(role = "user", content = "hi")),
                apiKey = cfg.apiKey,
                baseUrl = container.baseUrlFor(cfg.id),
                chatPath = cfg.chatPath,
                useResponseApi = cfg.useResponseApi == true,
            ),
        )
    }
    return if (result.isSuccess) true to modelId else false to (result.exceptionOrNull()?.message ?: "error")
}

