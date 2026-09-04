package com.psyche.memo.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Conversation entity. Mirrors Flutter `Conversation`; row layout is the
 * drift v3 conversation_rows table (see memo_schema_v3.sql).
 */
data class Conversation(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    var isPinned: Boolean = false,
    var assistantId: String? = null,
    var truncateIndex: Int = -1,
    var versionSelections: Map<String, Int> = emptyMap(),
    var summary: String? = null,
    var lastSummarizedMessageCount: Int = 0,
    var chatSuggestions: List<String> = emptyList(),
    var injectedMemoryHash: String? = null,
    var lastMemoryExtractedOrder: Int = -1,
    var chatModelProvider: String? = null,
    var chatModelId: String? = null,
    var extras: JsonObject? = null,
) {
    companion object {
        /** Reserved id for the ephemeral chat that opens on launch. */
        const val TEMPORARY_ID = "__temporary__"

        fun newId(): String = java.util.UUID.randomUUID().toString()

        fun create(
            id: String = newId(),
            title: String,
            assistantId: String? = null,
            now: Long = System.currentTimeMillis(),
        ): Conversation = Conversation(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            assistantId = assistantId,
        )
    }
}
