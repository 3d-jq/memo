package com.psyche.memo.ui.markdown

import com.psyche.memo.highlight.CodeHighlighter
import com.psyche.memo.highlight.HighlightToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块语法高亮的两条边界 —— 用户 2026-09-16「我们没有代码语法高亮（原版有，且带
 * 300 行/12000 字上限）」。
 *
 * 高亮引擎本身由 `:core:highlight` 模块自带 hljs 金标夹具测试覆盖（30 个语言，
 * 逐 token 对比）；这里只钉**我们的接线**：原版那条流式上限的判据，以及
 * 「引擎真的会产出带样式的 token」。
 */
class MarkdownCodeHighlightTest {

    @Test
    fun `highlight is skipped past the original caps`() {
        // 原版 `markdown_with_highlight.dart:107-108`：300 行 / 12000 字。
        assertEqualsCap(300, 12000)
        // 正好 300 行 → 高亮；301 行 → 不高亮
        val exactly300 = (1..300).joinToString("\n") { "line$it" }
        assertTrue(shouldHighlightCode(exactly300))
        assertTrue(!shouldHighlightCode("$exactly300\nline301"))
        // 正好 12000 字 → 高亮；12001 → 不高亮
        assertTrue(shouldHighlightCode("x".repeat(12_000)))
        assertTrue(!shouldHighlightCode("x".repeat(12_001)))
        // 空代码块不折腾
        assertTrue(!shouldHighlightCode(""))
    }

    private fun assertEqualsCap(expectedLines: Int, expectedChars: Int) {
        assertTrue(CODE_HIGHLIGHT_MAX_LINES == expectedLines)
        assertTrue(CODE_HIGHLIGHT_MAX_CHARS == expectedChars)
    }

    @Test
    fun `the ported engine really styles source code`() {
        val plain = CodeHighlighter().supports("kotlin")
        assertTrue("kotlin 语法必须注册进引擎", plain)
        val tokens = CodeHighlighter().highlight("val x = 1 // hi", "kotlin")
        assertTrue(
            "应产出带类型的 token（keyword/number/comment 之类）",
            tokens.any { it is HighlightToken.Styled },
        )
        // 未知语言退回纯文本（与原版一致：不支持就不高亮）
        val unknown = CodeHighlighter().highlight("val x = 1", "definitely-not-a-language")
        assertTrue(
            "未知语言应整体作为 Plain 返回",
            unknown.all { it is HighlightToken.Plain },
        )
    }
}
