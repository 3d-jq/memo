package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.R as UiR

/**
 * 消息流里的上下文压缩标记（**用户 2026-09-13 点名**：压缩不要弹对话框、压缩完也别把
 * 摘要塞进对话）：
 * - [inProgress] = true：`———— 上下文压缩中 ————`，文字走既有的扫光（[ThinkingShimmerText]）。
 * - [inProgress] = false：`———— 上下文已压缩 ————`，静态。
 *
 * 摘要本身只给模型看，不渲染、不可点、不进导出（见 PORTING §5.11）。
 */
@Composable
fun CompactionDivider(
    inProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val lineColor = cs.outlineVariant.copy(alpha = 0.5f)
    val label = stringResource(
        if (inProgress) UiR.string.compress_context_in_progress else UiR.string.compress_context_done,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = lineColor)
        Spacer(Modifier.width(8.dp))
        if (inProgress) {
            ThinkingShimmerText(
                phrases = listOf(label),
                intervalMs = 2200,
                sweepMs = 1500,
                fontSize = 12.sp,
                colorArgb = cs.onSurfaceVariant.toArgb(),
            )
        } else {
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                ),
            )
        }
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = lineColor)
    }
}

/** 进度条配色分档（占用/阈值）：<70% 主题色、70–90% 琥珀、>90% 红。 */
internal fun contextUsageColor(fraction: Float, cs: androidx.compose.material3.ColorScheme, auto: Boolean): Color {
    if (!auto) return cs.onSurfaceVariant.copy(alpha = 0.4f)
    return when {
        fraction < 0.7f -> cs.primary
        fraction < 0.9f -> Color(0xFFE0A02A)
        else -> cs.error
    }
}

/**
 * 输入栏上方那条常显的 2dp 上下文占用条（**用户 2026-09-13 定**：放这里、按自动压缩
 * 阈值算分母、点开看详情）。没有占用数据时不渲染。
 */
@Composable
fun ContextUsageBar(
    usedTokens: Int,
    thresholdTokens: Int,
    auto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val fraction = if (thresholdTokens <= 0) 0f else (usedTokens.toFloat() / thresholdTokens).coerceIn(0f, 1f)
    val description = stringResource(
        UiR.string.compress_context_usage_semantics,
        usedTokens.toString(),
        thresholdTokens.toString(),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 细条本身太难点：整条（全宽 × 14dp）都是触控区，视觉上仍是 2dp。
            .height(14.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(2.dp)
                .background(cs.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(999.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(contextUsageColor(fraction, cs, auto), RoundedCornerShape(999.dp)),
            )
        }
    }
}
