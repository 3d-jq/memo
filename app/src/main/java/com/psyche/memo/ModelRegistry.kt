package com.psyche.memo

/**
 * Kotlin port of lib/core/providers/model_provider.dart ModelRegistry —
 * infers model traits from the id via the same regexes (vision / tool /
 * reasoning / embedding), used by the model select sheet tag row.
 */
object ModelRegistry {
    data class Traits(
        val embedding: Boolean,
        val visionInput: Boolean,
        val tool: Boolean,
        val reasoning: Boolean,
    )

    private val VISION = Regex(
        "(gpt-4o|gpt-4\\.1|gpt-5(?!-chat)|o\\d|gemini|claude|kimi-k2([-.])(?:5|6|7)|" +
            "kimi-k3(?:$|[/_:@.-])|muse-spark-1\\.1(?:$|[/_:@.-])|" +
            "doubao.+(?:1([-.])(?:6|8)|seed-2|seed-evolving)|grok-4|step-3|intern-s1|" +
            "minimax-m3(?:$|[/_:@])|mimo-v2(?:-omni(?:$|[/_:@])|\\.5(?:$|[/_:@]))|" +
            "sensenova-6\\.7-flash-lite|deepseek.+vision)",
        RegexOption.IGNORE_CASE,
    )
    private val TOOL = Regex(
        "(gpt-4o|gpt-4\\.1|gpt-oss|gpt-5(?!-chat)|o\\d|gemini|claude|qwen-?3|" +
            "doubao.+(?:1([-.])(?:6|8)|seed-2|seed-evolving)|grok-4|kimi-k2|" +
            "kimi-k3(?:$|[/_:@.-])|muse-spark-1\\.1(?:$|[/_:@.-])|step-3|intern-s1|" +
            "glm-4([-.])(?:5|6|7)|glm-5|minimax-(?:m2|m3)|" +
            "deepseek-(?:r1|v3|chat|v3\\.1|v3\\.2|v4)|deepseek-reasoner|mimo-v2|" +
            "sensenova-6\\.7-flash-lite|laguna)",
        RegexOption.IGNORE_CASE,
    )
    private val REASONING = Regex(
        "(gpt-oss|gpt-5(?!-chat)|o\\d|gemini-(?:2\\.5|3).*|gemini-(?:flash-latest|pro-latest)|" +
            "gemini-3-pro-image-preview|gemma[-_]?4|claude|qwen-?3|" +
            "doubao.+(?:1([-.])(?:6|8)|seed-2|seed-evolving)|grok-4|kimi-k2|" +
            "kimi-k3(?:$|[/_:@.-])|muse-spark-1\\.1(?:$|[/_:@.-])|step-3|intern-s1|" +
            "glm-4([-.])(?:5|6|7)|glm-5|minimax-(?:m2|m3)|" +
            "deepseek-(?:r1|v3\\.1|v3\\.2|v4)|deepseek-reasoner|mimo-v2|laguna)",
        RegexOption.IGNORE_CASE,
    )
    private val EMBED = Regex("(^|[-_/])embed(?:dings?)?([-.]|$)", RegexOption.IGNORE_CASE)
    private val QWEN_35 = Regex("qwen-?3([-.])5", RegexOption.IGNORE_CASE)
    private val QWEN_37_PLUS_FLASH = Regex("qwen-?3([-.])7-(?:plus|flash)", RegexOption.IGNORE_CASE)
    private val QWEN_38_MAX = Regex("qwen-?3([-.])8-max", RegexOption.IGNORE_CASE)
    private val QWEN_37_MAX_SNAPSHOT = Regex("qwen-?3([-.])7-max-(\\d{4}-\\d{2}-\\d{2})", RegexOption.IGNORE_CASE)
    private val GEMINI_35_FLASH = Regex("(^|[/:_-])gemini-3\\.5-flash([._:@/-]|$)", RegexOption.IGNORE_CASE)

    fun isLikelyEmbeddingId(rawId: String): Boolean =
        rawId.lowercase().let { it.contains("embedding") || EMBED.containsMatchIn(it) }

    private fun isQwenVisionModel(id: String): Boolean {
        val lower = id.lowercase()
        if (QWEN_35.containsMatchIn(lower)) return true
        if (QWEN_37_PLUS_FLASH.containsMatchIn(lower)) return true
        if (QWEN_38_MAX.containsMatchIn(lower)) return true
        val snap = QWEN_37_MAX_SNAPSHOT.find(lower) ?: return false
        val date = runCatching {
            java.time.LocalDate.parse(snap.groupValues[2])
        }.getOrNull() ?: return false
        return !date.isBefore(java.time.LocalDate.of(2026, 6, 8))
    }

    /** ModelRegistry.infer — trait projection (chat models only). */
    fun infer(modelId: String): Traits {
        val id = modelId.lowercase()
        if (isLikelyEmbeddingId(id)) {
            return Traits(embedding = true, visionInput = false, tool = false, reasoning = false)
        }
        var visionInput = VISION.containsMatchIn(id) || isQwenVisionModel(id)
        var tool = TOOL.containsMatchIn(id)
        var reasoning = REASONING.containsMatchIn(id)
        if (GEMINI_35_FLASH.containsMatchIn(id)) {
            visionInput = true
            tool = true
            reasoning = true
        }
        return Traits(embedding = false, visionInput = visionInput, tool = tool, reasoning = reasoning)
    }

    /**
     * Full ModelInfo projection (model_provider.dart ModelRegistry.infer):
     * type/input/output/abilities with the image-model and gemini-3.5-flash
     * branches the tag-row projection skips. Mirrors ModelInfo defaults
     * (input [text], output [text], no abilities).
     */
    data class FullTraits(
        val type: String, // "chat" | "embedding"
        val input: Set<String>, // "text" | "image"
        val output: Set<String>,
        val abilities: Set<String>, // "tool" | "reasoning"
    )

    fun inferFull(modelId: String): FullTraits {
        val id = modelId.lowercase()
        val input = mutableSetOf("text")
        val output = mutableSetOf("text")
        val abilities = mutableSetOf<String>()
        if (isLikelyEmbeddingId(id)) {
            return FullTraits("embedding", setOf("text"), setOf("text"), emptySet())
        }
        if (id.contains("image")) {
            input.add("image")
            output.add("image")
            return FullTraits("chat", input, output, emptySet())
        }
        if (GEMINI_35_FLASH.containsMatchIn(id)) {
            input.add("image")
            return FullTraits("chat", input, setOf("text"), setOf("tool", "reasoning"))
        }
        if (VISION.containsMatchIn(id) || isQwenVisionModel(id)) input.add("image")
        if (TOOL.containsMatchIn(id)) abilities.add("tool")
        if (REASONING.containsMatchIn(id)) abilities.add("reasoning")
        return FullTraits("chat", input, output, abilities)
    }
}
