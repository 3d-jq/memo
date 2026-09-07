package com.psyche.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * assistant_provider.load() resolution: the persisted current-assistant id is
 * restored only while it still exists among the assistants; a stale/deleted id
 * falls back to null (the default-to-first only happens at initial seeding in
 * ensureDefaults).
 */
class AssistantSelectionTest {

    @Test
    fun resolve_restoresSavedIdWhenItStillExists() {
        assertEquals("a", resolveCurrentAssistantId("a", listOf("a", "b")))
    }

    @Test
    fun resolve_staleIdFallsBackToNull() {
        assertNull(resolveCurrentAssistantId("z", listOf("a", "b")))
    }

    @Test
    fun resolve_missingSavedIdIsNull() {
        assertNull(resolveCurrentAssistantId(null, listOf("a", "b")))
    }

    @Test
    fun resolve_emptyAssistantListIsNull() {
        assertNull(resolveCurrentAssistantId("a", emptyList()))
    }
}
