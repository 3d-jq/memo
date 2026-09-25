package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * deriveToolParams — 1:1 `mcp_provider.dart` L2337-2360（tools/list 合并时把
 * inputSchema 摊平成 params 一起写进 payload）。工具卡上的参数 chips 与
 * 备份里的 MCP 工具段落都靠它。
 */
class McpToolParamsTest {

    private fun schema(required: List<String>, vararg props: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject {
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }

    @Test
    fun `parameters come from properties with required flags`() {
        val params = deriveToolParams(
            schema(
                required = listOf("query"),
                "query" to buildJsonObject { put("type", "string") },
                "limit" to buildJsonObject { put("type", "integer") },
            ),
        )
        assertEquals(listOf("query", "limit"), params.map { it.name })
        assertTrue(params[0].required)
        assertEquals("string", params[0].type)
        assertEquals(false, params[1].required)
        assertEquals("integer", params[1].type)
    }

    @Test
    fun `type array is joined with a pipe`() {
        // Dart: `type is List ? type.map(toString).join('|') : type?.toString()`.
        val params = deriveToolParams(
            schema(
                required = emptyList(),
                "value" to buildJsonObject {
                    put("type", buildJsonArray { add(JsonPrimitive("string")); add(JsonPrimitive("null")) })
                },
            ),
        )
        assertEquals("string|null", params.single().type)
    }

    @Test
    fun `default values survive verbatim`() {
        val params = deriveToolParams(
            schema(
                required = emptyList(),
                "count" to buildJsonObject {
                    put("type", "integer")
                    put("default", 10)
                },
            ),
        )
        assertEquals(JsonPrimitive(10), params.single().defaultValue)
    }

    @Test
    fun `property without a default keeps it null`() {
        val params = deriveToolParams(
            schema(required = emptyList(), "q" to buildJsonObject { put("type", "string") }),
        )
        assertNull(params.single().defaultValue)
    }

    @Test
    fun `missing or empty schema yields no params`() {
        assertTrue(deriveToolParams(null).isEmpty())
        assertTrue(deriveToolParams(buildJsonObject { }).isEmpty())
        assertTrue(deriveToolParams(buildJsonObject { putJsonObject("properties") { } }).isEmpty())
    }

    @Test
    fun `required list is optional and non-object properties are tolerated`() {
        val params = deriveToolParams(
            buildJsonObject {
                putJsonObject("properties") {
                    put("flag", JsonPrimitive(true))
                    put("name", buildJsonObject { put("type", "string") })
                }
            },
        )
        assertEquals(listOf("flag", "name"), params.map { it.name })
        assertEquals(false, params[0].required)
        assertNull(params[0].type)
    }

    @Test
    fun `params round-trip through the stored payload`() {
        // 写进 mcp_server_rows.payload 的 McpToolConfig 必须带上 params（原版也写）。
        val tool = McpToolConfig(
            name = "aihot_get_latest",
            description = "latest items",
            params = listOf(McpParamSpec(name = "limit", required = false, type = "integer")),
            schema = buildJsonObject { put("type", "object") },
        )
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(McpToolConfig.serializer(), tool)
        assertTrue(text.contains("\"params\""))
        assertEquals(tool, json.decodeFromString(McpToolConfig.serializer(), text))
    }
}
