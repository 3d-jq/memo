package com.psyche.memo.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.R as UiR

/**
 * 长用户消息的折叠容器 —— **本工程新增**（上游 kelivo 与 RikkaHub 都没有把长正文折起来：
 * 上游那份 `bounded_large_text_view.dart` 在它自己仓里就是死代码，RikkaHub 的折叠只用在
 * 思维链卡 / 代码块 / 翻译上）。用户 2026-09-25「用户输入的内容比较多，发送到界面的时候
 * 可以收起和展开」+「在左下角显示收起和展开，可以点击」。
 *
 * **按高度封顶，不按行数**：用户正文走 `MarkdownText`（多段落 / 代码块 / 表格），
 * `Text(maxLines=…)` 那种"数行"的口径在这里算不出跨块的总高。上限取
 * [USER_BUBBLE_COLLAPSE_LINES] × 用户正文行高，所以字号设置变了折叠线会跟着走。
 *
 * 没溢出时**什么都不做**（不裁剪、不画控件），短消息与改动前逐像素相同。
 */
@Composable
fun CollapsibleUserBubble(
    expanded: Boolean,
    onToggle: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val capPx = with(LocalDensity.current) {
        (ChatStyleSpec.USER_TEXT_LINE_HEIGHT_SP * USER_BUBBLE_COLLAPSE_LINES).sp.toPx()
    }
    var contentPx by remember { mutableFloatStateOf(0f) }
    val overflowing = userBubbleOverflows(contentPx, capPx)
    val collapsed = overflowing && !expanded

    Column(modifier = modifier) {
        Box(
            Modifier
                .clipToBounds()
                // 折叠态的"截断"发生在测量层：内容始终按**无限高**测量（这与 LazyColumn
                // 本来的测量方式一致，嵌套滚动 / 表格不受影响），只是向外报告封顶后的高度。
                // 真实高度回写一次即稳定（同高时不再写），不会自激重排。
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        constraints.copy(maxHeight = Constraints.Infinity),
                    )
                    if (contentPx != placeable.height.toFloat()) {
                        contentPx = placeable.height.toFloat()
                    }
                    val height = if (collapsed) placeable.height.coerceAtMost(capPx.toInt()) else placeable.height
                    layout(placeable.width, height) { placeable.placeRelative(0, 0) }
                }
                // 渐隐用 DstIn 擦掉底部一截的 alpha，而不是盖一层背景色 —— 用户气泡是
                // 半透明的（primary@0.15/0.08），盖色块会在气泡上留一条不透明的"抹布"。
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    if (collapsed) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White,
                                (1f - BUBBLE_FADE_RATIO).coerceAtLeast(0f) to Color.White,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        ) { content() }

        if (overflowing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(onClick = onToggle),
            ) {
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(
                        if (expanded) UiR.string.user_message_collapse else UiR.string.user_message_expand,
                    ),
                    style = TextStyle(fontSize = ChatStyleSpec.USER_TEXT_SP.sp, color = textColor),
                )
            }
        }
    }
}

/** 折叠线（行）。与用户正文行高相乘得到封顶高度，见 [CollapsibleUserBubble]。 */
internal const val USER_BUBBLE_COLLAPSE_LINES = 8

/** 折叠态底部渐隐占封顶高度的比例。 */
internal const val BUBBLE_FADE_RATIO = 0.14f

/**
 * 溢出判据：正文真实高度**超过**封顶才算折得起来（差半个像素不算，测量是整数 px）。
 * 封顶为 0（字号设成极端值）时一律不折，避免把整条消息藏进零高。
 */
internal fun userBubbleOverflows(contentHeightPx: Float, capHeightPx: Float): Boolean =
    capHeightPx > 0f && contentHeightPx > capHeightPx + 0.5f
