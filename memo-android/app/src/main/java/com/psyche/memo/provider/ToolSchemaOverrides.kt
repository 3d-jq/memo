package com.psyche.memo.provider

import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.ui.ToolSchemaOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 把用户在「设置 → 工具描述」里改的描述**真的套到请求里的工具定义上** ——
 * 1:1 移植 `lib/core/services/tools/tool_schema_overrides.dart` 的 `apply`。
 *
 * 为什么单独一份：Dart 侧是在 `tool_handler_service.dart:284-286` 组装完全部工具
 * 定义之后统一 `apply(toolDefs, overrides)`；Memo 此前只有写覆盖的设置页
 * （`tool_schema_overrides_v1`），**没有任何消费点** —— 改了描述对请求毫无影响
 * （用户 2026-09-16「设置里面的工具描述是不是没有跟新」顺带查出来的）。
 *
 * 三条与上游一致的规矩：
 *  1. **只改描述**：`function` 的描述（[LlmToolSpec.description]）与既有的
 *     `properties.<path>.description`，结构（参数、required、类型）一律不动；
 *  2. **只认内置工具名**（[builtInNames]）—— MCP 工具是动态的，名字不在里面，
 *     保持原样、连对象身份都不换；
 *  3. 未知的工具名 / 未知的参数路径**静默忽略**（旧版本存的覆盖遇到改名/删参数时
 *     不能崩）。
 *
 * 与 Dart 的一处实现差异：那边 `jsonDecode(jsonEncode(...))` 拿到的是可变 Map，
 * 直接原地改；kotlinx 的 `JsonObject` 不可变，所以这里走「重建路径上的每一层」，
 * 结果等价（未触碰的分支沿用原对象引用）。
 */
object ToolSchemaOverrides {

    fun apply(
        definitions: List<LlmToolSpec>,
        overrides: Map<String, ToolSchemaOverride>,
        builtInNames: Set<String>,
    ): List<LlmToolSpec> {
        if (overrides.isEmpty() || definitions.isEmpty()) return definitions
        var changed = false
        val out = definitions.map { spec ->
            val applied = applyOne(spec, overrides, builtInNames)
            if (applied == null) {
                spec
            } else {
                changed = true
                applied
            }
        }
        return if (changed) out else definitions
    }

    /** 单个工具：没有覆盖 / 名字不是内置 / 覆盖是空的 → null（调用方保持原对象）。 */
    internal fun applyOne(
        spec: LlmToolSpec,
        overrides: Map<String, ToolSchemaOverride>,
        builtInNames: Set<String>,
    ): LlmToolSpec? {
        if (spec.name !in builtInNames) return null
        val override = overrides[spec.name] ?: return null
        val description = override.description?.trim()?.takeIf { it.isNotEmpty() }
        val params = override.paramDescriptions.filterValues { it.trim().isNotEmpty() }
        if (description == null && params.isEmpty()) return null

        val original = runCatching { Json.parseToJsonElement(spec.inputSchemaJson) as? JsonObject }
            .getOrNull()
        var schema: JsonObject? = original
        var schemaChanged = false
        if (original != null) {
            for ((path, value) in params) {
                val current = schema ?: break
                val updated = withParamDescription(current, path, value.trim())
                if (updated != null) {
                    schema = updated
                    schemaChanged = true
                }
            }
        }

        return LlmToolSpec(
            name = spec.name,
            description = description ?: spec.description,
            inputSchemaJson = if (schemaChanged && schema != null) schema.toString() else spec.inputSchemaJson,
        )
    }

    /**
     * 返回「把 `path` 指向的 description 换掉」的新树；路径任一段不存在 → null。
     *
     * 段名语义与上游 `_setParamDescription` 一致：`items` 走数组元素 schema，
     * 其余段名走 `properties`。例：`query`、`questions.items.id`。
     */
    internal fun withParamDescription(root: JsonObject, path: String, description: String): JsonObject? {
        val segments = path.split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size != path.split('.').size) return null
        return applyPath(root, segments, 0, description)
    }

    private fun applyPath(
        node: JsonObject,
        segments: List<String>,
        index: Int,
        value: String,
    ): JsonObject? {
        if (index == segments.size) {
            return JsonObject(node + ("description" to JsonPrimitive(value)))
        }
        val segment = segments[index]
        return if (segment == "items") {
            val items = node["items"] as? JsonObject ?: return null
            val updated = applyPath(items, segments, index + 1, value) ?: return null
            JsonObject(node + ("items" to updated))
        } else {
            val props = node["properties"] as? JsonObject ?: return null
            val child = props[segment] as? JsonObject ?: return null
            val updated = applyPath(child, segments, index + 1, value) ?: return null
            JsonObject(node + ("properties" to JsonObject(props + (segment to updated))))
        }
    }
}
