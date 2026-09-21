package com.psyche.memo.ui.chat

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 流式等待提示（[ThinkingShimmerText]）的自定义设置。
 *
 * 这个指示器本身是**有意偏离原版**的（原版是三点脉动，用户点名换成扫光文字），
 * 所以这三个键在原项目里没有对应物 —— 用户 2026-09-13 要求「加一个设置功能，
 * 让用户可以自定义文字大小、颜色、提示词」后新增：
 * `display_thinking_indicator_font_size_v1` / `_color_v1` / `_phrases_v1`。
 *
 * 键名走 `display_` 前缀只是与本工程既有命名一致；它们在生成的
 * `SettingsKeyRegistry` 里不存在（那是从 Flutter 路由生成的），
 * `PreferenceRepository` 会按 UNKNOWN 透传，读写都正常。
 */
data class ThinkingIndicatorSettings(
    val fontSizeSp: Float = DEFAULT_FONT_SP,
    /** null = 跟随主题色（原行为）。 */
    val colorArgb: Int? = null,
    val phrases: List<String> = ThinkingPhrases.ALL,
) {
    /** 主题色 / 自定义色 → 实际绘制用色。 */
    fun color(themePrimary: Color): Color = colorArgb?.let { Color(it) } ?: themePrimary

    companion object {
        const val FONT_SIZE_KEY = "display_thinking_indicator_font_size_v1"
        const val COLOR_KEY = "display_thinking_indicator_color_v1"
        const val PHRASES_KEY = "display_thinking_indicator_phrases_v1"

        const val DEFAULT_FONT_SP = 15f
        const val MIN_FONT_SP = 10f
        const val MAX_FONT_SP = 28f

        fun fromPrefs(read: (String) -> String?): ThinkingIndicatorSettings = ThinkingIndicatorSettings(
            fontSizeSp = clampFontSize(read(FONT_SIZE_KEY)?.trim()?.trim('"')?.toFloatOrNull() ?: DEFAULT_FONT_SP),
            colorArgb = parseColor(read(COLOR_KEY)),
            phrases = parsePhrases(read(PHRASES_KEY)),
        )

        internal fun clampFontSize(value: Float): Float = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

        /**
         * 提示词：优先按 JSON 数组解析（设置页写的就是数组）；否则按行/中英文逗号/顿号
         * 切分（手改过的值、或从别处粘过来的纯文本也能用）。空 → 回默认那组。
         */
        internal fun parsePhrases(raw: String?): List<String> {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return ThinkingPhrases.ALL
            val fromJson = runCatching {
                (Json.parseToJsonElement(text) as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            }.getOrNull()
            val parts = fromJson ?: text.split('\n', ',', '，', '、')
            val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return cleaned.ifEmpty { ThinkingPhrases.ALL }
        }

        internal fun encodePhrases(phrases: List<String>): String =
            JsonArray(phrases.map { JsonPrimitive(it) }).toString()

        /** `#RRGGBB` / `#AARRGGBB` / 十进制 ARGB；空或非法 → null（跟随主题）。 */
        internal fun parseColor(raw: String?): Int? {
            val text = raw?.trim()?.trim('"').orEmpty()
            if (text.isEmpty()) return null
            if (text.startsWith("#")) {
                val hex = text.removePrefix("#")
                val value = hex.toLongOrNull(16) ?: return null
                return when (hex.length) {
                    6 -> (0xFF000000L or value).toInt()
                    8 -> value.toInt()
                    else -> null
                }
            }
            return text.toLongOrNull()?.toInt()
        }

        /** 颜色 → `#RRGGBB`（设置页展示/编辑用，丢掉 alpha）。 */
        internal fun toHex(argb: Int): String = String.format(java.util.Locale.US, "#%06X", argb and 0xFFFFFF)
    }
}
