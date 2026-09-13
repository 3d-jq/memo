package com.psyche.memo.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式等待提示的自定义设置（用户 2026-09-13 要求）：字号夹取、颜色解析、提示词解析。
 * 这三个键是本工程新增的（原项目没有这个指示器），所以这里钉的是我们自己的契约。
 */
class ThinkingIndicatorSettingsTest {

    // ---- 字号 ----

    @Test
    fun fontSizeIsClampedToTheSupportedRange() {
        assertEquals(
            ThinkingIndicatorSettings.MIN_FONT_SP,
            ThinkingIndicatorSettings.clampFontSize(1f),
            0.001f,
        )
        assertEquals(
            ThinkingIndicatorSettings.MAX_FONT_SP,
            ThinkingIndicatorSettings.clampFontSize(99f),
            0.001f,
        )
        assertEquals(18f, ThinkingIndicatorSettings.clampFontSize(18f), 0.001f)
    }

    @Test
    fun prefsFallBackToTheDefaultsWhenAbsentOrBroken() {
        val empty = ThinkingIndicatorSettings.fromPrefs { null }
        assertEquals(ThinkingIndicatorSettings.DEFAULT_FONT_SP, empty.fontSizeSp, 0.001f)
        assertNull(empty.colorArgb)
        assertEquals(ThinkingPhrases.ALL, empty.phrases)

        val broken = ThinkingIndicatorSettings.fromPrefs { key ->
            when (key) {
                ThinkingIndicatorSettings.FONT_SIZE_KEY -> "不是数字"
                ThinkingIndicatorSettings.COLOR_KEY -> "#GGGGGG"
                ThinkingIndicatorSettings.PHRASES_KEY -> " , , "
                else -> null
            }
        }
        assertEquals(ThinkingIndicatorSettings.DEFAULT_FONT_SP, broken.fontSizeSp, 0.001f)
        assertNull(broken.colorArgb)
        assertEquals(ThinkingPhrases.ALL, broken.phrases)
    }

    // ---- 颜色 ----

    @Test
    fun colorParsesHexAndPlainInts() {
        assertEquals(0xFF6750A4.toInt(), ThinkingIndicatorSettings.parseColor("#6750A4"))
        assertEquals(0x8000FF00.toInt(), ThinkingIndicatorSettings.parseColor("#8000FF00"))
        assertEquals(0xFF6750A4.toInt(), ThinkingIndicatorSettings.parseColor(" #6750A4 "))
        // 没有 `#` 时按十进制 ARGB 读（带 `#` 才当十六进制）。
        assertEquals(-1, ThinkingIndicatorSettings.parseColor("-1"))
        assertEquals(0xFF6750A4.toInt(), ThinkingIndicatorSettings.parseColor("${0xFF6750A4.toInt()}"))
        assertNull(ThinkingIndicatorSettings.parseColor("FF6750A4"))
        assertNull(ThinkingIndicatorSettings.parseColor(""))
        assertNull(ThinkingIndicatorSettings.parseColor("#12345"))
        assertNull(ThinkingIndicatorSettings.parseColor("rgb(1,2,3)"))
    }

    @Test
    fun colorFallsBackToTheThemeWhenNotSet() {
        val theme = Color(0xFF112233)
        assertEquals(theme, ThinkingIndicatorSettings().color(theme))
        assertEquals(
            Color(0xFFE65100),
            ThinkingIndicatorSettings(colorArgb = 0xFFE65100.toInt()).color(theme),
        )
    }

    @Test
    fun toHexRoundTripsThroughParseColor() {
        val argb = 0xFF1B6EF3.toInt()
        val hex = ThinkingIndicatorSettings.toHex(argb)
        assertEquals("#1B6EF3", hex)
        assertEquals(argb, ThinkingIndicatorSettings.parseColor(hex))
    }

    // ---- 提示词 ----

    @Test
    fun phrasesParseFromJsonArray() {
        val parsed = ThinkingIndicatorSettings.parsePhrases("""["思考中","嘻嘻中","深挖中"]""")
        assertEquals(listOf("思考中", "嘻嘻中", "深挖中"), parsed)
    }

    @Test
    fun phrasesParseFromPlainTextAndDedupe() {
        // 纯文本 / 手改过的值：按行、中英文逗号、顿号切分，去空白去重复。
        val parsed = ThinkingIndicatorSettings.parsePhrases("思考中\n嘻嘻中，深挖中、思考中 , 搓手中")
        assertEquals(listOf("思考中", "嘻嘻中", "深挖中", "搓手中"), parsed)
    }

    @Test
    fun phrasesFallBackToTheBuiltInList() {
        assertEquals(ThinkingPhrases.ALL, ThinkingIndicatorSettings.parsePhrases(null))
        assertEquals(ThinkingPhrases.ALL, ThinkingIndicatorSettings.parsePhrases("   "))
        assertEquals(ThinkingPhrases.ALL, ThinkingIndicatorSettings.parsePhrases(" , , "))
        assertEquals(ThinkingPhrases.ALL, ThinkingIndicatorSettings.parsePhrases("[]"))
    }

    @Test
    fun phrasesEncodeDecodeRoundTrip() {
        val list = listOf("A", "B 中", "C")
        assertEquals(list, ThinkingIndicatorSettings.parsePhrases(ThinkingIndicatorSettings.encodePhrases(list)))
    }

    @Test
    fun prefsReadAllThreeKeys() {
        val store = mapOf(
            ThinkingIndicatorSettings.FONT_SIZE_KEY to "20",
            ThinkingIndicatorSettings.COLOR_KEY to "#00897B",
            ThinkingIndicatorSettings.PHRASES_KEY to """["只有一句"]""",
        )
        val settings = ThinkingIndicatorSettings.fromPrefs { store[it] }
        assertEquals(20f, settings.fontSizeSp, 0.001f)
        assertEquals(0xFF00897B.toInt(), settings.colorArgb)
        assertEquals(listOf("只有一句"), settings.phrases)
        assertTrue(ThinkingPhrases.ALL.size > 1)
    }
}
