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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Square
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR

/**
 * 1:1 port of theme_advanced_settings_page.dart — layered surfaces and
 * layered sheet tiles switches (L36-60).
 */
@Composable
fun ThemeAdvancedScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { ThemeState.load(container) }

    Column(modifier = Modifier.fillMaxSize()) {
        MemoTopBar(
            title = stringResource(UiR.string.theme_advanced_settings_page_title),
            onBack = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                SectionCard {
                    SettingsSwitchRow(
                        icon = Lucide.Layers,
                        label = stringResource(UiR.string.theme_advanced_settings_page_use_layered_surfaces_title),
                        tip = stringResource(UiR.string.theme_advanced_settings_page_use_layered_surfaces_subtitle),
                        value = ThemeState.useLayeredSurfaces,
                        onToggle = { ThemeState.setLayeredSurfaces(container, it) },
                    )
                    DividerRow()
                    SettingsSwitchRow(
                        icon = Lucide.Square,
                        label = stringResource(UiR.string.theme_advanced_settings_page_use_layered_sheet_tiles_title),
                        tip = stringResource(UiR.string.theme_advanced_settings_page_use_layered_sheet_tiles_subtitle),
                        value = ThemeState.useLayeredSheetTiles,
                        onToggle = { ThemeState.setLayeredSheetTiles(container, it) },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
