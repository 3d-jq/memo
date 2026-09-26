package com.psyche.memo.ui

import com.psyche.memo.provider.browser.BrowserTools
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
 *   (the legacy 3-tool variant is not ported).
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

    private fun memoryNames(): List<String> =
        BuiltInToolCatalog.entries(MemoryPromptLang.zh)
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
            memoryNames(),
        )
    }

    @Test
    fun entries_everyEntryHasNonBlankDescription() {
        // Regression for "工具也没有对应描述": pre-fix defaultDescription was
        // always null because of the same stringAt bug.
        val entries = BuiltInToolCatalog.entries(MemoryPromptLang.en)
        assertTrue("catalog should not be empty", entries.isNotEmpty())
        for (entry in entries) {
            assertNotNull("missing description for ${entry.name}", entry.defaultDescription)
            assertTrue("blank description for ${entry.name}", entry.defaultDescription!!.isNotBlank())
        }
    }

    @Test
    fun entries_entryNameMatchesDefinitionName() {
        for (entry in BuiltInToolCatalog.entries(MemoryPromptLang.zh)) {
            assertEquals(
                "entry.name must equal definition function.name",
                entry.name,
                BuiltInToolCatalogEntry.stringAt(entry.defaultDefinition, "function", "name"),
            )
        }
    }

    @Test
    fun entries_searchGroupHasSearchWeb_localGroupNonEmpty() {
        val catalog = BuiltInToolCatalog.entries(MemoryPromptLang.zh)
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

    // —— Browser family (added 2026-09-26: user asked for it in the tool-schema page) ——

    @Test
    fun entries_browserGroup_isTheOfferedListItselfNotACopy() {
        // 用户 2026-09-26「在工具描述里面加一下吧」：这一组的名单**必须等于真正递给模型的那份**
        // （`BrowserTools.catalogDefinitions()` = `definitions()`）。页面自己抄一张的话，改描述是
        // 按名字匹配的，名字对不上时用户改的是一套、模型拿到的是另一套 —— 这条断言就是防那件事。
        val group = BuiltInToolCatalog.entries(MemoryPromptLang.en)
            .filter { it.group == BuiltInToolGroup.BROWSER }
        assertEquals(BrowserTools.catalogDefinitions().map { it.name }, group.map { it.name })
        assertEquals("整族 14 颗都该在", 14, group.size)
        assertTrue(
            "每颗都要有默认描述（缺一颗这一页就只显示名字，编辑页等于白开）",
            group.all { !it.defaultDescription.isNullOrBlank() },
        )
        // 参数也得摊开给编辑页（describeParams 走同一份 schema）：browser_open 两颗。
        assertEquals(
            listOf("url", "new_tab"),
            BuiltInToolCatalog.describeParams(group.first().defaultDefinition).map { it.path },
        )
    }

    @Test
    fun entries_browserToolsStayOutOfTheAssistantGatedLocalNames() {
        // 进这一页只是为了「看得见、能改描述」。它们**绝不进** `LocalToolNames.all` ——
        // 那张表是「助手勾了才执行」的双闸之一，浏览器是设备能力不是人设能力
        //（与 `ToolRulesTest` 里那条同名断言互为兜底：那条盯递交侧，这条盯目录侧）。
        assertTrue(
            BrowserTools.ALL_TOOL_NAMES.none { it in BuiltInToolCatalog.LocalToolNames.all },
        )
    }
}
