package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tts_text_selection.dart 的移植（`TtsTextSelection.apply` + `markdownRemoveCode`
 * + tts_provider.dart 的 `_stripMarkdown`）。
 *
 * 这五种模式在设置页摆了两年，运行时从来没人读；同时 Memo 的朗读路径也从来没有
 * 剥过 markdown（上游 `_speakQueued` 第一句就剥），两件事一起在这里锁住。
 */
class TtsTextSelectionTest {

    private fun apply(mode: TtsTextSelectionMode, text: String) =
        TtsTextSelection.apply(text, mode = mode)

    @Test
    fun `mode storage falls back to full text`() {
        assertEquals(TtsTextSelectionMode.fullText, ttsTextSelectionModeOf(null))
        assertEquals(TtsTextSelectionMode.fullText, ttsTextSelectionModeOf("nonsense"))
        assertEquals(TtsTextSelectionMode.italicOnly, ttsTextSelectionModeOf("italicOnly"))
    }

    @Test
    fun `full text is returned as-is, code and all`() {
        val text = "看 `代码` 还有\n换行"
        assertEquals("看 `代码` 还有\n换行", apply(TtsTextSelectionMode.fullText, text))
    }

    @Test
    fun `quoted only keeps paired quotes and straight quotes`() {
        assertEquals(
            "你好\n走了",
            apply(TtsTextSelectionMode.quotedOnly, "他说“你好”而且‘走了’"),
        )
        assertEquals(
            "hello",
            apply(TtsTextSelectionMode.quotedOnly, "He said \"hello\" loudly"),
        )
    }

    @Test
    fun `an apostrophe inside a word is not a quote`() {
        // 没有成对引用 ⇒ 选取为空 ⇒ 回退整段（上游 fallbackToOriginal）。
        assertEquals(
            "don't worry, it's here",
            apply(TtsTextSelectionMode.quotedOnly, "don't worry, it's here"),
        )
    }

    @Test
    fun `outside parentheses collapses bracketed asides`() {
        assertEquals("甲 丙", apply(TtsTextSelectionMode.outsideParentheses, "甲（乙）丙"))
        assertEquals("甲", apply(TtsTextSelectionMode.outsideParentheses, "甲(乙(丙)丁(戊))"))
        // depth 0 上落单的右括号要保留（上游只在 depth>0 时把它当闭合）。
        assertEquals("甲）乙", apply(TtsTextSelectionMode.outsideParentheses, "甲）乙"))
    }

    @Test
    fun `italic only collects single markers and html tags but not bold`() {
        assertEquals(
            "强调\n斜",
            apply(TtsTextSelectionMode.italicOnly, "这是 *强调* 和 **加粗** 与 <i>斜</i>"),
        )
    }

    @Test
    fun `non italic deletes the ranges together with their markers`() {
        assertEquals("前 后", apply(TtsTextSelectionMode.nonItalic, "前 *斜* 后"))
    }

    @Test
    fun `an empty selection falls back to the code-stripped source`() {
        val text = "没有引用也没有斜体的\n```\n代码块\n```\n正文"
        // markdownRemoveCode 保行结构：围栏行与围栏内的行都换成一个空格。
        assertEquals(
            "没有引用也没有斜体的\n \n \n \n正文",
            apply(TtsTextSelectionMode.quotedOnly, text),
        )
    }

    @Test
    fun `fences and inline code are removed for the selecting modes`() {
        val out = apply(TtsTextSelectionMode.quotedOnly, "他说“看”\n```\n“假引用”\n```\n")
        assertEquals("看", out)
    }

    // ---- _stripMarkdown（朗读前那道，全模式共用）------------------------------

    @Test
    fun `markdown structure collapses into one plain line`() {
        val out = stripMarkdownForTts(
            "# 标题\n这是 **粗** 与 [链接](https://a.test) 和 `内码` | 表格杠",
        )
        assertFalse("井号留在正文里就是没剥干净", out.contains("#"))
        assertFalse(out.contains("**"))
        assertFalse(out.contains("`"))
        assertFalse(out.contains("|"))
        assertFalse("整段要压成一行", out.contains("\n"))
        assertTrue(out.contains("标题"))
        assertTrue("链接文字保留、地址去掉", out.contains("链接") && !out.contains("https"))
        assertFalse("行内代码整段丢弃", out.contains("内码"))
    }

    @Test
    fun `assistant reply uses the stored mode`() {
        assertEquals(
            "你好",
            assistantReplyForTts("quotedOnly", "他说“你好”"),
        )
        assertEquals(
            "他说“你好”",
            assistantReplyForTts(null, "他说“你好”"),
        )
    }
}
