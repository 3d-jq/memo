package com.psyche.memo.ui.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.psyche.memo.ui.ChatStyleSpec

/**
 * Chat surface foreground palette — port of
 * chat_message_widget.dart `_computeChatSurfaceForegroundPalette`
 * (3898-3933) `_ChatSurfaceForegroundPalette` + the `_ChatSurfaceTheme`
 * inherited widget (3871-3896).
 */
data class ChatSurfaceFg(
    val strong: Color,
    val medium: Color,
    val muted: Color,
    val body: Color,
    val divider: Color,
    val accent: Color,
)

/** CMW:3905-3915 —— defaultStyle 分支：strong/medium 走 secondary，divider 走 outline。 */
fun defaultChatSurfaceFg(cs: ColorScheme, isDark: Boolean): ChatSurfaceFg = ChatSurfaceFg(
    strong = cs.secondary,
    medium = cs.secondary.copy(alpha = 0.9f),
    muted = cs.onSurface.copy(alpha = 0.5f),
    body = cs.onSurface.copy(alpha = 0.7f),
    divider = if (isDark) cs.onSurface.copy(alpha = 0.24f) else cs.outline.copy(alpha = 0.15f),
    accent = cs.primary,
)

/** CMW:3918-3932 —— 非 default 分支：以气泡文字色为底的一整套透明度阶梯。 */
fun customChatSurfaceFg(base: Color, isDark: Boolean): ChatSurfaceFg = ChatSurfaceFg(
    strong = base.copy(alpha = if (isDark) ChatStyleSpec.FG_STRONG_DARK else ChatStyleSpec.FG_STRONG_LIGHT),
    medium = base.copy(alpha = if (isDark) ChatStyleSpec.FG_MEDIUM_DARK else ChatStyleSpec.FG_MEDIUM_LIGHT),
    muted = base.copy(alpha = if (isDark) ChatStyleSpec.FG_MUTED_DARK else ChatStyleSpec.FG_MUTED_LIGHT),
    body = base.copy(alpha = if (isDark) ChatStyleSpec.FG_BODY_DARK else ChatStyleSpec.FG_BODY_LIGHT),
    divider = base.copy(alpha = if (isDark) 0.16f else 0.14f),
    accent = base.copy(alpha = if (isDark) 0.84f else 0.74f),
)

fun computeChatSurfaceFg(cs: ColorScheme, isDark: Boolean, isUser: Boolean, styles: ChatBubbleStyles): ChatSurfaceFg {
    if (styles.style == ChatBubbleStyle.DEFAULT) return defaultChatSurfaceFg(cs, isDark)
    val base = resolveBubbleStyle(cs, isDark, styles.style, styles.overridesFor(isUser)).text
    return customChatSurfaceFg(base, isDark)
}

/** CMW:3871-3885 `_ChatSurfaceTheme` —— 由消息行提供，卡片刻度据此取色。 */
val LocalChatSurfaceFg = staticCompositionLocalOf<ChatSurfaceFg?> { null }

/**
 * CMW:3887-3896 `_chatSurfaceForegroundPalette(context, isUser:)`：非用户调用
 * 优先吃消息行挂上的继承色板；用户调用（气泡外的用户头像一类）当场重算。
 */
@Composable
fun chatSurfaceFg(isUser: Boolean = false): ChatSurfaceFg {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    if (!isUser) LocalChatSurfaceFg.current?.let { return it }
    return computeChatSurfaceFg(cs, isDark, isUser, LocalChatBubbleStyles.current)
}
