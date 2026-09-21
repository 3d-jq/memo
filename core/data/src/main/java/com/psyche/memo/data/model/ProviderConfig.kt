package com.psyche.memo.data.model

import com.psyche.memo.data.repo.ApiKeyManager
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
    val keyManagement: KeyManagementConfig? = null,
    val aihubmixAppCodeEnabled: Boolean? = null,
    val balanceEnabled: Boolean? = null,
    val balanceApiPath: String? = null,
    val balanceResultPath: String? = null,
    val claudePromptCachingEnabled: Boolean = false,
    val claudePromptCachingTtl: String = "5m",
) {
    fun effectiveApiKey(): String {
        if (multiKeyEnabled == true && !apiKeys.isNullOrEmpty()) {
            return ApiKeyManager.selectForProvider(this).key?.key ?: apiKey
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

        /**
         * `resolveClaudePromptCachingTtl` L6128-6136：去空白转小写后**只认 '1h'**，
         * 其它（含没设过）一律回落 '5m'。
         */
        fun resolveClaudePromptCachingTtl(raw: String?): String =
            if (raw?.trim()?.lowercase() == "1h") "1h" else "5m"
    }
}

/**
 * Port of lib/core/models/api_keys.dart — JSON field names kept verbatim
 * ("isEnabled", "status" as the enum name, nested "usage") so the payload
 * stays compatible with the upstream storage shape.
 */
enum class ApiKeyStatus { active, disabled, error, rateLimited }

@Serializable
data class ApiKeyUsage(
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastUsed: Long? = null,
)

@Serializable
data class ApiKeyConfig(
    val id: String = "",
    val key: String = "",
    val name: String? = null,
    val isEnabled: Boolean = true,
    val priority: Int = 5, // 1-10, smaller means higher priority
    val maxRequestsPerMinute: Int? = null,
    val usage: ApiKeyUsage = ApiKeyUsage(),
    val status: ApiKeyStatus = ApiKeyStatus.active,
    val lastError: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun copyWith(
        id: String? = null,
        key: String? = null,
        name: String? = null,
        nameClear: Boolean = false,
        isEnabled: Boolean? = null,
        priority: Int? = null,
        maxRequestsPerMinute: Int? = null,
        usage: ApiKeyUsage? = null,
        status: ApiKeyStatus? = null,
        lastError: String? = null,
        lastErrorClear: Boolean = false,
        createdAt: Long? = null,
        updatedAt: Long? = null,
    ): ApiKeyConfig = ApiKeyConfig(
        id = id ?: this.id,
        key = key ?: this.key,
        name = if (nameClear) null else (name ?: this.name),
        isEnabled = isEnabled ?: this.isEnabled,
        priority = priority ?: this.priority,
        maxRequestsPerMinute = maxRequestsPerMinute ?: this.maxRequestsPerMinute,
        usage = usage ?: this.usage,
        status = status ?: this.status,
        lastError = if (lastErrorClear) null else (lastError ?: this.lastError),
        createdAt = createdAt ?: this.createdAt,
        updatedAt = updatedAt ?: this.updatedAt,
    )

    companion object {
        private val rng = java.util.Random()
        private val counter = java.util.concurrent.atomic.AtomicInteger()

        /** key_<ts36>_<rng36>_<ctr36> — same shape as ApiKeyConfig._generateKeyId. */
        fun generateKeyId(): String {
            val ts = java.lang.Long.toString(System.currentTimeMillis(), 36)
            val r = Integer.toString(rng.nextInt(0x7fffffff), 36)
            val c = Integer.toString(counter.incrementAndGet() and 0x7fffffff, 36)
            return "key_${ts}_${r}_$c"
        }

        fun create(key: String, name: String? = null, priority: Int = 5): ApiKeyConfig {
            val now = System.currentTimeMillis()
            return ApiKeyConfig(
                id = generateKeyId(),
                key = key,
                name = name,
                isEnabled = true,
                priority = priority,
                usage = ApiKeyUsage(),
                status = ApiKeyStatus.active,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

enum class LoadBalanceStrategy { roundRobin, priority, leastUsed, random }

@Serializable
data class KeyManagementConfig(
    val strategy: LoadBalanceStrategy = LoadBalanceStrategy.roundRobin,
    val maxFailuresBeforeDisable: Int = 3,
    val failureRecoveryTimeMinutes: Int = 5,
    val enableAutoRecovery: Boolean = true,
    val roundRobinIndex: Int? = null,
)
