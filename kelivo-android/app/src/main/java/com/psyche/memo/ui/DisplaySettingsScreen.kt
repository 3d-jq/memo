package com.psyche.memo.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.MessageCircleMore
import com.composables.icons.lucide.LetterText
import com.composables.icons.lucide.Eclipse
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Vibrate
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.RectangleHorizontal
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.AppLocale
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.MemoTheme
import com.psyche.memo.ui.theme.paletteById

/**
 * Display settings ("Preferences" / 偏好) matching kelivo's
 * display_settings_page.dart. Kelivo keeps the app-language picker here, one
 * level below the settings home page — this screen hosts it (theme row above,
 * exactly like the original ordering).
 */
@Composable
fun DisplaySettingsScreen(
    container: AppContainerImpl,
    appLocale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
    onOpenChatItemDisplay: () -> Unit,
    onOpenRendering: () -> Unit,
    onBack: () -> Unit,
) {
    var languageSheetVisible by remember { mutableStateOf(false) }
    val palette = remember {
        val raw = container.preferenceRepository.readLocal(MemoTheme.PALETTE_KEY)
        paletteById(raw?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "default")
    }
    val paletteName = if (LocalConfiguration.current.locales[0].language == "zh") {
        palette.zhName
    } else {
        palette.enName
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Mirrors kelivo's ListView padding: LTRB(16, 12, 16, 16).
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        Lucide.ArrowLeft,
                        contentDescription = stringResource(UiR.string.settings_page_back_button),
                    )
                }
                Text(
                    text = stringResource(UiR.string.settings_page_display),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        item {
            SectionCard {
                SettingsRow(
                    Lucide.Palette,
                    stringResource(UiR.string.display_settings_page_theme_settings_title),
                    detailText = paletteName,
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.Languages,
                    stringResource(UiR.string.display_settings_page_language_title),
                    detailText = stringResource(languageLabelRes(appLocale)),
                    onTap = { languageSheetVisible = true },
                )
                DividerRow()
                // 以下行序与图标逐一对照 display_settings_page.dart L112-556。
                // 子页内容按批次移植：本批先呈现主屏全量行（结构 1:1）。
                SettingsRow(
                    Lucide.MessageCircleMore,
                    stringResource(UiR.string.display_settings_page_chat_item_display_title),
                    onTap = onOpenChatItemDisplay,
                )
                DividerRow()
                SettingsRow(
                    Lucide.LetterText,
                    stringResource(UiR.string.display_settings_page_rendering_settings_title),
                    onTap = onOpenRendering,
                )
                DividerRow()
                SettingsRow(
                    Lucide.Eclipse,
                    stringResource(UiR.string.display_settings_page_behavior_startup_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.Image,
                    stringResource(UiR.string.image_settings_page_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.MessageSquare,
                    stringResource(UiR.string.message_style_settings_page_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.RefreshCw,
                    stringResource(UiR.string.settings_page_auto_retry),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.Vibrate,
                    stringResource(UiR.string.display_settings_page_haptics_settings_title),
                    onTap = {},
                )
                DividerRow()
                // L200-231: Android-only background chat row, detail = mode
                // label (移植版尚无后台会话能力，默认 Off —— 与
                // AndroidBackgroundChatMode.off 的 l10n 一致)。
                SettingsRow(
                    Lucide.Monitor,
                    stringResource(UiR.string.display_settings_page_android_background_chat_title),
                    detailText = stringResource(UiR.string.android_background_option_off),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.Type,
                    stringResource(UiR.string.display_settings_page_app_font_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.Code,
                    stringResource(UiR.string.display_settings_page_code_font_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.CaseSensitive,
                    stringResource(UiR.string.display_settings_page_chat_font_size_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.ArrowDown,
                    stringResource(UiR.string.display_settings_page_auto_scroll_idle_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.Image,
                    stringResource(UiR.string.display_settings_page_chat_background_mask_title),
                    onTap = {},
                )
                DividerRow()
                SettingsRow(
                    Lucide.RectangleHorizontal,
                    stringResource(UiR.string.display_settings_page_chat_input_background_opacity_title),
                    onTap = {},
                )
            }
        }
    }

    if (languageSheetVisible) {
        LanguageSheet(
            current = appLocale,
            onSelect = {
                onLocaleChange(it)
                languageSheetVisible = false
            },
            onDismiss = { languageSheetVisible = false },
        )
    }
}
