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

    /**
     * 错误分类（超出上游的加法，见 `generationErrorDisplayText` 的文档）：
     * 判据要盖住各家措辞，也要**不误判**——裸数字不是状态码。
     */
    @Test
    fun classifiesProviderFailures() {
        // 用户 2026-09-20 真机那条 400 原文。
        assertEquals(
            GenerationErrorKind.UnsupportedImage,
            classifyGenerationError(
                "HTTP 400 {\"error\":{\"message\":\".messages[7].image[0]: You have uploaded an " +
                    "unsupported image. Please make sure your image is valid and has one of the " +
                    "following formats: webp, png, jpeg, and gif.\"}}",
            ),
        )
        assertEquals(
            GenerationErrorKind.RateLimited,
            classifyGenerationError("HTTP 429 Too Many Requests"),
        )
        assertEquals(
            GenerationErrorKind.Quota,
            classifyGenerationError("HTTP 400 {\"msg\":\"Insufficient Account Balance\"}"),
        )
        assertEquals(
            GenerationErrorKind.ContextLength,
            classifyGenerationError(
                "This model's maximum context length is 8192 tokens, reduce the length of the input",
            ),
        )
        assertEquals(
            GenerationErrorKind.ContentModeration,
            classifyGenerationError("HTTP 400 data_inspection_failed"),
        )
        assertEquals(
            GenerationErrorKind.Auth,
            classifyGenerationError("HTTP 401 {\"error\":{\"code\":\"invalid_api_key\"}}"),
        )
        assertEquals(
            GenerationErrorKind.Timeout,
            classifyGenerationError("java.net.SocketTimeoutException: timeout"),
        )
        // 各家状态码写法都要认得。
        assertEquals(
            GenerationErrorKind.RateLimited,
            classifyGenerationError("{\"status_code\":429,\"error\":{\"message\":\"\"}}"),
        )
    }

    /** 判不出就**别硬归类**：宁可只给原始信息，也不给用户一句错的中文。 */
    @Test
    fun leavesUnknownFailuresUnclassified() {
        assertEquals(null, classifyGenerationError("boom"))
        // 裸数字（token 数、请求 id）不能被当状态码误判。
        assertEquals(null, classifyGenerationError("processed 4290 tokens in 402 ms, code 4133"))
        assertEquals(null, classifyGenerationError("HTTP 500 internal server error"))
    }
}
