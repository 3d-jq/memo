package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CircleUserRound
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Wrench
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of display_settings_page.dart L1620-1764 —
 * ChatItemDisplaySettingsPage: 13 switch rows in source order. Keys are the
 * original Flutter prefs keys (settings_provider.dart:178-221) stored as
 * "1"/"0"; defaults per settings_provider.dart (useNewAssistantAvatarUx /
 * showProviderInChatMessage are false, the rest true).
 */
private data class SwitchItem(
    val icon: ImageVector,
    val labelRes: Int,
    val tipRes: Int? = null,
    val prefsKey: String,
    // settings_provider.dart default; only the two keys below are false
    // (settings_provider.dart:4803,4825).
    val default: Boolean = true,
)

/** 分类标题 key（用户要求：子页也按设置主屏的 分组卡片 形态）。 */
private data class SwitchSection(val titleRes: Int, val items: List<SwitchItem>)

private val sections = listOf(
    SwitchSection(UiR.string.display_settings_page_section_user_rows, listOf(
        SwitchItem(Lucide.User, UiR.string.display_settings_page_show_user_avatar_title, prefsKey = "display_show_user_avatar_v1"),
        SwitchItem(Lucide.MessageCircle, UiR.string.display_settings_page_show_user_name_title, prefsKey = "display_show_user_name_v1"),
        SwitchItem(Lucide.Clock, UiR.string.display_settings_page_show_user_timestamp_title, prefsKey = "display_show_user_timestamp_v1"),
        SwitchItem(Lucide.Ellipsis, UiR.string.display_settings_page_show_user_message_actions_title, prefsKey = "display_show_user_message_actions_v1"),
    )),
    SwitchSection(UiR.string.display_settings_page_section_model_rows, listOf(
        SwitchItem(Lucide.Bot, UiR.string.display_settings_page_chat_model_icon_title, prefsKey = "display_show_model_icon_v1"),
        // settings_provider.dart:4803 — _useNewAssistantAvatarUx = false.
        SwitchItem(Lucide.Bot, UiR.string.display_settings_page_use_new_assistant_avatar_ux_title, prefsKey = "display_use_new_assistant_avatar_ux_v1", default = false),
        SwitchItem(Lucide.MessageSquare, UiR.string.display_settings_page_show_model_name_title, prefsKey = "display_show_model_name_v1"),
        // Memo 新增（上游没有这个开关）：消息里显式显示助手头像。
        SwitchItem(
            Lucide.CircleUserRound,
            UiR.string.display_settings_page_show_assistant_avatar_title,
            prefsKey = com.psyche.memo.ui.DisplayPrefs.SHOW_ASSISTANT_AVATAR,
            default = false,
            tipRes = UiR.string.display_settings_page_show_assistant_avatar_subtitle,
        ),
        SwitchItem(Lucide.Clock, UiR.string.display_settings_page_show_model_timestamp_title, prefsKey = "display_show_model_timestamp_v1"),
        // settings_provider.dart:4825 — _showProviderInChatMessage = false.
        SwitchItem(Lucide.Globe, UiR.string.display_settings_page_show_provider_in_chat_message_title, prefsKey = "display_show_provider_in_chat_message_v1", default = false),
        SwitchItem(Lucide.Type, UiR.string.display_settings_page_show_token_stats_title, prefsKey = "display_show_token_stats_v1"),
    )),
    SwitchSection(UiR.string.display_settings_page_section_card_rows, listOf(
        SwitchItem(
            Lucide.Sparkles,
            UiR.string.display_settings_page_show_thinking_cards_title,
            tipRes = UiR.string.display_settings_page_show_thinking_cards_subtitle,
            prefsKey = "display_show_thinking_cards_v1",
        ),
        SwitchItem(
            Lucide.Wrench,
            UiR.string.display_settings_page_show_tool_cards_title,
            tipRes = UiR.string.display_settings_page_show_tool_cards_subtitle,
            prefsKey = "display_show_tool_cards_v1",
        ),
    )),
)

private val switchRows: List<SwitchItem> get() = sections.flatMap { it.items }

/** L1183-1192: iOS divider — height 6, 0.6 thick, indent 54, endIndent 12. */
@Composable
private fun IosDivider() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 54.dp, end = 12.dp)
            .height(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(cs.outlineVariant.copy(alpha = 0.18f)),
        )
    }
}

@Composable
fun ChatItemDisplaySettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Boolean prefs persisted as "1"/"0"; defaults per settings_provider.dart
    // (all true except the two keys flagged above).
    var values by remember {
        mutableStateOf(switchRows.associate { it.prefsKey to it.default })
    }
    LaunchedEffect(Unit) {
        // 组合线程不碰 SQLite：LaunchedEffect 体默认跑在主线程（PORTING §5.13）
        // LaunchedEffect 体默认跑在组合线程上，里面的 readJson 是真会打 SQLite 的
        withContext(Dispatchers.IO) {
            values = switchRows.associate {
                it.prefsKey to (container.preferenceRepository.readJson(it.prefsKey)?.let { v -> v == "1" } ?: it.default)
            }
        }
}
    fun writeBool(key: String, value: Boolean) {
        values = values + (key to value)
        container.preferenceRepository.writeJson(key, if (value) "1" else "0")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.display_settings_page_chat_item_display_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            sections.forEachIndexed { sectionIndex, section ->
                item(key = "header_$sectionIndex") {
                    SectionHeader(
                        stringResource(section.titleRes),
                        first = sectionIndex == 0,
                    )
                }
                item(key = "card_$sectionIndex") {
                    SettingsSectionCard {
                        section.items.forEachIndexed { index, item ->
                            SettingsSwitchRow(
                                icon = item.icon,
                                label = stringResource(item.labelRes),
                                tip = item.tipRes?.let { stringResource(it) },
                                value = values[item.prefsKey] ?: item.default,
                                onToggle = { writeBool(item.prefsKey, it) },
                            )
                            if (index != section.items.lastIndex) SettingsIosDivider()
                        }
                    }
                }
                if (sectionIndex != sections.lastIndex) {
                    item(key = "gap_$sectionIndex") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}


