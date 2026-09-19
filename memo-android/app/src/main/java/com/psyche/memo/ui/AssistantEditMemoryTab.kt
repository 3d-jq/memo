package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookHeart
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

/**
 * Port of assistant_settings_edit_memory_tab.dart (v2 branch): the memory
 * master switch and its nested behaviour rows, the past-conversation recall
 * chain and the memory-settings entry. The legacy-memory branch, the inline
 * entry list and the organize action stay in M2b (they need the memory
 * pipeline).
 */
@Composable
fun AssistantEditMemoryTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
    onOpenMemorySettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var frequencyPicker by remember { mutableStateOf(false) }
    var dedupePicker by remember { mutableStateOf(false) }
    var scopePicker by remember { mutableStateOf(false) }
    var summaryPicker by remember { mutableStateOf(false) }
    var customFrequency by remember { mutableStateOf(false) }
    var customSummary by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorEntry by remember { mutableStateOf<MemoryEntry?>(null) }
    var organizing by remember { mutableStateOf(false) }
    // 「管理总结」：编辑/清除的目标 + 数据版本（改完立刻重查）。
    var summaryEditor by remember { mutableStateOf<com.psyche.memo.data.model.Conversation?>(null) }
    var summaryDelete by remember { mutableStateOf<com.psyche.memo.data.model.Conversation?>(null) }
    var summariesRev by remember { mutableStateOf(0) }
    val organizeScope = rememberCoroutineScope()
    val pipeline = container.memoryPipeline
    // 与原版同一个闸门：过往对话回忆 + 生成对话总结都开才出现。
    val summaryGateOpen = assistant.allowPastConversationRecall && assistant.generateConversationSummary
    val summaries = remember(summaryGateOpen, assistant.id, summariesRev) {
        if (summaryGateOpen) {
            runCatching { container.conversationDao.withSummaryForAssistant(assistant.id) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    fun reloadSummaries() {
        summariesRev++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SettingsSectionCard {
                SettingsSwitchRow(
                    icon = Lucide.BookHeart,
                    label = stringResource(R.string.assistant_edit_memory_switch_title),
                    tip = stringResource(R.string.assistant_edit_memory_switch_subtitle),
                    value = assistant.enableMemory,
                    onToggle = { onEdit { a -> a.copy(enableMemory = it) } },
                )
                if (assistant.enableMemory) {
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        icon = Lucide.Sparkles,
                        label = stringResource(R.string.assistant_edit_auto_organize_title),
                        tip = stringResource(R.string.assistant_edit_auto_organize_subtitle),
                        value = assistant.autoOrganizeMemory,
                        onToggle = { onEdit { a -> a.copy(autoOrganizeMemory = it) } },
                    )
                    if (assistant.autoOrganizeMemory) {
                        SettingsIosDivider()
                        PickerRow(
                            label = stringResource(R.string.assistant_edit_organize_frequency_title),
                            detail = stringResource(
                                R.string.assistant_edit_organize_frequency_option,
                                assistant.memoryOrganizeEveryNTurns.toString(),
                            ),
                            onTap = { frequencyPicker = true },
                        )
                        SettingsIosDivider()
                        PickerRow(
                            label = stringResource(R.string.assistant_edit_dedupe_mode_title),
                            detail = stringResource(dedupeLabelRes(assistant.memorySmartAddMode)),
                            onTap = { dedupePicker = true },
                        )
                    }
                    SettingsIosDivider()
                    PickerRow(
                        label = stringResource(R.string.assistant_edit_write_scope_title),
                        detail = stringResource(scopeLabelRes(assistant.memoryWriteScope)),
                        onTap = { scopePicker = true },
                    )
                }
                SettingsIosDivider()
                SettingsSwitchRow(
                    icon = Lucide.History,
                    label = stringResource(R.string.assistant_edit_allow_past_recall_title),
                    tip = stringResource(R.string.assistant_edit_allow_past_recall_subtitle),
                    value = assistant.allowPastConversationRecall,
                    onToggle = { v ->
                        onEdit { a ->
                            a.copy(
                                allowPastConversationRecall = v,
                                generateConversationSummary = if (v) a.generateConversationSummary else false,
                            )
                        }
                    },
                )
                if (assistant.allowPastConversationRecall) {
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        icon = Lucide.FileText,
                        label = stringResource(R.string.assistant_edit_generate_summary_title),
                        tip = stringResource(R.string.assistant_edit_generate_summary_subtitle),
                        value = assistant.generateConversationSummary,
                        onToggle = { onEdit { a -> a.copy(generateConversationSummary = it) } },
                    )
                    if (assistant.generateConversationSummary) {
                        SettingsIosDivider()
                        PickerRow(
                            label = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_title),
                            detail = stringResource(
                                R.string.assistant_edit_recent_chats_summary_frequency_option,
                                assistant.recentChatsSummaryMessageCount.toString(),
                            ),
                            onTap = { summaryPicker = true },
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SettingsSectionCard {
                SettingsRow(
                    icon = Lucide.Layers,
                    label = stringResource(R.string.settings_page_memory),
                    detailText = stringResource(R.string.memory_settings_global_subtitle),
                    onTap = onOpenMemorySettings,
                )
            }
        }

        // 管理记忆 (assistant_settings_edit_memory_tab.dart L335-479): the add
        // action, the empty hints and the entries this assistant can see. The
        // organize action and its status line need the memory pipeline (M2d-c).
        val provider = container.memoryProviderV2
        androidx.compose.runtime.LaunchedEffect(Unit) { provider.ensureLoaded() }
        val rev = provider.version
        val visible = remember(rev, assistant.id, assistant.enableMemory) {
            if (assistant.enableMemory) provider.visibleFor(assistant.id) else emptyList()
        }
        val archived = remember(rev, assistant.id, assistant.enableMemory) {
            if (assistant.enableMemory) {
                provider.visibleFor(assistant.id, includeArchived = true)
                    .filter { it.status == MemoryStatus.archived }
            } else {
                emptyList()
            }
        }

        // The manual run needs a memory model and a chat with this assistant
        // open (§ L341-353). Collected rather than read off the StateFlow so
        // the row re-evaluates when the open conversation changes; kept as a
        // plain local so the null check below still smart-casts.
        val modelMissing = !com.psyche.memo.ui.MemorySettingsState(container).modelSet
        val currentConversationId = container.currentConversationId.collectAsState().value
        val canOrganize = !modelMissing && !organizing &&
            currentConversationId != null &&
            container.conversationDao.get(currentConversationId)?.assistantId == assistant.id

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.assistant_edit_manage_memory_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clickable(enabled = canOrganize) {
                        val conversationId = currentConversationId ?: return@clickable
                        organizing = true
                        organizeScope.launch {
                            runCatching { pipeline.runNow(conversationId, assistant.id) }
                            organizing = false
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (organizing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = cs.primary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(
                        Lucide.Sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (canOrganize) cs.primary else cs.onSurface.copy(alpha = 0.35f),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.memory_organize_button),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canOrganize) cs.primary else cs.onSurface.copy(alpha = 0.35f),
                    ),
                )
            }
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .clickable {
                        editorEntry = null
                        editorOpen = true
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.memory_entry_action_add),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                )
            }
        }
        Text(
            text = memoryOrganizeStatusLine(pipeline.lastStatus),
            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f)),
            modifier = Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
        )

        if (!assistant.enableMemory) {
            EmptyHint(stringResource(R.string.memory_entry_empty_disabled))
        } else if (visible.isEmpty() && archived.isEmpty()) {
            EmptyHint(stringResource(R.string.memory_entry_empty))
        }

        visible.forEach { entry ->
            MemoryEntryCard(
                entry = entry,
                useThisAssistantLabel = true,
                scopeToggleAssistantId = assistant.id,
                onEdit = {
                    editorEntry = entry
                    editorOpen = true
                },
                onArchive = { provider.archive(entry.id) },
                onRestore = { provider.restore(entry.id) },
                onHardDelete = { provider.hardDelete(entry.id) },
            )
        }
        if (archived.isNotEmpty()) {
            Text(
                text = stringResource(R.string.memory_entry_archived_section),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            archived.forEach { entry ->
                MemoryEntryCard(
                    entry = entry,
                    useThisAssistantLabel = true,
                    scopeToggleAssistantId = assistant.id,
                    onEdit = {
                        editorEntry = entry
                        editorOpen = true
                    },
                    onArchive = { provider.archive(entry.id) },
                    onRestore = { provider.restore(entry.id) },
                    onHardDelete = { provider.hardDelete(entry.id) },
                )
            }
        }

        // 管理总结（assistant_settings_edit_memory_tab.dart L481-573）：过了「过往对话
        // 回忆 + 生成对话总结」两个开关才出现；列出该助手名下**有总结**的会话，每条可
        // 编辑（空内容 = 清掉）或清除。
        if (summaryGateOpen) {
            Text(
                text = stringResource(R.string.assistant_edit_manage_summaries_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 4.dp),
            )
            if (summaries.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_edit_summary_empty),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                summaries.forEach { conv ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        SummaryCard(
                            title = conv.title,
                            summary = conv.summary.orEmpty(),
                            onEdit = { summaryEditor = conv },
                            onDelete = { summaryDelete = conv },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    // Same editor the memory list page opens (_showAddEditSheet L49-64): the
    // default scope follows the assistant's write policy.
    if (editorOpen) {
        val closeEditor = {
            editorOpen = false
            editorEntry = null
        }
        val prefersAssistant = assistant.memoryWriteScope == "alwaysAssistant" ||
            assistant.memoryWriteScope == "toolDefaultAssistant"
        MemoryEntryEditSheet(
            container = container,
            provider = container.memoryProviderV2,
            assistants = rememberLoaded(emptyList()) {
                com.psyche.memo.data.assistant.AssistantStore(container.database.readableDatabase).getAll()
            },
            existing = editorEntry,
            defaultAssistantId = assistant.id,
            defaultScope = if (editorEntry == null && prefersAssistant) MemoryScope.assistant else MemoryScope.global,
            onDismiss = closeEditor,
            onSaved = closeEditor,
        )
    }

    if (frequencyPicker) {
        MemoryChoiceSheet(
            title = stringResource(R.string.assistant_edit_organize_frequency_title),
            options = listOf(1, 3, 5, 10).map {
                ChoiceOption(
                    label = stringResource(R.string.assistant_edit_organize_frequency_option, it.toString()),
                    subtitle = null,
                    selected = it == assistant.memoryOrganizeEveryNTurns,
                ) to it
            } + listOf(
                ChoiceOption(
                    label = stringResource(R.string.assistant_edit_organize_frequency_custom_button),
                    subtitle = null,
                    selected = assistant.memoryOrganizeEveryNTurns !in listOf(1, 3, 5, 10),
                ) to -1,
            ),
            onDismiss = { frequencyPicker = false },
            onSelect = { value ->
                frequencyPicker = false
                if (value == -1) customFrequency = true
                else onEdit { a -> a.copy(memoryOrganizeEveryNTurns = value) }
            },
        )
    }

    if (dedupePicker) {
        MemoryChoiceSheet(
            title = stringResource(R.string.assistant_edit_dedupe_mode_title),
            options = listOf(
                ChoiceOption(
                    label = stringResource(R.string.assistant_edit_dedupe_mode_batched),
                    subtitle = stringResource(R.string.assistant_edit_dedupe_mode_batched_subtitle),
                    selected = assistant.memorySmartAddMode == "batched",
                ) to "batched",
                ChoiceOption(
                    label = stringResource(R.string.assistant_edit_dedupe_mode_per_item),
                    subtitle = stringResource(R.string.assistant_edit_dedupe_mode_per_item_subtitle),
                    selected = assistant.memorySmartAddMode == "perItem",
                ) to "perItem",
            ),
            onDismiss = { dedupePicker = false },
            onSelect = { mode ->
                dedupePicker = false
                onEdit { a -> a.copy(memorySmartAddMode = mode) }
            },
        )
    }

    if (scopePicker) {
        MemoryChoiceSheet(
            title = stringResource(R.string.assistant_edit_write_scope_title),
            options = listOf(
                "alwaysGlobal" to R.string.assistant_edit_write_scope_always_global,
                "alwaysAssistant" to R.string.assistant_edit_write_scope_always_assistant,
                "toolDefaultGlobal" to R.string.assistant_edit_write_scope_tool_default_global,
                "toolDefaultAssistant" to R.string.assistant_edit_write_scope_tool_default_assistant,
            ).map { (value, labelRes) ->
                ChoiceOption(
                    label = stringResource(labelRes),
                    subtitle = stringResource(scopeSubtitleRes(value)),
                    selected = assistant.memoryWriteScope == value,
                ) to value
            },
            onDismiss = { scopePicker = false },
            onSelect = { scope ->
                scopePicker = false
                onEdit { a -> a.copy(memoryWriteScope = scope) }
            },
        )
    }

    if (summaryPicker) {
        val options = listOf(1, 3, 5, 10, 20, 50)
        MemoryChoiceSheet(
            title = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_title),
            options = options.map {
                ChoiceOption(
                    label = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_option, it.toString()),
                    subtitle = null,
                    selected = it == assistant.recentChatsSummaryMessageCount,
                ) to it
            } + listOf(
                ChoiceOption(
                    label = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_custom_button),
                    subtitle = null,
                    selected = assistant.recentChatsSummaryMessageCount !in options,
                ) to -1,
            ),
            onDismiss = { summaryPicker = false },
            onSelect = { value ->
                summaryPicker = false
                if (value == -1) customSummary = true
                else onEdit { a -> a.copy(recentChatsSummaryMessageCount = value) }
            },
        )
    }

    if (customFrequency) {
        MemoryNumberDialog(
            title = stringResource(R.string.assistant_edit_organize_frequency_custom_title),
            label = stringResource(R.string.assistant_edit_organize_frequency_custom_label),
            hint = stringResource(R.string.assistant_edit_organize_frequency_custom_hint),
            description = stringResource(R.string.assistant_edit_organize_frequency_custom_description),
            initial = assistant.memoryOrganizeEveryNTurns.toString(),
            min = 1,
            max = 20,
            invalidMessage = stringResource(R.string.assistant_edit_organize_frequency_custom_invalid),
            onDismiss = { customFrequency = false },
            onValue = { value ->
                customFrequency = false
                onEdit { a -> a.copy(memoryOrganizeEveryNTurns = value) }
            },
        )
    }

    if (customSummary) {
        MemoryNumberDialog(
            title = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_custom_title),
            label = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_custom_label),
            hint = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_custom_hint),
            description = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_custom_description),
            initial = assistant.recentChatsSummaryMessageCount.toString(),
            min = 1,
            max = 999,
            invalidMessage = stringResource(R.string.assistant_edit_recent_chats_summary_frequency_custom_invalid),
            onDismiss = { customSummary = false },
            onValue = { value ->
                customSummary = false
                onEdit { a -> a.copy(recentChatsSummaryMessageCount = value) }
            },
        )
    }

    // 编辑总结（_showEditSummarySheet L579-603）：空内容 = 清掉该会话的总结。
    summaryEditor?.let { conv ->
        SummaryEditSheet(
            title = stringResource(R.string.assistant_edit_summary_dialog_title),
            label = stringResource(R.string.assistant_edit_summary_dialog_title),
            hint = stringResource(R.string.assistant_edit_summary_dialog_hint),
            initial = conv.summary.orEmpty(),
            onDismiss = { summaryEditor = null },
            onSave = { text ->
                summaryEditor = null
                organizeScope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (text.isEmpty()) {
                            container.conversationDao.clearSummary(conv.id)
                        } else {
                            container.conversationDao.updateSummary(
                                conv.id,
                                text,
                                conv.lastSummarizedMessageCount,
                            )
                        }
                    }
                    reloadSummaries()
                }
            },
        )
    }

    // 清除总结确认（_confirmDeleteSummary L605-634）。
    summaryDelete?.let { conv ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { summaryDelete = null },
            title = { Text(stringResource(R.string.assistant_edit_delete_summary_title)) },
            text = { Text(stringResource(R.string.assistant_edit_delete_summary_content)) },
            confirmButton = {
                TextButton(onClick = {
                    summaryDelete = null
                    organizeScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            container.conversationDao.clearSummary(conv.id)
                        }
                        reloadSummaries()
                    }
                }) {
                    Text(stringResource(R.string.assistant_edit_clear_button), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { summaryDelete = null }) {
                    Text(stringResource(R.string.home_page_cancel))
                }
            },
        )
    }
}

/**
 * 「管理总结」的一张卡（assistant_settings_edit_memory_tab.dart L516-567）：
 * r14 卡片、标题 12sp@0.6、总结 14sp 最多 3 行、右侧编辑/清除两枚 18dp 图标。
 */
@Composable
private fun SummaryCard(
    title: String,
    summary: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    androidx.compose.material3.Surface(
        color = semantic.surfaceCard,
        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, semantic.hairline),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MemoRadius.INNER_DP.dp)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface.copy(alpha = 0.6f),
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                SummaryIconAction(icon = Lucide.Pencil, tint = cs.primary, onTap = onEdit)
                SummaryIconAction(icon = Lucide.Trash2, tint = cs.error, onTap = onDelete)
            }
        }
    }
}

/** _TactileIconButton —— 18dp 图标、无背景、带触觉。 */
@Composable
private fun SummaryIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * 总结编辑 sheet —— 原版 `_MemoryTextInputForm`（L686-830）的手机形态：
 * 拖柄 + 居中标题 + 一张卡里的多行输入（label 行上、hint 内嵌、autofocus）+
 * 取消/保存页脚（保存按钮在 `allowEmpty` 时永远可点，空内容 = 清掉总结）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryEditSheet(
    title: String,
    label: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember(initial) { mutableStateOf(initial) }
    androidx.compose.material3.ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            MemoSheetHandle()
            Text(
                text = title,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SectionCard {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        IosFormField(
                            label = label,
                            value = text,
                            onValueChange = { text = it },
                            inline = false,
                            minLines = 3,
                            maxLines = 10,
                            autofocus = true,
                            hint = hint,
                        )
                    }
                }
            }
            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                MemorySheetActions(
                    confirmLabel = stringResource(R.string.user_profile_save),
                    confirmEnabled = true,
                    onCancel = onDismiss,
                    // 原版 pop 的是 `_controller.text.trim()`。
                    onConfirm = { onSave(text.trim()) },
                )
            }
        }
    }
}

private fun dedupeLabelRes(mode: String): Int = if (mode == "perItem") {
    R.string.assistant_edit_dedupe_mode_per_item
} else {
    R.string.assistant_edit_dedupe_mode_batched
}

private fun scopeLabelRes(scope: String): Int = when (scope) {
    "alwaysAssistant" -> R.string.assistant_edit_write_scope_always_assistant
    "toolDefaultGlobal" -> R.string.assistant_edit_write_scope_tool_default_global
    "toolDefaultAssistant" -> R.string.assistant_edit_write_scope_tool_default_assistant
    else -> R.string.assistant_edit_write_scope_always_global
}

private fun scopeSubtitleRes(scope: String): Int = when (scope) {
    "alwaysAssistant" -> R.string.assistant_edit_write_scope_always_assistant_subtitle
    "toolDefaultGlobal" -> R.string.assistant_edit_write_scope_tool_default_global_subtitle
    "toolDefaultAssistant" -> R.string.assistant_edit_write_scope_tool_default_assistant_subtitle
    else -> R.string.assistant_edit_write_scope_always_global_subtitle
}

@Composable
private fun PickerRow(label: String, detail: String, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
    }
}

private data class ChoiceOption(
    val label: String,
    val subtitle: String?,
    val selected: Boolean,
)

/** showMemoryOptionPicker: bottom sheet with label + optional subtitle + check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> MemoryChoiceSheet(
    title: String,
    options: List<Pair<ChoiceOption, T>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(8.dp))
            options.forEach { (option, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = TextStyle(
                                fontSize = 15.sp,
                                color = if (option.selected) cs.primary else cs.onSurface,
                            ),
                        )
                        option.subtitle?.let {
                            Text(
                                text = it,
                                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                    }
                    if (option.selected) {
                        Icon(
                            Lucide.Check,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** _showMemoryTextSheet: numeric input with range validation. */
@Composable
private fun MemoryNumberDialog(
    title: String,
    label: String,
    hint: String,
    description: String,
    initial: String,
    min: Int,
    max: Int,
    invalidMessage: String,
    onDismiss: () -> Unit,
    onValue: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val supporting: (@Composable () -> Unit)? = error?.let { message ->
        ({ Text(message, style = TextStyle(fontSize = 12.sp, color = cs.error)) })
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(label, style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter { c -> c.isDigit() }
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f))) },
                    supportingText = supporting,
                    shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = semantic.surfaceFill,
                        unfocusedContainerColor = semantic.surfaceFill,
                        focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(description, style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = text.toIntOrNull()
                if (parsed == null || parsed < min || parsed > max) {
                    error = invalidMessage
                } else {
                    onValue(parsed)
                }
            }) { Text(stringResource(R.string.default_model_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.search_services_add_dialog_cancel)) }
        },
    )
}

/** Empty-state line (assistant_settings_edit_memory_tab.dart L429-450). */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        ),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * `_statusLine` (assistant_settings_edit_memory_tab.dart L107-130): "Last
 * organized: 5 min ago · extracted 2", with skip reasons and failures called out
 * separately.
 */
@Composable
private fun memoryOrganizeStatusLine(status: com.psyche.memo.provider.MemoryOrganizeStatus): String {
    val lastAt = status.lastAt
    val result = status.lastResult
    if (lastAt == null || result == null) {
        return stringResource(R.string.memory_organize_status_never)
    }
    val parts = mutableListOf(
        stringResource(R.string.memory_organize_status_last, relativeTime(lastAt)),
    )
    val error = result.error
    if (!error.isNullOrEmpty()) {
        val label = memoryOutcomeLabel(error)
        parts.add(
            if (error in com.psyche.memo.provider.MemoryPipelineService.SKIP_REASON_CODES) {
                stringResource(R.string.memory_organize_status_skipped_reason, label)
            } else {
                stringResource(R.string.memory_organize_status_failed, label)
            },
        )
    } else if (result.gate == com.psyche.memo.provider.MemoryGateParseResult.SKIP ||
        (result.extractedCount == 0 && result.advanced)
    ) {
        parts.add(stringResource(R.string.memory_organize_status_skipped))
    } else {
        parts.add(stringResource(R.string.memory_organize_status_extracted, result.extractedCount.toString()))
    }
    return parts.joinToString(" · ")
}

/** `_formatRelative` L132-142. */
@Composable
private fun relativeTime(atMillis: Long): String {
    val minutes = ((System.currentTimeMillis() - atMillis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.memory_organize_just_now)
        minutes < 60 -> stringResource(R.string.memory_organize_minutes_ago, minutes.toString())
        minutes < 60 * 24 -> stringResource(R.string.memory_organize_hours_ago, (minutes / 60).toString())
        else -> stringResource(R.string.memory_organize_days_ago, (minutes / (60 * 24)).toString())
    }
}
