package com.psyche.memo.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure assistant-provider rules ported from
 * lib/core/providers/assistant_provider.dart (_buildCopyName L155-170,
 * duplicate insert position L377, reorderAssistants L508-521, delete
 * guard L487).
 */
class AssistantStoreLogicTest {

    // -------------------------------------------------------- buildCopyName

    @Test
    fun copyNameAppendsSuffix() {
        assertEquals(
            "翻译君 副本",
            buildCopyName("翻译君", setOf("翻译君"), "副本", "新助手"),
        )
    }

    @Test
    fun copyNameIncrementsCounterOnCollision() {
        val existing = setOf("翻译君", "翻译君 副本", "翻译君 副本 2")
        assertEquals(
            "翻译君 副本 3",
            buildCopyName("翻译君", existing, "副本", "新助手"),
        )
    }

    @Test
    fun copyNameWithoutSuffixUsesCounter() {
        // Dart: suffix empty -> "base 2", "base 3", ...
        val existing = setOf("A", "A 2")
        assertEquals("A 3", buildCopyName("A", existing, "", "新助手"))
    }

    @Test
    fun copyNameFallsBackForEmptySourceName() {
        assertEquals(
            "新助手 副本",
            buildCopyName("   ", emptySet(), "副本", "新助手"),
        )
    }

    @Test
    fun copyNameTrimsSuffix() {
        assertEquals("A Copy", buildCopyName("A", emptySet(), " Copy ", "新助手"))
    }

    // ----------------------------------------------------------- insertAfter

    @Test
    fun insertAfterPlacesCopyRightAfterSource() {
        assertEquals(
            listOf("a", "b", "x", "c"),
            insertAfter(listOf("a", "b", "c"), "b", "x"),
        )
    }

    @Test
    fun insertAfterTailAndHead() {
        assertEquals(listOf("a", "x"), insertAfter(listOf("a"), "a", "x"))
        assertEquals(listOf("a", "b", "x"), insertAfter(listOf("a", "b"), "b", "x"))
    }

    @Test
    fun insertAfterMissingSourceAppends() {
        assertEquals(
            listOf("a", "b", "x"),
            insertAfter(listOf("a", "b"), "ghost", "x"),
        )
    }

    // ------------------------------------------------------------ reorderMove

    @Test
    fun reorderMoveMovesItem() {
        assertEquals(
            listOf("c", "a", "b"),
            reorderMove(listOf("a", "b", "c"), 2, 0),
        )
        assertEquals(
            listOf("b", "c", "a"),
            reorderMove(listOf("a", "b", "c"), 0, 2),
        )
    }

    @Test
    fun reorderMoveRejectsInvalidIndices() {
        val ids = listOf("a", "b")
        assertNull(reorderMove(ids, 0, 0)) // no-op
        assertNull(reorderMove(ids, -1, 0))
        assertNull(reorderMove(ids, 2, 0))
        assertNull(reorderMove(ids, 0, 2))
    }

    // -------------------------------------------------------- delete guard

    @Test
    fun deleteGuardMatchesDartSemantics() {
        assertFalse(canDeleteAssistant(1))
        assertFalse(canDeleteAssistant(0))
        assertTrue(canDeleteAssistant(2))
        assertTrue(canDeleteAssistant(5))
    }

    // ------------------------------------------------- buildSeedAssistants

    @Test
    fun seedBuildsDefaultThenSample() {
        var n = 0
        val seeds = buildSeedAssistants(
            defaultName = "默认助手",
            sampleName = "示例助手",
            samplePrompt = "你是{model_name}，一位乐于助人的 AI 助手。",
            newId = { "id-${++n}" },
        )
        assertEquals(2, seeds.size)
        // Default assistant — plain name, everything else model defaults
        // (assistant_provider.dart L116 _defaultAssistant passes nulls).
        assertEquals("id-1", seeds[0].id)
        assertEquals("默认助手", seeds[0].name)
        assertEquals("", seeds[0].systemPrompt)
        assertEquals(null, seeds[0].temperature)
        assertEquals(null, seeds[0].topP)
        assertEquals(null, seeds[0].thinkingBudget)
        assertFalse(seeds[0].limitContextMessages)
        // Sample assistant — prompt template keeps the literal placeholder.
        assertEquals("id-2", seeds[1].id)
        assertEquals("示例助手", seeds[1].name)
        assertEquals("你是{model_name}，一位乐于助人的 AI 助手。", seeds[1].systemPrompt)
        assertEquals(null, seeds[1].temperature)
        assertFalse(seeds[1].limitContextMessages)
        // Unique ids across the two seeds.
        assertTrue(seeds[0].id != seeds[1].id)
    }
}
