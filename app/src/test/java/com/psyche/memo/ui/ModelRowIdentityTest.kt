package com.psyche.memo.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cover for the model-row display resolution and the models-list reorder —
 * the two pieces of [ProviderModelRow] / [ModelsTab] that are pure logic.
 *
 * `resolveModelIdentity` is the port of `_ModelCard._resolveBaseAndOverride`
 * (provider_detail_page.dart L4168-4186). The distinction it encodes matters:
 * the *display name* may be a local alias, but the *brand avatar* follows the
 * upstream `apiModelId`, so a renamed model still shows the right vendor logo.
 */
class ModelRowIdentityTest {

    private fun override(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `no override falls back to the model id for both name and brand`() {
        val identity = resolveModelIdentity("gpt-5", override = null)
        assertEquals("gpt-5", identity.displayName)
        assertEquals("gpt-5", identity.baseId)
    }

    @Test
    fun `empty override falls back to the model id`() {
        val identity = resolveModelIdentity("gpt-5", JsonObject(emptyMap()))
        assertEquals("gpt-5", identity.displayName)
        assertEquals("gpt-5", identity.baseId)
    }

    @Test
    fun `displayName override renames the row but not the brand`() {
        val identity = resolveModelIdentity(
            "gpt-5",
            override("displayName" to "Smart Model"),
        )
        assertEquals("Smart Model", identity.displayName)
        assertEquals("gpt-5", identity.baseId)
    }

    @Test
    fun `apiModelId override redirects the brand and the name follows it`() {
        // Flutter: baseId comes from apiModelId, so the avatar is the upstream
        // brand; with no displayName the label falls back to the local id.
        val identity = resolveModelIdentity(
            "my-alias",
            override("apiModelId" to "claude-sonnet-4"),
        )
        assertEquals("my-alias", identity.displayName)
        assertEquals("claude-sonnet-4", identity.baseId)
    }

    @Test
    fun `snake_case api_model_id is accepted too`() {
        val identity = resolveModelIdentity(
            "my-alias",
            override("api_model_id" to "claude-sonnet-4"),
        )
        assertEquals("claude-sonnet-4", identity.baseId)
    }

    @Test
    fun `camelCase wins over snake_case when both are present`() {
        val identity = resolveModelIdentity(
            "alias",
            override("apiModelId" to "camel", "api_model_id" to "snake"),
        )
        assertEquals("camel", identity.baseId)
    }

    @Test
    fun `blank override values are ignored rather than blanking the row`() {
        val identity = resolveModelIdentity(
            "gpt-5",
            override("displayName" to "   ", "apiModelId" to ""),
        )
        assertEquals("gpt-5", identity.displayName)
        assertEquals("gpt-5", identity.baseId)
    }

    @Test
    fun `override values are trimmed`() {
        val identity = resolveModelIdentity(
            "gpt-5",
            override("displayName" to "  Nice Name  ", "apiModelId" to "  gemini-2  "),
        )
        assertEquals("Nice Name", identity.displayName)
        assertEquals("gemini-2", identity.baseId)
    }

    @Test
    fun `both overrides present resolve independently`() {
        val identity = resolveModelIdentity(
            "local",
            override("displayName" to "Friendly", "apiModelId" to "gpt-5"),
        )
        assertEquals("Friendly", identity.displayName)
        assertEquals("gpt-5", identity.baseId)
    }
}

/** Reorder cover for the models list (twin of the providers-list contract). */
class ModelReorderTest {

    @Test
    fun `moving down places the model at the target index`() {
        assertEquals(
            listOf("b", "c", "a", "d"),
            applyModelMove(listOf("a", "b", "c", "d"), from = 0, to = 2),
        )
    }

    @Test
    fun `moving up places the model at the target index`() {
        assertEquals(
            listOf("a", "d", "b", "c"),
            applyModelMove(listOf("a", "b", "c", "d"), from = 3, to = 1),
        )
    }

    @Test
    fun `stale indices are dropped rather than clamped`() {
        val models = listOf("a", "b", "c")
        assertNull(applyModelMove(models, from = 3, to = 0))
        assertNull(applyModelMove(models, from = 0, to = 3))
        assertNull(applyModelMove(models, from = -1, to = 0))
        assertNull(applyModelMove(models, from = 0, to = -1))
        assertNull(applyModelMove(emptyList(), from = 0, to = 0))
    }

    @Test
    fun `no-op move is rejected`() {
        assertNull(applyModelMove(listOf("a", "b", "c"), from = 2, to = 2))
    }

    @Test
    fun `move keeps every model exactly once`() {
        val models = (1..25).map { "m$it" }
        val moved = applyModelMove(models, from = 4, to = 19)!!
        assertEquals(models.size, moved.size)
        assertEquals(models.toSet(), moved.toSet())
    }
}
