package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port coverage of chat_suggestion_service.dart. */
class SuggestionTextTest {

    @Test
    fun `parse strips bullets numbering and quotes`() {
        val raw = """
            - First suggestion
            2. Second suggestion
            "Third suggestion"
        """.trimIndent()
        assertEquals(
            listOf("First suggestion", "Second suggestion", "Third suggestion"),
            SuggestionText.parseSuggestions(raw),
        )
    }

    @Test
    fun `parse dedupes and caps at three`() {
        val raw = "a\na\nb\nc\nd"
        assertEquals(listOf("a", "b", "c"), SuggestionText.parseSuggestions(raw))
    }

    @Test
    fun `parse splits on sentence ends and drops over-long lines`() {
        // The upstream regex only splits where whitespace follows the
        // sentence punctuation.
        val raw = "你好。今天怎么样？ 这是第三句。" + "x".repeat(400)
        val out = SuggestionText.parseSuggestions(raw)
        // "这是第三句。" is glued to the 400-char tail (no whitespace after the
        // punctuation), so that line exceeds 300 chars and is dropped.
        assertEquals(listOf("你好。今天怎么样？"), out)
    }

    @Test
    fun `build content keeps the last eight turns and the tail`() {
        val messages = (1..12).map { "user" to "m$it" }
        val content = SuggestionText.buildContent(messages)
        assertEquals(8, content.split("\n\n").size)
        assertEquals(true, content.startsWith("User: m5"))
    }

    @Test
    fun `build content truncates from the head when too long`() {
        val messages = listOf("user" to ("x".repeat(5000)))
        val content = SuggestionText.buildContent(messages)
        assertEquals(4000, content.length)
        assertEquals(false, content.startsWith("User:"))
    }
}
