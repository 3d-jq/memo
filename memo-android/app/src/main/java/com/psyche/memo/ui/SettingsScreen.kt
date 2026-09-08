package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChartColumnBig
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.EthernetPort
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleWarning
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.Zap
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.AppLocale
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings page matching kelivo settings_page.dart: iOS-style SectionCard
 * groups (General / Models & Services / Data / About). Icons and row order
 * follow settings_page.dart:159-447 (C11); the warning banner appears when no
 * provider has an active model (L127-153, B1); color mode row shows the mode
 * label and opens the system/light/dark sheet (L52-96,159-165, B2); the chat
 * storage row shows a "N files · size" summary (L332-342,497-545, B4); the
 * Tool Descriptions row (L398-410, B5) and the conditional Logs row (L383-397,
 * B6) are restored. The Docs external link (L374-382, B7) is not ported: it
 * exists only to open the upstream docs site and Memo has no docs site of its
 * own, so it is dropped with the sponsor row under the brand rules.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainerImpl,
    appLocale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSearchServices: () -> Unit,
    onOpenDefaultModel: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenNetworkProxy: () -> Unit,
    onOpenToolSchema: () -> Unit,
    onOpenTtsServices: () -> Unit,
    onOpenLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // B1 — hasAnyActiveModel (settings_provider.dart:552-553).
    var hasActiveModel by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        hasActiveModel = withContext(Dispatchers.IO) {
            loadProviders(container).any { it.second.enabled && it.second.models.isNotEmpty() }
        }
    }

    // B2 — theme mode observed through ThemeState (theme_mode_v1), so the
    // label updates immediately and MainActivity reacts to the same store.
    LaunchedEffect(Unit) { ThemeState.load(container) }
    val themeMode = ThemeState.mode
    var colorModeSheetVisible by remember { mutableStateOf(false) }

    // B4 — chat storage summary (files + bytes over the app data dirs).
    var storageSummary by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(Unit) {
        storageSummary = withContext(Dispatchers.IO) {
            var count = 0L
            var bytes = 0L
            val roots = listOfNotNull(
                context.filesDir,
                context.getDatabasePath("memo.db")?.parentFile,
                context.cacheDir,
            )
            fun walk(dir: java.io.File?) {
                if (dir == null || !dir.exists()) return
                val stack = ArrayDeque<java.io.File>()
                stack.add(dir)
                while (stack.isNotEmpty()) {
                    val f = stack.removeLast()
                    val children = runCatching { f.listFiles() }.getOrNull() ?: continue
                    for (child in children) {
                        if (child.isDirectory) stack.add(child) else {
                            count += 1
                            bytes += runCatching { child.length() }.getOrDefault(0L)
                        }
                    }
                }
            }
            roots.forEach { walk(it) }
            count.toInt() to bytes
        }
    }

    fun fmtBytes(bytes: Long): String {
        val kb = 1024
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(java.util.Locale.US, "%.2f GB", bytes / gb.toDouble())
            bytes >= mb -> String.format(java.util.Locale.US, "%.2f MB", bytes / mb.toDouble())
            bytes >= kb -> String.format(java.util.Locale.US, "%.1f KB", bytes / kb.toDouble())
            else -> "$bytes B"
        }
    }

    // B6 — Logs row visible only when a log channel is enabled
    // (settings_provider.dart:324-327,1141-1146: request/context default on,
    // flutter default off).
    fun logEnabled(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readLocal(key)?.let { it == "1" } ?: default
    val logsVisible = logEnabled("request_log_enabled_v1", true) ||
        logEnabled("context_log_enabled_v1", true) ||
        logEnabled("flutter_log_enabled_v1", false)

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
            // B1 — warning banner when no model is active (L127-153).
            if (!hasActiveModel) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(cs.errorContainer.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Lucide.MessageCircleWarning,
                            contentDescription = null,
                            tint = cs.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(UiR.string.settings_page_warning_message),
                            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.8f)),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item { SectionHeader(stringResource(UiR.string.settings_page_general_section), first = !hasActiveModel) }
            item {
                SectionCard {
                    // B2 — color mode detail + three-option sheet (L52-96,159-165).
                    SettingsRow(
                        Lucide.SunMoon,
                        stringResource(UiR.string.settings_page_color_mode),
                        detailText = stringResource(
                            when (themeMode) {
                                "light" -> UiR.string.settings_page_light_mode
                                "dark" -> UiR.string.settings_page_dark_mode
                                else -> UiR.string.settings_page_system_mode
                            },
                        ),
                        onTap = { colorModeSheetVisible = true },
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.Monitor,
                        stringResource(UiR.string.settings_page_display),
                        onTap = onOpenDisplay,
                    )
                    DividerRow()
                    SettingsRow(Lucide.Bot, stringResource(UiR.string.settings_page_assistant), onTap = onOpenAssistants)
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.settings_page_models_services_section)) }
            item {
                SectionCard {
                    // C11 — icons per settings_page.dart:176-323.
                    SettingsRow(Lucide.Heart, stringResource(UiR.string.settings_page_default_model), onTap = onOpenDefaultModel)
                    DividerRow()
                    SettingsRow(Lucide.Boxes, stringResource(UiR.string.settings_page_providers), onTap = onOpenProviders)
                    DividerRow()
                    SettingsRow(Lucide.Earth, stringResource(UiR.string.settings_page_search), onTap = onOpenSearchServices)
                    DividerRow()
                    // settings_page.dart L311: TTS row opens TtsServicesPage directly.
                    SettingsRow(Lucide.Volume2, stringResource(UiR.string.settings_page_tts), onTap = onOpenTtsServices)
                    DividerRow()
                    SettingsRow(Lucide.Terminal, stringResource(UiR.string.settings_page_mcp), onTap = {})
                    DividerRow()
                    SettingsRow(Lucide.BookOpen, stringResource(UiR.string.settings_page_world_book), onTap = {})
                    DividerRow()
                    SettingsRow(Lucide.Brain, stringResource(UiR.string.settings_page_memory), onTap = onOpenMemory)
                    DividerRow()
                    SettingsRow(Lucide.Zap, stringResource(UiR.string.settings_page_quick_phrase), onTap = {})
                    DividerRow()
                    SettingsRow(Lucide.Layers, stringResource(UiR.string.settings_page_instruction_injection), onTap = {})
                    DividerRow()
                    SettingsRow(Lucide.EthernetPort, stringResource(UiR.string.settings_page_network_proxy), onTap = onOpenNetworkProxy)
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.settings_page_data_section)) }
            item {
                SectionCard {
                    SettingsRow(Lucide.Database, stringResource(UiR.string.settings_page_backup), onTap = {})
                    DividerRow()
                    // B4 — chat storage summary (L332-342,497-545); the row
                    // opens the storage space page (settings_page.dart L337).
                    val summary = storageSummary
                    SettingsRow(
                        Lucide.HardDrive,
                        stringResource(UiR.string.settings_page_chat_storage),
                        detailText = if (summary == null) {
                            stringResource(UiR.string.settings_page_calculating)
                        } else {
                            stringResource(
                                UiR.string.settings_page_files_count,
                                summary.first.toString(),
                                fmtBytes(summary.second),
                            )
                        },
                        onTap = onOpenStorage,
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.settings_page_about_section)) }
            item {
                SectionCard {
                    SettingsRow(Lucide.BadgeInfo, stringResource(UiR.string.settings_page_about), onTap = onOpenAbout)
                    DividerRow()
                    SettingsRow(Lucide.ChartColumnBig, stringResource(UiR.string.settings_page_statistics), onTap = onOpenStats)
                    // B7 — Docs row (L374-382) deliberately dropped: it only
                    // exists to open the upstream docs site, which is not a
                    // Memo endpoint.
                    // B6 — Logs row conditional (L383-397).
                    if (logsVisible) {
                        DividerRow()
                        SettingsRow(Lucide.FileText, stringResource(UiR.string.settings_page_logs), onTap = onOpenLogs)
                    }
                    // B5 — Tool Descriptions row (L398-410).
                    DividerRow()
                    SettingsRow(Lucide.Wrench, stringResource(UiR.string.tool_schema_settings_page_title), onTap = onOpenToolSchema)
                    // Sponsor row (L411-421) deliberately dropped: the whole
                    // sponsor page is upstream (kelivo afdian / WeChat QR /
                    // kelivo sponsors list) and is Memo-ized away per the
                    // brand rules.
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // L52-96,159-165 — color mode sheet: system / light / dark.
    if (colorModeSheetVisible) {
        ModalBottomSheet(onDismissRequest = { colorModeSheetVisible = false }) {
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                ColorModeOption(
                    labelRes = UiR.string.settings_page_system_mode,
                    icon = Lucide.Monitor,
                    value = "system",
                    current = themeMode,
                ) { mode ->
                    ThemeState.setMode(container, mode)
                    colorModeSheetVisible = false
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.18f))
                ColorModeOption(
                    labelRes = UiR.string.settings_page_light_mode,
                    icon = Lucide.Sun,
                    value = "light",
                    current = themeMode,
                ) { mode ->
                    ThemeState.setMode(container, mode)
                    colorModeSheetVisible = false
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.18f))
                ColorModeOption(
                    labelRes = UiR.string.settings_page_dark_mode,
                    icon = Lucide.Moon,
                    value = "dark",
                    current = themeMode,
                ) { mode ->
                    ThemeState.setMode(container, mode)
                    colorModeSheetVisible = false
                }
            }
        }
    }
}

@Composable
private fun ColorModeOption(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val selected = value == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (selected) cs.primary else cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}
