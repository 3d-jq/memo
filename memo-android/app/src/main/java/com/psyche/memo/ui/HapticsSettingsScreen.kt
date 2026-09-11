package com.psyche.memo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ToggleRight
import com.composables.icons.lucide.PanelRight
import com.composables.icons.lucide.ListOrdered
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Vibrate
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of display_settings_page.dart L2550-2634 — HapticsSettingsPage.
 * 6 switches; keys are the original Flutter strings (settings_provider.dart).
 */
@Composable
fun HapticsSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, if (value) "1" else "0")
    }

    var global by remember { mutableStateOf(readBool(HapticsSettings.KEY_GLOBAL, true)) }
    var iosSwitch by remember { mutableStateOf(readBool(HapticsSettings.KEY_IOS_SWITCH, true)) }
    var onSidebar by remember { mutableStateOf(readBool(HapticsSettings.KEY_DRAWER, true)) }
    var onListItemTap by remember { mutableStateOf(readBool(HapticsSettings.KEY_LIST_ITEM_TAP, true)) }
    var onCardTap by remember { mutableStateOf(readBool(HapticsSettings.KEY_CARD_TAP, true)) }
    var onGenerate by remember { mutableStateOf(readBool(HapticsSettings.KEY_GENERATE, false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.display_settings_page_haptics_settings_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            // 分类重组（用户要求对齐设置主屏的分组卡片形态）：总开关 /
            // 交互触感 / 生成触感。行顺序与 prefs key 均保持源序不动。
            item(key = "h_global") {
                SectionHeader(stringResource(UiR.string.haptics_section_master), first = true)
            }
            item(key = "c_global") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Vibrate,
                        stringResource(UiR.string.display_settings_page_haptics_global_title),
                        value = global,
                        onToggle = { global = it; writeBool(HapticsSettings.KEY_GLOBAL, it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ToggleRight,
                        stringResource(UiR.string.display_settings_page_haptics_ios_switch_title),
                        value = iosSwitch,
                        onToggle = { iosSwitch = it; writeBool(HapticsSettings.KEY_IOS_SWITCH, it) },
                    )
                }
            }
            item(key = "gap_global") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_interaction") {
                SectionHeader(stringResource(UiR.string.haptics_section_interaction))
            }
            item(key = "c_interaction") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.PanelRight,
                        stringResource(UiR.string.display_settings_page_haptics_on_sidebar_title),
                        value = onSidebar,
                        onToggle = { onSidebar = it; writeBool(HapticsSettings.KEY_DRAWER, it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ListOrdered,
                        stringResource(UiR.string.display_settings_page_haptics_on_list_item_tap_title),
                        value = onListItemTap,
                        onToggle = { onListItemTap = it; writeBool(HapticsSettings.KEY_LIST_ITEM_TAP, it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Square,
                        stringResource(UiR.string.display_settings_page_haptics_on_card_tap_title),
                        value = onCardTap,
                        onToggle = { onCardTap = it; writeBool(HapticsSettings.KEY_CARD_TAP, it) },
                    )
                }
            }
            item(key = "gap_interaction") { Spacer(Modifier.height(12.dp)) }
            item(key = "h_generation") {
                SectionHeader(stringResource(UiR.string.haptics_section_generation))
            }
            item(key = "c_generation") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        Lucide.Vibrate,
                        stringResource(UiR.string.display_settings_page_haptics_on_generate_title),
                        value = onGenerate,
                        onToggle = { onGenerate = it; writeBool(HapticsSettings.KEY_GENERATE, it) },
                    )
                }
            }
            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
        }
    }
}
