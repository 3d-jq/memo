package com.psyche.memo.ui.chat

import android.icu.text.BreakIterator

/**
 * `display_long_paste_as_file_v1` / `display_long_paste_as_file_threshold_v1` —
 * 长文本粘贴转成文件附件（settings_provider.dart:252-255、1172-1176；
 * chat_input_bar.dart:1602-1612 `_handlePastedText`）。
 *
 * 判定与原版逐字对齐：开关打开、且**字素数严格大于**阈值
 * （`text.characters.take(threshold + 1).length > threshold` 等价于全长 > 阈值）。
 * 原版用 `characters`（字素簇）而不是 UTF-16 长度，所以 emoji/组合字符按一个算。
 */
data class LongPasteSettings(
    val enabled: Boolean = true,
    val threshold: Int = DEFAULT_THRESHOLD,
) {
    fun isLongPaste(text: String, graphemeCount: (String) -> Int = ::countGraphemes): Boolean =
        enabled && text.isNotEmpty() && graphemeCount(text) > threshold

    companion object {
        const val ENABLED_KEY = "display_long_paste_as_file_v1"
        const val THRESHOLD_KEY = "display_long_paste_as_file_threshold_v1"
        const val DEFAULT_THRESHOLD = 5000
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 999999

        /** settings_provider.dart:1172-1176 —— bool 默认 true，阈值默认 5000 且 clamp。 */
        fun fromPrefs(read: (String) -> String?): LongPasteSettings {
            val enabled = read(ENABLED_KEY)?.trim()?.let { it == "1" || it == "true" } ?: true
            val threshold = read(THRESHOLD_KEY)
                ?.trim()?.trim('"')?.toIntOrNull()
                ?.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
                ?: DEFAULT_THRESHOLD
            return LongPasteSettings(enabled = enabled, threshold = threshold)
        }
    }
}

/** `characters` 包的字素簇计数（Android 侧用 ICU 的字符 BreakIterator）。 */
internal fun countGraphemes(text: String): Int {
    if (text.isEmpty()) return 0
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    var count = 0
    var at = it.first()
    while (at != BreakIterator.DONE) {
        count++
        at = it.next()
    }
    return count
}
