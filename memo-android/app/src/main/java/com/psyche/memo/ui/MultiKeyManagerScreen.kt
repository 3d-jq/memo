package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ApiKeyConfig
import com.psyche.memo.data.model.ApiKeyStatus
import com.psyche.memo.data.model.KeyManagementConfig
import com.psyche.memo.data.model.LoadBalanceStrategy
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.provider.MultiKeyLogic
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port of multi_key_manager_page.dart: stats card (total/normal/errors +
 * strategy), key list with status pills, enable switches, per-key test /
 * edit / delete, batch add sheet, edit sheet, strategy sheet (Round Robin /
 * Random only, like upstream), delete-all-errors dialog and the undo
 * snackbar. Edits flow through [onCfgChange] so the owning detail screen
 * persists them (debounced) — same pattern as the models tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiKeyManagerScreen(
    container: AppContainerImpl,
    cfg: ProviderConfig,
    onCfgChange: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detectModelId by remember { mutableStateOf<String?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var testingKeyId by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf<ApiKeyConfig?>(null) }
    var showStrategySheet by remember { mutableStateOf(false) }
    var showDeleteErrors by remember { mutableStateOf(false) }
    var showDetectModelPicker by remember { mutableStateOf(false) }

    val apiKeys = cfg.apiKeys ?: emptyList()
    val total = apiKeys.size
    val normal = apiKeys.count { it.status == ApiKeyStatus.active }
    val errors = apiKeys.count { it.status == ApiKeyStatus.error }

    // Hoisted strings; count-bearing ones resolve through the localized
    // context inside callbacks (stringResource is composable-only).
    val deletedOneMsg = stringResource(R.string.multi_key_page_delete_snackbar_deleted_one)
    val undoLabel = stringResource(R.string.multi_key_page_undo)
    val undoRestoredMsg = stringResource(R.string.multi_key_page_undo_restored)
    val pleaseAddModelMsg = stringResource(R.string.multi_key_page_please_add_model)
    val duplicateMsg = stringResource(R.string.multi_key_page_duplicate_key_warning)
    val detectLabel = stringResource(R.string.multi_key_page_detect)
    val editLabel = stringResource(R.string.multi_key_page_edit)
    val deleteLabel = stringResource(R.string.multi_key_page_delete)
    val cancelLabel = stringResource(R.string.multi_key_page_cancel)
    val addLabel = stringResource(R.string.multi_key_page_add)

    fun updateKey(updated: ApiKeyConfig) {
        val list = (cfg.apiKeys ?: emptyList()).toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
            onCfgChange(cfg.copy(apiKeys = list.toList()))
        }
    }

    suspend fun testSingleKey(modelId: String, key: ApiKeyConfig): Boolean = runCatching {
        val client = container.clientFor(cfg.classifiedKind())
        // 与真实聊天同一条合并路径：provider 层 {name,value} 行 → 请求头映射，
        // {key,value} 行 → 请求体（CustomRequestMerger）。
        val extraHeaders = com.psyche.memo.llm.client.CustomRequestMerger.mergeHeaders(
            provider = cfg.customHeaders.mapNotNull { row ->
                row["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it to row["value"].orEmpty() }
            }.toMap(),
        )
        val extraBody = com.psyche.memo.llm.client.CustomRequestMerger.mergeBody(
            providerRows = cfg.customBody,
        )
        client.complete(
            LlmRequest(
                providerId = cfg.id,
                modelId = modelId,
                messages = listOf(LlmMessage(role = "user", content = "hi")),
                apiKey = key.key,
                baseUrl = container.baseUrlFor(cfg.id),
                chatPath = cfg.chatPath,
                useResponseApi = cfg.useResponseApi == true,
                extraHeaders = extraHeaders,
                extraBodyJson = extraBody.takeIf { it.isNotEmpty() }?.toString(),
            ),
        )
    }.isSuccess

    /**
     * 逐条测试并写回结果。[baseList] 是这次测试要基于的密钥表（可能是**刚导入
     * 还没进 cfg** 的那一份 —— 原版 `_detectOnly` 会重新读一次 settings，我们
     * 不能拿闭包里的旧 cfg，否则 `indexOfFirst` 找不到新键、整批测试被跳过）。
     */
    suspend fun runTests(baseList: List<ApiKeyConfig>, toTest: List<ApiKeyConfig>, modelId: String) {
        val out = baseList.toMutableList()
        for (k in toTest) {
            val idx = out.indexOfFirst { it.id == k.id }
            if (idx < 0) continue
            val ok = testSingleKey(modelId, k)
            out[idx] = MultiKeyLogic.applyTestResult(out[idx], ok, System.currentTimeMillis())
            onCfgChange(cfg.copy(apiKeys = out.toList()))
            // Small delay between tests for UX (upstream 120ms).
            delay(120)
        }
    }

    fun resolveDetectModel(): String? {
        detectModelId?.let { return it }
        val models = cfg.models
        if (models.isEmpty()) {
            SnackbarManager.show(AppNotification(message = pleaseAddModelMsg, type = NotificationType.WARNING))
            return null
        }
        detectModelId = models.first()
        return models.first()
    }

    fun onDetect() {
        if (detecting) return
        val model = resolveDetectModel() ?: return
        detecting = true
        scope.launch {
            try {
                val all = cfg.apiKeys ?: emptyList()
                runTests(baseList = all, toTest = all, modelId = model)
            } finally {
                detecting = false
            }
        }
    }

    fun onTestSingle(key: ApiKeyConfig) {
        if (detecting || testingKeyId != null) return
        val model = resolveDetectModel() ?: return
        testingKeyId = key.id
        scope.launch {
            try {
                runTests(baseList = cfg.apiKeys ?: emptyList(), toTest = listOf(key), modelId = model)
            } finally {
                testingKeyId = null
            }
        }
    }

    fun deleteKey(k: ApiKeyConfig) {
        val list = (cfg.apiKeys ?: emptyList()).toMutableList()
        val idx = list.indexOfFirst { it.id == k.id }
        if (idx < 0) return
        val removed = list.removeAt(idx)
        onCfgChange(cfg.copy(apiKeys = list.toList()))
        SnackbarManager.show(
            AppNotification(
                message = deletedOneMsg,
                type = NotificationType.INFO,
                actionLabel = undoLabel,
                onAction = {
                    val cur = (cfg.apiKeys ?: emptyList()).toMutableList()
                    val insertIndex = if (idx <= cur.size) idx else cur.size
                    cur.add(insertIndex, removed)
                    onCfgChange(cfg.copy(apiKeys = cur))
                    SnackbarManager.show(
                        AppNotification(message = undoRestoredMsg, type = NotificationType.SUCCESS, durationMs = 2000),
                    )
                },
            ),
        )
    }

    fun editKeySave(updated: ApiKeyConfig) {
        val list = cfg.apiKeys ?: emptyList()
        val duplicate = list.any { it.id != updated.id && it.key.trim() == updated.key.trim() }
        if (duplicate) {
            SnackbarManager.show(AppNotification(message = duplicateMsg, type = NotificationType.WARNING))
            return
        }
        updateKey(updated)
    }

    fun onAddKeys(added: List<String>) {
        val base = cfg
        val unique = MultiKeyLogic.dedupeAdded(base.apiKeys ?: emptyList(), added)
        if (unique.isEmpty()) {
            SnackbarManager.show(
                AppNotification(
                    message = context.getString(R.string.multi_key_page_imported_snackbar, "0"),
                    type = NotificationType.INFO,
                ),
            )
            return
        }
        val newKeys = (base.apiKeys ?: emptyList()) + unique.map { ApiKeyConfig.create(it) }
        onCfgChange(base.copy(apiKeys = newKeys, multiKeyEnabled = true))
        SnackbarManager.show(
            AppNotification(
                message = context.getString(R.string.multi_key_page_imported_snackbar, unique.size.toString()),
                type = NotificationType.SUCCESS,
            ),
        )
        // Auto-detect imported keys (_detectOnly)：用**新表**做基准测新增的那些。
        scope.launch {
            val model = resolveDetectModel() ?: return@launch
            runTests(
                baseList = newKeys,
                toTest = newKeys.filter { it.key in unique },
                modelId = model,
            )
        }
    }

    // Opaque surface: this page renders as a full-screen overlay above the
    // detail screen, so without a background it shows through.
    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        // ---- AppBar (leading back, title, actions Trash2 / HeartPulse / Plus) ----
        MemoTopBar(
            title = stringResource(R.string.multi_key_page_title),
            onBack = onBack,
        ) {
            IconActionButton(Lucide.Trash2, cs.onSurface, deleteLabel) {
                if (apiKeys.any { it.status == ApiKeyStatus.error }) showDeleteErrors = true
            }
            if (detecting) {
                Box(modifier = Modifier.padding(horizontal = 12.dp).size(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cs.primary, modifier = Modifier.size(20.dp))
                }
            } else {
                DetectActionButton(Lucide.HeartPulse, cs.onSurface, detectLabel, onLongClick = { showDetectModelPicker = true }) {
                    onDetect()
                }
            }
            IconActionButton(Lucide.Plus, cs.onSurface, addLabel) {
                showAddSheet = true
            }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                SectionCard {
                    StatRow(stringResource(R.string.multi_key_page_total), total.toString())
                    StatRow(stringResource(R.string.multi_key_page_normal), normal.toString())
                    StatRow(stringResource(R.string.multi_key_page_error), errors.toString())
                    val strategy = cfg.keyManagement?.strategy ?: LoadBalanceStrategy.roundRobin
                    StrategyRow(
                        label = stringResource(R.string.multi_key_page_strategy_title),
                        value = stringResource(
                            when (strategy) {
                                LoadBalanceStrategy.priority -> R.string.multi_key_page_strategy_priority
                                LoadBalanceStrategy.leastUsed -> R.string.multi_key_page_strategy_least_used
                                LoadBalanceStrategy.random -> R.string.multi_key_page_strategy_random
                                LoadBalanceStrategy.roundRobin -> R.string.multi_key_page_strategy_round_robin
                            },
                        ),
                        onTap = { showStrategySheet = true },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                if (apiKeys.isEmpty()) {
                    SectionCard {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.multi_key_page_no_keys), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    SectionCard {
                        apiKeys.forEach { k ->
                            KeyRow(
                                k = k,
                                name = k.name?.takeIf { it.isNotEmpty() } ?: MultiKeyLogic.maskKey(k.key),
                                isTesting = testingKeyId == k.id,
                                detectLabel = detectLabel,
                                editLabel = editLabel,
                                deleteLabel = deleteLabel,
                                onToggle = { v -> updateKey(k.copyWith(isEnabled = v)) },
                                onTest = { onTestSingle(k) },
                                onEdit = { editKey = k },
                                onDelete = { deleteKey(k) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Add keys sheet ----
    if (showAddSheet) {
        AddKeysSheet(
            title = addLabel,
            hint = stringResource(R.string.multi_key_page_add_hint),
            addLabel = addLabel,
            onDismiss = { showAddSheet = false },
            onAdd = {
                showAddSheet = false
                onAddKeys(it)
            },
        )
    }

    // ---- Edit key sheet ----
    editKey?.let { k ->
        EditKeySheet(
            k = k,
            title = editLabel,
            aliasHint = stringResource(R.string.multi_key_page_alias),
            keyHint = stringResource(R.string.multi_key_page_key),
            priorityHint = stringResource(R.string.multi_key_page_priority),
            saveLabel = stringResource(R.string.multi_key_page_save),
            onDismiss = { editKey = null },
            onSave = {
                editKey = null
                editKeySave(it)
            },
        )
    }

    // ---- Strategy sheet (Round Robin / Random only, like upstream) ----
    if (showStrategySheet) {
        val current = cfg.keyManagement?.strategy ?: LoadBalanceStrategy.roundRobin
        ModalBottomSheet(
            sheetState = rememberMemoSheetState(),
            onDismissRequest = { showStrategySheet = false },
            containerColor = cs.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
            dragHandle = null,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)) {
                SheetHandle()
                Spacer(Modifier.height(12.dp))
                listOf(LoadBalanceStrategy.roundRobin, LoadBalanceStrategy.random).forEach { s ->
                    val label = stringResource(
                        when (s) {
                            LoadBalanceStrategy.roundRobin -> R.string.multi_key_page_strategy_round_robin
                            else -> R.string.multi_key_page_strategy_random
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showStrategySheet = false
                                if (s != current) {
                                    onCfgChange(
                                        cfg.copy(
                                            keyManagement = (cfg.keyManagement ?: KeyManagementConfig())
                                                .copy(strategy = s),
                                        ),
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        if (s == current) {
                            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }

    // ---- Detect model picker (long-press on the HeartPulse action) ----
    if (showDetectModelPicker) {
        ModelSelectSheet(
            container = container,
            options = cfg.models.map { m ->
                ModelOption(providerId = cfg.id, providerName = cfg.name.ifEmpty { cfg.id }, modelId = m, selected = m == detectModelId)
            },
            onSelect = {
                detectModelId = it.modelId
                showDetectModelPicker = false
            },
            onDismiss = { showDetectModelPicker = false },
        )
    }

    // ---- Delete-all-errors confirm dialog ----
    if (showDeleteErrors) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { showDeleteErrors = false },
            title = { Text(stringResource(R.string.multi_key_page_delete_errors_confirm_title)) },
            text = { Text(stringResource(R.string.multi_key_page_delete_errors_confirm_content)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteErrors = false
                    val keys = cfg.apiKeys ?: emptyList()
                    val errorKeys = keys.filter { it.status == ApiKeyStatus.error }
                    if (errorKeys.isEmpty()) return@TextButton
                    onCfgChange(cfg.copy(apiKeys = keys.filter { it.status != ApiKeyStatus.error }))
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(R.string.multi_key_page_deleted_errors_snackbar, errorKeys.size.toString()),
                            type = NotificationType.SUCCESS,
                        ),
                    )
                }) {
                    Text(deleteLabel, color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteErrors = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(
            value,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(end = 12.dp),
            color = cs.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun StrategyRow(label: String, value: String, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(value, style = TextStyle(fontSize = 15.sp))
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun KeyRow(
    k: ApiKeyConfig,
    name: String,
    isTesting: Boolean,
    detectLabel: String,
    editLabel: String,
    deleteLabel: String,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val statusColor = when (k.status) {
        ApiKeyStatus.active -> semantic.success
        ApiKeyStatus.disabled -> cs.onSurface.copy(alpha = 0.6f)
        ApiKeyStatus.error -> cs.error
        ApiKeyStatus.rateLimited -> cs.tertiary
    }
    val statusText = stringResource(
        when (k.status) {
            ApiKeyStatus.active -> R.string.multi_key_page_status_active
            ApiKeyStatus.disabled -> R.string.multi_key_page_status_disabled
            ApiKeyStatus.error -> R.string.multi_key_page_status_error
            ApiKeyStatus.rateLimited -> R.string.multi_key_page_status_rate_limited
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(statusText, style = TextStyle(fontSize = 11.sp, color = statusColor))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        IosSwitch(value = k.isEnabled, onValueChanged = onToggle, width = 46.dp, height = 28.dp)
        Spacer(Modifier.width(6.dp))
        if (isTesting) {
            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = cs.primary, modifier = Modifier.size(22.dp))
            }
        } else {
            KeyIconButton(Lucide.HeartPulse, cs.primary, detectLabel, onTest)
        }
        Spacer(Modifier.width(4.dp))
        KeyIconButton(Lucide.Pencil, cs.primary, editLabel, onEdit)
        Spacer(Modifier.width(4.dp))
        KeyIconButton(Lucide.Trash2, cs.error, deleteLabel, onDelete)
    }
}

@Composable
private fun KeyIconButton(icon: ImageVector, color: Color, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
    }
}

/** Top-bar icon action with a long-press (upstream onLongPress) — detect model pick. */
@Composable
private fun DetectActionButton(
    icon: ImageVector,
    color: Color,
    label: String,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
    }
}

/** Centered 40x4 drag handle shared by the sheets. */
@Composable
private fun SheetHandle() {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
        )
    }
}

@Composable
private fun SheetHeader(title: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Text(
            text = title,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(34.dp)
                .clickable(onClick = onDismiss)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeysSheet(
    title: String,
    hint: String,
    addLabel: String,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            SheetHandle()
            Spacer(Modifier.height(12.dp))
            SheetHeader(title, onDismiss)
            Spacer(Modifier.height(16.dp))
            SheetField(
                value = text,
                onValueChange = { text = it },
                hint = hint,
                minLines = 3,
                maxLines = 6,
            )
            Spacer(Modifier.height(16.dp))
            IosTileButton(
                label = addLabel,
                icon = Lucide.Plus,
                backgroundColor = cs.primary,
                onClick = { onAdd(MultiKeyLogic.splitKeys(text)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditKeySheet(
    k: ApiKeyConfig,
    title: String,
    aliasHint: String,
    keyHint: String,
    priorityHint: String,
    saveLabel: String,
    onDismiss: () -> Unit,
    onSave: (ApiKeyConfig) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var alias by remember { mutableStateOf(k.name ?: "") }
    var keyValue by remember { mutableStateOf(k.key) }
    var priority by remember { mutableStateOf(k.priority.toString()) }
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            SheetHandle()
            Spacer(Modifier.height(12.dp))
            SheetHeader(title, onDismiss)
            Spacer(Modifier.height(16.dp))
            SheetField(value = alias, onValueChange = { alias = it }, hint = aliasHint, singleLine = true)
            Spacer(Modifier.height(12.dp))
            SheetField(value = keyValue, onValueChange = { keyValue = it }, hint = keyHint, singleLine = true)
            Spacer(Modifier.height(12.dp))
            SheetField(
                value = priority,
                onValueChange = { priority = it.filter { c -> c.isDigit() } },
                hint = priorityHint,
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            IosTileButton(
                label = saveLabel,
                icon = Lucide.Check,
                backgroundColor = cs.primary,
                onClick = {
                    val p = priority.trim().toIntOrNull() ?: k.priority
                    onSave(
                        k.copyWith(
                            name = alias.trim(),
                            nameClear = alias.trim().isEmpty(),
                            key = keyValue.trim(),
                            priority = p.coerceIn(1, 10),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        placeholder = { Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.5f))) },
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = semantic.surfaceCard,
            unfocusedContainerColor = semantic.surfaceCard,
            focusedBorderColor = cs.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
