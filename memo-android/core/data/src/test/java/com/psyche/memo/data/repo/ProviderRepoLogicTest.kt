package com.psyche.memo.data.repo

import com.psyche.memo.data.model.ProviderGroup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRepoLogicTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- mergeOrder (providers_page merge semantics) ----

    @Test
    fun orderedKeysComeFirst() {
        val merged = ProviderRepository.mergeOrder(
            keys = listOf("a", "b", "c"),
            order = listOf("c", "a"),
        )
        assertEquals(listOf("c", "a", "b"), merged)
    }

    @Test
    fun unrecordedKeysAppendInPlace() {
        val merged = ProviderRepository.mergeOrder(
            keys = listOf("n1", "o1", "n2"),
            order = listOf("o1"),
        )
        assertEquals(listOf("o1", "n1", "n2"), merged)
    }

    @Test
    fun staleOrderEntriesAreIgnored() {
        val merged = ProviderRepository.mergeOrder(
            keys = listOf("a", "b"),
            order = listOf("deleted", "b", "deleted2"),
        )
        assertEquals(listOf("b", "a"), merged)
    }

    @Test
    fun emptyOrderKeepsKeysOrder() {
        assertEquals(listOf("x", "y"), ProviderRepository.mergeOrder(listOf("x", "y"), emptyList()))
    }

    // ---- ProviderGroup JSON roundtrip ----

    @Test
    fun providerGroupRoundTrips() {
        val group = ProviderGroup(id = "g1", name = "Work", createdAt = 1700000000000L)
        val back = ProviderGroup.fromJsonString(json, group.toJsonString(json))
        assertEquals(group, back)
    }

    @Test
    fun providerGroupToleratesUnknownKeys() {
        val group = ProviderGroup.fromJsonString(json, """{"id":"g","name":"n","createdAt":1,"future":true}""")
        assertEquals("g", group?.id)
    }

    @Test
    fun providerGroupToleratesGarbage() {
        assertNull(ProviderGroup.fromJsonString(json, "not-json"))
    }

    // ---- branding guards (agents.md: no kelivo name, no sponsor seeds) ----

    @Test
    fun builtinKeysCarryNoKelivoBrand() {
        ProviderRepository.BUILTIN_KEYS.forEach { key ->
            assertFalse("builtin key contains kelivo: $key", key.lowercase().contains("kelivo"))
        }
    }

    @Test
    fun sponsorSeedEntriesAreRemoved() {
        assertFalse(ProviderRepository.BUILTIN_KEYS.contains("随想AI中转站"))
        assertFalse(ProviderRepository.BUILTIN_KEYS.contains("MaruCode"))
    }
}
