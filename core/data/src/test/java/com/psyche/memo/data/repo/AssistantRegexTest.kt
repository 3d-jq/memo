package com.psyche.memo.data.repo

import com.psyche.memo.data.model.AssistantRegex
import com.psyche.memo.data.model.AssistantRegexScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port coverage of assistant_regex.dart parsing + TagProvider pure helpers. */
class AssistantRegexTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun `regex scopes round trip with the upstream wire names`() {
        val rule = AssistantRegex(
            id = "r1",
            name = "n",
            pattern = "p",
            replacement = "r",
            scopes = listOf("user", "assistant"),
            visualOnly = false,
            replaceOnly = true,
            enabled = false,
        )
        val text = json.encodeToString(AssistantRegex.serializer(), rule)
        assertEquals(
            """{"id":"r1","name":"n","pattern":"p","replacement":"r","scopes":["user","assistant"],"visualOnly":false,"replaceOnly":true,"enabled":false}""",
            text,
        )
        val decoded = json.decodeFromString(AssistantRegex.serializer(), text)
        assertEquals(listOf("user", "assistant"), decoded.scopes)
    }

    @Test
    fun `scope parsing tolerates unknown and blank names`() {
        assertEquals(AssistantRegexScope.USER, AssistantRegexScope.fromName("user"))
        assertEquals(AssistantRegexScope.ASSISTANT, AssistantRegexScope.fromName("ASSISTANT"))
        assertNull(AssistantRegexScope.fromName(""))
        assertNull(AssistantRegexScope.fromName("system"))
        assertNull(AssistantRegexScope.fromName(null))
    }

    @Test
    fun `json round trip normalizes scopes via scopesOf`() {
        val rule = AssistantRegex(
            id = "r2",
            name = "n",
            pattern = "p",
            scopes = listOf("user", "bogus", "assistant"),
        )
        assertEquals(listOf(AssistantRegexScope.USER, AssistantRegexScope.ASSISTANT), rule.scopesOf())
    }

    @Test
    fun `scopes default to empty when absent`() {
        val decoded = json.decodeFromString(AssistantRegex.serializer(), """{"id":"r3"}""")
        assertEquals(emptyList<String>(), decoded.scopes)
        assertEquals(true, decoded.enabled)
    }
}
