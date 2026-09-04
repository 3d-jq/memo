package com.psyche.memo.llm.retry

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

class RetryPolicyTest {

    @Test
    fun backoffExponentialWithMultiplier() {
        val opts = AutoRetryOptions(initialDelayMs = 1000, maxDelayMs = 15000, multiplier = 2.0, jitter = false)
        assertEquals(1000L, backoffDelay(0, opts))
        assertEquals(2000L, backoffDelay(1, opts))
        assertEquals(4000L, backoffDelay(2, opts))
        assertEquals(8000L, backoffDelay(3, opts))
        assertEquals(15000L, backoffDelay(4, opts)) // capped at maxDelay
    }

    @Test
    fun backoffJitterWithin20Percent() {
        val opts = AutoRetryOptions(initialDelayMs = 1000, maxDelayMs = 15000, multiplier = 2.0, jitter = true)
        val rng = kotlin.random.Random(42)
        for (attempt in 0..5) {
            val d = backoffDelay(attempt, opts, random = rng)
            // Deterministic value is capped at maxDelay BEFORE jitter; after
            // jitter it is capped again to maxDelay.
            val base = (1000L * Math.pow(2.0, attempt.toDouble()).toLong()).coerceAtMost(15000L)
            val lo = (base * 0.8).toLong()
            val hi = (base * 1.2).toLong().coerceAtMost(15000L) + 1
            assertTrue("delay $d out of range [$lo, $hi] base $base", d >= lo && d <= hi)
        }
    }

    @Test
    fun clampsMultiplier() {
        assertEquals(1.0, AutoRetryOptions.clampMultiplier(-1.0), 0.0001)
        assertEquals(1.0, AutoRetryOptions.clampMultiplier(0.0), 0.0001)
        assertEquals(2.5, AutoRetryOptions.clampMultiplier(2.5), 0.0001)
        assertEquals(1.0, AutoRetryOptions.clampMultiplier(Double.NaN), 0.0001)
        assertEquals(1.0, AutoRetryOptions.clampMultiplier(Double.POSITIVE_INFINITY), 0.0001)
    }

    @Test
    fun networkErrorIsRetryableWhenRetryOnNetworkEnabled() {
        val opts = AutoRetryOptions(retryOnNetworkError = true)
        assertTrue(shouldRetryError(SocketException("connection reset"), opts))
    }

    @Test
    fun networkErrorNotRetryableWhenDisabled() {
        val opts = AutoRetryOptions(retryOnNetworkError = false)
        assertFalse(shouldRetryError(SocketException("connection reset"), opts))
    }

    @Test
    fun retryStatusCodesMatch() {
        val opts = AutoRetryOptions(retryStatusCodes = setOf(408, 429, 500, 502, 503, 504))
        assertTrue(shouldRetryError(IOException("HTTP 503 Service Unavailable"), opts))
        assertTrue(shouldRetryError(IOException("HTTP 429 Too Many Requests"), opts))
        assertFalse(shouldRetryError(IOException("HTTP 401 Unauthorized"), opts))
        assertFalse(shouldRetryError(IOException("HTTP 404 Not Found"), opts))
    }

    @Test
    fun retryKeywordsMatch() {
        val opts = AutoRetryOptions(retryKeywords = listOf("rate limit"))
        assertTrue(shouldRetryError(IOException("rate limit exceeded"), opts))
        assertFalse(shouldRetryError(IOException("bad request"), opts))
    }

    @Test
    fun stopKeywordsNeverRetry() {
        val opts = AutoRetryOptions(stopKeywords = listOf("insufficient balance"))
        assertFalse(shouldRetryError(IOException("insufficient balance"), opts))
    }

    @Test
    fun cancellationNeverRetries() {
        val opts = AutoRetryOptions()
        assertFalse(shouldRetryError(CancellationException("cancelled"), opts))
        assertFalse(shouldRetryError(IOException("cancelled"), opts))
    }

    @Test
    fun unknownErrorNotRetryable() {
        val opts = AutoRetryOptions()
        assertFalse(shouldRetryError(IllegalStateException("bang"), opts))
    }
}
