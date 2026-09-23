package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.psyche.memo.ui.slider.MemoSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Check
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
import com.psyche.memo.ui.theme.themePaletteById

/**
 * Display settings ("Preferences" / 偏好) matching kelivo's
 * display_settings_page.dart. Rows 1:1 with display_settings_page.dart
 * L112-556; the Android background-chat row (L184-229) opens the
 * on/on_notify/off sheet (L469-544), the font rows show real detail text and
 * offer the local-file/reset sheet (L248-306), and chat font size /
 * auto-scroll idle / background mask / input opacity rows show live details
 * with slider sheets (L319-404, 612-1156).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    container: AppContainerImpl,
    appLocale: AppLocale,
    onLocaleChange: (AppLocale) -> Unit,
    onOpenChatItemDisplay: () -> Unit,
    onOpenRendering: () -> Unit,
    onOpenBehavior: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenMessageStyle: () -> Unit,
    onOpenAutoRetry: () -> Unit,
    onOpenHaptics: () -> Unit,
    onOpenTheme: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var languageSheetVisible by remember { mutableStateOf(false) }
    var backgroundChatSheetVisible by remember { mutableStateOf(false) }
    var fontSizeSheetVisible by remember { mutableStateOf(false) }
    var autoScrollSheetVisible by remember { mutableStateOf(false) }
    var maskSheetVisible by remember { mutableStateOf(false) }
    var inputOpacitySheetVisible by remember { mutableStateOf(false) }
    var fontSheetVisible by remember { mutableStateOf(false) }
    var fontSheetTarget by remember { mutableStateOf<String?>(null) } // "app" | "code"

    // ---- reactive pref reads (B8/B9-B14) --------------------------------
    var paletteName by remember { mutableStateOf("") }
    var themeMode by remember { mutableStateOf("system") }
    var backgroundChatMode by remember { mutableStateOf("off") }
    var liveUpdateEnabled by remember { mutableStateOf(false) }
    var appFontAlias by remember { mutableStateOf<String?>(null) }
    var appFontFamily by remember { mutableStateOf<String?>(null) }
    var codeFontAlias by remember { mutableStateOf<String?>(null) }
    var codeFontFamily by remember { mutableStateOf<String?>(null) }
    // settings_provider.dart:5041-5088 — scale 1.0 / autoScroll on / idle 8 /
    // mask 1.0 / opacity light 0.8236 / dark 0.7396.
    var chatFontScale by remember { mutableDoubleStateOf(1.0) }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var autoScrollIdleSeconds by remember { mutableIntStateOf(8) }
    var maskStrength by remember { mutableDoubleStateOf(1.0) }
    var inputOpacityLight by remember { mutableDoubleStateOf(0.8236) }
    var inputOpacityDark by remember { mutableDoubleStateOf(0.7396) }

    // Read once per recomposition at composable scope: local funs below are
    // recreated each recomposition and capture the latest value, and calling
    // LocalConfiguration.current inside them is illegal (non-composable ctx).
    val currentLanguage = LocalConfiguration.current.locales[0].language

    fun reloadPalette() {
        val raw = container.preferenceRepository.readJson(MemoTheme.PALETTE_KEY)
        // themePaletteById：RikkaHub 预设优先，其次 Memo 生成的调色板（见 RikkaHubPresets.kt）。
        val palette = themePaletteById(raw?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "default")
        paletteName = if (currentLanguage == "zh") palette.zhName else palette.enName
    }
    fun reloadAll() {
        reloadPalette()
        themeMode = container.preferenceRepository.readJson(MemoTheme.MODE_KEY)?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "system"
        backgroundChatMode = container.preferenceRepository.readJson("android_background_chat_mode_v1")?.takeIf { it.isNotEmpty() } ?: "off"
        liveUpdateEnabled =
            container.preferenceRepository.readJson("enable_live_update_notification_v1") == "1"
        appFontAlias = container.preferenceRepository.readJson("display_app_font_local_alias_v1")?.takeIf { it.isNotEmpty() }
        appFontFamily = container.preferenceRepository.readJson("display_app_font_family_v1")?.takeIf { it.isNotEmpty() }
        codeFontAlias = container.preferenceRepository.readJson("display_code_font_local_alias_v1")?.takeIf { it.isNotEmpty() }
        codeFontFamily = container.preferenceRepository.readJson("display_code_font_family_v1")?.takeIf { it.isNotEmpty() }
        chatFontScale = container.preferenceRepository.readJson("display_chat_font_scale_v1")?.toDoubleOrNull() ?: 1.0
        autoScrollEnabled = container.preferenceRepository.readJson("display_auto_scroll_enabled_v1")?.let { it == "1" } ?: true
        autoScrollIdleSeconds = container.preferenceRepository.readJson("display_auto_scroll_idle_seconds_v1")?.toIntOrNull() ?: 8
        maskStrength = container.preferenceRepository.readJson("display_chat_background_mask_strength_v1")?.toDoubleOrNull() ?: 1.0
        inputOpacityLight = container.preferenceRepository.readJson("display_chat_input_background_opacity_light_v1")?.toDoubleOrNull() ?: 0.8236
        inputOpacityDark = container.preferenceRepository.readJson("display_chat_input_background_opacity_dark_v1")?.toDoubleOrNull() ?: 0.7396
        // 字体键不走本页的 state —— 交给 ThemeState 重新解析，否则要等下次冷启动
        // （根 composable 观察 ThemeState，改完立即生效）。
        ThemeState.loadFonts(container)
    }
    LaunchedEffect(Unit) { reloadAll() }

    val context = LocalContext.current
    // 通知权限（Android 13+）：打开通知类开关时顺手申请一次。
    // RikkaHub 也是这么做的（`SettingPreferencesNotificationPage:126-130`，打开总开关时
    // `permissionState.requestPermissions()`）；不申请的话 `NotificationUtil.notify` 一律
    // 返回 false，功能看起来就是"没做"（用户 2026-09-23「实时更新通知功能那没有做好」）。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (com.psyche.memo.service.NotificationUtil.hasNotificationPermission(context)) return
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val fontPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val target = fontSheetTarget
        if (uri != null && target != null) {
            runCatching {
                val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
                val name = "${System.currentTimeMillis()}_${(uri.lastPathSegment ?: "font.ttf").substringAfterLast('/')}"
                val dest = java.io.File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                val alias = name.substringAfterLast('.')
                val prefix = if (target == "app") "display_app_font" else "display_code_font"
                container.preferenceRepository.writeJson("${prefix}_local_path_v1", dest.absolutePath)
                container.preferenceRepository.writeJson("${prefix}_local_alias_v1", alias)
                container.preferenceRepository.writeJson("${prefix}_family_v1", alias)
            }
            reloadAll()
        }
        fontSheetTarget = null
    }

    // kelivo wraps this page in a Scaffold: the AppBar stays pinned while
    // the body ListView scrolls under it, so the top bar lives outside the
    // list and below the status-bar inset.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.settings_page_display),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // Mirrors kelivo's ListView padding: LTRB(16, 12, 16, 16).
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // 分类重组（用户要求对齐设置主屏的 SectionHeader + SectionCard 形态；
            // 原项目此页是一整张卡，这里按功能域拆 5 组）：
            // 外观 / 聊天界面 / 字体 / 行为 / 通知与后台。
            item { SectionHeader(stringResource(UiR.string.display_settings_page_section_appearance), first = true) }
            item {
                SectionCard {
                    // B8 — theme row detail reads the stored palette responsively;
                    // the row opens the theme settings page (display_settings_page
                    // .dart L72-79).
                    SettingsRow(
                        Lucide.Palette,
                        stringResource(UiR.string.display_settings_page_theme_settings_title),
                        detailText = paletteName,
                        onTap = onOpenTheme,
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

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.display_settings_page_section_chat_ui)) }
            item {
                SectionCard {
                    SettingsRow(
                        Lucide.MessageCircleMore,
                        stringResource(UiR.string.display_settings_page_chat_item_display_title),
                        onTap = onOpenChatItemDisplay,
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.MessageSquare,
                        stringResource(UiR.string.message_style_settings_page_title),
                        onTap = onOpenMessageStyle,
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.LetterText,
                        stringResource(UiR.string.display_settings_page_rendering_settings_title),
                        onTap = onOpenRendering,
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.Image,
                        stringResource(UiR.string.image_settings_page_title),
                        onTap = onOpenImage,
                    )
                    DividerRow()
                    // B13 — L364-380: background mask detail + sheet.
                    SettingsRow(
                        Lucide.Image,
                        stringResource(UiR.string.display_settings_page_chat_background_mask_title),
                        detailText = "${(maskStrength * 100).toInt()}%",
                        onTap = { maskSheetVisible = true },
                    )
                    DividerRow()
                    // B14 — L382-404: input opacity (current brightness) detail + sheet.
                    val systemDark = isSystemInDarkTheme()
                    SettingsRow(
                        Lucide.RectangleHorizontal,
                        stringResource(UiR.string.display_settings_page_chat_input_background_opacity_title),
                        detailText = "${(((if (systemDark) inputOpacityDark else inputOpacityLight) * 100)).toInt()}%",
                        onTap = { inputOpacitySheetVisible = true },
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.display_settings_page_section_fonts)) }
            item {
                SectionCard {
                    // B10 — L248-306: app font row with detail + source sheet.
                    SettingsRow(
                        Lucide.Type,
                        stringResource(UiR.string.display_settings_page_app_font_title),
                        detailText = when {
                            appFontAlias?.isNotEmpty() == true ->
                                stringResource(UiR.string.display_settings_page_font_local_file_label)
                            appFontFamily?.isNotEmpty() == true -> appFontFamily!!
                            else -> stringResource(UiR.string.desktop_font_family_system_default)
                        },
                        onTap = { fontSheetTarget = "app"; fontSheetVisible = true },
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.Code,
                        stringResource(UiR.string.display_settings_page_code_font_title),
                        detailText = when {
                            codeFontAlias?.isNotEmpty() == true ->
                                stringResource(UiR.string.display_settings_page_font_local_file_label)
                            codeFontFamily?.isNotEmpty() == true -> codeFontFamily!!
                            else -> stringResource(UiR.string.desktop_font_family_monospace_default)
                        },
                        onTap = { fontSheetTarget = "code"; fontSheetVisible = true },
                    )
                    DividerRow()
                    // B11 — L319-336: chat font size detail + sheet.
                    SettingsRow(
                        Lucide.CaseSensitive,
                        stringResource(UiR.string.display_settings_page_chat_font_size_title),
                        detailText = "${(chatFontScale * 100).toInt()}%",
                        onTap = { fontSizeSheetVisible = true },
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.display_settings_page_section_behavior)) }
            item {
                SectionCard {
                    SettingsRow(
                        Lucide.Eclipse,
                        stringResource(UiR.string.display_settings_page_behavior_startup_title),
                        onTap = onOpenBehavior,
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.RefreshCw,
                        stringResource(UiR.string.settings_page_auto_retry),
                        onTap = onOpenAutoRetry,
                    )
                    DividerRow()
                    // B12 — L338-362: auto scroll idle detail + sheet.
                    SettingsRow(
                        Lucide.ArrowDown,
                        stringResource(UiR.string.display_settings_page_auto_scroll_idle_title),
                        detailText = if (!autoScrollEnabled) {
                            stringResource(UiR.string.display_settings_page_auto_scroll_disabled_label)
                        } else {
                            "${autoScrollIdleSeconds}s"
                        },
                        onTap = { autoScrollSheetVisible = true },
                    )
                    DividerRow()
                    SettingsRow(
                        Lucide.Vibrate,
                        stringResource(UiR.string.display_settings_page_haptics_settings_title),
                        onTap = onOpenHaptics,
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader(stringResource(UiR.string.display_settings_page_section_notifications)) }
            item {
                SectionCard {
                    // B9 — L184-229: Android background chat row with three-option
                    // sheet and real mode detail.
                    SettingsRow(
                        Lucide.Monitor,
                        stringResource(UiR.string.display_settings_page_android_background_chat_title),
                        detailText = stringResource(
                            when (backgroundChatMode) {
                                "on" -> UiR.string.android_background_status_on
                                "on_notify" -> UiR.string.android_background_status_other
                                else -> UiR.string.android_background_status_off
                            },
                        ),
                        onTap = { backgroundChatSheetVisible = true },
                    )
                    DividerRow()
                    // Live Update progress notification — RikkaHub
                    // SettingPreferencesNotificationPage L136-149 (nested under
                    // its master notification switch there; standalone here).
                    // Key enable_live_update_notification_v1 mirrors
                    // DisplaySetting.enableLiveUpdateNotification (default off).
                    SettingsSwitchRow(
                        Lucide.Bell,
                        stringResource(UiR.string.display_settings_page_live_update_notification),
                        tip = stringResource(UiR.string.display_settings_page_live_update_notification_desc),
                        value = liveUpdateEnabled,
                        onToggle = { v ->
                            liveUpdateEnabled = v
                            if (v) ensureNotificationPermission()
                            container.preferenceRepository.writeJson(
                                "enable_live_update_notification_v1",
                                if (v) "1" else "0",
                            )
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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

    // L469-544 — Android background chat mode sheet.
    if (backgroundChatSheetVisible) {
        SelectSheet(
            options = listOf(
                Triple(stringResource(UiR.string.android_background_option_on), "on", backgroundChatMode == "on"),
                Triple(stringResource(UiR.string.android_background_option_on_notify), "on_notify", backgroundChatMode == "on_notify"),
                Triple(stringResource(UiR.string.android_background_option_off), "off", backgroundChatMode == "off"),
            ),
            onSelect = { value ->
                backgroundChatMode = value
                // 「开启并在生成完时发送消息」这一档要发通知 —— 顺手把权限要了。
                if (value == "on_notify") ensureNotificationPermission()
                container.preferenceRepository.writeJson("android_background_chat_mode_v1", value)
                backgroundChatSheetVisible = false
            },
            onDismiss = { backgroundChatSheetVisible = false },
        )
    }

    // L612-727 — chat font size slider sheet (0.5-1.5, step 0.05) + sample.
    if (fontSizeSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { fontSizeSheetVisible = false }, dragHandle = null) {
            MemoSheetHandle()
            var scale by remember { mutableFloatStateOf(chatFontScale.toFloat()) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("50%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
                    Spacer(Modifier.size(8.dp))
                    MemoSlider(
                        value = scale,
                        onValueChange = { v ->
                            // 无极：直接取手指位置的值（原版这里 `stepSize: 0.05` 会吸附）。
                            scale = v
                            chatFontScale = scale.toDouble()
                            container.preferenceRepository.writeJson("display_chat_font_scale_v1", scale.toString())
                        },
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.weight(1f),
                        valueLabel = { "${Math.round(it * 100)}%" },
                    )
                    Spacer(Modifier.size(8.dp))
                    com.psyche.memo.ui.slider.SliderValueLabel(
                        text = "${(chatFontScale * 100).toInt()}%",
                        // 定宽：数值从 "50%" 变 "100%" 时不再把左边的 slider 挤短。
                        widest = "100%",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface),
                    )
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Surface(
                    color = cs.surfaceCardColorCompat(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(UiR.string.display_settings_page_chat_font_sample_text),
                        modifier = Modifier.padding(12.dp),
                        style = TextStyle(fontSize = (16 * chatFontScale).sp, color = cs.onSurface),
                    )
                }
            }
        }
    }

    // L729-858 — auto scroll idle sheet: enable switch + 2-64s slider.
    if (autoScrollSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { autoScrollSheetVisible = false }, dragHandle = null) {
            MemoSheetHandle()
            var enabled by remember { mutableStateOf(autoScrollEnabled) }
            var seconds by remember { mutableFloatStateOf(autoScrollIdleSeconds.toFloat()) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(UiR.string.display_settings_page_auto_scroll_enable_title),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                    )
                    IosSwitch(
                        value = enabled,
                        onValueChanged = { v ->
                            enabled = v
                            autoScrollEnabled = v
                            container.preferenceRepository.writeJson("display_auto_scroll_enabled_v1", if (v) "1" else "0")
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("2s", style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
                    Spacer(Modifier.size(8.dp))
                    MemoSlider(
                        value = seconds,
                        onValueChange = { v ->
                            seconds = v
                            autoScrollIdleSeconds = seconds.toInt()
                            if (enabled) {
                                container.preferenceRepository.writeJson("display_auto_scroll_idle_seconds_v1", seconds.toInt().toString())
                            }
                        },
                        valueRange = 2f..64f,
                        enabled = enabled,
                        valueLabel = { "${Math.round(it)}s" },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    com.psyche.memo.ui.slider.SliderValueLabel(
                        text = "${seconds.toInt()}s",
                        widest = "64s",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface),
                    )
                }
            }
        }
    }

    // L902-1001 — background mask sheet: 0-200%, step 5%.
    if (maskSheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { maskSheetVisible = false }, dragHandle = null) {
            MemoSheetHandle()
            var strength by remember { mutableFloatStateOf((maskStrength * 100).toFloat()) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemoSlider(
                        value = strength,
                        onValueChange = { v ->
                            strength = v
                            maskStrength = strength / 100.0
                            container.preferenceRepository.writeJson(
                                "display_chat_background_mask_strength_v1",
                                (strength / 100.0).toString(),
                            )
                        },
                        valueRange = 0f..200f,
                        modifier = Modifier.weight(1f),
                        valueLabel = { "${Math.round(it)}%" },
                    )
                    Spacer(Modifier.size(8.dp))
                    com.psyche.memo.ui.slider.SliderValueLabel(
                        text = "${strength.toInt()}%",
                        widest = "200%",
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface),
                    )
                }
            }
        }
    }

    // L1003-1156 — input opacity sheet: separate light/dark sliders, 0-100 step 5.
    if (inputOpacitySheetVisible) {
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { inputOpacitySheetVisible = false }, dragHandle = null) {
            MemoSheetHandle()
            var light by remember { mutableFloatStateOf((inputOpacityLight * 100).toFloat()) }
            var dark by remember { mutableFloatStateOf((inputOpacityDark * 100).toFloat()) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Text(
                    text = stringResource(UiR.string.settings_page_light_mode),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(8.dp))
                OpacitySliderRow(
                    value = light,
                    onCommit = { v ->
                        light = v
                        inputOpacityLight = v / 100.0
                        container.preferenceRepository.writeJson(
                            "display_chat_input_background_opacity_light_v1",
                            (v / 100.0).toString(),
                        )
                    },
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(UiR.string.settings_page_dark_mode),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(8.dp))
                OpacitySliderRow(
                    value = dark,
                    onCommit = { v ->
                        dark = v
                        inputOpacityDark = v / 100.0
                        container.preferenceRepository.writeJson(
                            "display_chat_input_background_opacity_dark_v1",
                            (v / 100.0).toString(),
                        )
                    },
                )
            }
        }
    }

    // L408-467 — font source sheet: local file (SAF) / reset.
    if (fontSheetVisible) {
        SelectSheet(
            options = listOf(
                Triple(stringResource(UiR.string.font_picker_choose_local_file), "local", false),
                Triple(stringResource(UiR.string.display_settings_page_font_reset_label), "reset", false),
            ),
            onSelect = { value ->
                val target = fontSheetTarget
                if (value == "local" && target != null) {
                    // Keep fontSheetTarget for the picker callback.
                    fontSheetVisible = false
                    fontPickerLauncher.launch(
                        arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"),
                    )
                } else {
                    if (target != null) {
                        val prefix = if (target == "app") "display_app_font" else "display_code_font"
                        container.preferenceRepository.remove("${prefix}_local_path_v1")
                        container.preferenceRepository.remove("${prefix}_local_alias_v1")
                        container.preferenceRepository.remove("${prefix}_family_v1")
                        reloadAll()
                    }
                    fontSheetTarget = null
                    fontSheetVisible = false
                }
            },
            onDismiss = {
                fontSheetTarget = null
                fontSheetVisible = false
            },
        )
    }
}

/** Shared three/multi-option bottom sheet (mirrors _sheetOption lists). */
@Composable
private fun OpacitySliderRow(value: Float, onCommit: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("0%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
        Spacer(Modifier.size(8.dp))
        MemoSlider(
            value = local,
            onValueChange = { v ->
                local = v
                onCommit(local)
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
            valueLabel = { "${Math.round(it)}%" },
        )
        Spacer(Modifier.size(8.dp))
        com.psyche.memo.ui.slider.SliderValueLabel(
            text = "${local.toInt()}%",
            widest = "100%",
            style = TextStyle(fontSize = 12.sp, color = cs.onSurface),
        )
    }
}

/** Shared three/multi-option bottom sheet (mirrors _sheetOption lists). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectSheet(
    options: List<Triple<String, String, Boolean>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            options.forEach { (label, value, selected) ->
                MemoSheetOptionRow(
                    label = label,
                    selected = selected,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}
