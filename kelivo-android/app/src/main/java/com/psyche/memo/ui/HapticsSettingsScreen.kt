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
        container.preferenceRepository.readLocal(key)?.let { it == "1" } ?: default
    fun writeBool(key: String, value: Boolean) {
        container.preferenceRepository.writeLocal(key, if (value) "1" else "0")
    }

    var global by remember { mutableStateOf(readBool("display_haptics_global_enabled_v1", true)) }
    var iosSwitch by remember { mutableStateOf(readBool("display_haptics_ios_switch_v1", true)) }
    var onSidebar by remember { mutableStateOf(readBool("display_haptics_on_drawer_v1", false)) }
    var onListItemTap by remember { mutableStateOf(readBool("display_haptics_on_list_item_tap_v1", true)) }
    var onCardTap by remember { mutableStateOf(readBool("display_haptics_on_card_tap_v1", true)) }
    var onGenerate by remember { mutableStateOf(readBool("display_haptics_on_generate_v1", false)) }

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
                text = stringResource(UiR.string.display_settings_page_haptics_settings_title),
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
                        Lucide.Vibrate,
                        stringResource(UiR.string.display_settings_page_haptics_global_title),
                        value = global,
                        onToggle = { global = it; writeBool("display_haptics_global_enabled_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ToggleRight,
                        stringResource(UiR.string.display_settings_page_haptics_ios_switch_title),
                        value = iosSwitch,
                        onToggle = { iosSwitch = it; writeBool("display_haptics_ios_switch_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.PanelRight,
                        stringResource(UiR.string.display_settings_page_haptics_on_sidebar_title),
                        value = onSidebar,
                        onToggle = { onSidebar = it; writeBool("display_haptics_on_drawer_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.ListOrdered,
                        stringResource(UiR.string.display_settings_page_haptics_on_list_item_tap_title),
                        value = onListItemTap,
                        onToggle = { onListItemTap = it; writeBool("display_haptics_on_list_item_tap_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Square,
                        stringResource(UiR.string.display_settings_page_haptics_on_card_tap_title),
                        value = onCardTap,
                        onToggle = { onCardTap = it; writeBool("display_haptics_on_card_tap_v1", it) },
                    )
                    SettingsIosDivider()
                    SettingsSwitchRow(
                        Lucide.Vibrate,
                        stringResource(UiR.string.display_settings_page_haptics_on_generate_title),
                        value = onGenerate,
                        onToggle = { onGenerate = it; writeBool("display_haptics_on_generate_v1", it) },
                    )
                }
            }
        }
    }
}
