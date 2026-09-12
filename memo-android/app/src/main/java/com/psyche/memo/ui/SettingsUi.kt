package com.psyche.memo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import com.psyche.memo.common.AppLocale
import com.psyche.memo.common.Haptics
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
    val view = LocalView.current
    val haptics = LocalHapticsSettings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // settings_page.dart L645-651: soft tick on row taps when
                // "list item tap" haptics are on.
                if (haptics.onListItemTap) Haptics.soft(view)
                onTap()
            }
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
            // settings_page.dart L573-584: label is single-line ellipsized so a
            // long detail never wraps the row.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detailText != null) {
            Text(
                text = detailText,
                // _iosNavRow detail is a single-line 13px@60% trailing summary.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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

/**
 * 自绘 sheet 拖柄（用户 2026-09-12：全站统一手绘，Material 原生胶囊把手一律不用）。
 * 40×4、onSurface@20%、全圆，含上方 8dp 与下方 [trailingGap] 间距 —— 直接放在
 * `ModalBottomSheet(dragHandle = null)` 内容的第一个子项即可。
 * 列表用 `verticalArrangement = spacedBy(...)` 的 sheet 传 `trailingGap = 0.dp`，
 * 免得间距叠成双份。
 */
@Composable
internal fun MemoSheetHandle(trailingGap: androidx.compose.ui.unit.Dp = 10.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(SHEET_HANDLE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
        )
    }
    Spacer(Modifier.height(trailingGap))
}

/** Test tags for the unified sheet chrome (see SheetStyleTest). */
internal const val SHEET_HANDLE_TAG = "memo-sheet-handle"
internal const val SHEET_OPTION_TAG = "memo-sheet-option"
internal const val SHEET_OPTION_CHECK_TAG = "memo-sheet-option-check"

/**
 * 统一的 sheet 状态：**一次展开到内容高度**（`skipPartiallyExpanded = true`）。
 *
 * 用户 2026-09-12：「很多 sheet 高度有问题，最后一个选项会被挡一下、拉一下才能看到」
 * —— M3 的默认状态在内容超过半屏时会先停在半屏（PartiallyExpanded），底部选项被裁掉，
 * 必须手动上拉。全站 sheet 一律用这个状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMemoSheetState(): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = true)

/**
 * 下拉选项行 —— 「更多」sheet（`BottomToolsSheet`）的卡片样式，用户 2026-09-12
 * 指定为全站选项面板统一样式：`surfaceCard` 底 + r14 + 48dp 高 + 左右 12，
 * 选中 = primary 文字 + 右侧 ✓。列表用 `Arrangement.spacedBy(8.dp)`，**不再用分隔线**。
 */
@Composable
internal fun MemoSheetOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalHapticsSettings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag(SHEET_OPTION_TAG)
            .background(LocalSemanticColors.current.surfaceCard, RoundedCornerShape(14.dp))
            .clickable {
                if (haptics.onListItemTap) Haptics.soft(view)
                onClick()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) cs.primary else cs.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) cs.primary else cs.onSurface,
            ),
        )
        if (selected) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(18.dp).testTag(SHEET_OPTION_CHECK_TAG),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSheet(
    current: AppLocale,
    onSelect: (AppLocale) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(sheetState = rememberMemoSheetState(), onDismissRequest = onDismiss, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)
            listOf(
                UiR.string.settings_page_system_mode to AppLocale.SYSTEM,
                UiR.string.display_settings_page_language_chinese_label to AppLocale.ZH_CN,
                UiR.string.language_display_traditional_chinese to AppLocale.ZH_HANT,
                UiR.string.display_settings_page_language_english_label to AppLocale.EN_US,
            ).forEach { (labelRes, locale) ->
                MemoSheetOptionRow(
                    label = stringResource(labelRes),
                    selected = locale == current,
                    onClick = { onSelect(locale) },
                )
            }
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
 * 行内 tip 图标（BadgeInfo 16dp@45%，28dp 触控区）——点击弹浮动 Tooltip 气泡
 * （tap 触发、isPersistent、maxWidth 280、点别处收起）。全站唯一的 tip 触发件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTipIcon(tip: String) {
    val cs = MaterialTheme.colorScheme
    val tipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        modifier = Modifier
            .size(28.dp)
            .clickable { scope.launch { tipState.show() } },
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip { Text(tip, modifier = Modifier.widthIn(max = 280.dp)) }
        },
        state = tipState,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Lucide.BadgeInfo,
                contentDescription = tip,
                tint = cs.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 「标签 + 紧随其后的 tip ⓘ」一组。
 *
 * **用户点名（2026-09-12）：「tip 图标应该在文字旁边」** —— 原版 `_iosSwitchRow`
 * （display_settings_page.dart L1508）是 `Expanded(标签) → MemoryTipIcon → 12 →
 * IosSwitch`，ⓘ 会浮到开关那侧、离文字很远。这里外层 `weight(1f)` 吃掉整行剩余
 * 宽度（所以行尾的开关/chevron 照旧贴边），内层文字 `weight(1f, fill=false)` 只占
 * 自己需要的宽度 ⇒ ⓘ 紧贴文字，余量留在组内右侧。属**有意偏离**，见 PORTING §5.11。
 */
@Composable
internal fun RowScope.TipHuggingLabel(
    label: String,
    tip: String?,
    labelStyle: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = labelStyle,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!tip.isNullOrEmpty()) {
            Spacer(Modifier.width(2.dp))
            SettingsTipIcon(tip)
        }
    }
}

/**
 * 源码 display_settings_page.dart L1363-1432 —— _iosSwitchRow + memory_ui.dart
 * L82-123 MemoryTipIcon：36dp 图标位 + 15sp 标签 + IosSwitch；说明文字一律走
 * BadgeInfo 图标的 Tooltip 浮动气泡（tap 触发、maxWidth 280、点别处收起），
 * 不在行内裸排——用户规范：项目内不统一的原生 subtitle 提示全部收敛为 Tooltip。
 * ⓘ 的位置见 [TipHuggingLabel]（紧跟标签文字，用户 2026-09-12 点名）。
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    label: String,
    tip: String? = null,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalHapticsSettings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (haptics.onListItemTap) Haptics.soft(view)
                onToggle(!value)
            }
            .padding(horizontal = 12.dp, vertical = if (tip == null) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        TipHuggingLabel(
            label = label,
            tip = tip,
            labelStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface),
        )
        Spacer(Modifier.width(12.dp))
        IosSwitch(value = value, onValueChanged = onToggle)
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

// ---------------------------------------------------------------------------
// Thin shell helpers shared by the backup/sponsor placeholder pages (they were
// ported with local_snapshots_page.dart, which now also uses them for real).
// ---------------------------------------------------------------------------

/** Section header + grouped card, as the backup pages lay them out. */
@Composable
internal fun ShellSection(
    title: String,
    first: Boolean = false,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val top = if (first) 6.dp else 0.dp
    Text(
        text = title,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        ),
        modifier = Modifier.padding(start = 12.dp, top = top, bottom = 6.dp),
    )
    SectionCard { content() }
}

@Composable
internal fun ShellDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
    )
}

/**
 * iOS-style settings row with a trailing [IosSwitch].
 *
 * `local_snapshots_page.dart` L2219: the original `_iosSwitchRow` uses
 * `EdgeInsets.symmetric(horizontal: 12, vertical: 2)` — intentionally tighter
 * than `_iosNavRow`'s 11dp, so the IosSwitch (26dp) + 4dp pad = 30dp row sits
 * more compact than the 42dp nav row.
 */
@Composable
internal fun LocalSnapshotSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    FactoryRowShell(icon, label, value, onChange)
}

@Composable
private fun FactoryRowShell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.9f)),
            modifier = Modifier.weight(1f),
        )
        IosSwitch(value = value, onValueChanged = onChange)
    }
}
