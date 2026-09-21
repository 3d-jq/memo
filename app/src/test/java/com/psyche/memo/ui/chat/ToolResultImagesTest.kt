package com.psyche.memo.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parseToolResultImages（timeline_visibility.dart）与 argsSummary
 * （chat_message_widget.dart _argsSummary）的纯逻辑测试。
 */
class ToolResultImagesTest {

    private fun images(content: String?) = parseToolResultImages(content).second
    private fun clean(content: String?) = parseToolResultImages(content).first

    @Test
    fun emptyOrNullContent_yieldsNothing() {
        assertEquals("" to emptyList<String>(), parseToolResultImages(null))
        assertEquals("" to emptyList<String>(), parseToolResultImages(""))
    }

    @Test
    fun standaloneImageLine_isExtracted() {
        val content = "![map](https://example.com/map.png)"
        assertEquals(listOf("https://example.com/map.png"), images(content))
        assertEquals("", clean(content))
    }

    @Test
    fun textAndImageLines_mixed() {
        val content = "Result summary\n\n![chart](http://x/c.png)\n\nDone"
        assertEquals(listOf("http://x/c.png"), images(content))
        // 图片行两侧的空行都保留（Dart kept.join('\n').trim() 同口径）。
        assertEquals("Result summary\n\n\nDone", clean(content))
    }

    @Test
    fun imageInsideParagraph_staysInText() {
        val content = "see ![a](http://x/a.png) here"
        assertTrue(images(content).isEmpty())
        assertEquals(content, clean(content))
    }

    @Test
    fun indentedCodeLine_isKeptAndNotScanned() {
        val content = "    ![a](http://x/a.png)"
        assertTrue(images(content).isEmpty())
        // 整体 .trim() 会把行首缩进去掉（与 Dart kept.join('\n').trim() 一致）。
        assertEquals("![a](http://x/a.png)", clean(content))
    }

    @Test
    fun fencedCodeBlock_isKept() {
        val content = "```\n![a](http://x/a.png)\n```\nbody"
        assertTrue(images(content).isEmpty())
        assertEquals(content, clean(content))
    }

    @Test
    fun fencedCodeBlock_withImageBeforeAndAfter() {
        val content = "![before](http://x/b.png)\n```\n![in](http://x/in.png)\n```\n![after](http://x/a.png)"
        assertEquals(listOf("http://x/b.png", "http://x/a.png"), images(content))
    }

    @Test
    fun tildeFence_isKept() {
        val content = "~~~\n![a](http://x/a.png)\n~~~"
        assertTrue(images(content).isEmpty())
    }

    @Test
    fun placeholderDestinations_areDropped() {
        val content = "![x]()\n![y](generated)"
        assertTrue(images(content).isEmpty())
        assertEquals("", clean(content))
    }

    @Test
    fun duplicatePaths_keepFirstSeen() {
        val content = "![a](http://x/1.png)\n![b](http://x/1.png)\n![c](http://x/2.png)"
        assertEquals(listOf("http://x/1.png", "http://x/2.png"), images(content))
    }

    @Test
    fun windowsPath_isKeptVerbatim() {
        val content = "![p](C:\\pics\\a.png)"
        assertEquals(listOf("C:\\pics\\a.png"), images(content))
    }

    @Test
    fun angleBracketDestination_isUnwrapped() {
        val content = "![p](<a b.png>)"
        assertEquals(listOf("a b.png"), images(content))
    }

    @Test
    fun escapedParens_areUnescaped() {
        val content = "![p](foo\\(bar\\).png)"
        assertEquals(listOf("foo(bar).png"), images(content))
    }

    @Test
    fun escapedBackslashAndGt_inAngleBrackets() {
        val content = "![p](<a\\>b.png>)"
        assertEquals(listOf("a>b.png"), images(content))
    }

    @Test
    fun unclosedParen_isNotAnImage() {
        val content = "![p](foo(bar)"
        assertTrue(images(content).isEmpty())
        assertEquals(content, clean(content))
    }

    @Test
    fun crlfLineEndings_areNormalized() {
        val content = "![a](http://x/1.png)\r\n![b](http://x/2.png)"
        assertEquals(listOf("http://x/1.png", "http://x/2.png"), images(content))
    }

    @Test
    fun imageLineWithTrailingText_isNotStandalone() {
        val content = "![a](http://x/a.png) trailing"
        assertTrue(images(content).isEmpty())
    }

    @Test
    fun spacesInsideDestination_areKept() {
        val content = "![p](http://x/my file.png)"
        assertEquals(listOf("http://x/my file.png"), images(content))
    }

    // ---- argsSummary (_argsSummary) ----

    @Test
    fun argsSummary_emptyArgsIsBlank() {
        assertEquals("", argsSummary(JsonObject(emptyMap())))
    }

    @Test
    fun argsSummary_showsFirstTwoPairs() {
        val args = JsonObject(
            mapOf(
                "query" to JsonPrimitive("weather"),
                "city" to JsonPrimitive("beijing"),
                "unit" to JsonPrimitive("c"),
            ),
        )
        assertEquals("query: weather, city: beijing ...", argsSummary(args))
    }

    @Test
    fun argsSummary_truncatesLongValues() {
        val args = JsonObject(mapOf("query" to JsonPrimitive("x".repeat(50))))
        assertEquals("query: ${"x".repeat(40)}...", argsSummary(args))
    }

    @Test
    fun argsSummary_stringsAreUnquoted() {
        val args = JsonObject(mapOf("text" to JsonPrimitive("hello")))
        assertEquals("text: hello", argsSummary(args))
    }
}
