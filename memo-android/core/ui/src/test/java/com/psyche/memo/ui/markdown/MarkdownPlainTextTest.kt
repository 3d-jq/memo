package com.psyche.memo.ui.markdown

import org.commonmark.node.Node
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「每个节点的纯文本」从「每个节点各存一份子树字符串」改成「整篇拼一次扁平缓冲 +
 * 每节点记区间（惰性切片）」之后的两条契约（用户 2026-09-15「点击对话历史…内容多的
 * 就会很卡」；原版 `markdown_with_highlight.dart` 用 `ByteLruCache` +
 * `IncrementalMarkdownDocument` 避免的是同一类开销）：
 *
 * 1. **逐字等价**：`plainTexts[node]` 必须和原来的递归实现完全一致（渲染靠它取值）；
 * 2. **不再重复**：扁平缓冲只含 Text 字面量 —— 老实现里文档根把整篇文本存了一份、
 *    每一层祖先又把后代文本各存一遍（O(内容 × 深度) 的分配）。
 */
class MarkdownPlainTextTest {

    private fun sample(): String = buildString {
        appendLine("# 标题")
        appendLine()
        appendLine("第一段 **粗体** 与 `代码`，还有 [链接](https://example.com)。")
        appendLine()
        appendLine("- 列表项一")
        appendLine("- 列表项二")
        appendLine("  - 嵌套项，**再加粗**")
        appendLine()
        appendLine("> 引用里有 *斜体*")
        appendLine()
        appendLine("```kotlin")
        appendLine("val x = 1")
        appendLine("```")
        appendLine()
        appendLine("| a | b |")
        appendLine("| - | - |")
        appendLine("| 1 | 2 |")
    }

    /** 老实现：自底向上为每个节点拼一份子树文本。 */
    private fun referencePlainText(node: Node, out: MutableMap<Node, String>): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            sb.append(
                if (child is Text) child.literal.orEmpty() else referencePlainText(child, out),
            )
            child = child.next
        }
        val text = sb.toString()
        out[node] = text
        return text
    }

    @Test
    fun `lazy plain text matches the recursive reference for every node`() {
        val markdown = sample()
        val parsed = parseForTest(markdown)

        val expected = HashMap<Node, String>()
        referencePlainText(parsed.root, expected)

        // 两边都必须覆盖整棵树，且每个节点的文本逐字相同。
        val mismatches = mutableListOf<String>()
        fun walk(node: Node) {
            val want = expected[node] ?: ""
            val got = parsed.plainTexts[node] ?: ""
            if (want != got) mismatches += "${node.javaClass.simpleName}: want=<$want> got=<$got>"
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }
        walk(parsed.root)

        assertTrue("节点纯文本不一致：$mismatches", mismatches.isEmpty())
        assertEquals("根节点的纯文本应是整篇正文", expected[parsed.root], parsed.plainTexts[parsed.root])
    }

    @Test
    fun `plain text stays available after the lazy view is read repeatedly`() {
        val parsed = parseForTest(sample())
        val first = parsed.plainTexts[parsed.root]
        val second = parsed.plainTexts[parsed.root]
        assertEquals(first, second)
    }

    @Test
    fun `the flat buffer holds the text once, not once per ancestor`() {
        // 200 段、每段都嵌在引用 + 列表里 —— 老的「每节点各存子树文本」在这种结构下
        // 会把同一段文本重复存很多遍。
        val markdown = buildString {
            for (i in 0 until 200) {
                appendLine("> - 段落 $i：这里是一段用来撑体量的正文，**加粗** 与 `代码`。")
            }
        }
        val parsed = parseForTest(markdown)

        // 扁平缓冲只含 Text 字面量 ⇒ 一定不长于源文本；老实现的总量是它的数倍。
        assertTrue(
            "扁平缓冲不该超过源文本长度：flat=${parsed.flatChars} source=${markdown.length}",
            parsed.flatChars <= markdown.length,
        )
        // 而且**恰好**等于全树 Text 字面量按序拼接：整篇只存一份、不重不漏。
        val expected = StringBuilder()
        fun collect(node: Node) {
            var child = node.firstChild
            while (child != null) {
                if (child is Text) expected.append(child.literal.orEmpty()) else collect(child)
                child = child.next
            }
        }
        collect(parsed.root)
        assertEquals(expected.length, parsed.flatChars)
        assertEquals(expected.toString(), parsed.plainTexts[parsed.root])
    }

    @Test
    fun `streaming render throttle matches the original thresholds`() {
        // 逐字照 markdown_with_highlight.dart:122-129（8000 字 / 50ms）与
        // `_syncRenderText` 的判据：不足 8000 字不做去抖（保打字机手感），够了才节流。
        assertEquals(8000, STREAMING_DEBOUNCE_THRESHOLD_CHARS)
        assertEquals(50L, STREAMING_LONG_RENDER_DEBOUNCE_MS)
        assertTrue(!shouldThrottleStreamingRender(7999))
        assertTrue(shouldThrottleStreamingRender(8000))
        assertTrue(shouldThrottleStreamingRender(120_000))
    }
}
