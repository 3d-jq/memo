package com.psyche.memo.data.repo

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

    // ---- branding guards (agents.md: no kelivo name, no sponsor seeds) ----

    @Test
    fun builtinKeysCarryNoKelivoBrand() {
        ProviderRepository.BUILTIN_KEYS.forEach { key ->
            assertFalse("builtin key contains kelivo: $key", key.lowercase().contains("kelivo"))
        }
    }

    @Test
    fun memoInSeedKeepsItsEndpointAndCarriesNoKelivoBrand() {
        val seed = ProviderRepository.defaultsFor("MemoIN")
        assertEquals("https://text.pollinations.ai/openai", seed.baseUrl)
        assertTrue(seed.enabled)
        assertFalse(
            "seeded api key carries kelivo: ${seed.apiKey}",
            seed.apiKey.lowercase().contains("kelivo"),
        )
    }

    @Test
    fun sponsorSeedEntriesAreRemoved() {
        assertFalse(ProviderRepository.BUILTIN_KEYS.contains("随想AI中转站"))
        assertFalse(ProviderRepository.BUILTIN_KEYS.contains("MaruCode"))
    }

    // ---- non-canonical builtin key canonicalization ----

    @Test
    fun canonicalKeyFoldsCaseAndWhitespaceOntoBuiltinSpelling() {
        assertEquals("Zhipu AI", ProviderRepository.canonicalBuiltinKey("zhipu ai"))
        assertEquals("Zhipu AI", ProviderRepository.canonicalBuiltinKey("ZHIPU AI"))
        assertEquals("Zhipu AI", ProviderRepository.canonicalBuiltinKey("  Zhipu AI "))
        assertEquals("OpenAI", ProviderRepository.canonicalBuiltinKey("openai"))
        assertEquals("SiliconFlow", ProviderRepository.canonicalBuiltinKey("siliconflow"))
    }

    @Test
    fun canonicalKeyLeavesUnknownProvidersAlone() {
        assertNull(ProviderRepository.canonicalBuiltinKey("随想ai中转站"))
        assertNull(ProviderRepository.canonicalBuiltinKey("marucode"))
        assertNull(ProviderRepository.canonicalBuiltinKey("my-custom-relay"))
    }

    @Test
    fun canonicalizePreservesUserAddedKeysVerbatim() {
        assertEquals("随想ai中转站", ProviderRepository.canonicalizeKey("随想ai中转站"))
        assertEquals("my-relay", ProviderRepository.canonicalizeKey("my-relay"))
        // A builtin still folds — but only its spelling, never the casing the
        // user relies on for a custom provider.
        assertEquals("Zhipu AI", ProviderRepository.canonicalizeKey("zhipu ai"))
    }

    @Test
    fun everyBuiltinKeyIsItsOwnCanonicalForm() {
        ProviderRepository.BUILTIN_KEYS.forEach { key ->
            assertEquals(key, ProviderRepository.canonicalizeKey(key))
        }
    }

    // ---- migration target resolution (dup builtin row fold) ----

    @Test
    fun missingCanonicalRowAdoptsTheIncomingConfig() {
        val incoming = ProviderRepository.defaultsFor("zhipu ai")
        val target = ProviderRepository.resolveMigrationTarget(null, incoming)
        assertEquals(incoming, target)
    }

    @Test
    fun incomingDataIsMergedOntoTheCanonicalRow() {
        val canonical = ProviderRepository.defaultsFor("Zhipu AI")
        val incoming = ProviderRepository.defaultsFor("zhipu ai").copy(
            apiKey = "sk-user-key",
            models = listOf("glm-5.3-flash"),
        )
        val merged = ProviderRepository.resolveMigrationTarget(canonical, incoming)!!
        assertEquals("sk-user-key", merged.apiKey)
        assertEquals(listOf("glm-5.3-flash"), merged.models)
    }

    @Test
    fun emptyIncomingLeavesTheCanonicalRowUntouched() {
        val canonical = ProviderRepository.defaultsFor("Zhipu AI").copy(
            apiKey = "sk-kept",
            models = listOf("glm-4"),
        )
        val incoming = ProviderRepository.defaultsFor("zhipu ai")
        assertNull(ProviderRepository.resolveMigrationTarget(canonical, incoming))
    }

    @Test
    fun mergeKeepsCanonicalScalarsWhenIncomingIsBlank() {
        val canonical = ProviderRepository.defaultsFor("Zhipu AI").copy(baseUrl = "https://custom/v1")
        val incoming = ProviderRepository.defaultsFor("zhipu ai").copy(
            apiKey = "sk-new",
            baseUrl = "",
        )
        val merged = ProviderRepository.mergeOnto(canonical, incoming)
        assertEquals("https://custom/v1", merged.baseUrl)
        assertEquals("sk-new", merged.apiKey)
    }

    @Test
    fun mergeUnionsModelListsWithoutDuplicates() {
        val canonical = ProviderRepository.defaultsFor("Zhipu AI").copy(models = listOf("a", "b"))
        val incoming = ProviderRepository.defaultsFor("zhipu ai").copy(models = listOf("b", "c"))
        val merged = ProviderRepository.mergeOnto(canonical, incoming)
        assertEquals(listOf("a", "b", "c"), merged.models)
    }

    @Test
    fun mergeNeverLeavesTheIdNonCanonical() {
        val canonical = ProviderRepository.defaultsFor("Zhipu AI")
        val incoming = ProviderRepository.defaultsFor("zhipu ai").copy(apiKey = "sk-x")
        val merged = ProviderRepository.mergeOnto(canonical, incoming)
        assertEquals("Zhipu AI", merged.id)
    }

    // ---- order rewriting after a rename ----

    @Test
    fun renameRewritesEntriesAndDropsTheDuplicate() {
        val order = listOf("Aliyun", "zhipu ai", "OpenAI", "Zhipu AI", "Grok")
        val next = ProviderRepository.renameOrderEntries(order, mapOf("zhipu ai" to "Zhipu AI"))
        assertEquals(listOf("Aliyun", "Zhipu AI", "OpenAI", "Grok"), next)
    }

    @Test
    fun renameKeepsFirstSeenPosition() {
        val order = listOf("zhipu ai", "OpenAI", "Zhipu AI")
        val next = ProviderRepository.renameOrderEntries(order, mapOf("zhipu ai" to "Zhipu AI"))
        assertEquals(listOf("Zhipu AI", "OpenAI"), next)
    }

    @Test
    fun renameWithNoEntriesIsANoOp() {
        val order = listOf("a", "b")
        assertEquals(order, ProviderRepository.renameOrderEntries(order, emptyMap()))
    }

    @Test
    fun hasUserDataDistinguishesSeededFromFilledRows() {
        assertFalse(ProviderRepository.hasUserData(ProviderRepository.defaultsFor("zhipu ai")))
        assertTrue(
            ProviderRepository.hasUserData(
                ProviderRepository.defaultsFor("zhipu ai").copy(apiKey = "sk"),
            ),
        )
        assertTrue(
            ProviderRepository.hasUserData(
                ProviderRepository.defaultsFor("zhipu ai").copy(models = listOf("m")),
            ),
        )
    }
}
