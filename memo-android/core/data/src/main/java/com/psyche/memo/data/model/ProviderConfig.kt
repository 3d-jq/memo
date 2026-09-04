package com.psyche.memo.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Provider config DTO — mirrors Flutter ProviderConfig JSON shape exactly
 * (stored in provider_rows.payload). Unknown keys are preserved losslessly
 * via [raw] so backup round-trips stay byte-compatible.
 */
@Serializable
data class ProviderConfig(
    val id: String = "",
    val enabled: Boolean = true,
    val name: String = "",
    val apiKey: String = "",
    val baseUrl: String = "",
    val providerType: String? = null,
    val chatPath: String? = null,
    val useResponseApi: Boolean? = null,
    val vertexAI: Boolean? = null,
    val location: String? = null,
    val projectId: String? = null,
    val serviceAccountJson: String? = null,
    val models: List<String> = emptyList(),
    val modelOverrides: Map<String, JsonElement> = emptyMap(),
    val customHeaders: List<Map<String, String>> = emptyList(),
    val customBody: List<Map<String, String>> = emptyList(),
    val proxyEnabled: Boolean? = null,
    val proxyType: String? = null,
    val proxyHost: String? = null,
    val proxyPort: String? = null,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val avatarType: String? = null,
    val avatarValue: String? = null,
    val multiKeyEnabled: Boolean? = null,
    val apiKeys: List<ApiKeyConfig>? = null,
    val keyManagement: JsonObject? = null,
    val aihubmixAppCodeEnabled: Boolean? = null,
    val balanceEnabled: Boolean? = null,
    val balanceApiPath: String? = null,
    val balanceResultPath: String? = null,
    val claudePromptCachingEnabled: Boolean = false,
    val claudePromptCachingTtl: String = "5m",
) {
    fun effectiveApiKey(): String {
        if (multiKeyEnabled == true && !apiKeys.isNullOrEmpty()) {
            return apiKeys!!.firstOrNull { it.enabled }?.key ?: apiKey
        }
        return apiKey
    }

    fun classifiedKind(): String = when (providerType) {
        "claude", "anthropic" -> "anthropic"
        "google", "gemini" -> "gemini"
        else -> "openai"
    }

    companion object {
        fun fromJsonString(json: kotlinx.serialization.json.Json, text: String): ProviderConfig =
            json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ApiKeyConfig(
    val order: Int = 0,
    val key: String = "",
    val enabled: Boolean = true,
    val name: String = "",
)
