package com.psyche.memo.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of compress_context_options.dart + utf16_safe_cut.dart. */
class CompressTextTest {

    private fun msg(role: String, content: String) = role to content

    @Test
    fun `bounded start window keeps the head within the budget`() {
        val messages = listOf(msg("user", "a".repeat(50)), msg("assistant", "b".repeat(50)))
        val text = CompressText.buildBoundedConversationText(messages, CompressText.Mode.START, 30)
        assertTrue(text.length <= 30)
        assertTrue(text.startsWith("User: a"))
    }

    @Test
    fun `bounded recent window keeps the tail`() {
        val messages = listOf(msg("user", "first"), msg("assistant", "second"), msg("user", "third"))
        val text = CompressText.buildBoundedConversationText(messages, CompressText.Mode.RECENT, 20)
        assertTrue(text.endsWith("third"))
        assertTrue(text.length <= 20)
    }

    @Test
    fun `keep recent starts at the nth-last user message`() {
        val messages = listOf(
            msg("user", "u1"), msg("assistant", "a1"),
            msg("user", "u2"), msg("assistant", "a2"),
            msg("user", "u3"), msg("assistant", "a3"),
        )
        val kept = CompressText.selectKeepRecentMessages(messages, 2)
        assertEquals(listOf("u2", "a2", "u3", "a3"), kept.map { it.second })
        assertEquals(3, CompressText.countUserMessages(messages))
        assertEquals(1, CompressText.defaultKeepUserMessageCountFor(4))
        assertEquals(2, CompressText.defaultKeepUserMessageCountFor(5))
        assertEquals(3, CompressText.defaultKeepUserMessageCountFor(10))
    }

    @Test
    fun `keep recent covers everything when count exceeds user turns`() {
        val messages = listOf(msg("user", "u1"), msg("assistant", "a1"))
        assertEquals(2, CompressText.selectKeepRecentMessages(messages, 5).size)
    }

    @Test
    fun `request char budget follows the context window formula`() {
        // 32000 * 0.7 * 1.6 = 35840
        assertEquals(35840, CompressText.compressRequestCharBudget(null))
        assertEquals(100000, CompressText.compressRequestCharBudget(1_000_000))
    }

    @Test
    fun `compress request contents split oversized windows`() {
        val messages = listOf(msg("user", "x".repeat(200)))
        val parts = CompressText.buildCompressRequestContents(
            messages,
            CompressText.Mode.START,
            maxChars = 500,
            safeRequestChars = 50,
        )
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 50 })
    }

    @Test
    fun `token estimate bands the summarized part at 10 to 30 percent`() {
        val total = "a".repeat(400)
        val kept = "a".repeat(100)
        val est = CompressText.estimateCompressionTokens(total, kept)
        assertEquals(100, est.totalTokens) // 400 / 4
        assertEquals(25, est.keptTokens)
        assertEquals(32, est.minResultTokens) // 25 + 75*0.10
        assertEquals(47, est.maxResultTokens) // 25 + 75*0.30
    }

    @Test
    fun `context length errors are detected conservatively`() {
        assertTrue(CompressText.isContextLengthError(RuntimeException("HTTP 400 context_length_exceeded")))
        assertTrue(CompressText.isContextLengthError(RuntimeException("prompt is too long")))
        assertTrue(!CompressText.isContextLengthError(RuntimeException("invalid api key")))
    }

    @Test
    fun `compress model resolution follows the fallback chain`() {
        assertEquals(
            "compress" to "m1",
            CompressText.resolveCompressModel("compress" to "m1", null, null, null, null),
        )
        assertEquals(
            "title" to "m3",
            CompressText.resolveCompressModel(null, null, "title" to "m3", null, "current" to "m5"),
        )
        assertNull(CompressText.resolveCompressModel(null, null, null, null, null))
    }

    @Test
    fun `utf16 helpers never tear a surrogate pair`() {
        val text = "a\uD83D\uDE00b" // a 😀 b
        val cut = Utf16SafeCut.truncateHead(text, 2) // would split the pair
        assertEquals("a", cut)
        val halves = Utf16SafeCut.splitHalves("ab\uD83D\uDE00cd")
        assertEquals("ab\uD83D\uDE00cd", (halves!!.first + halves.second))
        assertTrue(Utf16SafeCut.splitChunks("x".repeat(5), 2).all { it.length <= 2 })
    }
}
