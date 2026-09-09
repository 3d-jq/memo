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

    /** openAIEffortForBudget — high escalates to xhigh/max by budget + model. */
    fun openAiEffortForBudget(budget: Int?, modelId: String): String {
        val base = effortForBudget(budget)
        if (base != "high" || budget == null) return base
        return when {
            budget >= 128000 && supportsMaxReasoning(modelId) -> "max"
            budget >= 64000 -> "xhigh"
            else -> "high"
        }
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

    /** supportsMaxReasoning — models that accept the 'max' effort tier. */
    fun supportsMaxReasoning(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        return supportsXhighReasoning(lower) ||
            lower.contains("claude-opus-4-6") || lower.contains("claude-opus-4.6") ||
            lower.contains("claude-sonnet-4-6") || lower.contains("claude-sonnet-4.6") ||
            lower.contains("mythos")
    }

    /** supportsXhighReasoning — models that accept the 'xhigh' effort tier. */
    fun supportsXhighReasoning(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        return CLAUDE_5.containsMatchIn(lower) ||
            lower.contains("claude-opus-4-7") || lower.contains("claude-opus-4.7") ||
            lower.contains("claude-opus-4-8") || lower.contains("claude-opus-4.8") ||
            lower.contains("claude-fable") || lower.contains("claude-mythos")
    }

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
