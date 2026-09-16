package com.psyche.memo.ui.chat

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡里的失败文案：去掉 Kotlin 异常类名那层壳，其余原样（见 `generationErrorText`）。
 *
 * 用户 2026-09-16 报的就是 `java.io.IOException: HTTP 429` 这种裸文案。
 */
class GenerationErrorTextTest {

    private class Custom(val text: String) : Exception(text) {
        override fun toString(): String = text
    }

    @Test
    fun stripsTheJavaExceptionClassName() {
        assertEquals(
            "HTTP 429 {\"error\":\"rate limited\"}",
            generationErrorText(IOException("HTTP 429 {\"error\":\"rate limited\"}")),
        )
        assertEquals("HTTP 503", generationErrorText(IOException("HTTP 503")))
    }

    @Test
    fun stripsOtherJavaAndOkhttpPrefixes() {
        assertEquals(
            "Unable to resolve host \"api.example.com\"",
            generationErrorText(UnknownHostException("Unable to resolve host \"api.example.com\"")),
        )
        assertEquals(
            "PKIX path building failed",
            generationErrorText(javax.net.ssl.SSLHandshakeException("PKIX path building failed")),
        )
        // 只剥**一层**：真实的 OkHttp 异常是直接抛出的，toString() 本身就一层类名；
        // 这里把 IOException 包在外面只是为了让 toString() 有前缀可剥（一层）。
        assertEquals(
            "okhttp3.internal.http2.StreamResetException: stream was reset: CANCEL",
            generationErrorText(
                java.io.IOException("okhttp3.internal.http2.StreamResetException: stream was reset: CANCEL"),
            ),
        )
    }

    /** 正文里带冒号的普通错误**不能**被当成类名砍掉。 */
    @Test
    fun keepsMessagesThatOnlyLookLikeAPrefix() {
        assertEquals("HTTP 429: rate limited", generationErrorText(Custom("HTTP 429: rate limited")))
        assertEquals("boom", generationErrorText(Custom("boom")))
    }

    /** 类名后面没内容时退回完整 toString()，别给空字符串。 */
    @Test
    fun fallsBackToFullTextWhenNothingFollowsTheClassName() {
        val bare = Custom("")
        assertEquals(bare.toString(), generationErrorText(bare))
    }
}
