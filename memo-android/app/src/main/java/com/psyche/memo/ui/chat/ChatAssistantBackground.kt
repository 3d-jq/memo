package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File

/**
 * Port of chat_assistant_background.dart: the current assistant's wallpaper
 * behind the chat, with the surface mask gradient (mobile 0.20→0.50 scaled by
 * `display_chat_background_mask_strength_v1`). Empty/missing backgrounds paint
 * nothing.
 */
@Composable
fun ChatAssistantBackground(
    background: String?,
    maskStrength: Float,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val raw = background?.trim().orEmpty()
    val active = remember(raw) { isBackgroundActive(raw) }
    if (!active) return

    Box(modifier = modifier.fillMaxSize()) {
        // ColorScheme.shadow 在本版 Compose 缺失，用黑色 4% 等价（原版 shadow 即近黑）。
        val shadowTint = ColorFilter.tint(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.04f), BlendMode.SrcAtop)
        // NetworkImage / FileImage equivalent — Coil decodes both off-thread.
        AsyncImage(
            model = if (raw.startsWith("http")) raw else remember(raw) { File(raw) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = shadowTint,
            modifier = Modifier.fillMaxSize(),
        )
        // Mask gradient (chat_assistant_background.dart L88-104).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            cs.surface.copy(alpha = (0.20f * maskStrength).coerceIn(0f, 1f)),
                            cs.surface.copy(alpha = (0.50f * maskStrength).coerceIn(0f, 1f)),
                        ),
                    ),
                ),
        )
    }
}

/** ChatBackdropSpec.isBackgroundActive — http(s) or an existing local file. */
internal fun isBackgroundActive(raw: String): Boolean {
    if (raw.isEmpty()) return false
    if (raw.startsWith("http")) return true
    return runCatching { File(raw).exists() }.getOrDefault(false)
}
