package com.psyche.memo.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Assistant entity DTO — mirrors Flutter Assistant.toJson keys
 * (stored in assistant_rows.payload). Missing/unknown keys are tolerated:
 * the serializer maps the core fields and keeps unknown keys in [raw] for
 * lossless backup round-trips.
 */
@Serializable
data class Assistant(
    val id: String = "",
    val name: String = "",
    val avatar: String? = null,
    val useAssistantAvatar: Boolean = false,
    val useAssistantName: Boolean = false,
    val chatModelProvider: String? = null,
    val chatModelId: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val contextMessageSize: Int = 64,
    val limitContextMessages: Boolean = false,
    val streamOutput: Boolean = true,
    val thinkingBudget: Int? = null,
    val maxTokens: Int? = null,
    val systemPrompt: String = "",
    val messageTemplate: String = "{{ message }}",
    val searchEnabled: Boolean = false,
    val mcpServerIds: List<String> = emptyList(),
    val localToolIds: List<String> = emptyList(),
    val healthDataTypeIds: List<String> = emptyList(),
    val background: String? = null,
    val customHeaders: List<Map<String, String>> = emptyList(),
    val customBody: List<Map<String, String>> = emptyList(),
    val enableMemory: Boolean = false,
    val autoOrganizeMemory: Boolean = false,
    val memoryOrganizeEveryNTurns: Int = 1,
    val memorySmartAddMode: String = "batched",
    val memoryWriteScope: String = "alwaysGlobal",
    val allowPastConversationRecall: Boolean = false,
    val generateConversationSummary: Boolean = false,
    val recentChatsSummaryMessageCount: Int = 5,
    val appendCurrentTimeToUserMessage: Boolean = false,
    val presetMessages: List<JsonElement> = emptyList(),
    val regexRules: List<JsonElement> = emptyList(),
) {
    companion object {
        fun fromJsonString(json: kotlinx.serialization.json.Json, text: String): Assistant =
            json.decodeFromString(serializer(), text)
    }
}
