package com.psyche.memo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.psyche.memo.common.AppLocale
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors

/** Mirrors kelivo's `header()`: LTRB(12, first ? 2 : 12, 12, 6) at 13sp semibold. */
@Composable
internal fun SectionHeader(text: String, first: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.padding(
            start = 12.dp,
            top = if (first) 2.dp else 12.dp,
            end = 12.dp,
            bottom = 6.dp,
        ),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        ),
    )
}

/**
 * One iOS-style card per section: the whole group is a single rounded surface
 * (radius 12) filled with `surfaceCard` and outlined with `hairline`, exactly
 * like shared/widgets/section_card.dart. Rows stack inside it with only
 * dividers between them — there is no per-row card.
 */
@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    val semantic = LocalSemanticColors.current
    Surface(
        color = semantic.surfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, semantic.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/**
 * Mirrors kelivo's `_iosNavRow`: 36px icon slot + 12 gap + 15px label, with an
 * optional 13px detail and a trailing chevron.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    label: String,
    onTap: () -> Unit,
    detailText: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(icon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            modifier = Modifier.weight(1f),
        )
        if (detailText != null) {
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    color = cs.onSurface.copy(alpha = 0.6f),
                ),
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Mirrors kelivo's `_iosDivider`: a 0.6dp line centered in a 6dp slot. */
@Composable
internal fun DividerRow() {
    Box(
        modifier = Modifier.fillMaxWidth().height(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 54.dp, end = 12.dp),
            thickness = 0.6.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
        )
    }
}

/** The label shown next to the language row, matching Kelivo's own wording. */
internal fun languageLabelRes(locale: AppLocale): Int = when (locale) {
    AppLocale.SYSTEM -> UiR.string.settings_page_system_mode
    AppLocale.ZH_CN -> UiR.string.display_settings_page_language_chinese_label
    AppLocale.ZH_HANT -> UiR.string.language_display_traditional_chinese
    AppLocale.EN_US -> UiR.string.display_settings_page_language_english_label
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSheet(
    current: AppLocale,
    onSelect: (AppLocale) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            LanguageOption(UiR.string.settings_page_system_mode, AppLocale.SYSTEM, current, onSelect)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
            LanguageOption(
                UiR.string.display_settings_page_language_chinese_label,
                AppLocale.ZH_CN,
                current,
                onSelect,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
            LanguageOption(
                UiR.string.language_display_traditional_chinese,
                AppLocale.ZH_HANT,
                current,
                onSelect,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
            LanguageOption(
                UiR.string.display_settings_page_language_english_label,
                AppLocale.EN_US,
                current,
                onSelect,
            )
        }
    }
}

@Composable
private fun LanguageOption(
    labelRes: Int,
    locale: AppLocale,
    current: AppLocale,
    onSelect: (AppLocale) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val selected = locale == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(locale) }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (selected) cs.primary else cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Shared switch row + iOS divider (ported from display_settings_page.dart
// _iosSwitchRow L1363-1432 and _iosDivider L1183-1192) for settings sub-pages.
// ---------------------------------------------------------------------------

/** 源码 display_settings_page.dart L1183-1192 —— iOS 分隔线。 */
@Composable
fun SettingsIosDivider() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 54.dp, end = 12.dp)
            .height(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(cs.outlineVariant.copy(alpha = 0.18f)),
        )
    }
}

/**
 * 源码 display_settings_page.dart L1363-1432 —— _iosSwitchRow：
 * 36dp 图标位 + 15sp 标签（+tip 副标题 12sp@56% 与提示图标）+ IosSwitch。
 */
@Composable
fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tip: String? = null,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!value) }
            .padding(horizontal = 12.dp, vertical = if (tip == null) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(36.dp)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = TextStyle(fontSize = 15.sp, color = cs.onSurface))
                if (!tip.isNullOrEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tip,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            color = cs.onSurface.copy(alpha = 0.56f),
                        ),
                    )
                }
            }
        }
        // MemoryTipIcon slot (L1424-1426) — CircleHelp glyph（Info 缺失）。
        if (!tip.isNullOrEmpty()) {
            Icon(
                Lucide.Lightbulb,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = cs.primary,
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = cs.surfaceVariant,
            ),
        )
    }
}

/**
 * 源码 section_card.dart L30-66 —— SectionCard standard：r12、hairline 边框
 * （outlineVariant @ dark 0.08 / light 0.06）、背景 surfaceCard、纵向内边距 4。
 */
@Composable
fun SettingsSectionCard(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val dark = lum < 0.5f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.lerp(cs.surface, Color.White, if (dark) 0.10f else 0.96f),
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                cs.outlineVariant.copy(alpha = if (dark) 0.08f else 0.06f),
                RoundedCornerShape(12.dp),
            )
            .padding(vertical = 4.dp),
    ) { content() }
}


/** AppSemanticColors.surfaceCard（AppSemanticColors.kt L29）。 */
internal fun androidx.compose.material3.ColorScheme.surfaceCardColorCompat(): androidx.compose.ui.graphics.Color {
    val lum = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue
    val dark = lum < 0.5f
    return androidx.compose.ui.graphics.lerp(
        surface,
        androidx.compose.ui.graphics.Color.White,
        if (dark) 0.10f else 0.96f,
    )
}
