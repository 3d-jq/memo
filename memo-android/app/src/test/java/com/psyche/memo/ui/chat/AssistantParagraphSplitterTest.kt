package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Translations of `test/.../assistant_paragraph_splitter_test.dart`-style cases
 * for [splitAssistantParagraphs]. A failure here means the "one bubble per
 * paragraph" split diverged from `assistant_paragraph_splitter.dart`.
 */
class AssistantParagraphSplitterTest {

    @Test
    fun blankLineSplitsParagraphs() {
        assertEquals(
            listOf("first", "second", "third"),
            splitAssistantParagraphs("first\n\nsecond\n\nthird"),
        )
    }

    @Test
    fun singleParagraphIsReturnedUnchanged() {
        assertEquals(
            listOf("only one\nline break inside"),
            splitAssistantParagraphs("only one\nline break inside"),
        )
    }

    @Test
    fun blankTextIsReturnedAsIs() {
        assertEquals(listOf("   \n  "), splitAssistantParagraphs("   \n  "))
    }

    @Test
    fun blankLineInsideFenceDoesNotSplit() {
        val text = "before\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nafter"
        assertEquals(listOf("before", "```kotlin\nval a = 1\n\nval b = 2\n```", "after"), splitAssistantParagraphs(text))
    }

    @Test
    fun unclosedFenceKeepsStreamingTail() {
        // 流式输出时围栏还没闭合：里面的空行同样不切。
        val text = "intro\n\n```\nline1\n\nline2"
        assertEquals(listOf("intro", "```\nline1\n\nline2"), splitAssistantParagraphs(text))
    }

    @Test
    fun tildeFenceIsProtectedAndCloserMustMatchLength() {
        val text = "a\n\n~~~\ncode\n\ncode\n~~~~\n\nb"
        assertEquals(listOf("a", "~~~\ncode\n\ncode\n~~~~", "b"), splitAssistantParagraphs(text))
        // 短的 closer 闭合不了 4 个波浪号的围栏 —— 空行仍受保护。
        val mismatched = "a\n\n~~~~\ncode\n\n~~~\n\nb"
        assertEquals(listOf("a", "~~~~\ncode\n\n~~~\n\nb"), splitAssistantParagraphs(mismatched))
    }

    @Test
    fun backtickFenceWithBacktickInfoCannotOpen() {
        val text = "a\n\n```` bad ` info\ncode\n\ncode\n\ntail"
        // 围栏没打开（info 里带反引号）⇒ 后面那个空行照常切段。
        assertEquals(
            listOf("a", "```` bad ` info\ncode", "code", "tail"),
            splitAssistantParagraphs(text),
        )
    }

    @Test
    fun indentedContinuationStaysWithPreviousChunk() {
        val text = "paragraph\n\n    indented continuation\n\nnext"
        assertEquals(
            listOf("paragraph\n\n    indented continuation", "next"),
            splitAssistantParagraphs(text),
        )
    }

    @Test
    fun adjacentListBlocksMerge() {
        // 分开两个气泡会让有序列表在第二个气泡里从 1 重新编号。
        val text = "1. one\n2. two\n\n3. three\n4. four"
        assertEquals(listOf("1. one\n2. two\n\n3. three\n4. four"), splitAssistantParagraphs(text))
    }

    @Test
    fun listFollowedByParagraphStillSplits() {
        val text = "- item\n\nplain paragraph"
        assertEquals(listOf("- item", "plain paragraph"), splitAssistantParagraphs(text))
    }

    @Test
    fun headingOnlyChunkMergesWithTheBlockItIntroduces() {
        val text = "## 标题\n\n正文第一段\n\n正文第二段"
        assertEquals(listOf("## 标题\n\n正文第一段", "正文第二段"), splitAssistantParagraphs(text))
    }

    @Test
    fun blankLinesAreCollapsedIntoTheChunkBoundary() {
        val text = "a\n\n\n\nb"
        assertEquals(listOf("a", "b"), splitAssistantParagraphs(text))
    }

    @Test
    fun fenceOpenerIndentedUpToThreeSpacesStillOpens() {
        val text = "a\n\n   ```\ncode\n\ncode\n   ```\n\nb"
        // 段首缩进被 trim 掉，块内那行 closer 的缩进保留。
        assertEquals(listOf("a", "```\ncode\n\ncode\n   ```", "b"), splitAssistantParagraphs(text))
    }
}
