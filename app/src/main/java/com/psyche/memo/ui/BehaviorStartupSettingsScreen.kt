package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.Check
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviorStartupSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, if (value) "1" else "0")
    }
    fun readInt(key: String, default: Int): Int =
        container.preferenceRepository.readJson(key)?.toIntOrNull() ?: default

    var autoCollapseThinking by remember { mutableStateOf(readBool("display_auto_collapse_thinking_v1", true)) }
    // settings_provider.dart:4721 —— _collapseThinkingSteps = false。
    var collapseThinkingSteps by remember { mutableStateOf(readBool("display_collapse_thinking_steps_v1", false)) }
    var showToolResultSummary by remember { mutableStateOf(readBool("display_show_tool_result_summary_v1", false)) }
    var hideToolResultImages by remember { mutableStateOf(readBool("display_hide_tool_result_images_v1", false)) }
    var insertSuggestionOnTapOnly by remember { mutableStateOf(readBool("suggestion_insert_on_tap_only_v1", false)) }
    var regenerateDeleteTrailing by remember { mutableStateOf(readBool("display_regenerate_delete_trailing_messages_v1", false)) }
    var showRegenerateConfirm by remember { mutableStateOf(readBool("display_show_regenerate_confirm_dialog_v1", true)) }
    var forkKeepMessageVersions by remember { mutableStateOf(readBool("chat_fork_keep_message_versions_v1", false)) }
    var keepThinkingAndToolCards by remember { mutableStateOf(readBool("chat_edit_assistant_keep_thinking_tool_cards_v1", false)) }
    var showAppUpdates by remember { mutableStateOf(readBool("display_show_app_updates_v1", true)) }
    // 源码 display_settings_page.dart:2139-2153 / settings_provider.dart:4933,5008-5024
    // —— 消息导航为三态（always/scroll/never）；旧 bool 键 display_show_message_nav_v1
    // 仅作迁移回退（true→scroll / false→never）。
    val initialNavMode = run {
        when (container.preferenceRepository.readJson("display_mobile_message_nav_buttons_mode_v1")) {
            "always" -> "always"
            "never" -> "never"
            "scroll" -> "scroll"
            else -> if (readBool("display_show_message_nav_v1", true)) "scroll" else "never"
        }
    }
    var showMessageNavMode by remember { mutableStateOf(initialNavMode) }
    var navModeSheetVisible by remember { mutableStateOf(false) }
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
    // 已存值与草稿分离：解析失败回退当前已存值（C4）。
    var longPasteThreshold by remember { mutableIntStateOf(readInt("display_long_paste_as_file_threshold_v1", 5000)) }
    var longPasteThresholdText by remember { mutableStateOf(longPasteThreshold.toString()) }
    var thresholdFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    fun commitThreshold(text: String) {
        val parsed = text.toIntOrNull() ?: run {
            longPasteThresholdText = longPasteThreshold.toString()
            return
        }
        longPasteThreshold = parsed.coerceIn(1, 999999)
        longPasteThresholdText = longPasteThreshold.toString()
        container.preferenceRepository.writeJson("display_long_paste_as_file_threshold_v1", longPasteThreshold.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.display_settings_page_behavior_startup_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            // 分类重组（用户要求对齐设置主屏的分组卡片形态）。行顺序与 prefs
            // key 均保持源序不动，仅按功能域分 5 组卡：思考与工具卡 / 消息与
            // 编辑 / 侧边栏与会话列表 / 新对话 / 输入。
            item(key = "h_cards") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_thinking_cards), first = true)
            }
            item(key = "c_cards") {
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
                        Lucide.Pencil,
                        stringResource(UiR.string.display_settings_page_edit_assistant_keep_thinking_tool_cards_title),
                        tip = stringResource(UiR.string.display_settings_page_edit_assistant_keep_thinking_tool_cards_subtitle),
                        value = keepThinkingAndToolCards,
                        onToggle = { keepThinkingAndToolCards = it; writeBool("chat_edit_assistant_keep_thinking_tool_cards_v1", it) },
                    )
                }
            }
            item(key = "gap_cards") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_msg") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_messages_editing))
            }
            item(key = "c_msg") {
                SettingsSectionCard {
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
                    // L2139-2153 —— 三态 nav row + 底部弹层（C6）。
                    SettingsRow(
                        icon = Lucide.ChevronRight,
                        label = stringResource(UiR.string.display_settings_page_message_nav_buttons_title),
                        detailText = stringResource(
                            when (showMessageNavMode) {
                                "always" -> UiR.string.display_settings_page_message_nav_buttons_mode_always
                                "never" -> UiR.string.display_settings_page_message_nav_buttons_mode_never
                                else -> UiR.string.display_settings_page_message_nav_buttons_mode_scroll
                            },
                        ),
                        onTap = { navModeSheetVisible = true },
                    )
                }
            }
            item(key = "gap_msg") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_side") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_sidebar_list))
            }
            item(key = "c_side") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.BadgeInfo,
                        stringResource(UiR.string.display_settings_page_show_updates_title),
                        value = showAppUpdates,
                        onToggle = { showAppUpdates = it; writeBool("display_show_app_updates_v1", it) },
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
                }
            }
            item(key = "gap_side") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_new") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_new_chat))
            }
            item(key = "c_new") {
                SettingsSectionCard {
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
                }
            }
            item(key = "gap_new") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_input") {
                SectionHeader(stringResource(UiR.string.display_settings_page_section_input))
            }
            item(key = "c_input") {
                SettingsSectionCard {
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
                                value = longPasteThresholdText,
                                onValueChange = { v ->
                                    longPasteThresholdText = v.filter { it.isDigit() }.take(6)
                                },
                                modifier = Modifier
                                    .width(72.dp)
                                    .onFocusChanged {
                                        if (thresholdFocused && !it.hasFocus) commitThreshold(longPasteThresholdText)
                                        thresholdFocused = it.hasFocus
                                    },
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
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
        }
    }

    // L1574-1616 —— always / scroll / never 三选弹层。
    if (navModeSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { navModeSheetVisible = false }, dragHandle = null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoSheetHandle(trailingGap = 0.dp)
                listOf(
                    UiR.string.display_settings_page_message_nav_buttons_mode_always to "always",
                    UiR.string.display_settings_page_message_nav_buttons_mode_scroll to "scroll",
                    UiR.string.display_settings_page_message_nav_buttons_mode_never to "never",
                ).forEach { (labelRes, mode) ->
                    MemoSheetOptionRow(
                        label = stringResource(labelRes),
                        selected = mode == showMessageNavMode,
                        onClick = {
                            showMessageNavMode = mode
                            container.preferenceRepository.writeJson("display_mobile_message_nav_buttons_mode_v1", mode)
                            navModeSheetVisible = false
                        },
                    )
                }
            }
        }
    }
}

/** AppSemanticColors.surfaceCard（AppSemanticColors.kt L29）。 */
