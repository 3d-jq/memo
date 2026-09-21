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
        // gpt-5 has no xhigh tier: the request is normalized back to high.
        assertEquals("high", ReasoningBudget.openAiEffortForBudget(64000, "gpt-5"))
        assertEquals("max", ReasoningBudget.openAiEffortForBudget(128000, "claude-opus-4-8"))
        // gpt-5.6 carries a max tier: 128k escalates straight to max.
        assertEquals("max", ReasoningBudget.openAiEffortForBudget(128000, "gpt-5.6"))
        // 64k on gpt-5.6 requests xhigh, which the table supports.
        assertEquals("xhigh", ReasoningBudget.openAiEffortForBudget(64000, "gpt-5.6"))
        assertEquals("auto", ReasoningBudget.openAiEffortForBudget(-1, "gpt-5"))
        // off with no offFallback stays off.
        assertEquals("off", ReasoningBudget.openAiEffortForBudget(0, "gpt-5"))
        // gpt-5.1 falls back to low when off is unsupported.
        assertEquals("low", ReasoningBudget.openAiEffortForBudget(0, "gpt-5.1-codex"))
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

    @Test
    fun `zhipu gets thinking type instead of reasoning_effort`() {
        val fields = ReasoningBudget.vendorReasoningFields(
            providerId = "zhipu ai",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelId = "glm-5.3-flash",
            thinkingBudget = 16000,
            reasoning = true,
        )
        assertEquals("""{"type":"enabled"}""", fields["thinking"].toString())
        assertEquals(false, fields.containsKey("reasoning_effort"))
    }

    @Test
    fun `openai compatible keeps reasoning_effort`() {
        val fields = ReasoningBudget.vendorReasoningFields(
            providerId = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-5",
            thinkingBudget = 32000,
            reasoning = true,
        )
        assertEquals("\"high\"", fields["reasoning_effort"].toString())
    }

    @Test
    fun `non reasoning requests carry no reasoning fields`() {
        val fields = ReasoningBudget.vendorReasoningFields(
            providerId = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-4o",
            thinkingBudget = 1024,
            reasoning = false,
        )
        assertEquals(true, fields.isEmpty())
    }

    @Test
    fun `glm 5_3 supports max but not xhigh per the model table`() {
        // openai_model_compat.dart _glm53Support: ['low','high','max'].
        assertTrue(ReasoningBudget.supportsMaxReasoning("glm-5.3"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("glm-5.3-flash"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("openai/glm-5.3"))
        assertFalse(ReasoningBudget.supportsXhighReasoning("glm-5.3"))
        // 5.2 keeps xhigh; unrelated models keep neither.
        assertTrue(ReasoningBudget.supportsXhighReasoning("glm-5.2"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("glm-5.2"))
        assertFalse(ReasoningBudget.supportsMaxReasoning("glm-4.5"))
        assertFalse(ReasoningBudget.supportsXhighReasoning("glm-4.5"))
    }

    @Test
    fun `openai model table gates xhigh and max per family`() {
        // deepseek/kimi/kimi: max without xhigh.
        assertTrue(ReasoningBudget.supportsMaxReasoning("deepseek-reasoner"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("kimi-k3"))
        assertFalse(ReasoningBudget.supportsXhighReasoning("deepseek-reasoner"))
        // grok-4.6 has xhigh but no max.
        assertTrue(ReasoningBudget.supportsXhighReasoning("grok-4.6"))
        assertFalse(ReasoningBudget.supportsMaxReasoning("grok-4.6"))
        // gpt-5.6 carries both; plain gpt-5 carries neither; unlisted → neither.
        assertTrue(ReasoningBudget.supportsMaxReasoning("gpt-5.6"))
        assertTrue(ReasoningBudget.supportsXhighReasoning("gpt-5.6"))
        assertFalse(ReasoningBudget.supportsMaxReasoning("gpt-5"))
        assertFalse(ReasoningBudget.supportsXhighReasoning("gpt-5"))
        assertFalse(ReasoningBudget.supportsMaxReasoning("some-random-model"))
        // gpt-5.1-codex-max: xhigh only.
        assertTrue(ReasoningBudget.supportsXhighReasoning("gpt-5.1-codex-max"))
        assertFalse(ReasoningBudget.supportsMaxReasoning("gpt-5.1-codex-max"))
    }

    @Test
    fun `claude capability gates follow the upstream ladders`() {
        // opus/sonnet 5 family: xhigh + max.
        assertTrue(ReasoningBudget.supportsXhighReasoning("claude-opus-5"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("claude-sonnet-5-20260101"))
        // opus 4.7/4.8: xhigh + max; sonnet 4.6: max only; sonnet 4.5: neither.
        assertTrue(ReasoningBudget.supportsXhighReasoning("claude-opus-4.7"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("claude-opus-4-8"))
        assertFalse(ReasoningBudget.supportsXhighReasoning("claude-sonnet-4.6"))
        assertTrue(ReasoningBudget.supportsMaxReasoning("claude-sonnet-4-6"))
        assertFalse(ReasoningBudget.supportsXhighReasoning("claude-sonnet-4-5"))
        assertFalse(ReasoningBudget.supportsMaxReasoning("claude-sonnet-4-5"))
        // fable/mythos always top-tier.
        assertTrue(ReasoningBudget.supportsMaxReasoning("claude-mythos-1"))
        // max implies xhigh in openAiEffortForBudget escalation (128k budget).
        assertEquals("max", ReasoningBudget.openAiEffortForBudget(128000, "glm-5.3"))
        // 64k requests xhigh; glm-5.3 lacks it, normalization picks max (next
        // in the xhigh preference order: xhigh → max → high …).
        assertEquals("max", ReasoningBudget.openAiEffortForBudget(64000, "glm-5.3"))
        // grok-4.6 has xhigh but no max.
        assertEquals("xhigh", ReasoningBudget.openAiEffortForBudget(64000, "grok-4.6"))
        // A plain model keeps high (no xhigh/max anywhere).
        assertEquals("high", ReasoningBudget.openAiEffortForBudget(64000, "mimo-v2"))
    }
}
