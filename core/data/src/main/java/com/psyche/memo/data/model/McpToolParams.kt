package com.psyche.memo.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `inputSchema.properties` → 工具参数规格（1:1 `mcp_provider.dart` L2337-2360）。
 *
 * 原版在 tools/list 合并时把 schema 摊平成 `params` 一起写进 payload：每个属性一条，
 * `required` 取自 `schema.required`，`type` 是数组时用 `'|'` 连接（Dart
 * `type is List ? join('|') : type?.toString()`），`default` 原样保留。
 * 顺序＝ properties 的插入顺序（两边都是有序 map）。
 */
fun deriveToolParams(schema: JsonObject?): List<McpParamSpec> {
    val properties = schema?.get("properties") as? JsonObject ?: return emptyList()
    val required = (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty()
    return properties.map { (name, value) ->
        val spec = value as? JsonObject
        McpParamSpec(
            name = name,
            required = name in required,
            type = when (val type = spec?.get("type")) {
                is JsonPrimitive -> type.contentOrNull
                is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("|")
                else -> null
            },
            defaultValue = spec?.get("default"),
        )
    }
}
