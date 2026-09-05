package com.psyche.memo.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for ToolSchemaCatalog — the user-visible symptom chain:
 *
 * 1. stringAt() coerced the FINAL path segment to JsonObject before reading,
 *    so `stringAt(def, "function", "name")` always returned null (the value
 *    is a JsonPrimitive). Every memory tool was dropped by `?: continue` in
 *    entries() -> the MEMORY group vanished from the tool-schema settings
 *    page (report: "工具描述里面没有记忆工具").
 * 2. The same bug made BuiltInToolCatalogEntry.defaultDescription always
 *    null (report: "工具也没有对应描述").
 *
 * Expected catalogs mirror the Flutter originals byte-for-byte:
 * - lib/core/services/tools/built_in_tool_catalog.dart (entries/_toolName)
 * - lib/core/services/memory/memory_tools.dart L98-108 (catalog, 7 tools)
 *   and L114-... (legacy, 3 tools).
 */
class ToolSchemaCatalogTest {

    private fun sampleDef(): JsonObject = Json.parseToJsonElement(
        """
        {
          "type": "function",
          "function": {
            "name": "memory_read",
            "description": "Read the user's long-term memory.",
            "parameters": {"type": "object", "properties": {"type": {"type": "string"}}}
          }
        }
        """.trimIndent(),
    ).jsonObject()

    private fun kotlinx.serialization.json.JsonElement.jsonObject() =
        this as JsonObject

    // —— stringAt path walking ——

    @Test
    fun stringAt_readsFinalPrimitiveThroughNestedObjects() {
        val def = sampleDef()
        // Regression: pre-fix this coerced "name" to JsonObject and returned null.
        assertEquals("memory_read", BuiltInToolCatalogEntry.stringAt(def, "function", "name"))
        assertEquals(
            "Read the user's long-term memory.",
            BuiltInToolCatalogEntry.stringAt(def, "function", "description"),
        )
    }

    @Test
    fun stringAt_singleSegmentPath() {
        val def = sampleDef()
        assertEquals("function", BuiltInToolCatalogEntry.stringAt(def, "type"))
    }

    @Test
    fun stringAt_deepNestedPath() {
        val def = sampleDef()
        assertEquals(
            "string",
            BuiltInToolCatalogEntry.stringAt(def, "function", "parameters", "properties", "type", "type"),
        )
    }

    @Test
    fun stringAt_missingIntermediateReturnsNull() {
        val def = sampleDef()
        assertNull(BuiltInToolCatalogEntry.stringAt(def, "nope", "name"))
    }

    @Test
    fun stringAt_missingFinalKeyReturnsNull() {
        val def = sampleDef()
        assertNull(BuiltInToolCatalogEntry.stringAt(def, "function", "missing"))
    }

    @Test
    fun stringAt_nonStringFinalValueReturnsNull() {
        val def = sampleDef()
        // "parameters" is a JsonObject, not a string primitive.
        assertNull(BuiltInToolCatalogEntry.stringAt(def, "function", "parameters"))
    }

    // —— BuiltInToolCatalogEntry.defaultDescription ——

    @Test
    fun entry_defaultDescription_readsFromDefinition() {
        val def = sampleDef()
        val entry = BuiltInToolCatalogEntry("memory_read", def, BuiltInToolGroup.MEMORY)
        assertEquals("Read the user's long-term memory.", entry.defaultDescription)
    }

    // —— BuiltInToolCatalog.entries vs Flutter original ——

    private fun memoryNames(legacy: Boolean): List<String> =
        BuiltInToolCatalog.entries(MemoryPromptLang.zh, legacy)
            .filter { it.group == BuiltInToolGroup.MEMORY }
            .map { it.name }

    @Test
    fun entries_catalogMode_listsAllSevenMemoryTools() {
        // memory_tools.dart L98-108: read, search_profile, update, edit,
        // delete, update_user_profile, chat_search.
        assertEquals(
            listOf(
                "memory_read",
                "memory_search_profile",
                "memory_update",
                "memory_edit",
                "memory_delete",
                "update_user_profile",
                "chat_search",
            ),
            memoryNames(legacy = false),
        )
    }

    @Test
    fun entries_legacyMode_listsAllThreeLegacyMemoryTools() {
        // memory_tools.dart L114-...: create/edit/delete_memory.
        assertEquals(
            listOf("create_memory", "edit_memory", "delete_memory"),
            memoryNames(legacy = true),
        )
    }

    @Test
    fun entries_everyEntryHasNonBlankDescription() {
        // Regression for "工具也没有对应描述": pre-fix defaultDescription was
        // always null because of the same stringAt bug.
        for (legacy in listOf(false, true)) {
            val entries = BuiltInToolCatalog.entries(MemoryPromptLang.en, legacy)
            assertTrue("catalog should not be empty (legacy=$legacy)", entries.isNotEmpty())
            for (entry in entries) {
                assertNotNull(
                    "missing description for ${entry.name} (legacy=$legacy)",
                    entry.defaultDescription,
                )
                assertTrue(
                    "blank description for ${entry.name} (legacy=$legacy)",
                    entry.defaultDescription!!.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun entries_entryNameMatchesDefinitionName() {
        for (legacy in listOf(false, true)) {
            for (entry in BuiltInToolCatalog.entries(MemoryPromptLang.zh, legacy)) {
                assertEquals(
                    "entry.name must equal definition function.name",
                    entry.name,
                    BuiltInToolCatalogEntry.stringAt(entry.defaultDefinition, "function", "name"),
                )
            }
        }
    }

    @Test
    fun entries_searchGroupHasSearchWeb_localGroupNonEmpty() {
        val catalog = BuiltInToolCatalog.entries(MemoryPromptLang.zh, legacyMemoryMode = false)
        assertEquals(
            listOf("search_web"),
            catalog.filter { it.group == BuiltInToolGroup.SEARCH }.map { it.name },
        )
        // Android keeps screen_time / calendar_* local tools (iOS-only ones filtered).
        assertTrue(
            "LOCAL group should have Android-available tools",
            catalog.any { it.group == BuiltInToolGroup.LOCAL },
        )
    }
}
