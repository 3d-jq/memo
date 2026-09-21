package com.psyche.memo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Central access to localized strings for the app UI. All text shown to the
 * user comes from the generated strings.xml resources (values/zh/zh-rTW) —
 * never hardcode copy in screens.
 */
object UIStrings {
    @Composable
    fun inputBarHint(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_hint)

    @Composable
    fun temporaryChatEmptyMessage(): String =
        stringResource(com.psyche.memo.ui.R.string.temporary_chat_empty_message)

    @Composable
    fun temporaryChatTitle(): String = stringResource(com.psyche.memo.ui.R.string.temporary_chat_title)

    @Composable
    fun newChatTitle(): String = stringResource(com.psyche.memo.ui.R.string.title_for_locale)

    @Composable
    fun drawerSearchHint(): String = stringResource(com.psyche.memo.ui.R.string.side_drawer_search_hint)

    @Composable
    fun drawerChooseAssistant(): String =
        stringResource(com.psyche.memo.ui.R.string.side_drawer_choose_assistant_title)

    @Composable
    fun drawerPinned(): String = stringResource(com.psyche.memo.ui.R.string.side_drawer_pinned_label)

    @Composable
    fun drawerToday(): String = stringResource(com.psyche.memo.ui.R.string.side_drawer_date_today)

    @Composable
    fun drawerYesterday(): String = stringResource(com.psyche.memo.ui.R.string.side_drawer_date_yesterday)

    @Composable
    fun settingsTitle(): String = stringResource(com.psyche.memo.ui.R.string.settings_page_title)

    @Composable
    fun modelSearchHint(): String = stringResource(com.psyche.memo.ui.R.string.model_select_sheet_search_hint)

    @Composable
    fun defaultAssistant(): String = stringResource(com.psyche.memo.ui.R.string.home_page_default_assistant)

    @Composable
    fun selectModelTooltip(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_select_model_tooltip)

    @Composable
    fun onlineSearchTooltip(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_online_search_tooltip)

    @Composable
    fun reasoningStrengthTooltip(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_reasoning_strength_tooltip)

    @Composable
    fun mcpServersTooltip(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_mcp_servers_tooltip)

    @Composable
    fun moreTooltip(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_more_tooltip)

    @Composable
    fun voiceInputTooltip(): String = stringResource(com.psyche.memo.ui.R.string.chat_input_bar_voice_input_tooltip)

    @Composable
    fun miniMapTooltip(): String = stringResource(com.psyche.memo.ui.R.string.mini_map_tooltip)

    @Composable
    fun temporaryChatToggleTooltip(): String = stringResource(com.psyche.memo.ui.R.string.temporary_chat_toggle_tooltip)
}
