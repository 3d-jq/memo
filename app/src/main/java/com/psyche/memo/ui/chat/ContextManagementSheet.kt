package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import com.psyche.memo.ui.rememberMemoSheetState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package2
import com.psyche.memo.ChatViewModel
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * Port of context_management_sheet.dart: "compress context" and "clear context"
 * (truncateIndex toggle) rows, plus the context-usage details (user 2026-09-13:
 * the 2dp bar above the input bar opens this sheet for the numbers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextManagementSheet(
    clearLabel: String,
    usage: ChatViewModel.ContextUsage?,
    onCompress: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))
            usage?.let {
                ContextUsageCard(usage = it)
                Spacer(Modifier.height(12.dp))
            }
            OptionRow(
                icon = Lucide.Package2,
                label = stringResource(UiR.string.compress_context),
                description = stringResource(UiR.string.compress_context_desc),
                onTap = {
                    Haptics.light(view)
                    onCompress()
                },
            )
            Spacer(Modifier.height(8.dp))
            OptionRow(
                icon = Lucide.Eraser,
                label = clearLabel,
                description = stringResource(UiR.string.clear_context_desc),
                onTap = {
                    Haptics.light(view)
                    onClear()
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 上下文占用详情：百分比 + 进度条 + 估算/阈值/窗口/保留/缓冲/自动开关。 */
@Composable
private fun ContextUsageCard(usage: ChatViewModel.ContextUsage) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val fraction = if (usage.thresholdTokens <= 0) {
        0f
    } else {
        (usage.usedTokens.toFloat() / usage.thresholdTokens).coerceIn(0f, 1f)
    }
    val barColor = com.psyche.memo.ui.chat.contextUsageColor(fraction, cs, usage.auto)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCardFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(UiR.string.compress_context_usage_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = barColor),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(cs.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(barColor, RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                UiR.string.compress_context_usage_detail,
                formatTokens(usage.usedTokens),
                formatTokens(usage.thresholdTokens),
                formatTokens(usage.windowTokens),
            ),
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.62f)),
        )
    }
}

/** 1200 → "1.2k"、128000 → "128k"（详情行用，避免一串数字）。 */
internal fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 10_000 -> "${tokens / 1000}k"
    tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", tokens / 1000.0)
    else -> tokens.toString()
}

/** _OptionRow L85-142 — sheet tile with icon + label + description. */
@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCardFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = cs.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.55f)),
            )
        }
    }
}
