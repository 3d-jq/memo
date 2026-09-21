package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Applies an assistant's regex rules to a piece of text — port of
 * `lib/utils/assistant_regex.dart applyAssistantRegexes`.
 *
 * Three transform targets decide which rules run:
 *  - [Target.PERSIST] rewrites stored content (default rules),
 *  - [Target.VISUAL] rewrites rendered content (visualOnly rules),
 *  - [Target.SEND] rewrites request content (replaceOnly rules).
 *
 * `$0`-`$99` references in the replacement expand to the match's capture
 * groups; unknown references stay literal.
 */
object AssistantRegexApplier {

    enum class Target { PERSIST, VISUAL, SEND }

    // Reused across [decodeRules] calls; rebuilding per-call is the warning
    // Kotlin emits from `Json { ... }` literals.
    private val decodeJson = Json { ignoreUnknownKeys = true }

    private val compiledCache = LinkedHashMap<String, Regex?>()
    private const val MAX_COMPILED = 256

    fun applyAll(
        input: String,
        rules: List<AssistantRegex>,
        scope: AssistantRegexScope,
        target: Target,
    ): String {
        if (input.isEmpty() || rules.isEmpty()) return input
        var out = input
        for (rule in rules) {
            if (!rule.enabled) continue
            if (scope !in rule.scopesOf()) continue
            when {
                rule.visualOnly -> if (target != Target.VISUAL) continue
                rule.replaceOnly -> if (target != Target.SEND) continue
                else -> if (target != Target.PERSIST) continue
            }
            val pattern = rule.pattern.trim()
            if (pattern.isEmpty()) continue
            val regex = compile(pattern) ?: continue
            out = regex.replace(out) { match ->
                expandReplacement(rule.replacement, match)
            }
        }
        return out
    }

    /** Decodes the assistant payload's `regexRules` (JSON elements) into rules. */
    fun decodeRules(raw: List<JsonElement>): List<AssistantRegex> =
        raw.mapNotNull { element ->
            runCatching {
                decodeJson.decodeFromString(AssistantRegex.serializer(), element.toString())
            }.getOrNull()
        }

    private fun compile(pattern: String): Regex? {
        if (compiledCache.containsKey(pattern)) return compiledCache[pattern]
        if (compiledCache.size >= MAX_COMPILED) compiledCache.clear()
        val regex = runCatching { Regex(pattern) }.getOrNull()
        compiledCache[pattern] = regex
        return regex
    }

    /** `$1`/`$2`… expand to capture groups; `$0` is the whole match. */
    private fun expandReplacement(replacement: String, match: MatchResult): String {
        val ref = Regex("\\$(\\d{1,2})")
        return ref.replace(replacement) { m ->
            val index = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (index <= match.groupValues.size) {
                match.groupValues.getOrElse(index) { "" }
            } else {
                m.value
            }
        }
    }
}
