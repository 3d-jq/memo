package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.psyche.memo.ui.ChatStyleSpec

/**
 * chat_message_widget.dart:3777-3838 `_buildSharedChatSurface` —— 三个样式的
 * 气泡外壳。
 *
 * - `default`：用户气泡 = primary@0.15(dark)/0.08(light)、r16、内边距 12；助手
 *   气泡无底色无内边距（CMW:3823-3826 `bareOnDefault`）；传了 [defaultColor]
 *   （译文卡一类）时按它上色并保留内边距。
 * - `frosted` / `solid`：`resolveBubbleStyle` 解析出的底色 + 描边 + 圆角 + 内边距。
 *   两个样式一旦选中就**接管**所有 `_buildSharedChatSurface` 调用点（译文卡也一起
 *   换肤，与 CMW:3801-3837 的 switch 一致）。
 *
 * **frosted 的平台差异**：原版是一整套 backdrop 快照 / `BackdropFilter` 管线
 * （`frosted/frosted_surface.dart`），Compose 没有等价物 —— 本工程对输入栏早已
 * 统一为「只画 tint」的口径（HomeScreen 输入栏注释），这里沿用同一个 Tier 0
 * 分支：`FrostedSurface` 在 `scope == null` 时也是只画 tint，所以 `blurSigma`
 * 不参与绘制（与「无壁纸 / 无 scope」时的原版像素一致）。
 */
@Composable
fun ChatBubbleSurface(
    isUser: Boolean,
    modifier: Modifier = Modifier,
    defaultColor: Color? = null,
    padding: PaddingValues = PaddingValues(ChatStyleSpec.BUBBLE_PADDING_DP.dp),
    cornerDp: Float = ChatStyleSpec.BUBBLE_CORNER_DP,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val styles = LocalChatBubbleStyles.current
    val style = styles.style

    if (style == ChatBubbleStyle.DEFAULT) {
        val shape = RoundedCornerShape(cornerDp.dp)
        val bg = defaultColor
            ?: if (isUser) {
                cs.primary.copy(
                    alpha = if (isDark) ChatStyleSpec.USER_BUBBLE_ALPHA_DARK
                    else ChatStyleSpec.USER_BUBBLE_ALPHA_LIGHT,
                )
            } else {
                Color.Transparent
            }
        // CMW:3823-3826 —— 助手默认无底色、无内边距；用户/显式 defaultColor 才有。
        val padded = isUser || defaultColor != null
        Box(
            modifier = modifier
                .background(bg, shape)
                .then(if (padded) Modifier.padding(padding) else Modifier),
        ) { content() }
        return
    }

    val overrides = styles.overridesFor(isUser)
    val resolved = resolveBubbleStyle(cs, isDark, style, overrides)
    val shape = RoundedCornerShape(resolved.radius.toFloat().dp)
    // CMW:3793-3794 —— 只在**显式覆盖了本亮度文字色**时套 DefaultTextStyle.merge。
    val textOverride = if (overrides.hasTextOverride(isDark)) resolved.text else null
    Box(
        modifier = modifier
            .background(resolved.background, shape)
            .border(resolved.borderWidth.toFloat().dp, resolved.border, shape)
            .padding(padding),
    ) {
        if (textOverride != null) {
            CompositionLocalProvider(
                LocalChatSurfaceTextOverride provides textOverride,
                LocalContentColor provides textOverride,
            ) { content() }
        } else {
            content()
        }
    }
}

/**
 * 非 default 样式的文字色覆盖（`null` = 跟随主题）。**只看显式覆盖的字段**，
 * 与 CMW:3793-3794 的 `overrides.hasTextOverride` 判断同口径。
 */
val LocalChatSurfaceTextOverride = staticCompositionLocalOf<Color?> { null }

/**
 * CMW:3762-3775 `_chatSurfacePlainTextColor` —— 关掉 Markdown 时的纯文本颜色：
 * default 样式走 onSurface，其余走气泡解析出的文字色。
 */
@Composable
fun chatSurfacePlainTextColor(isUser: Boolean = false): Color {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val styles = LocalChatBubbleStyles.current
    if (styles.style == ChatBubbleStyle.DEFAULT) return cs.onSurface
    val overrides = styles.overridesFor(isUser)
    if (!overrides.hasTextOverride(isDark)) return cs.onSurface
    return resolveBubbleStyle(cs, isDark, styles.style, overrides).text
}
