package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Thermometer
import com.composables.icons.lucide.User
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Zap
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * assistant_settings_edit_page.dart — edit scaffold: AppBar with back +
 * assistant-name title, the 44dp segmented tab bar (8 tabs) above a
 * HorizontalPager, tabs switch closes the IME. Tabs other than basic are
 * ported in follow-up batches and render empty until then.
 */
@Composable
fun AssistantSettingsEditScreen(
    container: com.psyche.memo.AppContainerImpl,
    assistantId: String,
    onBack: () -> Unit,
    onOpenMemorySettings: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var assistant by remember { mutableStateOf<Assistant?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey, assistantId) {
        assistant = withContext(Dispatchers.IO) {
            AssistantStore(container.database.readableDatabase).get(assistantId)
        }
    }

    fun edit(transform: (Assistant) -> Assistant) {
        val current = assistant ?: return
        com.psyche.memo.data.assistant.AssistantStore(
            container.database.writableDatabase,
        ).update(transform(current))
        reloadKey++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton44(onClick = onBack) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(UiR.string.settings_page_back_button),
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = assistant?.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(UiR.string.assistant_edit_page_title),
                maxLines = 1,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
        }

        val a = assistant
        if (a == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(UiR.string.assistant_edit_page_not_found),
                    style = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                )
            }
            return@Column
        }

        // assistant_edit_tab_layout.dart defaultAssistantEditTabIds — the
        // displayed order comes from there, not from _assistantEditTabSpecs.
        val tabLabels = listOf(
            stringResource(UiR.string.assistant_edit_page_basic_tab),
            stringResource(UiR.string.assistant_edit_page_prompts_tab),
            stringResource(UiR.string.assistant_edit_page_memory_tab),
            stringResource(UiR.string.assistant_edit_page_quick_phrase_tab),
            stringResource(UiR.string.assistant_edit_page_custom_tab),
            stringResource(UiR.string.assistant_edit_page_regex_tab),
            stringResource(UiR.string.assistant_edit_page_local_tools_tab),
            stringResource(UiR.string.assistant_edit_page_mcp_tab),
        )
        val pagerState = rememberPagerState { tabLabels.size }
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(pagerState.settledPage) {
            if (pagerState.settledPage >= 0) keyboard?.hide()
        }

        // AppBar bottom — 52dp slot: padding 12/2/12/8 around the 44 shell.
        Box(Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 8.dp)) {
            EditSegTabBar(
                tabs = tabLabels,
                selected = pagerState.currentPage,
                onSelect = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
            )
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> BasicSettingsTab(
                    container = container,
                    assistantId = a.id,
                    assistant = a,
                    onEdit = ::edit,
                    onReload = { reloadKey++ },
                )
                1 -> PromptTab(assistant = a, onEdit = ::edit)
                2 -> AssistantEditMemoryTab(
                    container = container,
                    assistant = a,
                    onEdit = ::edit,
                    onOpenMemorySettings = onOpenMemorySettings,
                )
                6 -> AssistantEditLocalToolsTab(
                    container = container,
                    assistant = a,
                    onEdit = ::edit,
                )
                7 -> AssistantEditMcpTab(
                    container = container,
                    assistant = a,
                    onEdit = ::edit,
                )
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }
}


/** _SegTabBar L632-720: 44dp capsule, r18 shell, 4dp inset, 6dp gaps, ≥88dp scrollable segments. */
@Composable
private fun EditSegTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(semantic.surfaceCard, RoundedCornerShape(18.dp)),
    ) {
        val n = tabs.size
        val innerAvail = maxWidth - 8.dp
        val segWidth = maxOf(88.dp, (innerAvail - 6.dp * (n - 1)) / n)
        Row(
            Modifier
                .padding(4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .width(segWidth)
                        .height(36.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = if (isSelected) cs.primary else cs.onSurface.copy(alpha = 0.82f),
                        ),
                    )
                }
            }
        }
    }
}

/** assistant_settings_edit_basic_tab.dart — identity card + settings card + chat-model card. */
@Composable
private fun BasicSettingsTab(
    container: com.psyche.memo.AppContainerImpl,
    assistantId: String,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
    onReload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var paramSheet by remember { mutableStateOf<ParamSheet?>(null) }
    var contextInputDialog by remember { mutableStateOf(false) }
    var avatarSheet by remember { mutableStateOf(false) }
    var emojiDialog by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var qqDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val avatarScope = rememberCoroutineScope()
    // `_pickLocalImage` L1948-1980 — the gallery path is copied into app storage
    // because a content:// URI does not survive a reboot.
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            avatarScope.launch(Dispatchers.IO) {
                val copied = runCatching {
                    val dir = File(context.filesDir, "assistant_avatars").apply { mkdirs() }
                    val dest = File(dir, assistantId + "_" + System.currentTimeMillis() + ".jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    dest.absolutePath
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (copied != null) {
                        onEdit { it.copy(avatar = copied) }
                    } else {
                        SnackbarManager.show(
                            AppNotification(
                                message = context.getString(UiR.string.assistant_edit_general_error_message),
                                type = NotificationType.ERROR,
                            ),
                        )
                        urlDialog = true
                    }
                }
            }
        }
    }

    // `_inputQQAvatar` random branch L1874-1907 — 20 tries, first hit wins.
    fun probeRandomQqAvatar() {
        avatarScope.launch(Dispatchers.IO) {
            var found: String? = null
            var tries = 0
            while (found == null && tries < 20) {
                tries++
                val candidate = qqAvatarUrl(randomQqNumber())
                if (qqAvatarResolves(candidate)) found = candidate
            }
            val url = found
            withContext(Dispatchers.Main) {
                if (url != null) {
                    qqDialog = false
                    onEdit { it.copy(avatar = url) }
                } else {
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(UiR.string.assistant_edit_q_q_avatar_failed_message),
                            type = NotificationType.ERROR,
                        ),
                    )
                }
            }
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
    ) {
        // Identity card (avatar + name) — SectionCard radius 16 padding 14.
        item {
            Surface16Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { avatarSheet = true },
                    ) {
                        AssistantListAvatar(assistant, 64.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    NameField(
                        initial = assistant.name,
                        hint = stringResource(UiR.string.assistant_edit_assistant_name_label),
                        onChanged = { v -> onEdit { it.copy(name = v) } },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
        // Settings card — 5 nav rows + 3 switch rows.
        item {
            SectionCard {
                EditNavRow(
                    icon = Lucide.Thermometer,
                    label = "Temperature",
                    detailText = assistant.temperature?.let { String.format("%.2f", it) }
                        ?: stringResource(UiR.string.assistant_edit_parameter_disabled),
                    onTap = { paramSheet = ParamSheet.Temperature },
                )
                DividerRow()
                EditNavRow(
                    icon = Lucide.WandSparkles,
                    label = "Top P",
                    detailText = assistant.topP?.let { String.format("%.2f", it) }
                        ?: stringResource(UiR.string.assistant_edit_parameter_disabled),
                    onTap = { paramSheet = ParamSheet.TopP },
                )
                DividerRow()
                EditNavRow(
                    icon = Lucide.MessagesSquare,
                    label = stringResource(UiR.string.assistant_edit_context_messages_title),
                    detailText = if (assistant.limitContextMessages) {
                        assistant.contextMessageSize.toString()
                    } else {
                        stringResource(UiR.string.assistant_edit_parameter_disabled)
                    },
                    onTap = { paramSheet = ParamSheet.Context },
                )
                DividerRow()
                // TODO(port): opens showReasoningBudgetSheet, not ported yet.
                EditNavRow(
                    icon = Lucide.Brain,
                    label = stringResource(UiR.string.assistant_edit_thinking_budget_title),
                    detailText = assistant.thinkingBudget?.toString() ?: "-",
                    onTap = {},
                )
                DividerRow()
                EditNavRow(
                    icon = Lucide.Hash,
                    label = stringResource(UiR.string.assistant_edit_max_tokens_title),
                    detailText = assistant.maxTokens?.toString()
                        ?: stringResource(UiR.string.assistant_edit_max_tokens_hint),
                    onTap = { paramSheet = ParamSheet.MaxTokens },
                )
                DividerRow()
                SwitchRow(icon = Lucide.User, label = stringResource(UiR.string.assistant_edit_use_assistant_avatar_title), checked = assistant.useAssistantAvatar) { v ->
                    onEdit { it.copy(useAssistantAvatar = v) }
                }
                DividerRow()
                SwitchRow(icon = Lucide.CaseSensitive, label = stringResource(UiR.string.assistant_edit_use_assistant_name_title), checked = assistant.useAssistantName) { v ->
                    onEdit { it.copy(useAssistantName = v) }
                }
                DividerRow()
                SwitchRow(icon = Lucide.Zap, label = stringResource(UiR.string.assistant_edit_stream_output_title), checked = assistant.streamOutput) { v ->
                    onEdit { it.copy(streamOutput = v) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
        // Chat model card (L264-357): header + reset + subtitle + selector row.
        item {
            val semanticBg = LocalSemanticColors.current
            // Override display name wins (assistant_settings_edit_basic_tab.dart L343-354).
            val modelDisplay = remember(assistant.chatModelProvider, assistant.chatModelId) {
                val p = assistant.chatModelProvider
                val m = assistant.chatModelId
                if (p == null || m == null) {
                    null
                } else {
                    runCatching {
                        val row = com.psyche.memo.data.db.PayloadEntityDao(
                            container.database.readableDatabase,
                            "provider_rows",
                            primaryKey = "provider_key",
                        ).get(p)
                        val cfg = row?.let {
                            com.psyche.memo.data.model.ProviderConfig.fromJsonString(
                                kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                                it.payload,
                            )
                        }
                        val override = cfg?.modelOverrides?.get(m)
                        val name = (override as? Map<*, *>)?.get("name") as? String
                        if (!name.isNullOrEmpty()) name else m
                    }.getOrDefault(m)
                }
            }
            var modelSheet by remember { mutableStateOf(false) }
            Surface16Card {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.MessageCircle, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(UiR.string.assistant_edit_chat_model_title),
                            maxLines = 1,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                            modifier = Modifier.weight(1f),
                        )
                        if (assistant.chatModelProvider != null && assistant.chatModelId != null) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clickable { onEdit { it.copy(chatModelProvider = null, chatModelId = null) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Lucide.RotateCcw,
                                    contentDescription = stringResource(UiR.string.default_model_page_reset_default),
                                    tint = cs.onSurface,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(UiR.string.assistant_edit_chat_model_subtitle),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.height(8.dp))
                    val display = modelDisplay
                        ?: stringResource(UiR.string.assistant_edit_model_use_global_default)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(semanticBg.surfaceFill)
                            .clickable { modelSheet = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderAvatarSmall(assistant.chatModelProvider ?: "", display, 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = display,
                            maxLines = 1,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                    }
                }
                if (modelSheet) {
                    val options = remember {
                        com.psyche.memo.data.db.PayloadEntityDao(
                            container.database.readableDatabase,
                            "provider_rows",
                            primaryKey = "provider_key",
                        ).getAll().flatMap { row ->
                            val config = runCatching {
                                com.psyche.memo.data.model.ProviderConfig.fromJsonString(
                                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                                    row.payload,
                                )
                            }.getOrNull() ?: return@flatMap emptyList()
                            config.models.map { id ->
                                ModelOption(
                                    providerId = config.id,
                                    providerName = config.name,
                                    modelId = id,
                                    selected = id == assistant.chatModelId && config.id == assistant.chatModelProvider,
                                )
                            }
                        }
                    }
                    ModelSelectSheet(
                        container = container,
                        options = options,
                        onSelect = { sel ->
                            modelSheet = false
                            onEdit { it.copy(chatModelProvider = sel.providerId, chatModelId = sel.modelId) }
                        },
                        onDismiss = { modelSheet = false },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
        // Chat background card (L373-510): title + description + pick/clear + preview.
        item {
            val semanticBg = LocalSemanticColors.current
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = rememberCoroutineScope()
            val bgPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia(),
            ) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        val copied = runCatching {
                            val dir = File(context.filesDir, "assistant_backgrounds").apply { mkdirs() }
                            val dest = File(dir, assistantId + "_" + System.currentTimeMillis() + ".jpg")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { input.copyTo(it) }
                            } ?: return@runCatching null
                            dest.absolutePath
                        }.getOrNull()
                        if (copied != null) {
                            AssistantStore(container.database.writableDatabase)
                                .update(assistant.copy(background = copied))
                            launch(kotlinx.coroutines.Dispatchers.Main) { onReload() }
                        }
                    }
                }
            }
            Surface16Card {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Image, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(UiR.string.assistant_edit_chat_background_title),
                            maxLines = 1,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(UiR.string.assistant_edit_chat_background_description),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.height(8.dp))
                    val hasBackground = !assistant.background.isNullOrBlank()
                    if (!hasBackground) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(semanticBg.surfaceFill)
                                .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickable {
                                    bgPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Lucide.Image, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(UiR.string.assistant_edit_choose_image_button),
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.9f)),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IosButton(
                                label = stringResource(UiR.string.assistant_edit_choose_image_button),
                                icon = Lucide.Image,
                                modifier = Modifier.weight(1f),
                                onTap = {
                                    bgPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                            IosButton(
                                label = stringResource(UiR.string.assistant_edit_clear_button),
                                icon = Lucide.X,
                                modifier = Modifier.weight(1f),
                                onTap = { onEdit { it.copy(background = null) } },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        val preview = remember(assistant.background) {
                            assistant.background?.let { path ->
                                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                            }
                        }
                        if (preview != null) {
                            androidx.compose.foundation.Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }
        }
    }

    val temperatureDescription = stringResource(UiR.string.assistant_edit_temperature_description)
    val topPDescription = stringResource(UiR.string.assistant_edit_top_p_description)
    val contextTitle = stringResource(UiR.string.assistant_edit_context_messages_title)
    val contextDescription = stringResource(UiR.string.assistant_edit_context_messages_description)
    val parameterDisabled = stringResource(UiR.string.assistant_edit_parameter_disabled)
    val maxTokensTitle = stringResource(UiR.string.assistant_edit_max_tokens_title)
    val maxTokensHint = stringResource(UiR.string.assistant_edit_max_tokens_hint)
    val maxTokensDescription = stringResource(UiR.string.assistant_edit_max_tokens_description)
    val saveLabel = stringResource(UiR.string.assistant_settings_add_sheet_save)

    when (paramSheet) {
        // _showTemperatureSheet L635-747.
        ParamSheet.Temperature -> ParamSliderSheet(
            title = "Temperature",
            description = temperatureDescription,
            disabledText = parameterDisabled,
            enabled = assistant.temperature != null,
            value = assistant.temperature ?: Assistant.DefaultTemperature,
            minValue = 0.0,
            maxValue = 2.0,
            divisions = 20,
            labelOf = { String.format("%.2f", it) },
            onEnabledChange = { v ->
                onEdit {
                    if (v) {
                        it.copy(temperature = Assistant.DefaultTemperature)
                    } else {
                        it.copy(temperature = null)
                    }
                }
                paramSheet = null
            },
            onValueChange = { v -> onEdit { it.copy(temperature = v) } },
            onDismiss = { paramSheet = null },
        )
        // _showTopPSheet L749-859.
        ParamSheet.TopP -> ParamSliderSheet(
            title = "Top P",
            description = topPDescription,
            disabledText = parameterDisabled,
            enabled = assistant.topP != null,
            value = assistant.topP ?: 1.0,
            minValue = 0.0,
            maxValue = 1.0,
            divisions = 20,
            labelOf = { String.format("%.2f", it) },
            onEnabledChange = { v ->
                onEdit { if (v) it.copy(topP = 1.0) else it.copy(topP = null) }
                paramSheet = null
            },
            onValueChange = { v -> onEdit { it.copy(topP = v) } },
            onDismiss = { paramSheet = null },
        )
        // _showContextMessagesSheet L861-998.
        ParamSheet.Context -> {
            ParamSliderSheet(
                title = contextTitle,
                description = contextDescription,
                disabledText = stringResource(UiR.string.assistant_edit_parameter_disabled2),
                enabled = assistant.limitContextMessages,
                value = clampContextMessages(assistant.contextMessageSize).toDouble(),
                minValue = Assistant.MinContextMessageSize.toDouble(),
                maxValue = Assistant.MaxContextMessageSize.toDouble(),
                divisions = Assistant.MaxContextMessageSize - Assistant.MinContextMessageSize,
                labelOf = { it.roundToInt().toString() },
                customLabelStops = ContextMessageLabelStops,
                onEnabledChange = { v ->
                    onEdit {
                        val bumped = if (v && it.contextMessageSize < Assistant.MinContextMessageSize) {
                            it.copy(contextMessageSize = Assistant.MinContextMessageSize)
                        } else {
                            it
                        }
                        bumped.copy(limitContextMessages = v)
                    }
                    paramSheet = null
                },
                onValueChange = { v ->
                    onEdit { it.copy(contextMessageSize = clampContextMessages(v.roundToInt())) }
                },
                onValuePillTap = { contextInputDialog = true },
                onDismiss = { paramSheet = null },
            )
            if (contextInputDialog) {
                ContextMessageInputDialog(
                    initialValue = assistant.contextMessageSize,
                    minValue = Assistant.MinContextMessageSize,
                    maxValue = Assistant.MaxContextMessageSize,
                    onDismiss = { contextInputDialog = false },
                    onConfirm = { v -> onEdit { it.copy(contextMessageSize = v) } },
                )
            }
        }
        // _showMaxTokensSheet L1000-1130.
        ParamSheet.MaxTokens -> MaxTokensSheet(
            initial = assistant.maxTokens,
            title = maxTokensTitle,
            hint = maxTokensHint,
            description = maxTokensDescription,
            saveLabel = saveLabel,
            onDismiss = { paramSheet = null },
            onSave = { v -> onEdit { it.copy(maxTokens = v) } },
        )
        null -> Unit
    }

    // _showAvatarPicker L517-617.
    if (avatarSheet) {
        AvatarPickerSheet(
            onDismiss = { avatarSheet = false },
            onChooseImage = {
                avatarPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onChooseEmoji = { emojiDialog = true },
            onEnterLink = { urlDialog = true },
            onImportQq = { qqDialog = true },
            onReset = { onEdit { it.copy(avatar = null) } },
        )
    }
    if (emojiDialog) {
        EmojiPickerDialog(
            onDismiss = { emojiDialog = false },
            onPick = { emoji ->
                emojiDialog = false
                onEdit { it.copy(avatar = emoji) }
            },
        )
    }
    if (urlDialog) {
        AvatarUrlDialog(
            onDismiss = { urlDialog = false },
            onSave = { url ->
                urlDialog = false
                onEdit { it.copy(avatar = url) }
            },
        )
    }
    if (qqDialog) {
        QQAvatarDialog(
            onDismiss = { qqDialog = false },
            onApplyUrl = { url ->
                qqDialog = false
                onEdit { it.copy(avatar = url) }
            },
            onRandom = { probeRandomQqAvatar() },
        )
    }
}

private enum class ParamSheet { Temperature, TopP, Context, MaxTokens }

/** `_showContextMessagesSheet` customLabelStops L941-950. */
private val ContextMessageLabelStops = listOf(1.0, 64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0, 4096.0)

/** assistant_settings_edit_page.dart L178-179 `_clampContextMessages`. */
private fun clampContextMessages(value: Int): Int =
    value.coerceIn(Assistant.MinContextMessageSize, Assistant.MaxContextMessageSize)

/** SectionCard radius 16 padding 14 (Flutter SectionCard(radius:16, padding:14)). */
@Composable
private fun Surface16Card(content: @Composable () -> Unit) {
    val semantic = LocalSemanticColors.current
    androidx.compose.material3.Surface(
        color = semantic.surfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, semantic.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

/** Flutter _InputRow — borderless inline text field persisting on change. */
@Composable
private fun NameField(initial: String, hint: String, onChanged: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val cs = MaterialTheme.colorScheme
    TextField(
        value = text,
        onValueChange = { v ->
            text = v
            onChanged(v)
        },
        placeholder = { Text(hint, style = TextStyle(fontSize = 16.sp, color = cs.onSurface.copy(alpha = 0.4f))) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = cs.onSurface),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Flutter _iosNavRow — 36dp icon slot, 15sp single-line label, 13sp detail, chevron. */
@Composable
private fun EditNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detailText: String,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(icon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = detailText,
            maxLines = 1,
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Flutter _iosSwitchRow — 36dp icon slot + 15sp label + trailing IosSwitch. */
@Composable
private fun SwitchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(icon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            modifier = Modifier.weight(1f),
        )
        IosSwitch(value = checked, onValueChanged = onChanged)
    }
}

@Composable
private fun IconButton44(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
