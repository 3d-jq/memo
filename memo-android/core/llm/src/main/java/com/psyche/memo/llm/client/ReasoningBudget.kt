package com.psyche.memo.llm.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reasoning-budget → provider payload mappings — port of chat_api_helpers.dart
 * (`isOff` / `effortForBudget` / `claudeThinkingConfig`) and google_common.dart
 * `_googleThinkingConfig`.
 *
 * Budget convention (settings_provider.dart thinking_budget_v1):
 *   null / -1 = auto, 0 = off, >0 = explicit token budget.
 */
object ReasoningBudget {

    const val AUTO = -1
    const val OFF = 0

    /** isOff — null and auto are not "off"; everything below 1024 is. */
    fun isOff(budget: Int?): Boolean = budget != null && budget != AUTO && budget < 1024

    /** effortForBudget — off/auto/low/medium/high. */
    fun effortForBudget(budget: Int?): String = when {
        budget == null || budget == AUTO -> "auto"
        isOff(budget) -> "off"
        budget <= 2000 -> "low"
        budget <= 20000 -> "medium"
        else -> "high"
    }

    /** _claudeEffortForBudget — adds the xhigh (64k) and max (128k) tiers. */
    fun claudeEffortForBudget(budget: Int?): String = when {
        budget == null || budget == AUTO -> "auto"
        isOff(budget) -> "off"
        budget <= 2000 -> "low"
        budget <= 20000 -> "medium"
        budget <= 32000 -> "high"
        budget <= 64000 -> "xhigh"
        else -> "max"
    }

    /** openAIEffortForBudget — high escalates to xhigh/max by budget + model,
     *  then normalizes through the model's supported tier table
     *  (openai_vendor_compat.dart:285 + openAINormalizeReasoningEffort).
     *  Claude-family ids are not in the OpenAI compat table (upstream serves
     *  them through _claudeEffortForBudget instead), so they take the Claude
     *  ladder directly instead of being normalized down to high. */
    fun openAiEffortForBudget(budget: Int?, modelId: String): String {
        val lower = modelId.trim().lowercase()
        if (lower.contains("claude-")) return claudeEffortForBudget(budget)
        val base = effortForBudget(budget)
        var requested = base
        if (base == "high" && budget != null) {
            requested = when {
                budget >= 128000 && supportsMaxReasoning(modelId) -> "max"
                budget >= 64000 -> "xhigh"
                else -> "high"
            }
        }
        return normalizeOpenAiEffort(requested, modelId)
    }

    /**
     * openAINormalizeReasoningEffort — clamps the requested effort to what the
     * model actually supports (unlisted models keep low/medium/high as-is and
     * fall xhigh/max back to high).
     */
    private fun normalizeOpenAiEffort(effort: String, modelId: String): String {
        val normalized = effort.trim().lowercase()
        if (normalized.isEmpty()) return effort
        if (normalized == "auto") return "auto"

        val support = openAiReasoningSupport(modelId)
        if (normalized == "off") {
            return support?.offFallback ?: "off"
        }
        if ((normalized == "xhigh" || normalized == "max") && support == null) return "high"
        if (support == null) return normalized
        if (normalized in support.supportedEfforts) return normalized

        val preference = when (normalized) {
            "none" -> listOf("none", "low", "medium", "high", "xhigh", "max")
            "low" -> listOf("low", "medium", "high", "xhigh", "max")
            "medium" -> listOf("medium", "high", "xhigh", "max", "low")
            "high" -> listOf("high", "xhigh", "max", "medium", "low")
            "xhigh" -> listOf("xhigh", "max", "high", "medium", "low")
            else -> listOf("max", "xhigh", "high", "medium", "low", "none")
        }
        return preference.firstOrNull { it in support.supportedEfforts }
            ?: support.supportedEfforts.last()
    }

    /** claudeThinkingConfig — disabled / enabled+budget_tokens / adaptive. */
    fun claudeThinkingConfig(modelId: String, budget: Int?): JsonObject? {
        val lower = modelId.trim().lowercase()
        val alwaysOn = lower.contains("claude-fable") || lower.contains("claude-mythos")
        if (alwaysOn) {
            if (!isReasoningEnabled(budget)) return null
            return buildJsonObject {
                put("type", "adaptive")
                put("display", "summarized")
            }
        }
        if (!isReasoningEnabled(budget)) {
            return buildJsonObject { put("type", "disabled") }
        }
        if (supportsClaudeAdaptiveThinking(lower)) {
            return buildJsonObject {
                put("type", "adaptive")
                put("display", "summarized")
            }
        }
        if (budget != null && budget > 0) {
            return buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", budget)
            }
        }
        return buildJsonObject { put("type", "disabled") }
    }

    /** isClaudeReasoningEnabled — only budget 0 disables reasoning. */
    fun isReasoningEnabled(budget: Int?): Boolean = budget != 0

    /** _supportsClaudeAdaptiveThinking — Claude 5 / fable / mythos families. */
    fun supportsClaudeAdaptiveThinking(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        if (!lower.contains("claude-")) return false
        if (lower.contains("fable") || lower.contains("mythos")) return true
        if (CLAUDE_5.containsMatchIn(lower)) return true
        val m = CLAUDE_MAJOR_MINOR.find(lower) ?: return false
        val major = m.groupValues[2].toIntOrNull() ?: return false
        val minor = m.groupValues[3].toIntOrNull() ?: return false
        return major >= 5 || (major == 4 && minor >= 7)
    }

    /** supportsMaxReasoning — models that accept the 'max' effort tier.
     *  settings_provider.dart:603 — Claude falls to _claudeSupportsMaxReasoning,
     *  OpenAI-compatible models go through the openai_model_compat.dart table. */
    fun supportsMaxReasoning(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        if (lower.contains("claude-")) return claudeSupportsMaxReasoning(lower)
        return openAiSupportsMaxReasoning(lower)
    }

    /** supportsXhighReasoning — models that accept the 'xhigh' effort tier. */
    fun supportsXhighReasoning(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        if (lower.contains("claude-")) return claudeSupportsXhighReasoning(lower)
        return openAiSupportsXhighReasoning(lower)
    }

    /** _claudeSupportsXhighReasoning (settings_provider.dart:634). */
    private fun claudeSupportsXhighReasoning(lower: String): Boolean {
        if (!lower.contains("claude-")) return false
        if (lower.contains("fable") || lower.contains("mythos")) return true
        if (CLAUDE_5.containsMatchIn(lower)) return true
        val m = CLAUDE_MAJOR_MINOR.find(lower) ?: run {
            return lower.contains("claude-opus-4-7") || lower.contains("claude-opus-4.7") ||
                lower.contains("claude-opus-4-8") || lower.contains("claude-opus-4.8")
        }
        val family = m.groupValues[1].lowercase()
        val major = m.groupValues[2].toIntOrNull() ?: return false
        val minor = m.groupValues[3].toIntOrNull() ?: return false
        return family == "opus" && (major > 4 || (major == 4 && minor >= 7))
    }

    /** _claudeSupportsMaxReasoning (settings_provider.dart:664). */
    private fun claudeSupportsMaxReasoning(lower: String): Boolean {
        if (!lower.contains("claude-")) return false
        if (lower.contains("fable") || lower.contains("mythos")) return true
        if (CLAUDE_5.containsMatchIn(lower)) return true
        val m = CLAUDE_MAJOR_MINOR.find(lower) ?: run {
            return lower.contains("claude-opus-4-7") || lower.contains("claude-opus-4.7") ||
                lower.contains("claude-opus-4-8") || lower.contains("claude-opus-4.8") ||
                lower.contains("claude-opus-4-6") || lower.contains("claude-opus-4.6") ||
                lower.contains("claude-sonnet-4-6") || lower.contains("claude-sonnet-4.6")
        }
        val family = m.groupValues[1].lowercase()
        val major = m.groupValues[2].toIntOrNull() ?: return false
        val minor = m.groupValues[3].toIntOrNull() ?: return false
        if (family == "opus" && (major > 4 || (major == 4 && minor >= 7))) return true
        return major == 4 && minor == 6
    }

    /**
     * openai_model_compat.dart `openAIReasoningSupport` — the per-model effort
     * tier table for OpenAI-compatible vendors (GPT-5.x/6, GLM, DeepSeek,
     * Kimi, Grok, MiMo, Muse). Null = unlisted model: xhigh/max unsupported.
     */
    private fun openAiReasoningSupport(modelId: String): OpenAiReasoningSupport? {
        val n = modelId.trim().lowercase()
        if (n.contains("deepseek")) return OpenAiReasoningSupport.DEEPSEEK
        if (matchesModel(n, "(^|[/_:@])mimo-v2(?:$|[-.])")) return OpenAiReasoningSupport.MIMO
        if (matchesModel(n, "(^|[/_:@])kimi-k3(?:$|[-.])")) return OpenAiReasoningSupport.KIMI_K3
        if (matchesModel(n, "(^|[/_:@])grok-4\\.6(?:$|[-.])")) return OpenAiReasoningSupport.GROK_46
        if (matchesModel(n, "(^|[/_:@])grok-4\\.5(?:$|[-.])")) return OpenAiReasoningSupport.GROK_45
        if (matchesModel(n, "(^|[/_:@])muse-spark-1\\.3(?:$|[-.])")) {
            return if (n.contains("contributor")) OpenAiReasoningSupport.MUSE_SPARK else OpenAiReasoningSupport.MUSE_SPARK_13
        }
        if (matchesModel(n, "(^|[/_:@])muse-spark-1(?:$|[-.])")) return OpenAiReasoningSupport.MUSE_SPARK
        if (matchesModel(n, "(^|[/_:@])glm-5\\.3(?:$|[-.])")) return OpenAiReasoningSupport.GLM_53
        if (matchesModel(n, "(^|[/_:@])glm-5\\.2(?:$|[-.])")) return OpenAiReasoningSupport.GLM_52
        if (matchesModel(n, "(^|[/_:@])gpt-6(?:$|[-.])")) return OpenAiReasoningSupport.GPT_6_ASTRA
        if (!matchesModel(n, "gpt-5(?=$|[-.])")) return null

        return when {
            matchesModel(n, "(^|[/_:@])gpt-5\\.6(?:-(?:sol|terra|luna))?(?:$|[.@])") -> OpenAiReasoningSupport.GPT_56
            matchesModel(n, "(^|[/_:@])gpt-5\\.5-pro(?:$|[-.])") -> OpenAiReasoningSupport.GPT_55_PRO
            matchesModel(n, "(^|[/_:@])gpt-5\\.5-(?:codex|chat-latest)(?:$|[-.])") -> null
            matchesModel(n, "(^|[/_:@])gpt-5\\.5(?:$|[-.])") -> OpenAiReasoningSupport.GPT_55
            matchesModel(n, "(^|[/_:@])gpt-5\\.4-pro(?:$|[-.])") -> OpenAiReasoningSupport.GPT_54_PRO
            matchesModel(n, "(^|[/_:@])gpt-5\\.4-(?:codex|chat-latest)(?:$|[-.])") -> null
            matchesModel(n, "(^|[/_:@])gpt-5\\.4(?:$|[-.])") -> OpenAiReasoningSupport.GPT_54
            matchesModel(n, "(^|[/_:@])gpt-5\\.3-codex(?:$|[-.])") -> OpenAiReasoningSupport.GPT_53_CODEX
            matchesModel(n, "(^|[/_:@])gpt-5\\.3-chat-latest(?:$|[-.])") -> OpenAiReasoningSupport.GPT_53_CHAT_LATEST
            matchesModel(n, "(^|[/_:@])gpt-5\\.3(?:$|[-.])") -> null
            matchesModel(n, "(^|[/_:@])gpt-5\\.2-pro(?:$|[-.])") -> OpenAiReasoningSupport.GPT_52_PRO
            matchesModel(n, "(^|[/_:@])gpt-5\\.2-codex(?:$|[-.])") -> OpenAiReasoningSupport.GPT_52_CODEX
            matchesModel(n, "(^|[/_:@])gpt-5\\.2-chat-latest(?:$|[-.])") -> OpenAiReasoningSupport.GPT_52_CHAT_LATEST
            matchesModel(n, "(^|[/_:@])gpt-5\\.2(?:$|[-.])") -> OpenAiReasoningSupport.GPT_52
            matchesModel(n, "(^|[/_:@])gpt-5\\.1-chat-latest(?:$|[-.])") -> OpenAiReasoningSupport.GPT_51_CHAT_LATEST
            matchesModel(n, "(^|[/_:@])gpt-5\\.1-codex-max(?:$|[-.])") -> OpenAiReasoningSupport.GPT_51_CODEX_MAX
            matchesModel(n, "(^|[/_:@])gpt-5\\.1-codex(?:$|[-.])") -> OpenAiReasoningSupport.GPT_51_CODEX
            matchesModel(n, "(^|[/_:@])gpt-5\\.1-pro(?:$|[-.])") -> null
            matchesModel(n, "(^|[/_:@])gpt-5\\.1(?:$|[-.])") -> OpenAiReasoningSupport.GPT_51
            matchesModel(n, "(^|[/_:@])gpt-5-pro(?:$|[-.])") -> OpenAiReasoningSupport.GPT_5_PRO
            matchesModel(n, "(^|[/_:@])gpt-5-codex(?:$|[-.])") -> OpenAiReasoningSupport.GPT_5_CODEX
            matchesModel(n, "(^|[/_:@])gpt-5-chat-latest(?:$|[-.])") -> null
            matchesModel(n, "(^|[/_:@])gpt-5(?:$|-)") -> OpenAiReasoningSupport.GPT_5
            else -> null
        }
    }

    private fun matchesModel(modelId: String, pattern: String): Boolean =
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(modelId)

    fun openAiSupportsXhighReasoning(modelId: String): Boolean =
        openAiReasoningSupport(modelId)?.supportsXhigh ?: false

    fun openAiSupportsMaxReasoning(modelId: String): Boolean =
        openAiReasoningSupport(modelId)?.supportsMax ?: false

    /**
     * _googleThinkingConfig — generationConfig.thinkingConfig for Gemini.
     * Gemini 3 families use thinkingLevel; Gemini 2.x and below use
     * thinkingBudget; "off" only hides the thoughts on always-thinking models.
     */
    fun googleThinkingConfig(modelId: String, budget: Int?): JsonObject {
        val off = isOff(budget)
        val id = modelId.trim().lowercase()

        if (GEMMA_4.containsMatchIn(id)) {
            if (off) return JsonObject(emptyMap())
            return buildJsonObject {
                put("includeThoughts", true)
                put("thinkingLevel", "high")
            }
        }
        if (GEMINI_3_FLASH_IMAGE.containsMatchIn(id)) {
            val level = if (!off && budget != null && budget >= LOW_BUDGET_CEILING) "high" else "minimal"
            return buildJsonObject {
                put("includeThoughts", !off)
                put("thinkingLevel", level)
            }
        }
        val proMinor = gemini3Minor(id, GEMINI_3_PRO)
        if (proMinor != null) {
            val hasMedium = proMinor >= PRO_MEDIUM_MINOR
            val level = when {
                off -> "low"
                budget != null && budget > 0 && budget < LOW_BUDGET_CEILING -> "low"
                budget != null && budget > 0 && budget < MEDIUM_BUDGET_CEILING && hasMedium -> "medium"
                else -> "high"
            }
            return buildJsonObject {
                put("includeThoughts", !off)
                put("thinkingLevel", level)
            }
        }
        val flashMinor = gemini3Minor(id, GEMINI_3_FLASH)
        if (flashMinor != null) {
            val lowest = if (flashMinor >= FLASH_NO_MINIMAL_MINOR) "low" else "minimal"
            var level = if (GEMINI_3_FLASH_LITE.containsMatchIn(id)) {
                lowest
            } else if (flashMinor >= FLASH_MODERN_MINOR) {
                "medium"
            } else {
                "high"
            }
            when {
                off -> level = lowest
                budget != null && budget > 0 -> {
                    level = when {
                        budget < LOW_BUDGET_CEILING -> "low"
                        budget < MEDIUM_BUDGET_CEILING -> "medium"
                        else -> "high"
                    }
                }
            }
            return buildJsonObject {
                put("includeThoughts", !off)
                put("thinkingLevel", level)
            }
        }
        // Gemini 2.x and below.
        if (off) return buildJsonObject { put("includeThoughts", false) }
        return buildJsonObject {
            put("includeThoughts", true)
            if (budget != null && budget >= 0) put("thinkingBudget", budget)
        }
    }

    /**
     * applyVendorReasoningKnobs — per-vendor reasoning fields (openai_vendor_
     * compat.dart L503-570). Returns the JSON fields to merge into the request
     * body; the generic OpenAI-compatible path uses `reasoning_effort`.
     */
    fun vendorReasoningFields(
        providerId: String,
        baseUrl: String,
        modelId: String,
        thinkingBudget: Int?,
        reasoning: Boolean,
    ): Map<String, kotlinx.serialization.json.JsonElement> {
        if (!reasoning) return emptyMap()
        val host = runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("").lowercase()
        val provider = providerId.lowercase()
        val model = modelId.lowercase()
        val off = isOff(thinkingBudget)
        val isZhipu = provider.contains("zhipu") || provider.contains("智谱") ||
            host.contains("open.bigmodel.cn") || host.contains("bigmodel") ||
            host == "api.z.ai" || model.startsWith("glm-")
        val isMimo = host.contains("xiaomimimo") || model.startsWith("mimo-") || model.contains("/mimo-")
        val isVolc = host.contains("ark.cn-beijing.volces.com") || host.contains("volc") || host.contains("ark")
        val isDashScope = host.contains("dashscope") || host.contains("aliyun")
        val isOpenRouter = provider.contains("openrouter") || host.contains("openrouter.ai")
        val isLaguna = model.startsWith("laguna-") || model.contains("/laguna-")
        return when {
            isZhipu || isMimo || isVolc -> mapOf(
                "thinking" to buildJsonObject { put("type", if (off) "disabled" else "enabled") },
            )
            isDashScope -> buildMap {
                put("enable_thinking", kotlinx.serialization.json.JsonPrimitive(!off))
                if (!off && (thinkingBudget ?: 0) > 0) {
                    put("thinking_budget", kotlinx.serialization.json.JsonPrimitive(thinkingBudget))
                }
            }
            isOpenRouter -> mapOf(
                "reasoning" to buildJsonObject {
                    put("enabled", !off)
                    if (!off && (thinkingBudget ?: 0) > 0) put("max_tokens", thinkingBudget)
                },
            )
            isLaguna -> mapOf(
                "chat_template_kwargs" to buildJsonObject { put("enable_thinking", !off) },
            )
            else -> {
                val effort = openAiEffortForBudget(thinkingBudget, modelId)
                if (effort != "off" && effort != "auto") {
                    mapOf("reasoning_effort" to kotlinx.serialization.json.JsonPrimitive(effort))
                } else {
                    emptyMap()
                }
            }
        }
    }

    // ---- internals ----

    /**
     * openai_model_compat.dart `OpenAIReasoningSupport` — the supported effort
     * tiers per model family (upstream 2026-09 model table). `supportsMax`
     * drives the Max stop in the reasoning budget sheet.
     */
    private class OpenAiReasoningSupport(val supportedEfforts: List<String>, val offFallback: String? = null) {
        val supportsXhigh: Boolean get() = "xhigh" in supportedEfforts
        val supportsMax: Boolean get() = "max" in supportedEfforts

        companion object {
            private val NONE_LOW_MEDIUM_HIGH = listOf("none", "low", "medium", "high")
            val GPT_5 = OpenAiReasoningSupport(NONE_LOW_MEDIUM_HIGH)
            val GPT_5_PRO = OpenAiReasoningSupport(listOf("high"), offFallback = "high")
            val GPT_5_CODEX = OpenAiReasoningSupport(listOf("low", "medium", "high"), offFallback = "low")
            val GPT_51 = OpenAiReasoningSupport(NONE_LOW_MEDIUM_HIGH, offFallback = "low")
            val GPT_51_CHAT_LATEST = OpenAiReasoningSupport(NONE_LOW_MEDIUM_HIGH)
            val GPT_51_CODEX = OpenAiReasoningSupport(listOf("low", "medium", "high"), offFallback = "low")
            val GPT_51_CODEX_MAX = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh"), offFallback = "low")
            val GPT_52 = OpenAiReasoningSupport(listOf("none", "low", "medium", "high", "xhigh"))
            val GPT_52_CHAT_LATEST = OpenAiReasoningSupport(listOf("none", "low", "medium", "high", "xhigh"))
            val GPT_52_CODEX = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh"), offFallback = "low")
            val GPT_52_PRO = OpenAiReasoningSupport(listOf("medium", "high", "xhigh"), offFallback = "medium")
            val GPT_53_CHAT_LATEST = OpenAiReasoningSupport(listOf("none", "low", "medium", "high", "xhigh"), offFallback = "low")
            val GPT_53_CODEX = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh"), offFallback = "low")
            val GPT_54 = OpenAiReasoningSupport(listOf("none", "low", "medium", "high", "xhigh"), offFallback = "medium")
            val GPT_54_PRO = OpenAiReasoningSupport(listOf("medium", "high", "xhigh"), offFallback = "medium")
            val GPT_55 = OpenAiReasoningSupport(listOf("none", "low", "medium", "high", "xhigh"), offFallback = "medium")
            val GPT_55_PRO = OpenAiReasoningSupport(listOf("medium", "high", "xhigh"), offFallback = "medium")
            val GPT_56 = OpenAiReasoningSupport(listOf("none", "low", "medium", "high", "xhigh", "max"), offFallback = "low")
            val GPT_6_ASTRA = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh", "max"), offFallback = "low")
            val GLM_53 = OpenAiReasoningSupport(listOf("low", "high", "max"), offFallback = "low")
            val GLM_52 = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh", "max"))
            val DEEPSEEK = OpenAiReasoningSupport(listOf("low", "high", "max"))
            val KIMI_K3 = OpenAiReasoningSupport(listOf("low", "high", "max"), offFallback = "low")
            val GROK_45 = OpenAiReasoningSupport(listOf("low", "medium", "high"), offFallback = "low")
            val GROK_46 = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh"), offFallback = "low")
            val MIMO = OpenAiReasoningSupport(NONE_LOW_MEDIUM_HIGH)
            val MUSE_SPARK = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh"), offFallback = "low")
            val MUSE_SPARK_13 = OpenAiReasoningSupport(listOf("low", "medium", "high", "xhigh", "max"), offFallback = "low")
        }
    }

    private val CLAUDE_5 = Regex("claude-(?:opus|sonnet)-5(?:$|[._:@/-])", RegexOption.IGNORE_CASE)
    private val CLAUDE_MAJOR_MINOR = Regex("claude-(opus|sonnet)-(\\d+)[-.](\\d+)", RegexOption.IGNORE_CASE)
    private val GEMMA_4 = Regex("(^|[/:_-])gemma[-_]?4([._-]|$)", RegexOption.IGNORE_CASE)
    private val GEMINI_3_NON_TEXT = Regex("(^|[-_/])(image|tts|live)([-._:@/]|$)", RegexOption.IGNORE_CASE)
    private val GEMINI_3_FLASH_IMAGE = Regex("gemini-3(?:\\.\\d+)?-flash(-lite)?-image([._:@/-]|$)", RegexOption.IGNORE_CASE)
    private val GEMINI_3_FLASH = Regex("gemini-3(?:\\.(?<minor>\\d+))?-flash([._:@/-]|$)", RegexOption.IGNORE_CASE)
    private val GEMINI_3_PRO = Regex("gemini-3(?:\\.(?<minor>\\d+))?-pro(-preview)?([._:@/-]|$)", RegexOption.IGNORE_CASE)
    private val GEMINI_3_FLASH_LITE = Regex("gemini-3(?:\\.\\d+)?-flash-lite([._:@/-]|$)", RegexOption.IGNORE_CASE)

    private const val PRO_MEDIUM_MINOR = 1
    private const val FLASH_MODERN_MINOR = 5
    private const val FLASH_NO_MINIMAL_MINOR = 7
    private const val LOW_BUDGET_CEILING = 8000
    private const val MEDIUM_BUDGET_CEILING = 24000

    private fun gemini3Minor(modelId: String, family: Regex): Int? {
        if (GEMINI_3_NON_TEXT.containsMatchIn(modelId)) return null
        val match = family.find(modelId) ?: return null
        val minor = match.groups["minor"]?.value ?: "0"
        return minor.toIntOrNull() ?: 0
    }
}
