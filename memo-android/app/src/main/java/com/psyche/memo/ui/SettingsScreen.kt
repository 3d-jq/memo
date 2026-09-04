package com.psyche.memo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Zap
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.AppLocale
import com.psyche.memo.ui.R as UiR

/**
 * Settings page matching kelivo settings_page.dart: iOS-style SectionCard
 * groups (General / Models & Services / Data / About). The language row lives
 * one level deeper — kelivo puts it inside Display settings
 * (display_settings_page.dart), so this page only navigates there.
 */
@Composable
fun SettingsScreen(
    container: AppContainerImpl,
    appLocale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
) {
    // Fixed top bar (like kelivo's Scaffold AppBar) so the back button never
    // scrolls away; the body list scrolls under it.
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(UiR.string.settings_page_back_button),
                )
            }
            Text(
                text = stringResource(UiR.string.settings_page_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // Mirrors kelivo's ListView padding: LTRB(16, 12, 16, 16).
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item { SectionHeader(stringResource(UiR.string.settings_page_general_section), first = true) }
        item {
            SectionCard {
                SettingsRow(Lucide.SunMoon, stringResource(UiR.string.settings_page_color_mode), onTap = {})
                DividerRow()
                SettingsRow(
                    Lucide.Monitor,
                    stringResource(UiR.string.settings_page_display),
                    onTap = onOpenDisplay,
                )
                DividerRow()
                SettingsRow(Lucide.Bot, stringResource(UiR.string.settings_page_assistant), onTap = {})
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { SectionHeader(stringResource(UiR.string.settings_page_models_services_section)) }
        item {
            SectionCard {
                SettingsRow(Lucide.Boxes, stringResource(UiR.string.settings_page_default_model), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Database, stringResource(UiR.string.settings_page_providers), onTap = onOpenProviders)
                DividerRow()
                SettingsRow(Lucide.Search, stringResource(UiR.string.settings_page_search), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Volume2, stringResource(UiR.string.settings_page_tts), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Hammer, stringResource(UiR.string.settings_page_mcp), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Globe, stringResource(UiR.string.settings_page_world_book), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Settings, stringResource(UiR.string.settings_page_memory), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Zap, stringResource(UiR.string.settings_page_quick_phrase), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Bot, stringResource(UiR.string.settings_page_instruction_injection), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Monitor, stringResource(UiR.string.settings_page_network_proxy), onTap = {})
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { SectionHeader(stringResource(UiR.string.settings_page_data_section)) }
        item {
            SectionCard {
                SettingsRow(Lucide.Database, stringResource(UiR.string.settings_page_backup), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Moon, stringResource(UiR.string.settings_page_chat_storage), onTap = {})
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
        item { SectionHeader(stringResource(UiR.string.settings_page_about_section)) }
        item {
            SectionCard {
                SettingsRow(Lucide.Settings, stringResource(UiR.string.settings_page_about), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Boxes, stringResource(UiR.string.settings_page_statistics), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Monitor, stringResource(UiR.string.settings_page_docs), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Bot, stringResource(UiR.string.settings_page_logs), onTap = {})
                DividerRow()
                SettingsRow(Lucide.Palette, stringResource(UiR.string.settings_page_sponsor), onTap = {})
            }
        }
        }
    }
}
