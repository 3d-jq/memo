package com.psyche.memo.ui.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Chat surface foreground palette — port of
 * chat_message_widget.dart `_computeChatSurfaceForegroundPalette`
 * (3898-3933) `_ChatSurfaceForegroundPalette`.
 *
 * Only the `ChatMessageBackgroundStyle.defaultStyle` branch is reachable on
 * Android today: the bubble-style selection (`_chatSurfaceStyleSelection`,
 * CMW:3887-3896) is not wired yet, so the resolved style is always the default
 * one. The non-default branch's alpha ladder lives in
 * [com.psyche.memo.ui.ChatStyleSpec] (FG_STRONG_* &c.) for that batch.
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
fun chatSurfaceFg(cs: ColorScheme, isDark: Boolean): ChatSurfaceFg = ChatSurfaceFg(
    strong = cs.secondary,
    medium = cs.secondary.copy(alpha = 0.9f),
    muted = cs.onSurface.copy(alpha = 0.5f),
    body = cs.onSurface.copy(alpha = 0.7f),
    divider = if (isDark) cs.onSurface.copy(alpha = 0.24f) else cs.outline.copy(alpha = 0.15f),
    accent = cs.primary,
)
