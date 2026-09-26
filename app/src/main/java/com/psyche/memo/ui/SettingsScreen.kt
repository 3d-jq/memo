package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleWarning
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Video
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
    /** 「设置 → 模型与服务」里的浏览器功能入口（用户 2026-09-26 点名要从偏好设置搬过来）。 */
    onOpenBrowserFeature: () -> Unit,
    /** 生成服务（自研功能）：设置里两个入口。 */
    onOpenImageGeneration: () -> Unit,
    onOpenVideoGeneration: () -> Unit,
    onOpenDefaultModel: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenNetworkProxy: () -> Unit,
    onOpenToolSchema: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenInstructionInjection: () -> Unit,
    onOpenWorldBook: () -> Unit,
    onOpenTtsServices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSponsor: () -> Unit,
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

    // B4 — chat storage summary. 与「存储空间」页共用同一份计算（原版也是同一个
    // `StorageUsageService.computeReport`，行与页面口径必须一致）。此前这里自己
    // 又写了一遍遍历：口径不同，还把沙箱 rootfs 的三万多个文件算进「聊天记录」，
    // 于是数字永远填不上、一直显示「统计中」（用户 2026-09-15）。
    // 失败也必须落地成一个可见状态 —— 抛异常就永远停在「统计中」是最糟的。
    var storageSummary by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var storageFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val report = com.psyche.memo.ui.StorageUsage.computeReport(context)
                report.totalFiles to report.totalBytes
            }.getOrNull()
        }
        if (result == null) {
            android.util.Log.w("SettingsScreen", "chat storage summary failed")
            storageFailed = true
        } else {
            storageSummary = result
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
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default
    val logsVisible = logEnabled("request_log_enabled_v1", true) ||
        logEnabled("context_log_enabled_v1", true) ||
        logEnabled("flutter_log_enabled_v1", false)

    // Fixed top bar (like kelivo's Scaffold AppBar) so the back button never
    // scrolls away; the body list scrolls under it.
    Column(modifier = Modifier.fillMaxSize()) {
        MemoTopBar(
            title = stringResource(UiR.string.settings_page_title),
            onBack = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        )
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
                            .background(cs.errorContainer.copy(alpha = 0.30f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
                    // 浏览器是设备能力（与搜索/MCP/技能/工作区同一族），不是显示偏好 —— 入口就在这一组。
                    SettingsRow(Lucide.Globe, stringResource(UiR.string.browser_feature_title), onTap = onOpenBrowserFeature)
                    DividerRow()
                    // 生成图片 / 生成视频（自研功能，上游没有）：两个入口各管一类服务。
                    SettingsRow(Lucide.Image, stringResource(UiR.string.settings_page_image_generation), onTap = onOpenImageGeneration)
                    DividerRow()
                    SettingsRow(Lucide.Video, stringResource(UiR.string.settings_page_video_generation), onTap = onOpenVideoGeneration)
                    DividerRow()
                    // settings_page.dart L311: TTS row opens TtsServicesPage directly.
                    SettingsRow(Lucide.Volume2, stringResource(UiR.string.settings_page_tts), onTap = onOpenTtsServices)
                    DividerRow()
                    SettingsRow(Lucide.Terminal, stringResource(UiR.string.settings_page_mcp), onTap = onOpenMcp)
                    DividerRow()
                    SettingsRow(Lucide.Puzzle, stringResource(UiR.string.settings_page_skills), onTap = onOpenSkills)
                    DividerRow()
                    SettingsRow(Lucide.HardDrive, stringResource(UiR.string.workspace_page_title), onTap = onOpenWorkspaces)
                    DividerRow()
                    SettingsRow(Lucide.BookOpen, stringResource(UiR.string.settings_page_world_book), onTap = onOpenWorldBook)
                    DividerRow()
                    SettingsRow(Lucide.Brain, stringResource(UiR.string.settings_page_memory), onTap = onOpenMemory)
                    DividerRow()
                    SettingsRow(Lucide.Zap, stringResource(UiR.string.settings_page_quick_phrase), onTap = onOpenQuickPhrases)
                    DividerRow()
                    SettingsRow(Lucide.Layers, stringResource(UiR.string.settings_page_instruction_injection), onTap = onOpenInstructionInjection)
                    DividerRow()
                    SettingsRow(Lucide.EthernetPort, stringResource(UiR.string.settings_page_network_proxy), onTap = onOpenNetworkProxy)
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.settings_page_data_section)) }
            item {
                SectionCard {
                    SettingsRow(Lucide.Database, stringResource(UiR.string.settings_page_backup), onTap = onOpenBackup)
                    DividerRow()
                    // B4 — chat storage summary (L332-342,497-545); the row
                    // opens the storage space page (settings_page.dart L337).
                    val summary = storageSummary
                    SettingsRow(
                        Lucide.HardDrive,
                        stringResource(UiR.string.settings_page_chat_storage),
                        detailText = when {
                            summary != null -> stringResource(
                                UiR.string.settings_page_files_count,
                                summary.first.toString(),
                                fmtBytes(summary.second),
                            )
                            // 统计失败也要落地：永远卡在「统计中」是最糟的状态。
                            storageFailed -> "—"
                            else -> stringResource(UiR.string.settings_page_calculating)
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
                    // Sponsor row (L411-421). Ported in batch 1 of the
                    // all-UI pass: re-added as a thin shell even though the
                    // content (afdian/WeChat/sponsors list) is upstream.
                    DividerRow()
                    SettingsRow(Lucide.Heart, stringResource(UiR.string.settings_page_sponsor), onTap = onOpenSponsor)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // L52-96,159-165 — color mode sheet: system / light / dark.
    if (colorModeSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { colorModeSheetVisible = false }, dragHandle = null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoSheetHandle(trailingGap = 0.dp)
                listOf(
                    Triple(UiR.string.settings_page_system_mode, Lucide.Monitor, "system"),
                    Triple(UiR.string.settings_page_light_mode, Lucide.Sun, "light"),
                    Triple(UiR.string.settings_page_dark_mode, Lucide.Moon, "dark"),
                ).forEach { (labelRes, icon, mode) ->
                    MemoSheetOptionRow(
                        label = stringResource(labelRes),
                        icon = icon,
                        selected = mode == themeMode,
                        onClick = {
                            ThemeState.setMode(container, mode)
                            colorModeSheetVisible = false
                        },
                    )
                }
            }
        }
    }
}

