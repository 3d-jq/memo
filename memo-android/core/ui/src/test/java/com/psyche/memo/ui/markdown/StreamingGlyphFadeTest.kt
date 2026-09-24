package com.psyche.memo.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字渐显的纯逻辑（照 Agora `StreamingGlyphFade`）。
 *
 * 守三件事：**前缀增长不许动老字的出生时刻**（否则整段重播＝闪）、
 * **alpha 按 2f/秒线性爬到 1 且不越界**、**span 只落在码点边界上**
 * （emoji 是代理对，切一半会出现半个字亮半个字暗）。
 */
class StreamingGlyphFadeTest {

    @Test
    fun `appending text keeps earlier glyphs' birth times`() {
        val tracker = StreamTailFadeTracker()
        assertEquals(listOf(100L), tracker.birthTimes("你", 100L).toList())
        assertEquals(listOf(100L, 500L), tracker.birthTimes("你好", 500L).toList())
        // 老三行都不许被刷新
        assertEquals(listOf(100L, 500L, 900L), tracker.birthTimes("你好吗", 900L).toList())
    }

    /** 反例（曾经的直觉写法）：不按前缀判等，而是每次全表重算 ⇒ 已变实的字又被拉回半透明。 */
    @Test
    fun `a non-prefix rewrite is treated as brand new content`() {
        val tracker = StreamTailFadeTracker()
        tracker.birthTimes("第一段", 100L)
        val after = tracker.birthTimes("第二段", 800L)
        assertTrue(after.all { it == 800L })
    }

    @Test
    fun `empty text yields an empty table and survives a restart`() {
        val tracker = StreamTailFadeTracker()
        assertEquals(0, tracker.birthTimes("", 10L).size)
        assertEquals(listOf(20L), tracker.birthTimes("a", 20L).toList())
    }

    @Test
    fun `alpha ramps over half a second and clamps at both ends`() {
        assertEquals(STREAM_TAIL_MIN_ALPHA, streamTailAlpha(birthMs = 1_000L, nowMs = 1_000L), 0.001f)
        assertEquals(0.5f, streamTailAlpha(birthMs = 1_000L, nowMs = 1_250L), 0.001f)
        assertEquals(1f, streamTailAlpha(birthMs = 1_000L, nowMs = 1_500L), 0.001f)
        // 时钟回拨（uptimeMillis 不会，但表可能跨进程复用）也不许变负
        assertEquals(STREAM_TAIL_MIN_ALPHA, streamTailAlpha(birthMs = 5_000L, nowMs = 1_000L), 0.001f)
        // 没有出生记录 = 不是流式尾部，一律全实
        assertEquals(1f, streamTailAlpha(birthMs = 0L, nowMs = 1_000L), 0.001f)
    }

    @Test
    fun `fully opaque text returns the very same instance`() {
        val text = AnnotatedString("已经写完了")
        val births = LongArray(text.length) { 1_000L }
        assertSame(text, text.withStreamTailFade(births, nowMs = 9_000L, color = Color.Black))
    }

    @Test
    fun `only the tail gets spans and the text is untouched`() {
        val base = AnnotatedString("老内容新内容")
        val births = LongArray(6) { 1_000L } + LongArray(3) { 1_010L }
        val faded = base.withStreamTailFade(births, nowMs = 1_010L, color = Color.Red)
        assertEquals("老内容新内容", faded.text)
        assertTrue("尾部必须真有 span", faded.spanStyles.isNotEmpty())
        assertTrue(
            "span 只能落在最后 3 个字上",
            faded.spanStyles.all { it.start >= 4 && it.end <= 7 },
        )
        assertTrue(
            "刚出生的字必须比全实暗",
            faded.spanStyles.any { it.item.color.alpha < 1f },
        )
    }

    /** 反例：按 char 索引切，代理对会被劈成两半 —— emoji 半个亮半个暗。 */
    @Test
    fun `fade boundaries land on code point boundaries`() {
        val text = AnnotatedString("表情😀尾巴") // 5 个码点 / 6 个 char
        val births = LongArray(5) { 1_000L }.also { it[2] = 1_010L; it[3] = 1_010L; it[4] = 1_010L }
        val faded = text.withStreamTailFade(births, nowMs = 1_010L, color = Color.White)
        assertTrue(
            "span 起点不能落在 emoji 的半个代理马上",
            faded.spanStyles.all { !Character.isSurrogate(text.text[it.start]) || it.start == 0 },
        )
        assertTrue(faded.spanStyles.all { it.end <= text.text.length })
    }

    @Test
    fun `tick and rate match the reference`() {
        // Agora：40ms tick、2f/秒 —— 改了这两个值观感就不是「半秒变实」了
        assertEquals(40L, STREAM_TAIL_FADE_TICK_MS)
        assertEquals(2f, STREAM_TAIL_ALPHA_PER_SECOND, 0f)
    }
}
