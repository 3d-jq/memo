package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import com.psyche.memo.ui.slider.MemoSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.User
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.psyche.memo.ui.chat.BubbleOverrides
import com.psyche.memo.ui.chat.ChatBubbleStyle
import com.psyche.memo.ui.chat.ResolvedBubbleStyle
import com.psyche.memo.ui.chat.resolveBubbleStyle

/**
 * 1:1 port of message_style_settings_page.dart — style picker (Default /
 * Frosted / Solid) with _StyleSwatch colors and subtitles, layout switches,
 * light/dark + user/assistant segmented toggles, live preview panel, AppBar
 * reset with confirm dialog, and the per-role parameter card (blur slider
 * only for frosted + hint, background color / opacity, border color /
 * opacity / width, text color, corner radius).
 *
 * Persistence: style at 'display_chat_message_background_style_v1'; overrides
 * at 'chat_bubble_style_overrides_v1' (assistant/global) and
 * 'chat_bubble_style_overrides_user_v1' (user) — JSON of nullable fields
 * (lib/theme/chat_bubble_style.dart:65-78, settings_provider.dart:312-315).
 */

// --------------------------------------------------------------- overrides

// 样式 / 覆盖 / 解析三件套与聊天页共用 `ui.chat.ChatBubbleStyle`（唯一事实源），
// 这里只保留设置页自己的预览配色包装。

/** Base colors for the *editing* brightness (theme-following fallbacks). */
private data class PreviewColors(
    val bgBase: Color,
    val borderBase: Color,
    val textBase: Color,
) {
    fun background(argb: Int?): Color = argb?.let { Color(it) } ?: bgBase
    fun border(argb: Int?): Color = argb?.let { Color(it) } ?: borderBase
    fun text(argb: Int?): Color = argb?.let { Color(it) } ?: textBase
}

// chat_bubble_style.dart:163-203 — resolve overrides + theme + style.
private fun resolveStyle(
    colors: PreviewColors,
    dark: Boolean,
    style: String,
    overrides: BubbleOverrides,
): ResolvedBubbleStyle {
    // 预览用的是「编辑中的亮度」的主题色，直接喂给共享解析器（ColorScheme 只
    // 参与回退色，这里换成预览色）。
    val cs = previewColorScheme(colors)
    return resolveBubbleStyle(cs, dark, ChatBubbleStyle.fromWire(style), overrides)
}

/** 把预览用的三个基准色包成一个只读 [ColorScheme]，供共享解析器取回退值。 */
private fun previewColorScheme(colors: PreviewColors): ColorScheme =
    androidx.compose.material3.lightColorScheme(
        surfaceContainerHigh = colors.bgBase,
        outlineVariant = colors.borderBase,
        onSurface = colors.textBase,
    )

// --------------------------------------------------------------- screen

@Composable
fun MessageStyleSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val systemDark = isSystemInDarkTheme()

    var style by remember { mutableStateOf("default") }
    var fitContent by remember { mutableStateOf(false) }
    var splitParagraphs by remember { mutableStateOf(false) }
    var editingDark by remember { mutableStateOf(systemDark) }
    var editingUser by remember { mutableStateOf(false) }
    var assistantOverrides by remember { mutableStateOf(BubbleOverrides.NONE) }
    var userOverrides by remember { mutableStateOf(BubbleOverrides.NONE) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf<String?>(null) } // "bg" | "border" | "text"

    LaunchedEffect(Unit) {
        // LaunchedEffect 体默认跑在组合线程上，里面的 readJson 是真会打 SQLite 的
        withContext(Dispatchers.IO) {
            style = container.preferenceRepository.readJson("display_chat_message_background_style_v1")
                ?.takeIf { it.isNotEmpty() } ?: "default"
            fitContent = container.preferenceRepository.readJson("display_assistant_bubble_fit_content_v1") == "1"
            splitParagraphs = container.preferenceRepository.readJson("display_assistant_bubble_split_paragraphs_v1") == "1"
            assistantOverrides = BubbleOverrides.fromJson(
                container.preferenceRepository.readJson("chat_bubble_style_overrides_v1"),
            )
            userOverrides = BubbleOverrides.fromJson(
                container.preferenceRepository.readJson("chat_bubble_style_overrides_user_v1"),
            )
        }
}

    fun saveStyle(v: String) {
        style = v
        container.preferenceRepository.writeJson("display_chat_message_background_style_v1", v)
    }
    fun saveBool(key: String, v: Boolean) {
        container.preferenceRepository.writeJson(key, if (v) "1" else "0")
    }
    // settings_provider.dart:2862-2894 — per-role write.
    fun saveOverrides(v: BubbleOverrides) {
        if (editingUser) {
            userOverrides = v
            container.preferenceRepository.writeJson("chat_bubble_style_overrides_user_v1", v.toJson())
        } else {
            assistantOverrides = v
            container.preferenceRepository.writeJson("chat_bubble_style_overrides_v1", v.toJson())
        }
    }
    // settings_provider.dart:2844-2860 — reset clears both roles.
    fun resetOverrides() {
        assistantOverrides = BubbleOverrides.NONE
        userOverrides = BubbleOverrides.NONE
        container.preferenceRepository.writeJson("chat_bubble_style_overrides_v1", "{}")
        container.preferenceRepository.remove("chat_bubble_style_overrides_user_v1")
    }

    val overrides = if (editingUser) userOverrides else assistantOverrides
    val isDefault = style == "default"
    // The preview needs the *editing* brightness's theme colors while only the
    // current scheme exists; when they differ the theme fallbacks are
    // approximated by lerping toward white/black. Explicit overrides (what the
    // sliders act on) always take precedence.
    val previewColors = if (systemDark == editingDark) {
        PreviewColors(cs.surfaceContainerHigh, cs.outlineVariant, cs.onSurface)
    } else {
        val target = if (editingDark) Color.Black else Color.White
        PreviewColors(
            lerp(cs.surfaceContainerHigh, target, 0.85f),
            lerp(cs.outlineVariant, target, 0.6f),
            lerp(cs.onSurface, target, 0.9f),
        )
    }
    val resolved = resolveStyle(previewColors, editingDark, style, overrides)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.message_style_settings_page_title),
            onBack = onBack,
        ) {
            // message_style_settings_page.dart:34-45 — AppBar reset action.
            IconButton(onClick = { showResetConfirm = true }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Lucide.RotateCcw,
                    contentDescription = stringResource(UiR.string.message_style_settings_page_reset),
                    tint = cs.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp,
            ),
        ) {
            item(key = "h_style") {
                SectionHeader(stringResource(UiR.string.message_style_settings_page_section_style), first = true)
            }
            item(key = "c_style") {
                SettingsSectionCard {
                    StyleRow(
                        styleId = "default",
                        label = stringResource(UiR.string.display_settings_page_chat_message_background_default),
                        tip = stringResource(UiR.string.message_style_settings_page_style_default_subtitle),
                        selected = isDefault,
                        onTap = { saveStyle("default") },
                    )
                    SettingsIosDivider()
                    StyleRow(
                        styleId = "frosted",
                        label = stringResource(UiR.string.display_settings_page_chat_message_background_frosted),
                        tip = stringResource(UiR.string.message_style_settings_page_style_frosted_subtitle),
                        selected = style == "frosted",
                        onTap = { saveStyle("frosted") },
                    )
                    SettingsIosDivider()
                    StyleRow(
                        styleId = "solid",
                        label = stringResource(UiR.string.display_settings_page_chat_message_background_solid),
                        tip = stringResource(UiR.string.message_style_settings_page_style_solid_subtitle),
                        selected = style == "solid",
                        onTap = { saveStyle("solid") },
                    )
                }
            }
            item(key = "gap_style") { Spacer(Modifier.size(12.dp)) }
            item(key = "h_bubble") {
                SectionHeader(stringResource(UiR.string.message_style_settings_page_section_bubble))
            }
            item(key = "c_bubble") {
                SettingsSectionCard {
                    TextSwitchRow(
                        label = stringResource(UiR.string.message_style_settings_page_assistant_fit_content),
                        tip = stringResource(UiR.string.message_style_settings_page_assistant_fit_content_subtitle),
                        value = fitContent,
                        onToggle = { fitContent = it; saveBool("display_assistant_bubble_fit_content_v1", it) },
                    )
                    SettingsIosDivider()
                    TextSwitchRow(
                        label = stringResource(UiR.string.message_style_settings_page_assistant_split_paragraphs),
                        tip = stringResource(UiR.string.message_style_settings_page_assistant_split_paragraphs_subtitle),
                        value = splitParagraphs,
                        onToggle = { splitParagraphs = it; saveBool("display_assistant_bubble_split_paragraphs_v1", it) },
                    )
                }
            }
            item {
                Spacer(Modifier.size(12.dp))
                // L372-383 — light/dark segmented toggle.
                SegmentedToggle(
                    leftLabel = stringResource(UiR.string.message_style_settings_page_light),
                    leftIcon = Lucide.Sun,
                    rightLabel = stringResource(UiR.string.message_style_settings_page_dark),
                    rightIcon = Lucide.Moon,
                    rightSelected = editingDark,
                    onChanged = { editingDark = it },
                )
            }
            if (!isDefault) {
                item {
                    Spacer(Modifier.size(12.dp))
                    // L384-411 — user/assistant toggle + hint.
                    SegmentedToggle(
                        leftLabel = stringResource(UiR.string.message_style_settings_page_role_user),
                        leftIcon = Lucide.User,
                        rightLabel = stringResource(UiR.string.message_style_settings_page_role_assistant),
                        rightIcon = Lucide.Bot,
                        rightSelected = !editingUser,
                        onChanged = { right -> editingUser = !right },
                    )
                    Text(
                        text = stringResource(UiR.string.message_style_settings_page_role_assistant_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.56f)),
                    )
                }
            }
            item {
                Spacer(Modifier.size(12.dp))
                // L197-204,412-413 — live preview panel.
                PreviewPanel(
                    cs = cs,
                    editingDark = editingDark,
                    style = style,
                    userOverrides = userOverrides,
                    assistantOverrides = assistantOverrides,
                    colors = previewColors,
                )
            }
            if (isDefault) {
                item {
                    // L414-426 — default hint instead of params.
                    Text(
                        text = stringResource(UiR.string.message_style_settings_page_default_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurface.copy(alpha = 0.56f)),
                    )
                }
            } else {
                item {
                    Spacer(Modifier.size(12.dp))
                    // L206-360 — parameter card.
                    SettingsSectionCard {
                        if (style == "frosted") {
                            SliderRow(
                                label = stringResource(UiR.string.message_style_settings_page_blur),
                                valueText = (overrides.blurSigma ?: 14.0).roundToInt().toString(),
                                value = (overrides.blurSigma ?: 14.0).toFloat(),
                                range = 0f..30f,
                                onChanged = { v -> saveOverrides(overrides.copy(blurSigma = v.toDouble())) },
                            )
                            Text(
                                text = stringResource(UiR.string.message_style_settings_page_blur_hint),
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.58f)),
                            )
                            SettingsIosDivider()
                        }
                        ColorRow(
                            label = stringResource(UiR.string.message_style_settings_page_background_color),
                            color = previewColors.background(
                                if (editingDark) overrides.backgroundArgbDark else overrides.backgroundArgbLight,
                            ),
                            onTap = { colorPicker = "bg" },
                        )
                        SettingsIosDivider()
                        val bgOpacity = if (style == "frosted") overrides.frostedOpacity ?: 0.66 else overrides.solidOpacity ?: 1.0
                        SliderRow(
                            label = stringResource(UiR.string.message_style_settings_page_background_opacity),
                            valueText = "${(bgOpacity * 100).roundToInt()}%",
                            value = (bgOpacity * 100).toFloat(),
                            range = 0f..100f,
                            onChanged = { v ->
                                val opacity = (v / 100.0)
                                saveOverrides(
                                    if (style == "frosted") overrides.copy(frostedOpacity = opacity)
                                    else overrides.copy(solidOpacity = opacity),
                                )
                            },
                        )
                        SettingsIosDivider()
                        ColorRow(
                            label = stringResource(UiR.string.message_style_settings_page_border_color),
                            color = previewColors.border(
                                if (editingDark) overrides.borderArgbDark else overrides.borderArgbLight,
                            ),
                            onTap = { colorPicker = "border" },
                        )
                        SettingsIosDivider()
                        val borderOpacity = overrides.borderOpacity ?: if (style == "frosted") 0.14 else 0.16
                        SliderRow(
                            label = stringResource(UiR.string.message_style_settings_page_border_opacity),
                            valueText = "${(borderOpacity * 100).roundToInt()}%",
                            value = (borderOpacity * 100).toFloat(),
                            range = 0f..100f,
                            onChanged = { v -> saveOverrides(overrides.copy(borderOpacity = v / 100.0)) },
                        )
                        SettingsIosDivider()
                        SliderRow(
                            label = stringResource(UiR.string.message_style_settings_page_border_width),
                            valueText = String.format(java.util.Locale.US, "%.1f", overrides.borderWidth ?: 0.8),
                            value = (overrides.borderWidth ?: 0.8).toFloat(),
                            range = 0f..3f,
                            onChanged = { v -> saveOverrides(overrides.copy(borderWidth = v.toDouble())) },
                        )
                        SettingsIosDivider()
                        ColorRow(
                            label = stringResource(UiR.string.message_style_settings_page_text_color),
                            color = previewColors.text(
                                if (editingDark) overrides.textArgbDark else overrides.textArgbLight,
                            ),
                            onTap = { colorPicker = "text" },
                        )
                        SettingsIosDivider()
                        SliderRow(
                            label = stringResource(UiR.string.message_style_settings_page_corner_radius),
                            valueText = (overrides.cornerRadius ?: 16.0).roundToInt().toString(),
                            value = (overrides.cornerRadius ?: 16.0).toFloat(),
                            range = 0f..28f,
                            onChanged = { v -> saveOverrides(overrides.copy(cornerRadius = v.toDouble())) },
                        )
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        // L503-582 — reset confirmation.
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { showResetConfirm = false },
            text = { Text(stringResource(UiR.string.message_style_settings_page_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    resetOverrides()
                }) { Text(stringResource(UiR.string.message_style_settings_page_reset), color = cs.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(UiR.string.message_style_settings_page_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }

    // Pragmatic color picker: a hex (#RRGGBB / #AARRGGBB) input dialog stands
    // in for showAppColorPicker, which is not yet ported in this pass.
    if (colorPicker != null) {
        val title = when (colorPicker) {
            "bg" -> stringResource(UiR.string.message_style_settings_page_background_color)
            "border" -> stringResource(UiR.string.message_style_settings_page_border_color)
            else -> stringResource(UiR.string.message_style_settings_page_text_color)
        }
        val initial = when (colorPicker) {
            "bg" -> previewColors.background(if (editingDark) overrides.backgroundArgbDark else overrides.backgroundArgbLight)
            "border" -> previewColors.border(if (editingDark) overrides.borderArgbDark else overrides.borderArgbLight)
            else -> previewColors.text(if (editingDark) overrides.textArgbDark else overrides.textArgbLight)
        }
        // Compose Color has no .rgb — convert to ARGB int first, keep the
        // low 24 bits for the #RRGGBB field.
        var hex by remember(colorPicker) {
            mutableStateOf(String.format(java.util.Locale.US, "%06X", initial.toArgb() and 0xFFFFFF))
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { colorPicker = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    singleLine = true,
                    placeholder = { Text("#RRGGBB") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val argb = parseHexColor(hex)
                    if (argb != null) {
                        val next = when (colorPicker) {
                            "bg" -> if (editingDark) overrides.copy(backgroundArgbDark = argb) else overrides.copy(backgroundArgbLight = argb)
                            "border" -> if (editingDark) overrides.copy(borderArgbDark = argb) else overrides.copy(borderArgbLight = argb)
                            else -> if (editingDark) overrides.copy(textArgbDark = argb) else overrides.copy(textArgbLight = argb)
                        }
                        saveOverrides(next)
                    }
                    colorPicker = null
                }) { Text(stringResource(UiR.string.model_detail_sheet_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { colorPicker = null }) {
                    Text(stringResource(UiR.string.message_style_settings_page_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }
}

// --------------------------------------------------------------- helpers

private fun parseHexColor(hex: String): Int? {
    val cleaned = hex.trim().removePrefix("#")
    return when (cleaned.length) {
        6 -> runCatching { "#$cleaned".toColorInt() or 0xFF000000.toInt() }.getOrNull()
        8 -> runCatching { "#$cleaned".toColorInt() }.getOrNull()
        else -> null
    }
}

// --------------------------------------------------------------- widgets

/** L698-730 — _StyleSwatch colors per style. */
@Composable
private fun StyleSwatch(styleId: String) {
    val cs = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()
    val fill = when (styleId) {
        "default" -> cs.primary.copy(alpha = if (isDark) 0.22f else 0.14f)
        "frosted" -> cs.surfaceContainerHigh.copy(alpha = 0.62f)
        else -> cs.surfaceContainerHigh
    }
    val border = when (styleId) {
        "default" -> cs.primary.copy(alpha = 0.18f)
        "frosted" -> cs.outlineVariant.copy(alpha = 0.42f)
        else -> cs.outlineVariant.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(fill, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .border(0.8.dp, border, RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
    )
}

/** L584-645 — style row: swatch + label + info Tooltip + check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleRow(styleId: String, label: String, tip: String, selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StyleSwatch(styleId)
        Spacer(Modifier.size(12.dp))
        TipHuggingLabel(
            label = label,
            tip = tip,
            labelStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.9f)),
        )
        if (selected) {
            Spacer(Modifier.size(10.dp))
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** L647-696 — switch row; explanation via info-icon Tooltip, not a bare subtitle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSwitchRow(
    label: String,
    tip: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipHuggingLabel(
            label = label,
            tip = tip,
            labelStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.9f)),
        )
        Spacer(Modifier.width(12.dp))
        IosSwitch(value = value, onValueChanged = onToggle)
    }
}

/** L732+ — two-segment toggle. */
@Composable
private fun SegmentedToggle(
    leftLabel: String,
    leftIcon: ImageVector,
    rightLabel: String,
    rightIcon: ImageVector,
    rightSelected: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceCardColorCompat(), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.18f), RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
    ) {
        SegmentedHalf(leftLabel, leftIcon, !rightSelected) { onChanged(false) }
        SegmentedHalf(rightLabel, rightIcon, rightSelected) { onChanged(true) }
    }
}

@Composable
private fun RowScope.SegmentedHalf(label: String, icon: ImageVector, selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onTap)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) cs.primary else cs.onSurface.copy(alpha = 0.6f),
            ),
        )
    }
}

/** Live preview panel — thinking row + assistant bubble + user bubble. */
@Composable
private fun PreviewPanel(
    cs: ColorScheme,
    editingDark: Boolean,
    style: String,
    userOverrides: BubbleOverrides,
    assistantOverrides: BubbleOverrides,
    colors: PreviewColors,
) {
    val scrim = if (editingDark) Color(0xFF202428) else Color(0xFFF2F3F5)
    val userResolved = resolveStyle(colors, editingDark, style, userOverrides)
    val assistantResolved = resolveStyle(colors, editingDark, style, assistantOverrides)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scrim, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, cs.outlineVariant.copy(alpha = 0.18f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(UiR.string.message_style_settings_page_preview_thinking),
            style = TextStyle(fontSize = 11.sp, color = assistantResolved.text.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(6.dp))
        Bubble(
            label = stringResource(UiR.string.message_style_settings_page_preview_assistant),
            resolved = assistantResolved,
            isUser = false,
        )
        Spacer(Modifier.height(8.dp))
        Bubble(
            label = stringResource(UiR.string.message_style_settings_page_preview_user),
            resolved = userResolved,
            isUser = true,
        )
    }
}

@Composable
private fun Bubble(label: String, resolved: ResolvedBubbleStyle, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .background(resolved.background, RoundedCornerShape(resolved.radius.roundToInt().dp))
                .border(resolved.borderWidth.dp, resolved.border, RoundedCornerShape(resolved.radius.roundToInt().dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(fontSize = 13.sp, color = resolved.text),
            )
        }
    }
}

/** L209-222 etc. — labeled slider row. */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChanged: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = cs.onSurface))
            Text(valueText, style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)))
        }
        MemoSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChanged,
            valueRange = range,
            // 行内已经有常显的 valueText，拖动胶囊直接复用它，避免两处文案不一致。
            valueLabel = { valueText },
        )
    }
}

/** L236-252 etc. — color picker row with swatch. */
@Composable
private fun ColorRow(label: String, color: Color, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = cs.onSurface))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(color, RoundedCornerShape(MemoRadius.SMALL_DP.dp))
                .border(0.8.dp, cs.outlineVariant.copy(alpha = 0.42f), RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
        )
    }
}
