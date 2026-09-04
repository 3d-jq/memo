package com.psyche.memo.llm.retry

import kotlin.random.Random

/**
 * Auto-retry configuration mirroring Flutter AutoRetryOptions defaults
 * (lib/core/models/auto_retry_options.dart).
 */
data class AutoRetryOptions(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 15000,
    val multiplier: Double = 2.0,
    val jitter: Boolean = true,
    val retryOnNetworkError: Boolean = true,
    val retryStatusCodes: Set<Int> = setOf(408, 409, 429, 500, 502, 503, 504),
    val retryKeywords: List<String> = emptyList(),
    val stopKeywords: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_DELAY_MS = 15000L
        const val DEFAULT_INITIAL_DELAY_MS = 1000L
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_MULTIPLIER = 2.0

        fun clampMultiplier(value: Double): Double = when {
            value.isNaN() || value.isInfinite() || value <= 0 -> 1.0
            else -> value
        }

        fun defaults(): AutoRetryOptions = AutoRetryOptions()
    }
}

/** Delay before retrying after [attemptIndex] (0 = first retry). */
fun backoffDelay(
    attemptIndex: Int,
    options: AutoRetryOptions,
    random: Random = Random.Default,
): Long {
    val initial = if (options.initialDelayMs < 0) 0 else options.initialDelayMs
    val maxDelay = if (options.maxDelayMs < 0) 0 else options.maxDelayMs
    val multiplier = AutoRetryOptions.clampMultiplier(options.multiplier)
    val factor = if (attemptIndex <= 0) 1.0 else multiplier.coerceAtMost(Int.MAX_VALUE.toDouble()).pow(attemptIndex)
    var ms = initial * factor
    if (!ms.isFinite() || ms > maxDelay) ms = maxDelay.toDouble()
    if (ms < 0) ms = 0.0
    if (options.jitter) {
        // ±20%
        val jittered = ms * (0.8 + random.nextDouble() * 0.4)
        ms = jittered
    }
    if (!ms.isFinite() || ms > maxDelay) ms = maxDelay.toDouble()
    return ms.toLong()
}

private fun Double.pow(n: Int): Double {
    var result = 1.0
    var base = this
    var exp = n
    while (exp > 0) {
        if (exp and 1 == 1) result *= base
        base *= base
        exp = exp shr 1
    }
    return result
}
