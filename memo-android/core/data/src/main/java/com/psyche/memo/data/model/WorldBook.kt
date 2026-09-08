package com.psyche.memo.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * World book DTOs — mirror lib/core/models/world_book.dart's JSON shape
 * (stored in world_book_rows.payload, one row per book).
 *
 * The enums keep the upstream wire spelling (`BEFORE_SYSTEM_PROMPT`, `USER`…);
 * unknown values fall back to the property default when the reader is built
 * with coerceInputValues, which is what the Dart fromJson switch does.
 */
@Serializable
enum class WorldBookInjectionPosition {
    @SerialName("BEFORE_SYSTEM_PROMPT")
    BEFORE_SYSTEM_PROMPT,

    @SerialName("AFTER_SYSTEM_PROMPT")
    AFTER_SYSTEM_PROMPT,

    @SerialName("TOP_OF_CHAT")
    TOP_OF_CHAT,

    @SerialName("BOTTOM_OF_CHAT")
    BOTTOM_OF_CHAT,

    @SerialName("AT_DEPTH")
    AT_DEPTH,
}

@Serializable
enum class WorldBookInjectionRole {
    @SerialName("USER")
    USER,

    @SerialName("ASSISTANT")
    ASSISTANT,
}

@Serializable
data class WorldBookEntry(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val position: WorldBookInjectionPosition = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT,
    val content: String = "",
    val injectDepth: Int = 4,
    val role: WorldBookInjectionRole = WorldBookInjectionRole.USER,
    val keywords: List<String> = emptyList(),
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val scanDepth: Int = 4,
    val constantActive: Boolean = false,
)

@Serializable
data class WorldBook(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<WorldBookEntry> = emptyList(),
)
