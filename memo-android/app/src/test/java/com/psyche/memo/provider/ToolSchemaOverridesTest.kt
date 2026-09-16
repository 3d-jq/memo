package com.psyche.memo.provider

import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.ui.ToolSchemaOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置 → 工具描述」的覆盖要**真的**套到请求里的工具定义上（Dart
 * `tool_schema_overrides.dart` 的 `apply`）。此前 Memo 只写了 `tool_schema_overrides_v1`
 * 却没有任何消费点 —— 改了描述对请求毫无影响。
 */
class ToolSchemaOverridesTest {

    private val builtIn = setOf("search_web", "use_skill", "workspace_shell", "workspace_read_file")

    private fun spec(
        name: String = "search_web",
        description: String = "default description",
        schema: String = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"default query"},
              "questions":{"type":"array","items":{"type":"object","properties":{
                 "id":{"type":"string","description":"default id"}
              }}}
            },"required":["query"]}
        """.trimIndent(),
    ) = LlmToolSpec(name, description, schema)

    private fun paramDescription(spec: LlmToolSpec, path: String): String? {
        var node: JsonObject = Json.parseToJsonElement(spec.inputSchemaJson).jsonObject
        for (segment in path.split('.')) {
            node = if (segment == "items") {
                node["items"]!!.jsonObject
            } else {
                node["properties"]!!.jsonObject[segment]!!.jsonObject
            }
        }
        return node["description"]?.jsonPrimitive?.content
    }

    @Test
    fun `no overrides keeps the very same list`() {
        val specs = listOf(spec())
        assertSame(specs, ToolSchemaOverrides.apply(specs, emptyMap(), builtIn))
    }

    @Test
    fun `a tool without an override keeps its own instance`() {
        val original = spec()
        val result = ToolSchemaOverrides.apply(
            listOf(original),
            mapOf("search_web" to ToolSchemaOverride()),
            builtIn,
        )
        assertSame(original, result.single())
    }

    @Test
    fun `an empty override is ignored`() {
        assertNull(
            ToolSchemaOverrides.applyOne(
                spec(),
                mapOf("search_web" to ToolSchemaOverride(description = "  ")),
                builtIn,
            ),
        )
    }

    @Test
    fun `non built-in tools are never touched`() {
        val mcp = spec(name = "mcp_some_tool")
        val result = ToolSchemaOverrides.apply(
            listOf(mcp),
            mapOf("mcp_some_tool" to ToolSchemaOverride(description = "hacked")),
            builtIn,
        )
        assertSame(mcp, result.single())
    }

    @Test
    fun `the description override wins and the structure stays`() {
        val applied = ToolSchemaOverrides.apply(
            listOf(spec()),
            mapOf("search_web" to ToolSchemaOverride(description = "我的搜索工具")),
            builtIn,
        ).single()
        assertEquals("我的搜索工具", applied.description)
        val root = Json.parseToJsonElement(applied.inputSchemaJson).jsonObject
        // 结构（required / 类型）不动。
        assertEquals(listOf("query"), root["required"]!!.let { req ->
            (req as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        })
        assertEquals("default query", paramDescription(applied, "query"))
    }

    @Test
    fun `parameter paths walk properties and items`() {
        val applied = ToolSchemaOverrides.apply(
            listOf(spec()),
            mapOf(
                "search_web" to ToolSchemaOverride(
                    paramDescriptions = mapOf(
                        "query" to "改成中文说明",
                        "questions.items.id" to "题号",
                    ),
                ),
            ),
            builtIn,
        ).single()
        assertEquals("改成中文说明", paramDescription(applied, "query"))
        assertEquals("题号", paramDescription(applied, "questions.items.id"))
        // 没被覆盖的参数保持默认。
        assertEquals("default description", applied.description)
    }

    @Test
    fun `unknown parameter paths are ignored without breaking the others`() {
        val applied = ToolSchemaOverrides.applyOne(
            spec(),
            mapOf(
                "search_web" to ToolSchemaOverride(
                    paramDescriptions = mapOf("nope.deep" to "x", "query" to "ok"),
                ),
            ),
            builtIn,
        )!!
        assertEquals("ok", paramDescription(applied, "query"))
    }

    @Test
    fun `a broken schema does not crash the request path`() {
        val broken = LlmToolSpec("workspace_shell", "shell", "not json")
        val applied = ToolSchemaOverrides.applyOne(
            broken,
            mapOf("workspace_shell" to ToolSchemaOverride(description = "沙箱命令")),
            builtIn,
        )!!
        assertEquals("沙箱命令", applied.description)
        assertEquals("not json", applied.inputSchemaJson)
    }

    @Test
    fun `workspace and skill tools are overridable too`() {
        val specs = listOf(
            LlmToolSpec("workspace_read_file", "read", """{"type":"object","properties":{"path":{"type":"string","description":"p"}}}"""),
            LlmToolSpec("use_skill", "skill", """{"type":"object","properties":{"name":{"type":"string","description":"n"}}}"""),
        )
        val applied = ToolSchemaOverrides.apply(
            specs,
            mapOf(
                "workspace_read_file" to ToolSchemaOverride(description = "读沙箱文件"),
                "use_skill" to ToolSchemaOverride(
                    paramDescriptions = mapOf("name" to "技能名"),
                ),
            ),
            builtIn,
        )
        assertEquals("读沙箱文件", applied[0].description)
        assertEquals("技能名", paramDescription(applied[1], "name"))
    }

    @Test
    fun `the tool catalog lists workspace and skill tools`() {
        val names = com.psyche.memo.ui.BuiltInToolCatalog
            .allBuiltInNames(com.psyche.memo.ui.MemoryPromptLang.zh)
        assertTrue("工作区四个工具都要在目录里", names.containsAll(com.psyche.memo.provider.workspace.WorkspaceTools.ALL_TOOL_NAMES))
        assertTrue("use_skill 也要在目录里", names.contains("use_skill"))
    }

    @Test
    fun `catalog entries carry describable parameters`() {
        val entries = com.psyche.memo.ui.BuiltInToolCatalog
            .entries(com.psyche.memo.ui.MemoryPromptLang.zh)
        val readFile = entries.first { it.name == "workspace_read_file" }
        assertEquals(com.psyche.memo.ui.BuiltInToolGroup.WORKSPACE, readFile.group)
        assertTrue(readFile.defaultDescription!!.isNotBlank())
        assertTrue(
            "参数要走 describeParams 能列出来（编辑器靠它渲染每一行）",
            com.psyche.memo.ui.BuiltInToolCatalog.describeParams(readFile.defaultDefinition).isNotEmpty(),
        )
        val useSkill = entries.first { it.name == "use_skill" }
        assertEquals(com.psyche.memo.ui.BuiltInToolGroup.SKILL, useSkill.group)
    }
}
