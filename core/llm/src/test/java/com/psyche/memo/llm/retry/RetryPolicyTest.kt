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

    /**
     * 原版 `retry_policy.dart:96-105`：**没有 HTTP 状态码的传输层异常**一律算网络错误
     * （`DioExceptionType` 的 connectionError / connectionTimeout / sendTimeout /
     * receiveTimeout 四种，以及 `http.ClientException && status == null`）。
     * 我们原来只认 Socket/Connect/InterruptedIO + 四个固定文案 ⇒ DNS/TLS/截断这类真实故障
     * 全都不重试（用户 2026-09-16「自动重试这个没做完吧」）。
     */
    @Test
    fun transportFailuresWithoutAStatusAreRetryable() {
        val on = AutoRetryOptions(retryOnNetworkError = true)
        val off = AutoRetryOptions(retryOnNetworkError = false)
        val cases = listOf(
            java.net.UnknownHostException("Unable to resolve host \"api.example.com\""),
            java.net.NoRouteToHostException("No route to host"),
            javax.net.ssl.SSLException("Handshake failed"),
            java.io.EOFException("unexpected end of stream"),
            IOException("stream was reset: CANCEL"),
        )
        for (error in cases) {
            assertTrue("${error.javaClass.simpleName} 应该算网络错误", shouldRetryError(error, on))
            assertFalse("${error.javaClass.simpleName} 关掉网络重试后不该重试", shouldRetryError(error, off))
        }
    }

    /** 带状态码的 HTTP 错误仍按状态码/关键词判，别被"传输层"这条错判成重试。 */
    @Test
    fun httpErrorsWithAStatusStillFollowStatusCodes() {
        val opts = AutoRetryOptions()
        assertFalse(shouldRetryError(IOException("HTTP 400 bad request"), opts))
        assertFalse(shouldRetryError(IOException("HTTP 401 unauthorized"), opts))
        assertTrue(shouldRetryError(IOException("HTTP 503 service unavailable"), opts))
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

    // ---- 出厂默认值（2026-09-15 用户报「自动重试的触发条件这个没有做完吧」：
    // 链路是通的，但两个词表出厂是空的 ⇒ 关键词触发永不命中）----
    // 下面几例逐字钉住 lib/core/models/auto_retry_options.dart:41-96，
    // 防的就是默认值再被改空/改错。

    @Test
    fun defaultStatusCodesMatchDartSource() {
        val codes = AutoRetryOptions().retryStatusCodes
        assertEquals(setOf(408, 425, 429, 500, 502, 503, 504, 529), codes)
        // 旧 Kotlin 默认值多带了 409、漏了 425/529 —— 这两条专门钉方向。
        assertFalse("409 不在 Dart 的默认集合里", 409 in codes)
        assertTrue("425 Too Early 必须在", 425 in codes)
        assertTrue("529 Overloaded 必须在", 529 in codes)
    }

    @Test
    fun defaultKeywordsMatchDartSource() {
        assertEquals(
            listOf(
                "并发", "稍后", "重试", "访问量过大", "繁忙", "限流",
                "rate limit", "too many requests", "overloaded", "try again", "timeout", "超时",
            ),
            AutoRetryOptions().retryKeywords,
        )
        assertEquals(
            listOf(
                "余额", "不足", "额度", "欠费", "balance", "insufficient", "quota",
                "invalid api key", "unauthorized", "permission", "未实名",
            ),
            AutoRetryOptions().stopKeywords,
        )
    }

    @Test
    fun defaultKeywordsAndDelaysAreNotDegenerate() {
        val opts = AutoRetryOptions()
        assertTrue("重试词表不能是空的（空了关键词触发就永不命中）", opts.retryKeywords.isNotEmpty())
        assertTrue("停止词表不能是空的（空了余额/额度类错误会被重试）", opts.stopKeywords.isNotEmpty())
        assertEquals(30000L, AutoRetryOptions.DEFAULT_MAX_DELAY_MS)
        assertEquals(30000L, opts.maxDelayMs)
        // 有意偏离 Dart（那边 defaults() 是 false）：用户 2026-09-15 拍板出厂即开。
        assertTrue(opts.enabled)
    }

    @Test
    fun defaultKeywordsDriveRetryDecisions() {
        val opts = AutoRetryOptions()
        // 命中出厂重试词 → 重试（含中文与英文两条路径）
        assertTrue(shouldRetryError(IOException("服务繁忙，请稍后重试"), opts))
        assertTrue(shouldRetryError(IOException("HTTP 429 rate limit exceeded"), opts))
        assertTrue(shouldRetryError(IOException("request timeout"), opts))
        // 命中出厂停止词 → 绝不重试（余额/额度/密钥类，重试没有意义）
        assertFalse(shouldRetryError(IOException("账户余额不足"), opts))
        assertFalse(shouldRetryError(IOException("insufficient quota"), opts))
        assertFalse(shouldRetryError(IOException("invalid api key"), opts))
    }

    @Test
    fun defaultStatusCodesDriveRetryDecisions() {
        val opts = AutoRetryOptions()
        assertTrue(shouldRetryError(IOException("HTTP 425 Too Early"), opts))
        assertTrue(shouldRetryError(IOException("HTTP 529 Overloaded"), opts))
        assertFalse(shouldRetryError(IOException("HTTP 409 Conflict"), opts))
    }
}
