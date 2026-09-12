package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.AudioWaveform
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.HeartPulse
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
 * Flutter floats it (`Positioned(bottom: 12 + safeArea)`) over the list as a
 * `borderRadius: 999` capsule whose colour is `appColors.surfaceFill`. Both
 * states share identical geometry; only the button set changes.
 *
 * Below 370dp of available width the labels collapse to icons only
 * (`compact` branch, L2434-2439).
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalMargin)
                .background(semantic.surfaceFill, RoundedCornerShape(999.dp))
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
                    onClick = onFetch,
                )
                Spacer(Modifier.width(itemGap))
                ToolbarButton(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_add_new_model_button),
                    icon = Lucide.Plus,
                    showLabel = !compact,
                    compact = compact,
                    primary = true,
                    onClick = onAddNew,
                )
                if (hasModels) {
                    Spacer(Modifier.width(itemGap))
                    ToolbarButton(
                        label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_all_models_tooltip),
                        icon = Lucide.Trash2,
                        showLabel = false,
                        compact = compact,
                        destructive = true,
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
                    showLabel = !compact,
                    compact = compact,
                    onClick = onToggleSelectAll,
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
                    icon = Lucide.HeartPulse,
                    showLabel = !compact,
                    compact = compact,
                    loading = detecting,
                    enabled = selectionCount > 0 && !detecting,
                    onClick = onDetect,
                )
                if (hasFailed) {
                    Spacer(Modifier.width(itemGap))
                    ToolbarButton(
                        label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_failed_detected_models_button),
                        icon = Lucide.CircleX,
                        showLabel = false,
                        compact = compact,
                        destructive = true,
                        onClick = onDeleteFailed,
                    )
                }
                Spacer(Modifier.width(itemGap))
                ToolbarButton(
                    label = stringResource(com.psyche.memo.ui.R.string.provider_detail_page_delete_selected_models_button),
                    icon = Lucide.Trash2,
                    showLabel = !compact,
                    compact = compact,
                    destructive = true,
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
                RoundedCornerShape(999.dp),
            )
            .border(0.5.dp, cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
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
 * One capsule button inside [ModelActionToolbar] — port of
 * `_buildActionToolbarButton` (L2520-2586).
 *
 * Four mutually exclusive stylings: [primary] fills with `cs.primary` and swaps
 * the label to `onPrimary`; [destructive] tints icon and label with `cs.error`;
 * otherwise the button is outlined with the surface-strong hairline and uses
 * `onSurface`. A spinner replaces neither — it sits *in place of* the icon while
 * [loading], keeping the button width stable.
 */
@Composable
private fun ToolbarButton(
    label: String,
    icon: ImageVector,
    showLabel: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val contentColor = when {
        !enabled -> cs.onSurface.copy(alpha = 0.38f)
        primary -> cs.onPrimary
        destructive -> cs.error
        else -> cs.onSurface
    }
    val background = if (primary) cs.primary.copy(alpha = if (enabled) 1f else 0.4f) else Color.Transparent
    val borderColor = when {
        primary -> Color.Transparent
        destructive -> cs.error.copy(alpha = 0.35f)
        else -> semantic.hairlineStrong
    }
    val horizontal = when {
        !showLabel -> if (compact) 12.dp else 12.dp
        compact -> 12.dp
        else -> 18.dp
    }

    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .border(0.5.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = horizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
        if (showLabel) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = contentColor),
            )
        }
    }
}
