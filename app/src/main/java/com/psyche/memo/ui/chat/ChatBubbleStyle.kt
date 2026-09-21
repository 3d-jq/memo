package com.psyche.memo.ui.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 1:1 port of `lib/theme/chat_bubble_style.dart` — the three bubble background
 * styles, the nullable per-role override bag, and the resolver that folds
 * theme + style + overrides into the values the chat surface paints.
 *
 * 消费点（原版 `chat_message_widget.dart`）：
 * - `_buildSharedChatSurface` 3777-3838 → [ChatBubbleSurface]
 * - `_chatSurfacePlainTextColor` 3762-3775 → [chatSurfacePlainTextColor]
 * - `_computeChatSurfaceForegroundPalette` 3898-3933 → [computeChatSurfaceFg]
 */

/** chat_bubble_style.dart + settings_provider.dart `ChatMessageBackgroundStyle`. */
enum class ChatBubbleStyle(val wire: String) {
    DEFAULT("default"),
    FROSTED("frosted"),
    SOLID("solid"),
    ;

    companion object {
        fun fromWire(raw: String?): ChatBubbleStyle =
            entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}

/**
 * chat_bubble_style.dart:9-168 —— 每个字段都可空；`null` 表示沿用硬编码默认
 * （主题色 / 0.8pt 描边 / 16 圆角 / sigma 14 …）。
 */
data class BubbleOverrides(
    val backgroundArgbLight: Int? = null,
    val backgroundArgbDark: Int? = null,
    val borderArgbLight: Int? = null,
    val borderArgbDark: Int? = null,
    val textArgbLight: Int? = null,
    val textArgbDark: Int? = null,
    /** 几何：null → 0.8 / frosted 0.14·solid 0.16 / 16。 */
    val borderWidth: Double? = null,
    val borderOpacity: Double? = null,
    val cornerRadius: Double? = null,
    /** 仅 frosted：null → 14。 */
    val blurSigma: Double? = null,
    val frostedOpacity: Double? = null,
    val solidOpacity: Double? = null,
) {
    /** chat_bubble_style.dart:43-55 —— 全空即「没改过」。 */
    val isDefault: Boolean
        get() = this == NONE

    /** chat_bubble_style.dart:57-59 `hasTextOverride(Brightness)`。 */
    fun hasTextOverride(dark: Boolean): Boolean =
        if (dark) textArgbDark != null else textArgbLight != null

    fun toJson(): String {
        val obj = buildJsonObject {
            backgroundArgbLight?.let { put("backgroundArgbLight", it) }
            backgroundArgbDark?.let { put("backgroundArgbDark", it) }
            borderArgbLight?.let { put("borderArgbLight", it) }
            borderArgbDark?.let { put("borderArgbDark", it) }
            textArgbLight?.let { put("textArgbLight", it) }
            textArgbDark?.let { put("textArgbDark", it) }
            borderWidth?.let { put("borderWidth", it) }
            borderOpacity?.let { put("borderOpacity", it) }
            cornerRadius?.let { put("cornerRadius", it) }
            blurSigma?.let { put("blurSigma", it) }
            frostedOpacity?.let { put("frostedOpacity", it) }
            solidOpacity?.let { put("solidOpacity", it) }
        }
        return obj.toString()
    }

    companion object {
        val NONE = BubbleOverrides()

        /** chat_bubble_style.dart:120-135 `fromJson`。 */
        fun fromJson(raw: String?): BubbleOverrides {
            if (raw.isNullOrEmpty()) return NONE
            return runCatching {
                val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return NONE
                fun i(k: String) = (obj[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
                fun d(k: String) = (obj[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
                BubbleOverrides(
                    backgroundArgbLight = i("backgroundArgbLight"),
                    backgroundArgbDark = i("backgroundArgbDark"),
                    borderArgbLight = i("borderArgbLight"),
                    borderArgbDark = i("borderArgbDark"),
                    textArgbLight = i("textArgbLight"),
                    textArgbDark = i("textArgbDark"),
                    borderWidth = d("borderWidth"),
                    borderOpacity = d("borderOpacity"),
                    cornerRadius = d("cornerRadius"),
                    blurSigma = d("blurSigma"),
                    frostedOpacity = d("frostedOpacity"),
                    solidOpacity = d("solidOpacity"),
                )
            }.getOrDefault(NONE)
        }
    }
}

/** chat_bubble_style.dart:170-186 `ResolvedBubbleStyle`。 */
data class ResolvedBubbleStyle(
    val background: Color,
    val border: Color,
    val text: Color,
    val borderWidth: Double,
    val radius: Double,
    val blurSigma: Double,
)

/**
 * chat_bubble_style.dart:192-226 `resolveBubbleStyle` —— 回退值与上一版硬编码的
 * frosted / solid 分支一致，因此全字段 `null` 时像素不变。
 */
fun resolveBubbleStyle(
    cs: ColorScheme,
    isDark: Boolean,
    style: ChatBubbleStyle,
    overrides: BubbleOverrides,
): ResolvedBubbleStyle {
    val bgArgb = if (isDark) overrides.backgroundArgbDark else overrides.backgroundArgbLight
    val borderArgb = if (isDark) overrides.borderArgbDark else overrides.borderArgbLight
    val textArgb = if (isDark) overrides.textArgbDark else overrides.textArgbLight

    val opacity = when (style) {
        ChatBubbleStyle.FROSTED -> overrides.frostedOpacity ?: 0.66
        else -> overrides.solidOpacity ?: 1.0
    }
    val borderOpacity = overrides.borderOpacity ?: if (style == ChatBubbleStyle.FROSTED) 0.14 else 0.16

    return ResolvedBubbleStyle(
        background = (bgArgb?.let { Color(it) } ?: cs.surfaceContainerHigh).copy(alpha = opacity.toFloat()),
        border = (borderArgb?.let { Color(it) } ?: cs.outlineVariant).copy(alpha = borderOpacity.toFloat()),
        text = textArgb?.let { Color(it) } ?: cs.onSurface,
        borderWidth = overrides.borderWidth ?: 0.8,
        radius = overrides.cornerRadius ?: 16.0,
        blurSigma = overrides.blurSigma ?: 14.0,
    )
}

/**
 * 当前页面的气泡样式选择（settings_provider.dart `chatMessageBackgroundStyle`
 * + `chatBubbleStyleOverridesFor(isUser:)`）。
 */
data class ChatBubbleStyles(
    val style: ChatBubbleStyle = ChatBubbleStyle.DEFAULT,
    val assistantOverrides: BubbleOverrides = BubbleOverrides.NONE,
    val userOverrides: BubbleOverrides = BubbleOverrides.NONE,
) {
    /** settings_provider.dart:310-318 `chatBubbleStyleOverridesFor`。 */
    fun overridesFor(isUser: Boolean): BubbleOverrides =
        if (isUser) userOverrides else assistantOverrides
}

/** 消息级气泡样式（CMW:3749-3760 `_chatSurfaceStyleSelection` 的本地化版本）。 */
val LocalChatBubbleStyles = staticCompositionLocalOf { ChatBubbleStyles() }
