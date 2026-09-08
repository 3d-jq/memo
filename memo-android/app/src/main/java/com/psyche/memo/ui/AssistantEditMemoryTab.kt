package com.psyche.memo.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.composables.icons.lucide.Sparkles
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.theme.LocalSemanticColors

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
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
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
                    shape = RoundedCornerShape(12.dp),
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
