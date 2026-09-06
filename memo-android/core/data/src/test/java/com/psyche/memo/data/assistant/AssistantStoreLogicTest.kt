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
}
