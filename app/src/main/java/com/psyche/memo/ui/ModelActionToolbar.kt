package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.AudioWaveform
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareEqual
import com.composables.icons.lucide.Trash2
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * The floating capsule toolbar under the models list — port of
 * `_buildModelActionToolbar` (default) and `_buildModelSelectionToolbar`
 * (selection), `provider_detail_page.dart` L2426-2784.
 *
 * 外壳对齐 `_buildToolbarShell`（L2398-2424）：胶囊**按内容自适应宽度**
 *（原版 Row mainAxisSize.min），居中放置；按钮各自有底色，超预算时靠
 * `showLabel` 预算逐级收缩（见下）——此前强制 fillMaxWidth + 按钮不收缩，
 * 按钮一多就撑破胶囊（用户 2026-09-14 截图）。
 *
 * Below 370dp of available width the labels collapse to icons only
 * (`compact` branch, L2434-2439). 选择模式还走 `totalWidth`/`toolbarTextBudget`
 * 的逐级 hide-label（L2700-2727）：删除标签 → 全选标签 → 检测标签。
 */
@Composable
internal fun ModelActionToolbar(
    selectMode: Boolean,
    detecting: Boolean,
    detectUseStream: Boolean,
    hasModels: Boolean,
    allSelected: Boolean,
    selectionCount: Int,
    hasFailed: Boolean,
    onFetch: () -> Unit,
    onAddNew: () -> Unit,
    onDeleteAll: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleUseStream: () -> Unit,
    onDetect: () -> Unit,
    onDeleteFailed: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val compact = maxWidth < 370.dp
        val horizontalMargin = if (compact) 10.dp else 16.dp
        val itemGap = if (compact) 8.dp else 10.dp
        val hasSelection = selectionCount > 0 && !detecting

        // 选择模式的 showLabel 预算（L2663-2727）：innerWidth-8 里放不下就按
        // 「删除 → 全选 → 检测」顺序关标签。用 dp 近似原版 TextPainter 实测
        //（label 宽 ≈ 0.62 × fontSize × 字符数 + 首尾 padding）。
        val toolbarInnerWidth = maxWidth - horizontalMargin * 2 -
            (if (compact) 10.dp * 2 else 14.dp * 2)
        val textBudget = toolbarInnerWidth - 8.dp
        fun labelWidthDp(label: String): Int = (label.length * 8.7f).toInt() + (if (compact) 28 else 36)
        var deleteSelectedLabeled = true
        var selectAllLabeled = true
        var detectLabeled = true
        if (selectMode) {
            val selectLabel = if (allSelected) "清空" else "全选"
            val detectLabel = if (detecting) "检测中" else "检测"
            val deleteFailedW = if (hasFailed) itemGap.value.toInt() + labelWidthDp("") + 8 else 0
            fun totalWidth(sel: Boolean, det: Boolean, del: Boolean): Int =
                (if (sel) labelWidthDp(selectLabel) else 24 + 12) +
                    itemGap.value.toInt() + 24 + 12 + // stream icon button
                    itemGap.value.toInt() + (if (det) labelWidthDp(detectLabel) else 24 + 12) +
                    deleteFailedW +
                    itemGap.value.toInt() + (if (del) labelWidthDp("删除所选") else 24 + 12)
            if (totalWidth(true, true, true) > textBudget.value) deleteSelectedLabeled = false
            if (totalWidth(true, true, deleteSelectedLabeled) > textBudget.value) selectAllLabeled = false
            if (totalWidth(selectAllLabeled, true, deleteSelectedLabeled) > textBudget.value) detectLabeled = false
        }

        Row(
            modifier = Modifier
                .padding(horizontal = horizontalMargin)
                .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!selectMode) {
                ToolbarButton(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_fetch_models_button),
                    icon = Lucide.Boxes,
                    showLabel = !compact,
                    compact = compact,
                    style = ToolbarStyle.OUTLINED,
                    onClick = onFetch,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(itemGap))
                ToolbarButton(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_add_new_model_button),
                    icon = Lucide.Plus,
                    showLabel = !compact,
                    compact = compact,
                    style = ToolbarStyle.FILLED,
                    onClick = onAddNew,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasModels) {
                    Spacer(Modifier.width(itemGap))
                    ToolbarButton(
                        label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_all_models_tooltip),
                        icon = Lucide.Trash2,
                        showLabel = false,
                        compact = compact,
                        style = ToolbarStyle.DESTRUCTIVE,
                        // 默认工具条的删除全部：图标 18、底 error@10%（原版
                        // `_buildActionToolbarButton(destructive: true)`）。
                        iconSize = 18.dp,
                        errorAlpha = 0.10f,
                        onClick = onDeleteAll,
                    )
                }
            } else {
                ToolbarButton(
                    label = stringResource(
                        if (allSelected) com.psyche.memo.ui.R.string.mcp_assistant_sheet_clear_all
                        else com.psyche.memo.ui.R.string.mcp_assistant_sheet_select_all,
                    ),
                    icon = if (allSelected) Lucide.CheckCheck else Lucide.Square,
                    showLabel = !compact && selectAllLabeled,
                    compact = compact,
                    style = ToolbarStyle.NEUTRAL,
                    onClick = onToggleSelectAll,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(itemGap))
                // 「使用流式」批量开关（L2745-2749 + `_buildSelectionToolbarStreamButton`，
                // L2786+）：不选中态、图标在 AudioWaveform/SquareEqual 之间切换，
                // 决定批量检测是否要求 SSE（`_detectUseStream` → testConnection(useStream:)）。
                StreamingToggleButton(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_use_streaming_label),
                    enabled = !detecting,
                    useStream = detectUseStream,
                    onClick = onToggleUseStream,
                )
                Spacer(Modifier.width(itemGap))
                ToolbarButton(
                    label = stringResource(
                        if (detecting) com.psyche.memo.ui.R.string.provider_detail_page_batch_detecting
                        else com.psyche.memo.ui.R.string.provider_detail_page_batch_detect_button,
                    ),
                    icon = if (detecting) Lucide.Loader else Lucide.HeartPulse,
                    showLabel = !compact && detectLabeled,
                    compact = compact,
                    style = ToolbarStyle.FILLED,
                    // 原版检测钮与其它不同：忙碌时换 Loader 图标（不是转圈 spinner），
                    // 禁用时底 onSurface@10%、前景 onSurface@50%。
                    loading = false,
                    enabled = selectionCount > 0 && !detecting,
                    onClick = onDetect,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hasFailed) {
                    Spacer(Modifier.width(itemGap))
                    ToolbarButton(
                        label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_failed_detected_models_button),
                        icon = Lucide.CircleX,
                        showLabel = false,
                        compact = compact,
                        style = ToolbarStyle.DESTRUCTIVE,
                        onClick = onDeleteFailed,
                    )
                }
                Spacer(Modifier.width(itemGap))
                ToolbarButton(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_selected_models_button),
                    icon = Lucide.Trash2,
                    showLabel = !compact && deleteSelectedLabeled,
                    compact = compact,
                    style = ToolbarStyle.DESTRUCTIVE,
                    enabled = hasSelection,
                    onClick = onDeleteSelected,
                )
            }
        }
    }
}

/**
 * 批量检测的「使用流式」开关（`_buildSelectionToolbarStreamButton`）：44dp 圆形
 * 描边按钮，选中态自带 8% onSurface 底；图标在 AudioWaveform（流式）/ SquareEqual
 * （非流式）之间切换，长度 160ms（原版 AnimatedSwitcher + ScaleTransition）。
 */
@Composable
private fun StreamingToggleButton(
    label: String,
    enabled: Boolean,
    useStream: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(
                if (useStream) cs.onSurface.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(MemoRadius.PILL_DP.dp),
            )
            .border(0.5.dp, cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (useStream) Lucide.AudioWaveform else Lucide.SquareEqual,
            contentDescription = label,
            tint = cs.onSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 工具条上的一种按钮样式（原版三个 builder 各自的配色）。
 */
private enum class ToolbarStyle {
    /** 拉取模型：透明底 + `primary@35%` 描边 + primary 前景，14sp semibold。 */
    OUTLINED,

    /** 新增模型 / 批量检测：`primary@12%` 底、无描边、primary 前景，14sp medium。 */
    FILLED,

    /** 删除类：`error@10~12%` 底、无描边、error 前景，14sp semibold。 */
    DESTRUCTIVE,

    /** 全选/清空：透明底 + `onSurface@20%` 描边、onSurface 前景，14sp semibold。 */
    NEUTRAL,
}

/**
 * One capsule button inside [ModelActionToolbar] — port of
 * `_buildActionToolbarButton` (L2520-2586) + `_buildSelectionToolbarDetectButton`
 * (L2917-2973) + `_buildSelectionToolbarDestructiveButton` (L2960+) +
 * `_buildSelectionToolbarSelectButton` (L2794-2861).
 *
 * 四种配色见 [ToolbarStyle]；最小 44×44、胶囊全圆、图标 20dp（默认工具条的
 * 删除全部是 18dp）、标签 14sp。禁用态统一换成 `onSurface@10%` 底 +
 * `onSurface@50%` 前景（原版三个 builder 都这么写）。
 */
@Composable
private fun ToolbarButton(
    label: String,
    icon: ImageVector,
    showLabel: Boolean,
    compact: Boolean,
    style: ToolbarStyle,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    iconSize: Dp = 20.dp,
    errorAlpha: Float = 0.12f,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> cs.onSurface.copy(alpha = 0.5f)
        style == ToolbarStyle.DESTRUCTIVE -> cs.error
        style == ToolbarStyle.NEUTRAL -> cs.onSurface
        else -> cs.primary
    }
    val background = when {
        !enabled -> cs.onSurface.copy(alpha = 0.10f)
        style == ToolbarStyle.FILLED -> cs.primary.copy(alpha = 0.12f)
        style == ToolbarStyle.DESTRUCTIVE -> cs.error.copy(alpha = errorAlpha)
        else -> Color.Transparent
    }
    val bordered = when (style) {
        ToolbarStyle.OUTLINED -> cs.primary.copy(alpha = 0.35f)
        ToolbarStyle.NEUTRAL -> cs.onSurface.copy(alpha = 0.2f)
        else -> null
    }
    // 文字钮左右 14/18、图标钮 12/18（原版 textButtonPadding / iconButtonPadding）。
    val horizontal = if (showLabel) (if (compact) 14.dp else 18.dp) else (if (compact) 12.dp else 18.dp)

    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .background(background, RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .then(
                if (bordered != null) {
                    Modifier.border(1.dp, bordered, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = horizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 14.sp,
                    // 只有 FILLED（新增/检测）用 medium，其余三档 semibold。
                    fontWeight = if (style == ToolbarStyle.FILLED) FontWeight.Medium else FontWeight.SemiBold,
                    color = contentColor,
                ),
            )
        }
    }
}
