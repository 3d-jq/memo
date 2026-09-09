package com.psyche.memo.data.model

import kotlinx.serialization.Serializable

/**
 * Assistant regex rule — mirrors lib/core/models/assistant_regex.dart.
 * `scopes` keeps the upstream wire spelling ("user"/"assistant" names).
 */
@Serializable
data class AssistantRegex(
    val id: String = "",
    val name: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scopes: List<String> = emptyList(),
    val visualOnly: Boolean = false,
    val replaceOnly: Boolean = false,
    val enabled: Boolean = true,
) {
    /** AssistantRegexScopeX.fromName — null for unknown names. */
    fun scopesOf(): List<AssistantRegexScope> =
        scopes.mapNotNull { AssistantRegexScope.fromName(it) }
}

/** assistant_regex.dart AssistantRegexScope — names match the JSON values. */
enum class AssistantRegexScope(val json: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromName(name: String?): AssistantRegexScope? =
            entries.firstOrNull { it.json == name?.trim()?.lowercase() }
    }
}
