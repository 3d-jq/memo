package com.psyche.memo.data.model

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Preset conversation turn — mirrors Flutter PresetMessage
 * (lib/core/models/preset_message.dart). Lives inside assistant_rows.payload
 * under `presetMessages`, which the DTO keeps as raw JSON.
 */
@Serializable
data class PresetMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String = "user",
    val content: String = "",
) {
    companion object {
        private val json = Json { encodeDefaults = true }

        /** decodeList L26-38 — non-object entries drop, missing id gets a uuid. */
        fun decodeList(raw: List<JsonElement>): List<PresetMessage> =
            raw.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement(serializer(), element).normalized() }.getOrNull()
            }

        /** encodeList L40-42. */
        fun encodeList(list: List<PresetMessage>): List<JsonElement> =
            list.map { json.encodeToJsonElement(serializer(), it) }

        /** fromJson L22 — anything but 'assistant' is a user turn. */
        private fun PresetMessage.normalized(): PresetMessage =
            if (role == "assistant") this else copy(role = "user")
    }
}
