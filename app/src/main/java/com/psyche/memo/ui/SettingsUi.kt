package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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

/**
 * 设置页分组标题的颜色 —— **跟随主题色**（`colorScheme.primary`）。
 *
 * 照 RikkaHub：所有设置页的骨架 `CardGroup` 都是
 * `LocalContentColor provides MaterialTheme.colorScheme.primary`（`CardGroup.kt:157`），
 * 他们主题页的「预设主题 / 自定义主题」标题也直接写 `colorScheme.primary`
 * （`SettingThemePage.kt:150/181`）。Memo 原先一律写死 `onSurface@80%`，于是换主题时
 * 这一行字完全不动 —— 用户 2026-09-14：「主题设置里面那个分类的字的颜色没有跟着
 * 主题走呀 rikkhub就可以呀」。五个分组标题（本文件的 SectionHeader + 主题页 +
 * 内存设置 + 搜索服务 + 记忆追踪）都走这里，保证判据只有一处。
 */
internal fun settingsSectionHeaderColor(scheme: ColorScheme): Color = scheme.primary

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
            color = settingsSectionHeaderColor(MaterialTheme.colorScheme),
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
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, semantic.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MemoRadius.CARD_DP.dp)),
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

/**
 * 数值项两侧的 ± 圆钮（搜索服务的最大结果数/超时、生成服务的张数/时长共用）。
 * 28dp 触摸区 + 18dp 图标，与项目里其它小图标钮一致。
 */
@Composable
internal fun StepperIcon(icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
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

/** 底部操作面板的一项（图标 + 标签，可标红）。`onClick` 放最后以便尾随 lambda。 */
internal data class SheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 底部操作面板 —— **统一用全站选项面板样式**（`LanguageSheet` 那一套：自绘拖柄 +
 * `spacedBy(8.dp)` + [MemoSheetOptionRow] 卡片行）。
 *
 * 用户 2026-09-14：「添加 skill 这个 sheet 你没有用我们统一那个样式呀 不用添加技能
 * 这个标题」—— 所以这里既不手撸行、也没有标题（统一样式的范例 `LanguageSheet` /
 * 各 SelectSheet 同样没有标题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(
    onDismiss: () -> Unit,
    actions: List<SheetAction>,
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
            actions.forEach { action ->
                MemoSheetOptionRow(
                    label = action.label,
                    selected = false,
                    icon = action.icon,
                    destructive = action.destructive,
                    onClick = action.onClick,
                )
            }
        }
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
 * `ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
dragHandle = null)` 内容的第一个子项即可。
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
                .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
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
 *
 * 两个可选能力都是为了把别的面板并进来而不是另起一套（用户 2026-09-14
 * 「没有用我们那个统一的 sheet 样式」）：
 *  - [subtitle]：第二行小字（记忆/本机副本那类带说明的选项）。有副标题时行高改为
 *    `heightIn(min = 48.dp)`，**没有副标题时仍是精确 48dp**（`SheetStyleTest` 锁着）。
 *  - [destructive]：危险项（删除）用 `cs.error` 着色，替代原来各写各的红字。
 */
@Composable
internal fun MemoSheetOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** 右对齐的次要说明（如端点路径）——对齐设置行的 detailText 样式。 */
    detail: String? = null,
    /** 第二行小字（左对齐，标签下方）。 */
    subtitle: String? = null,
    /** 危险操作（删除）：文字与图标用 `cs.error`。 */
    destructive: Boolean = false,
    /** 行尾自定义件（如 `IosSwitch`）——开关类行也用同一颗组件，不再另起一套。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 标签的说明走 ⓘ 浮泡，**紧贴文字**（[TipHuggingLabel]），不是挂到行尾。 */
    tip: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val haptics = LocalHapticsSettings.current
    val accent = if (destructive) cs.error else cs.primary
    val labelColor = when {
        destructive -> cs.error
        selected -> cs.primary
        else -> cs.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (subtitle == null) Modifier.height(48.dp) else Modifier.heightIn(min = 48.dp))
            .testTag(SHEET_OPTION_TAG)
            .background(LocalSemanticColors.current.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable {
                if (haptics.onListItemTap) Haptics.soft(view)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = if (subtitle == null) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected || destructive) accent else cs.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        val labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = labelColor)
        if (subtitle == null && tip != null) {
            // ⓘ 必须紧贴标签文字（用户 2026-09-12 点名、2026-09-14 又强调一次）——
            // 不是挂到行尾开关那侧。TipHuggingLabel 是 RowScope 扩展，所以这里
            // 标签直接放在行里，不再套一层 Column。
            TipHuggingLabel(label = label, tip = tip, labelStyle = labelStyle, maxLines = 1)
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = labelStyle)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = cs.onSurface.copy(alpha = 0.6f),
                        ),
                    )
                }
            }
        }
        if (!detail.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = accent,
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
 *
 * [icon] 可空：原版 `_iosRow`（provider_detail_page L1350-1390）的每一行都**只有
 * 标签 + 开关**，没有前置图标 —— 供应商详情页照此传 null（用户 2026-09-12 比对
 * 原版后点名：「人家这个是否启用和多Key管理 没有图标呀」）。
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector? = null,
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
        if (icon != null) {
            Box(modifier = Modifier.width(36.dp)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
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
 *
 * **底色走主题语义卡色**（与 [SectionCard] 同源）：这里原本是
 * `lerp(surface, 纯白, 96%)` 的兼容近似 —— 浅色主题下等于死白、不跟主题，
 * 于是「工具描述」「供应商详情」这些用本容器的页面卡片永远是白的
 *（用户 2026-09-17「工具描述那个卡片…全是固定白色呀 没有跟着主题」）。
 */
@Composable
fun SettingsSectionCard(content: @Composable () -> Unit) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(vertical = 4.dp),
    ) { content() }
}


/**
 * 带标签的输入框 —— 全站编辑页统一这一种：`surfaceFill` 底、聚焦时 primary 描边、
 * 圆角 12、可选的行尾图标（如密钥的明文开关）。搜索服务编辑器、生成服务编辑器与
 * 对话里的生成面板都用它，保证观感一致。
 */
@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    obscure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    maxLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.72f)),
            )
            Spacer(Modifier.height(6.dp))
        }
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = if (obscure) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            placeholder = {
                Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)))
            },
            trailingIcon = trailing,
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceFill,
                unfocusedContainerColor = semantic.surfaceFill,
                focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 卡片底色 —— 直接取主题的 [LocalSemanticColors.surfaceCard]（与 [SectionCard] 同源）。
 *
 * 原来是 `lerp(surface, 纯白, 96%)` 的兼容近似：浅色主题下等于死白、不跟主题
 *（用户 2026-09-17「没有跟着主题…全是固定白色」）。用法遍布输入框底、日志页 tab 条
 * 容器、工具参数胶囊等，改成主题色后这些位置都会跟着换主题。
 */
@Composable
internal fun androidx.compose.material3.ColorScheme.surfaceCardColorCompat(): androidx.compose.ui.graphics.Color =
    LocalSemanticColors.current.surfaceCard

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
