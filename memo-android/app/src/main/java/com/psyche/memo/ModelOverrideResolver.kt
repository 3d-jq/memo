package com.psyche.memo

import com.psyche.memo.data.model.ProviderConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Kotlin port of `lib/core/services/model_override_resolver.dart` +
 * `chat_api_helpers.dart:137-153 effectiveModelInfo`.
 *
 * 模型能力（输入模态 / 工具 / 推理）在运行时必须取「名称推断 **叠加** 用户在
 * 模型编辑页写的 override」，而不是只看名称推断 —— 否则：
 *  · 第三方模型（名字推断不出任何能力）永远拿不到思考；
 *  · 在编辑页把输入改成 image 之后，模型选择页的标签/图片入口都不变。
 */
object ModelOverrideResolver {

    /** chat_api_helpers.dart:44-53 `apiModelId(cfg, modelId)`. */
    fun apiModelId(ov: JsonObject?, modelId: String): String =
        ((ov?.get("apiModelId") ?: ov?.get("api_model_id")) as? JsonPrimitive)
            ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: modelId

    /** 解析后的有效能力（type/displayName/input/output/abilities）。 */
    data class EffectiveModel(
        val type: String,
        val displayName: String,
        val input: Set<String>,
        val output: Set<String>,
        val abilities: Set<String>,
    ) {
        val embedding: Boolean get() = type == "embedding"
        val visionInput: Boolean get() = "image" in input
        val reasoning: Boolean get() = "reasoning" in abilities
        val tool: Boolean get() = "tool" in abilities
    }

    /** `_norm` — null/空串/大小写归一。 */
    private fun norm(v: JsonElement?): String =
        ((v as? JsonPrimitive)?.contentOrNull ?: "").trim().lowercase()

    /** `parseModelTypeOverride`. */
    private fun parseType(raw: JsonElement?): String? = when (norm(raw)) {
        "embedding", "embeddings" -> "embedding"
        "chat" -> "chat"
        else -> null
    }

    /** `parseModalities` — 非 List / 空 List → null（= 无覆盖）。 */
    fun parseModalities(raw: JsonElement?): Set<String>? {
        val arr = raw as? JsonArray ?: return null
        if (arr.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        for (e in arr) {
            when (val s = norm(e)) {
                "text", "image" -> out.add(s)
            }
        }
        return out.ifEmpty { null }
    }

    /** `parseAbilities` — 同上。 */
    fun parseAbilities(raw: JsonElement?): Set<String>? {
        val arr = raw as? JsonArray ?: return null
        if (arr.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        for (e in arr) {
            when (val s = norm(e)) {
                "tool", "reasoning" -> out.add(s)
            }
        }
        return out.ifEmpty { null }
    }

    /** `_parseName`. */
    private fun parseName(ov: JsonObject): String? =
        (ov["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** `_nonEmptyMods` — 空集合回落 text。 */
    private fun nonEmptyMods(mods: Set<String>): Set<String> =
        if (mods.isEmpty()) setOf("text") else mods

    /** `applyModelOverride(base, ov, applyDisplayName)`. */
    fun apply(
        base: ModelRegistry.FullTraits,
        ov: JsonObject?,
        applyDisplayName: Boolean,
        displayNameFallback: String,
    ): EffectiveModel {
        val typeOv = parseType(ov?.get("type") ?: ov?.get("t"))
        val effectiveType = typeOv ?: base.type
        val nameOv = if (applyDisplayName && ov != null) parseName(ov) else null
        val displayName = nameOv ?: displayNameFallback

        val inputOv = parseModalities(ov?.get("input"))
        val outputOv = if (effectiveType == "embedding") null else parseModalities(ov?.get("output"))
        val abilitiesOv = if (effectiveType == "embedding") null else parseAbilities(ov?.get("abilities"))

        val hasOverrides = (typeOv != null && typeOv != base.type) ||
            (nameOv != null && nameOv != displayNameFallback) ||
            inputOv != null || outputOv != null || abilitiesOv != null
        if (!hasOverrides) {
            return EffectiveModel(base.type, displayName, base.input, base.output, base.abilities)
        }

        val inMods = nonEmptyMods(inputOv ?: base.input)
        if (effectiveType == "embedding") {
            return EffectiveModel("embedding", displayName, inMods, setOf("text"), emptySet())
        }
        return EffectiveModel(
            type = effectiveType,
            displayName = displayName,
            input = inMods,
            output = nonEmptyMods(outputOv ?: base.output),
            abilities = abilitiesOv ?: base.abilities,
        )
    }

    /**
     * `effectiveModelInfo(cfg, modelId)` 的等价入口：先按 override 的
     * apiModelId 取名称推断基线，再把该模型的 override 叠上去。
     */
    fun forModel(
        cfg: ProviderConfig?,
        modelId: String,
        applyDisplayName: Boolean = false,
        rawOverride: JsonObject? = null,
    ): EffectiveModel {
        val ov = rawOverride
            ?: (cfg?.modelOverrides?.get(modelId) as? JsonObject)
        val upstreamId = apiModelId(ov, modelId)
        val base = ModelRegistry.inferFull(upstreamId.ifEmpty { modelId.ifEmpty { "custom" } })
        return apply(base, ov, applyDisplayName, upstreamId.ifEmpty { modelId })
    }
}
