package com.psyche.memo.llm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of chat_api_helpers.dart / google_common.dart budget mappings. */
class ReasoningBudgetTest {

    @Test
    fun `isOff treats auto and null as on`() {
        assertFalse(ReasoningBudget.isOff(null))
        assertFalse(ReasoningBudget.isOff(-1))
        assertFalse(ReasoningBudget.isOff(1024))
        assertTrue(ReasoningBudget.isOff(0))
        assertTrue(ReasoningBudget.isOff(512))
    }

    @Test
    fun `effortForBudget buckets match the upstream ladder`() {
        assertEquals("auto", ReasoningBudget.effortForBudget(null))
        assertEquals("auto", ReasoningBudget.effortForBudget(-1))
        assertEquals("off", ReasoningBudget.effortForBudget(0))
        assertEquals("low", ReasoningBudget.effortForBudget(1024))
        assertEquals("low", ReasoningBudget.effortForBudget(2000))
        assertEquals("medium", ReasoningBudget.effortForBudget(16000))
        assertEquals("high", ReasoningBudget.effortForBudget(32000))
    }

    @Test
    fun `openAI effort escalates high to xhigh and max`() {
        assertEquals("high", ReasoningBudget.openAiEffortForBudget(32000, "gpt-5"))
        assertEquals("xhigh", ReasoningBudget.openAiEffortForBudget(64000, "gpt-5"))
        assertEquals("max", ReasoningBudget.openAiEffortForBudget(128000, "claude-opus-4-8"))
        assertEquals("xhigh", ReasoningBudget.openAiEffortForBudget(128000, "gpt-5"))
        assertEquals("auto", ReasoningBudget.openAiEffortForBudget(-1, "gpt-5"))
        assertEquals("off", ReasoningBudget.openAiEffortForBudget(0, "gpt-5"))
    }

    @Test
    fun `claude thinking config picks disabled enabled and adaptive`() {
        assertEquals(
            """{"type":"disabled"}""",
            ReasoningBudget.claudeThinkingConfig("claude-sonnet-4-5", 0).toString(),
        )
        assertEquals(
            """{"type":"enabled","budget_tokens":1024}""",
            ReasoningBudget.claudeThinkingConfig("claude-sonnet-4-5", 1024).toString(),
        )
        assertEquals(
            """{"type":"adaptive","display":"summarized"}""",
            ReasoningBudget.claudeThinkingConfig("claude-opus-5-20260101", 32000).toString(),
        )
        // auto budget on a non-adaptive model → disabled (no positive tokens).
        assertEquals(
            """{"type":"disabled"}""",
            ReasoningBudget.claudeThinkingConfig("claude-sonnet-4-5", -1).toString(),
        )
    }

    @Test
    fun `gemini 2 fallback uses thinkingBudget`() {
        assertEquals(
            """{"includeThoughts":true,"thinkingBudget":1024}""",
            ReasoningBudget.googleThinkingConfig("gemini-2.5-pro", 1024).toString(),
        )
        assertEquals(
            """{"includeThoughts":false}""",
            ReasoningBudget.googleThinkingConfig("gemini-2.5-pro", 0).toString(),
        )
    }

    @Test
    fun `gemini 3 pro maps presets to thinking levels`() {
        assertEquals(
            """{"includeThoughts":true,"thinkingLevel":"low"}""",
            ReasoningBudget.googleThinkingConfig("gemini-3.1-pro-preview", 1024).toString(),
        )
        assertEquals(
            """{"includeThoughts":true,"thinkingLevel":"medium"}""",
            ReasoningBudget.googleThinkingConfig("gemini-3.1-pro-preview", 16000).toString(),
        )
        assertEquals(
            """{"includeThoughts":true,"thinkingLevel":"high"}""",
            ReasoningBudget.googleThinkingConfig("gemini-3.1-pro-preview", 32000).toString(),
        )
    }

    @Test
    fun `gemini 3 flash and image variants follow their own ladders`() {
        // 3.5 flash defaults to medium and drops 'minimal'
        assertEquals(
            """{"includeThoughts":true,"thinkingLevel":"medium"}""",
            ReasoningBudget.googleThinkingConfig("gemini-3.5-flash", -1).toString(),
        )
        // flash-image only has minimal/high
        assertEquals(
            """{"includeThoughts":true,"thinkingLevel":"minimal"}""",
            ReasoningBudget.googleThinkingConfig("gemini-3-flash-image", 1024).toString(),
        )
        assertEquals(
            """{"includeThoughts":true,"thinkingLevel":"high"}""",
            ReasoningBudget.googleThinkingConfig("gemini-3-flash-image", 32000).toString(),
        )
    }
}
