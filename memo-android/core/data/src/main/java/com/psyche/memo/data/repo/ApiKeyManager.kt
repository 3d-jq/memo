package com.psyche.memo.data.repo

import com.psyche.memo.data.model.ApiKeyConfig
import com.psyche.memo.data.model.ApiKeyStatus
import com.psyche.memo.data.model.LoadBalanceStrategy
import com.psyche.memo.data.model.ProviderConfig
import kotlin.random.Random

/**
 * Port of lib/core/services/api_key_manager.dart — strategy-based key
 * selection over a provider's enabled keys, plus usage bookkeeping.
 * In-memory rotation state, same as upstream (process lifetime).
 */
object ApiKeyManager {

    data class KeySelectionResult(val key: ApiKeyConfig?, val reason: String)

    private val roundRobinIndexMap = HashMap<String, Int>() // providerId -> index
    private val keyUsageMap = HashMap<String, Int>() // keyId -> total uses (ephemeral)

    fun selectForProvider(provider: ProviderConfig): KeySelectionResult {
        val keys = (provider.apiKeys ?: emptyList()).filter { it.isEnabled }
        if (keys.isEmpty()) return KeySelectionResult(null, "no_keys")

        // Filter by status and cooldown. Upstream note kept verbatim: only
        // keys marked active are selected; error keys are filtered by the
        // cooldown check above and disabled keys are skipped.
        val now = System.currentTimeMillis()
        val cooldownMs =
            (provider.keyManagement?.failureRecoveryTimeMinutes ?: 5) * 60 * 1000L
        val available = keys.filter { k ->
            if (k.status == ApiKeyStatus.disabled) return@filter false
            if (k.status == ApiKeyStatus.error) {
                val since = now - k.updatedAt
                if (since < cooldownMs) return@filter false
            }
            k.status == ApiKeyStatus.active
        }
        if (available.isEmpty()) return KeySelectionResult(null, "no_available_keys")

        val strategy = provider.keyManagement?.strategy ?: LoadBalanceStrategy.roundRobin
        val chosen: ApiKeyConfig = when (strategy) {
            LoadBalanceStrategy.priority -> available.minBy { it.priority }
            LoadBalanceStrategy.leastUsed -> available.minBy { it.usage.totalRequests }
            LoadBalanceStrategy.random -> available[Random.nextInt(available.size)]
            LoadBalanceStrategy.roundRobin -> {
                val cur = roundRobinIndexMap[provider.id]
                    ?: (provider.keyManagement?.roundRobinIndex ?: 0)
                val idx = cur % available.size
                roundRobinIndexMap[provider.id] = (idx + 1) % available.size
                available[idx]
            }
        }
        return KeySelectionResult(chosen, "strategy_${strategy.name}")
    }

    fun updateKeyStatus(
        provider: ProviderConfig,
        key: ApiKeyConfig,
        success: Boolean,
        error: String? = null,
    ): ApiKeyConfig {
        val now = System.currentTimeMillis()
        val updated = key.copyWith(
            usage = key.usage.copy(
                totalRequests = key.usage.totalRequests + 1,
                successfulRequests = key.usage.successfulRequests + if (success) 1 else 0,
                failedRequests = key.usage.failedRequests + if (success) 0 else 1,
                consecutiveFailures = if (success) 0 else key.usage.consecutiveFailures + 1,
                lastUsed = now,
            ),
            status = if (success) {
                ApiKeyStatus.active
            } else if ((key.usage.consecutiveFailures + 1) >=
                (provider.keyManagement?.maxFailuresBeforeDisable ?: 3)
            ) {
                ApiKeyStatus.error
            } else {
                key.status
            },
            lastError = if (success) null else (error ?: key.lastError),
            updatedAt = now,
        )
        keyUsageMap[updated.id] = (keyUsageMap[updated.id] ?: 0) + 1
        return updated
    }

    fun recordKeyUsage(keyId: String, success: Boolean) {
        keyUsageMap[keyId] = (keyUsageMap[keyId] ?: 0) + 1
    }
}
