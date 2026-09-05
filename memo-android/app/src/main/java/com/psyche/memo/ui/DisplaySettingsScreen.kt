package com.psyche.memo.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import com.psyche.memo.ui.theme.paletteById

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
    var appFontAlias by remember { mutableStateOf<String?>(null) }
    var appFontFamily by remember { mutableStateOf<String?>(null) }
    var codeFontAlias by remember { mutableStateOf<String?>(null) }
    var codeFontFamily by remember { mutableStateOf<String?>(null) }
    // settings_provider.dart:5041-5088 — scale 1.0 / autoScroll on / idle 8 /
    // mask 1.0 / opacity light 0.8236 / dark 0.7396.
    var chatFontScale by remember { mutableStateOf(1.0) }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var autoScrollIdleSeconds by remember { mutableStateOf(8) }
    var maskStrength by remember { mutableStateOf(1.0) }
    var inputOpacityLight by remember { mutableStateOf(0.8236) }
    var inputOpacityDark by remember { mutableStateOf(0.7396) }

    // Read once per recomposition at composable scope: local funs below are
    // recreated each recomposition and capture the latest value, and calling
    // LocalConfiguration.current inside them is illegal (non-composable ctx).
    val currentLanguage = LocalConfiguration.current.locales[0].language

    fun reloadPalette() {
        val raw = container.preferenceRepository.readLocal(MemoTheme.PALETTE_KEY)
        val palette = paletteById(raw?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "default")
        paletteName = if (currentLanguage == "zh") palette.zhName else palette.enName
    }
    fun reloadAll() {
        reloadPalette()
        themeMode = container.preferenceRepository.readLocal(MemoTheme.MODE_KEY)?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "system"
        backgroundChatMode = container.preferenceRepository.readLocal("android_background_chat_mode_v1")?.takeIf { it.isNotEmpty() } ?: "off"
        appFontAlias = container.preferenceRepository.readLocal("display_app_font_local_alias_v1")?.takeIf { it.isNotEmpty() }
        appFontFamily = container.preferenceRepository.readLocal("display_app_font_family_v1")?.takeIf { it.isNotEmpty() }
        codeFontAlias = container.preferenceRepository.readLocal("display_code_font_local_alias_v1")?.takeIf { it.isNotEmpty() }
        codeFontFamily = container.preferenceRepository.readLocal("display_code_font_family_v1")?.takeIf { it.isNotEmpty() }
        chatFontScale = container.preferenceRepository.readLocal("display_chat_font_scale_v1")?.toDoubleOrNull() ?: 1.0
        autoScrollEnabled = container.preferenceRepository.readLocal("display_auto_scroll_enabled_v1")?.let { it == "1" } ?: true
        autoScrollIdleSeconds = container.preferenceRepository.readLocal("display_auto_scroll_idle_seconds_v1")?.toIntOrNull() ?: 8
        maskStrength = container.preferenceRepository.readLocal("display_chat_background_mask_strength_v1")?.toDoubleOrNull() ?: 1.0
        inputOpacityLight = container.preferenceRepository.readLocal("display_chat_input_background_opacity_light_v1")?.toDoubleOrNull() ?: 0.8236
        inputOpacityDark = container.preferenceRepository.readLocal("display_chat_input_background_opacity_dark_v1")?.toDoubleOrNull() ?: 0.7396
    }
    LaunchedEffect(Unit) { reloadAll() }

    val context = LocalContext.current
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
                container.preferenceRepository.writeLocal("${prefix}_local_path_v1", dest.absolutePath)
                container.preferenceRepository.writeLocal("${prefix}_local_alias_v1", alias)
                container.preferenceRepository.writeLocal("${prefix}_family_v1", alias)
            }
            reloadAll()
        }
        fontSheetTarget = null
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
                // B8 — theme row detail reads the stored palette responsively.
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
                    onTap = onOpenBehavior,
                )
                DividerRow()
                SettingsRow(
                    Lucide.Image,
                    stringResource(UiR.string.image_settings_page_title),
                    onTap = onOpenImage,
                )
                DividerRow()
                SettingsRow(
                    Lucide.MessageSquare,
                    stringResource(UiR.string.message_style_settings_page_title),
                    onTap = onOpenMessageStyle,
                )
                DividerRow()
                SettingsRow(
                    Lucide.RefreshCw,
                    stringResource(UiR.string.settings_page_auto_retry),
                    onTap = onOpenAutoRetry,
                )
                DividerRow()
                SettingsRow(
                    Lucide.Vibrate,
                    stringResource(UiR.string.display_settings_page_haptics_settings_title),
                    onTap = onOpenHaptics,
                )
                DividerRow()
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
                container.preferenceRepository.writeLocal("android_background_chat_mode_v1", value)
                backgroundChatSheetVisible = false
            },
            onDismiss = { backgroundChatSheetVisible = false },
        )
    }

    // L612-727 — chat font size slider sheet (0.5-1.5, step 0.05) + sample.
    if (fontSizeSheetVisible) {
        ModalBottomSheet(onDismissRequest = { fontSizeSheetVisible = false }) {
            var scale by remember { mutableStateOf(chatFontScale.toFloat()) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("50%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
                    Spacer(Modifier.size(8.dp))
                    Slider(
                        value = scale,
                        onValueChange = { v ->
                            scale = (Math.round(v / 0.05f) * 0.05f)
                            chatFontScale = scale.toDouble()
                            container.preferenceRepository.writeLocal("display_chat_font_scale_v1", scale.toString())
                        },
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("${(chatFontScale * 100).toInt()}%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface))
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Surface(
                    color = cs.surfaceCardColorCompat(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
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
        ModalBottomSheet(onDismissRequest = { autoScrollSheetVisible = false }) {
            var enabled by remember { mutableStateOf(autoScrollEnabled) }
            var seconds by remember { mutableStateOf(autoScrollIdleSeconds.toFloat()) }
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
                            container.preferenceRepository.writeLocal("display_auto_scroll_enabled_v1", if (v) "1" else "0")
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("2s", style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
                    Spacer(Modifier.size(8.dp))
                    Slider(
                        value = seconds,
                        onValueChange = { v ->
                            seconds = Math.round(v / 2f) * 2f
                            autoScrollIdleSeconds = seconds.toInt()
                            if (enabled) {
                                container.preferenceRepository.writeLocal("display_auto_scroll_idle_seconds_v1", seconds.toInt().toString())
                            }
                        },
                        valueRange = 2f..64f,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("${seconds.toInt()}s", style = TextStyle(fontSize = 12.sp, color = cs.onSurface))
                }
            }
        }
    }

    // L902-1001 — background mask sheet: 0-200%, step 5%.
    if (maskSheetVisible) {
        ModalBottomSheet(onDismissRequest = { maskSheetVisible = false }) {
            var strength by remember { mutableStateOf((maskStrength * 100).toFloat()) }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = strength,
                        onValueChange = { v ->
                            strength = Math.round(v / 5f) * 5f
                            maskStrength = strength / 100.0
                            container.preferenceRepository.writeLocal(
                                "display_chat_background_mask_strength_v1",
                                (strength / 100.0).toString(),
                            )
                        },
                        valueRange = 0f..200f,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("${strength.toInt()}%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface))
                }
            }
        }
    }

    // L1003-1156 — input opacity sheet: separate light/dark sliders, 0-100 step 5.
    if (inputOpacitySheetVisible) {
        ModalBottomSheet(onDismissRequest = { inputOpacitySheetVisible = false }) {
            var light by remember { mutableStateOf((inputOpacityLight * 100).toFloat()) }
            var dark by remember { mutableStateOf((inputOpacityDark * 100).toFloat()) }
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
                        container.preferenceRepository.writeLocal(
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
                        container.preferenceRepository.writeLocal(
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
    var local by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("0%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)))
        Spacer(Modifier.size(8.dp))
        Slider(
            value = local,
            onValueChange = { v ->
                local = Math.round(v / 5f) * 5f
                onCommit(local)
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        Text("${local.toInt()}%", style = TextStyle(fontSize = 12.sp, color = cs.onSurface))
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
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            options.forEachIndexed { index, (label, value, selected) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .padding(horizontal = 20.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = if (selected) cs.primary else cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    }
                }
                if (index != options.lastIndex) {
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.18f))
                }
            }
        }
    }
}
