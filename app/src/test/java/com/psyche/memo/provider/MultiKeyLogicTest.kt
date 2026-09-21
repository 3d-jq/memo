package com.psyche.memo.provider

import com.psyche.memo.data.model.ApiKeyConfig
import com.psyche.memo.data.model.ApiKeyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of the multi_key_manager_page.dart helpers. */
class MultiKeyLogicTest {

    @Test
    fun `splitKeys treats commas and whitespace as separators`() {
        assertEquals(
            listOf("k1", "k2", "k3", "k4", "k4"),
            MultiKeyLogic.splitKeys("k1, k2\n\tk3,,k4  , k4"),
        )
    }

    @Test
    fun `splitKeys drops blanks and keeps duplicates`() {
        assertEquals(listOf("a", "a"), MultiKeyLogic.splitKeys(" , a,, a ,"))
        assertEquals(emptyList<String>(), MultiKeyLogic.splitKeys("  ,,,  "))
    }

    @Test
    fun `short keys stay unmasked`() {
        assertEquals("short", MultiKeyLogic.maskKey("short"))
        assertEquals("12345678", MultiKeyLogic.maskKey("12345678"))
    }

    @Test
    fun `long keys mask the middle`() {
        assertEquals("abcd••••mnop", MultiKeyLogic.maskKey("abcdefghijklmnop"))
    }

    @Test
    fun `dedupe drops blanks and existing keys`() {
        val existing = listOf(ApiKeyConfig(id = "1", key = "k1"), ApiKeyConfig(id = "2", key = "k2 "))
        // Real flow: candidates come from splitKeys (already trimmed); the
        // stored set is compared trimmed.
        val added = MultiKeyLogic.dedupeAdded(
            existing,
            MultiKeyLogic.splitKeys("k1, k1 ,k3,,k2,k4"),
        )
        assertEquals(listOf("k3", "k4"), added)
    }

    @Test
    fun `applyTestResult success path`() {
        val base = ApiKeyConfig(id = "k", key = "sk", status = ApiKeyStatus.error, lastError = "old")
        val updated = MultiKeyLogic.applyTestResult(base, ok = true, nowMs = 1234L)
        assertEquals(ApiKeyStatus.active, updated.status)
        assertNull(updated.lastError)
        assertEquals(1, updated.usage.totalRequests)
        assertEquals(1, updated.usage.successfulRequests)
        assertEquals(0, updated.usage.failedRequests)
        assertEquals(0, updated.usage.consecutiveFailures)
        assertEquals(1234L, updated.usage.lastUsed)
        assertEquals(1234L, updated.updatedAt)
    }

    @Test
    fun `applyTestResult failure path accumulates`() {
        val base = ApiKeyConfig(id = "k", key = "sk")
        var updated = MultiKeyLogic.applyTestResult(base, ok = false, nowMs = 1L)
        updated = MultiKeyLogic.applyTestResult(updated, ok = false, nowMs = 2L)
        assertEquals(ApiKeyStatus.error, updated.status)
        assertEquals("Test failed", updated.lastError)
        assertEquals(2, updated.usage.totalRequests)
        assertEquals(0, updated.usage.successfulRequests)
        assertEquals(2, updated.usage.failedRequests)
        assertEquals(2, updated.usage.consecutiveFailures)
    }

    @Test
    fun `applyTestResult clears consecutive failures on success`() {
        val base = ApiKeyConfig(
            id = "k",
            key = "sk",
            usage = com.psyche.memo.data.model.ApiKeyUsage(
                totalRequests = 5,
                consecutiveFailures = 2,
            ),
        )
        val updated = MultiKeyLogic.applyTestResult(base, ok = true, nowMs = 9L)
        assertEquals(6, updated.usage.totalRequests)
        assertEquals(0, updated.usage.consecutiveFailures)
        assertTrue(updated.usage.lastUsed != null)
    }
}
