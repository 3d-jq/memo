package com.psyche.memo.llm.retry

import kotlin.random.Random

/**
 * Auto-retry configuration mirroring Flutter AutoRetryOptions defaults
 * (lib/core/models/auto_retry_options.dart:41-96).
 *
 * ⚠️ **有意偏离**：Dart 的 `AutoRetryOptions.defaults()` 是 `enabled = false`，
 * 这里默认 **true** —— 用户 2026-09-15 拍板（Memo 出厂就把自动重试打开）。
 * 其余五项（状态码集合 / 两个词表 / `maxDelayMs` / `maxRetries` / 退避参数）
 * 逐字照 Dart，见下面的 `DEFAULT_*` 常量。
 *
 * **别把两个词表改回空表**：2026-09-15 用户报「自动重试的触发条件没有做完吧」
 * 「和原项目人家有触发字的呀」——链路本来是通的（[shouldRetryError] 的网络错误/
 * 关键词/状态码三档都在、容器也按请求实时读盘），但出厂默认的两个词表是
 * `emptyList()`，于是「关键词触发」这一档永远不命中。设置页一直显示着正确的
 * 词表（它有自己那份 private 拷贝），所以现象是「页面看着对、实际不重试」。
 */
data class AutoRetryOptions(
    val enabled: Boolean = true,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val multiplier: Double = DEFAULT_MULTIPLIER,
    val jitter: Boolean = true,
    val retryOnNetworkError: Boolean = true,
    val retryStatusCodes: Set<Int> = DEFAULT_RETRY_STATUS_CODES,
    val retryKeywords: List<String> = DEFAULT_RETRY_KEYWORDS,
    val stopKeywords: List<String> = DEFAULT_STOP_KEYWORDS,
) {
    companion object {
        const val DEFAULT_MAX_DELAY_MS = 30000L
        const val DEFAULT_INITIAL_DELAY_MS = 1000L
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_MULTIPLIER = 2.0

        /** Dart `defaultRetryStatusCodes`（auto_retry_options.dart:58-67）逐字：有 425/529、**没有** 409。 */
        val DEFAULT_RETRY_STATUS_CODES: Set<Int> = setOf(408, 425, 429, 500, 502, 503, 504, 529)

        /** Dart `defaultRetryKeywords`（:69-82）逐字，12 条。 */
        val DEFAULT_RETRY_KEYWORDS: List<String> = listOf(
            "并发", "稍后", "重试", "访问量过大", "繁忙", "限流",
            "rate limit", "too many requests", "overloaded", "try again", "timeout", "超时",
        )

        /** Dart `defaultStopKeywords`（:84-96）逐字，11 条。 */
        val DEFAULT_STOP_KEYWORDS: List<String> = listOf(
            "余额", "不足", "额度", "欠费", "balance", "insufficient", "quota",
            "invalid api key", "unauthorized", "permission", "未实名",
        )

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
