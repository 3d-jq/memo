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
