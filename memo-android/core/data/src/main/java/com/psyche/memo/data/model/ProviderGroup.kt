package com.psyche.memo.data.model

import kotlinx.serialization.Serializable

/**
 * Provider group — mirrors Flutter lib/core/models/provider_group.dart
 * (stored in provider_group_rows.payload).
 */
@Serializable
data class ProviderGroup(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
) {
    fun toJsonString(json: kotlinx.serialization.json.Json): String =
        json.encodeToString(serializer(), this)

    companion object {
        fun fromJsonString(json: kotlinx.serialization.json.Json, text: String): ProviderGroup? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
