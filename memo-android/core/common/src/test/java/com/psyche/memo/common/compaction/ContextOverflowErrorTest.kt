package com.psyche.memo.common.compaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「上下文超长」的判据：只认明确指向长度/容量的说法，普通 400 不能被误判。 */
class ContextOverflowErrorTest {

    @Test
    fun recognisesTheProviderPhrasingsWeHaveActuallySeen() {
        val samples = listOf(
            "Error code: 400 - {'error': {'message': \"This model's maximum context length is 128000 tokens.\"}}",
            "context_length_exceeded",
            "prompt is too long: 210000 tokens > 200000 maximum",
            "The input token count exceeds the maximum number of tokens allowed",
            "400 Bad Request: too many tokens in the messages",
            "Invalid request: input is too long for this model",
            "请缩短输入：超过最大长度",
            "上下文长度超限",
        )
        samples.forEach { message ->
            assertTrue("应当识别为上下文超长：$message", isContextLengthError(message))
        }
    }

    @Test
    fun ignoresUnrelatedFailures() {
        val samples = listOf(
            null,
            "",
            "401 Unauthorized: invalid api key",
            "400 Bad Request: model `gpt-5` does not exist",
            "429 Too Many Requests: rate limit reached",
            "The model returned an empty response",
            "Invalid image format: unsupported image",
        )
        samples.forEach { message ->
            assertFalse("不该被判成上下文超长：$message", isContextLengthError(message))
        }
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(isContextLengthError("MAXIMUM CONTEXT LENGTH is 8192 tokens"))
        assertTrue(isContextLengthError("Context_Length_Exceeded"))
    }
}
