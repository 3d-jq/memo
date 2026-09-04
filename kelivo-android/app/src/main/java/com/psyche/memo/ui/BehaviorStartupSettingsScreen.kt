package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.CornerDownLeft
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.ImageOff
import com.composables.icons.lucide.ListOrdered
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.MessageCirclePlus
import com.composables.icons.lucide.MessageCircleWarning
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Shuffle
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.TextSelect
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UnfoldVertical
import com.composables.icons.lucide.WrapText
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of display_settings_page.dart L1998-2385 —
 * BehaviorStartupSettingsPage. Fields map 1:1 to SettingsProvider fields;
 * prefs keys use the original Flutter strings (display_*_v1 /
 * chat_*_v1 / suggestion_insert_on_tap_only_v1). Threshold defaults 5000,
 * clamped 1..999999 (settings_provider.dart:4879-4881).
 */
@Composable
fun BehaviorStartupSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readLocal(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeLocal(key, if (value) "1" else "0")
    }
    fun readInt(key: String, default: Int): Int =
        container.preferenceRepository.readLocal(key)?.toIntOrNull() ?: default

    var autoCollapseThinking by remember { mutableStateOf(readBool("display_auto_collapse_thinking_v1", true)) }
    var collapseThinkingSteps by remember { mutableStateOf(readBool("display_collapse_thinking_steps_v1", true)) }
    var showToolResultSummary by remember { mutableStateOf(readBool("display_show_tool_result_summary_v1", false)) }
    var hideToolResultImages by remember { mutableStateOf(readBool("display_hide_tool_result_images_v1", false)) }
    var insertSuggestionOnTapOnly by remember { mutableStateOf(readBool("suggestion_insert_on_tap_only_v1", false)) }
    var regenerateDeleteTrailing by remember { mutableStateOf(readBool("display_regenerate_delete_trailing_messages_v1", false)) }
    var showRegenerateConfirm by remember { mutableStateOf(readBool("display_show_regenerate_confirm_dialog_v1", true)) }
    var forkKeepMessageVersions by remember { mutableStateOf(readBool("chat_fork_keep_message_versions_v1", false)) }
    var keepThinkingAndToolCards by remember { mutableStateOf(readBool("chat_edit_assistant_keep_thinking_tool_cards_v1", false)) }
    var showAppUpdates by remember { mutableStateOf(readBool("display_show_app_updates_v1", true)) }
    var keepScreenOn by remember { mutableStateOf(readBool("display_keep_screen_on_during_generation_v1", false)) }
    var showMessageNav by remember { mutableStateOf(readBool("display_show_message_nav_v1", true)) }
    var showChatListDate by remember { mutableStateOf(readBool("display_show_chat_list_date_v1", false)) }
    var keepSidebarOnAssistantTap by remember { mutableStateOf(readBool("display_keep_sidebar_open_on_assistant_tap_v1", false)) }
    var keepSidebarOnTopicTap by remember { mutableStateOf(readBool("display_keep_sidebar_open_on_topic_tap_v1", false)) }
    var keepAssistantListExpanded by remember { mutableStateOf(readBool("display_keep_assistant_list_expanded_on_sidebar_close_v1", false)) }
    var newChatOnAssistantSwitch by remember { mutableStateOf(readBool("display_new_chat_on_assistant_switch_v1", false)) }
    var newChatAfterDelete by remember { mutableStateOf(readBool("display_new_chat_after_delete_v1", false)) }
    var newChatOnLaunch by remember { mutableStateOf(readBool("display_new_chat_on_launch_v1", true)) }
    var enterToSend by remember { mutableStateOf(readBool("display_enter_to_send_on_mobile_v1", false)) }
    var longPasteAsFile by remember { mutableStateOf(readBool("display_long_paste_as_file_v1", true)) }
    // 源码 settings_provider.dart:4879-4881 —— 默认 5000，clamp 1..999999。
    var longPasteThreshold by remember { mutableStateOf(readInt("display_long_paste_as_file_threshold_v1", 5000).toString()) }

    fun commitThreshold(text: String) {
        val next = (text.toIntOrNull() ?: 5000).coerceIn(1, 999999)
        longPasteThreshold = next.toString()
        container.preferenceRepository.writeLocal("display_long_paste_as_file_threshold_v1", next.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(UiR.string.settings_page_back_button),
                    tint = cs.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(UiR.string.display_settings_page_behavior_startup_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            item {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Brain,
                        stringResource(UiR.string.display_settings_page_auto_collapse_thinking_title),
                        value = autoCollapseThinking,
                        onToggle = { autoCollapseThinking = it; writeBool("display_auto_collapse_thinking_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ListTree,
                        stringResource(UiR.string.display_settings_page_collapse_thinking_steps_title),
                        value = collapseThinkingSteps,
                        onToggle = { collapseThinkingSteps = it; writeBool("display_collapse_thinking_steps_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.FileText,
                        stringResource(UiR.string.display_settings_page_show_tool_result_summary_title),
                        value = showToolResultSummary,
                        onToggle = { showToolResultSummary = it; writeBool("display_show_tool_result_summary_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ImageOff,
                        stringResource(UiR.string.display_settings_page_hide_tool_result_images_title),
                        value = hideToolResultImages,
                        onToggle = { hideToolResultImages = it; writeBool("display_hide_tool_result_images_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.TextSelect,
                        stringResource(UiR.string.display_settings_page_insert_suggestion_only_title),
                        value = insertSuggestionOnTapOnly,
                        onToggle = { insertSuggestionOnTapOnly = it; writeBool("suggestion_insert_on_tap_only_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.RefreshCw,
                        stringResource(UiR.string.display_settings_page_regenerate_delete_trailing_messages_title),
                        value = regenerateDeleteTrailing,
                        onToggle = { regenerateDeleteTrailing = it; writeBool("display_regenerate_delete_trailing_messages_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.MessageCircleWarning,
                        stringResource(UiR.string.display_settings_page_show_regenerate_confirm_dialog_title),
                        value = showRegenerateConfirm,
                        onToggle = { showRegenerateConfirm = it; writeBool("display_show_regenerate_confirm_dialog_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.GitFork,
                        stringResource(UiR.string.display_settings_page_fork_keep_message_versions_title),
                        value = forkKeepMessageVersions,
                        onToggle = { forkKeepMessageVersions = it; writeBool("chat_fork_keep_message_versions_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Pencil,
                        stringResource(UiR.string.display_settings_page_edit_assistant_keep_thinking_tool_cards_title),
                        tip = stringResource(UiR.string.display_settings_page_edit_assistant_keep_thinking_tool_cards_subtitle),
                        value = keepThinkingAndToolCards,
                        onToggle = { keepThinkingAndToolCards = it; writeBool("chat_edit_assistant_keep_thinking_tool_cards_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.BadgeInfo,
                        stringResource(UiR.string.display_settings_page_show_updates_title),
                        value = showAppUpdates,
                        onToggle = { showAppUpdates = it; writeBool("display_show_app_updates_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Sun,
                        stringResource(UiR.string.display_settings_page_keep_screen_on_during_generation_title),
                        value = keepScreenOn,
                        onToggle = { keepScreenOn = it; writeBool("display_keep_screen_on_during_generation_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ChevronRight,
                        stringResource(UiR.string.display_settings_page_message_nav_buttons_title),
                        value = showMessageNav,
                        onToggle = { showMessageNav = it; writeBool("display_show_message_nav_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Calendar,
                        stringResource(UiR.string.display_settings_page_show_chat_list_date_title),
                        value = showChatListDate,
                        onToggle = { showChatListDate = it; writeBool("display_show_chat_list_date_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.PanelLeft,
                        stringResource(UiR.string.display_settings_page_keep_sidebar_open_on_assistant_tap_title),
                        value = keepSidebarOnAssistantTap,
                        onToggle = { keepSidebarOnAssistantTap = it; writeBool("display_keep_sidebar_open_on_assistant_tap_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ListTree,
                        stringResource(UiR.string.display_settings_page_keep_sidebar_open_on_topic_tap_title),
                        value = keepSidebarOnTopicTap,
                        onToggle = { keepSidebarOnTopicTap = it; writeBool("display_keep_sidebar_open_on_topic_tap_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.UnfoldVertical,
                        stringResource(UiR.string.display_settings_page_keep_assistant_list_expanded_on_sidebar_close_title),
                        value = keepAssistantListExpanded,
                        onToggle = { keepAssistantListExpanded = it; writeBool("display_keep_assistant_list_expanded_on_sidebar_close_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Shuffle,
                        stringResource(UiR.string.display_settings_page_new_chat_on_assistant_switch_title),
                        value = newChatOnAssistantSwitch,
                        onToggle = { newChatOnAssistantSwitch = it; writeBool("display_new_chat_on_assistant_switch_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Trash2,
                        stringResource(UiR.string.display_settings_page_new_chat_after_delete_title),
                        value = newChatAfterDelete,
                        onToggle = { newChatAfterDelete = it; writeBool("display_new_chat_after_delete_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.MessageCirclePlus,
                        stringResource(UiR.string.display_settings_page_new_chat_on_launch_title),
                        value = newChatOnLaunch,
                        onToggle = { newChatOnLaunch = it; writeBool("display_new_chat_on_launch_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.CornerDownLeft,
                        stringResource(UiR.string.display_settings_page_enter_to_send_title),
                        value = enterToSend,
                        onToggle = { enterToSend = it; writeBool("display_enter_to_send_on_mobile_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Clipboard,
                        stringResource(UiR.string.display_settings_page_long_paste_as_file_title),
                        value = longPasteAsFile,
                        onToggle = { longPasteAsFile = it; writeBool("display_long_paste_as_file_v1", it) },
                    )
                    // L2243: threshold row only while long-paste-as-file is on.
                    if (longPasteAsFile) {
                        SettingsIosDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.width(36.dp)) {
                                Icon(
                                    Lucide.ListOrdered,
                                    contentDescription = null,
                                    tint = cs.onSurface.copy(alpha = 0.9f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(UiR.string.display_settings_page_long_paste_as_file_threshold_title),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                            )
                            OutlinedTextField(
                                value = longPasteThreshold,
                                onValueChange = { v ->
                                    val digits = v.filter { it.isDigit() }.take(6)
                                    longPasteThreshold = digits
                                    commitThreshold(digits)
                                },
                                modifier = Modifier.width(72.dp),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                    color = cs.onSurface,
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = cs.surfaceCardColorCompat(),
                                    unfocusedContainerColor = cs.surfaceCardColorCompat(),
                                    focusedBorderColor = cs.primary,
                                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.18f),
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(UiR.string.display_settings_page_long_paste_as_file_threshold_unit),
                                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** AppSemanticColors.surfaceCard（AppSemanticColors.kt L29）。 */
