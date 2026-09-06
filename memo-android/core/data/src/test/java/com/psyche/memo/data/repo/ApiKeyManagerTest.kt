package com.psyche.memo.data.repo

import com.psyche.memo.data.model.ApiKeyConfig
import com.psyche.memo.data.model.ApiKeyStatus
import com.psyche.memo.data.model.KeyManagementConfig
import com.psyche.memo.data.model.LoadBalanceStrategy
import com.psyche.memo.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port coverage of lib/core/services/api_key_manager.dart + the api_keys.dart
 * JSON shape (field names must stay compatible with the upstream payload).
 */
class ApiKeyManagerTest {

    private fun provider(
        keys: List<ApiKeyConfig>,
        strategy: LoadBalanceStrategy = LoadBalanceStrategy.roundRobin,
        recoveryMinutes: Int = 5,
        maxFailures: Int = 3,
        id: String = "p1",
    ): ProviderConfig = ProviderConfig(
        id = id,
        apiKeys = keys,
        keyManagement = KeyManagementConfig(
            strategy = strategy,
            failureRecoveryTimeMinutes = recoveryMinutes,
            maxFailuresBeforeDisable = maxFailures,
        ),
    )

    private fun key(id: String, priority: Int = 5, status: ApiKeyStatus = ApiKeyStatus.active) =
        ApiKeyConfig(id = id, key = "sk-$id", priority = priority, status = status)

    @Test
    fun `empty key list yields no_keys`() {
        val res = ApiKeyManager.selectForProvider(provider(emptyList()))
        assertNull(res.key)
        assertEquals("no_keys", res.reason)
    }

    @Test
    fun `round robin cycles through keys in order`() {
        val keys = listOf(key("a"), key("b"), key("c"))
        val cfg = provider(keys, id = "rr-cycle")
        val picked = (1..6).map { ApiKeyManager.selectForProvider(cfg).key!!.id }
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), picked)
    }

    @Test
    fun `round robin persists pointer per provider`() {
        val keys = listOf(key("a"), key("b"))
        val cfg = provider(keys, id = "rr-persist")
        assertEquals("a", ApiKeyManager.selectForProvider(cfg).key!!.id)
        // A fresh-but-equal config keeps the in-memory pointer (upstream
        // semantics: _roundRobinIndexMap survives config copies).
        assertEquals("b", ApiKeyManager.selectForProvider(cfg.copy()).key!!.id)
    }

    @Test
    fun `priority picks the smallest priority number`() {
        val keys = listOf(key("big", priority = 9), key("small", priority = 1), key("mid", priority = 5))
        val res = ApiKeyManager.selectForProvider(provider(keys, LoadBalanceStrategy.priority))
        assertEquals("small", res.key!!.id)
    }

    @Test
    fun `least used picks the lowest totalRequests`() {
        val used = key("busy").copyWith(usage = com.psyche.memo.data.model.ApiKeyUsage(totalRequests = 10))
        val fresh = key("fresh")
        val res = ApiKeyManager.selectForProvider(
            provider(listOf(used, fresh), LoadBalanceStrategy.leastUsed),
        )
        assertEquals("fresh", res.key!!.id)
    }

    @Test
    fun `random picks one of the available keys`() {
        val keys = listOf(key("a"), key("b"), key("c"))
        repeat(20) {
            val res = ApiKeyManager.selectForProvider(provider(keys, LoadBalanceStrategy.random))
            assertTrue(res.key!!.id in listOf("a", "b", "c"))
        }
    }

    @Test
    fun `disabled keys are never selected`() {
        val keys = listOf(key("off").copyWith(isEnabled = true, status = ApiKeyStatus.disabled), key("on"))
        val res = ApiKeyManager.selectForProvider(provider(keys))
        assertEquals("on", res.key!!.id)
    }

    @Test
    fun `keys disabled by isEnabled are skipped`() {
        val keys = listOf(key("off").copyWith(isEnabled = false), key("on"))
        val res = ApiKeyManager.selectForProvider(provider(keys))
        assertEquals("on", res.key!!.id)
    }

    @Test
    fun `error keys inside cooldown leave nothing available`() {
        val now = System.currentTimeMillis()
        val errored = key("bad").copyWith(status = ApiKeyStatus.error, updatedAt = now - 60_000)
        val res = ApiKeyManager.selectForProvider(provider(listOf(errored), recoveryMinutes = 5))
        assertNull(res.key)
        assertEquals("no_available_keys", res.reason)
    }

    @Test
    fun `error keys past cooldown are still excluded upstream — only active is selectable`() {
        // Verbatim upstream behavior: the cooldown filter passes but the final
        // `status == active` check drops error keys regardless.
        val now = System.currentTimeMillis()
        val errored = key("bad").copyWith(status = ApiKeyStatus.error, updatedAt = now - 60 * 60 * 1000L)
        val res = ApiKeyManager.selectForProvider(provider(listOf(errored)))
        assertNull(res.key)
        assertEquals("no_available_keys", res.reason)
    }

    @Test
    fun `successful update marks active and bumps counters`() {
        val cfg = provider(listOf(key("a")))
        val updated = ApiKeyManager.updateKeyStatus(cfg, cfg.apiKeys!![0], success = true)
        assertEquals(ApiKeyStatus.active, updated.status)
        assertEquals(1, updated.usage.totalRequests)
        assertEquals(1, updated.usage.successfulRequests)
        assertEquals(0, updated.usage.failedRequests)
        assertEquals(0, updated.usage.consecutiveFailures)
        assertTrue(updated.usage.lastUsed != null)
        assertNull(updated.lastError)
    }

    @Test
    fun `failures below the threshold keep status and above it mark error`() {
        val cfg = provider(listOf(key("a")), maxFailures = 3)
        var k = cfg.apiKeys!![0]
        k = ApiKeyManager.updateKeyStatus(cfg, k, success = false, error = "boom")
        assertEquals(ApiKeyStatus.active, k.status)
        assertEquals(1, k.usage.consecutiveFailures)
        assertEquals("boom", k.lastError)
        k = ApiKeyManager.updateKeyStatus(cfg, k, success = false)
        k = ApiKeyManager.updateKeyStatus(cfg, k, success = false)
        assertEquals(ApiKeyStatus.error, k.status)
        assertEquals(3, k.usage.failedRequests)
    }

    @Test
    fun `json roundtrip keeps upstream field names`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val k = ApiKeyConfig.create("sk-test", name = "alias").copyWith(priority = 3)
        val text = json.encodeToString(ApiKeyConfig.serializer(), k)
        assertTrue(text.contains("\"isEnabled\":true"))
        assertTrue(text.contains("\"status\":\"active\""))
        assertTrue(text.contains("\"usage\""))
        val back = json.decodeFromString(ApiKeyConfig.serializer(), text)
        assertEquals(k, back)
    }
}
