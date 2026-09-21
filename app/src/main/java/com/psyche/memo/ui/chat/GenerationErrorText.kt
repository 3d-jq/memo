package com.psyche.memo.ui.chat

import android.content.Context
import androidx.annotation.StringRes
import com.psyche.memo.ui.R as UiR

/**
 * 终止失败时进气泡的那行错误文案。
 *
 * 原版（`chat_actions.dart:2605`）取的是 `e.toString()`：Dart 侧长这样
 * `HttpException: HTTP 429: {...}, uri = https://…`；Kotlin 侧同样取 `toString()` 会
 * 多一层 **Java 异常类名** —— `java.io.IOException: HTTP 429 {...}`，读起来像崩溃日志
 *（用户 2026-09-16「先怎么还是会裸出 java.io.IOException: HTTP 429 这样的报错呀」）。
 *
 * 规则（只做减法，**不吞信息**）：
 * - 去掉开头那层 `包.类 Exception/Error: ` 前缀（`java.io.IOException`、
 *   `javax.net.ssl.SSLHandshakeException`、`okhttp3.internal.http2.StreamResetException`…），
 *   剩下的正文原样保留；
 * - 前缀后面没内容（`java.io.IOException`（无 message））时退回完整 `toString()`，
 *   免得给用户一个空字符串；
 * - 其他形态（自定义异常、协程取消说明等）一律原样。
 */
internal fun generationErrorText(error: Throwable): String {
    val full = error.toString()
    val body = JAVA_EXCEPTION_PREFIX.find(full)?.let { full.substring(it.value.length) }?.trim()
    return if (body.isNullOrEmpty()) full else body
}

/**
 * 面向用户的完整错误行：**第一行中文说明（能判出是哪类问题时）＋ 第二行原始信息**。
 *
 * 这是**有意超出上游**：上游只把 `e.toString()` 原样甩进气泡（`chat_actions.dart:2605`），
 * 中文用户看不懂（2026-09-20 用户「一单大模型侧出问题全是英文结果呀，报错结果也不太友好，
 * 看不懂」）。原始信息留在第二行是因为它常带唯一线索（哪个字段、超额多少）；完整响应体
 * 另外还会进请求日志。
 */
internal fun generationErrorDisplayText(context: Context, error: Throwable): String {
    val raw = generationErrorText(error)
    val kind = classifyGenerationError(raw) ?: return raw
    return context.getString(generationErrorSummaryRes(kind)) + "\n" + raw
}

/** 能判出类别的错误 —— 每类对应一句中文说明。 */
internal enum class GenerationErrorKind {
    ContextLength,
    UnsupportedImage,
    ContentModeration,
    Quota,
    RateLimited,
    Auth,
    Timeout,
}

/**
 * 从错误文本判出「哪类问题」；判不出返回 null。
 *
 * 判据同时看**状态码**与**厂商原文关键词**：各家措辞不一（OpenAI `rate limit`、智谱
 * `Insufficient Account Balance`、阿里 `data_inspection_failed`、DeepSeek
 * `Reduce your prompt length`），覆盖面从窄到宽排：上下文 → 图片 → 审核 → 余额 →
 * 限流 → 鉴权 → 超时。
 */
internal fun classifyGenerationError(text: String): GenerationErrorKind? {
    val s = text.lowercase()
    fun has(vararg needles: String) = needles.any { s.contains(it) }
    // 只认「HTTP 429」「status_code":429」这类**带上下文的状态码**写法。
    // 裸数字正则会误伤：错误体里的 token 数、请求 id 都能凑出一个 413。
    fun status(code: Int) = has(
        "http $code",
        "status $code",
        "status_code\":$code",
        "error_code\":$code",
        "$code client error",
        "$code server error",
    )
    return when {
        has(
            "context length", "maximum context", "context window", "context_length",
            "too many tokens", "reduce the length", "reduce your prompt", "prompt is too long",
            "input length",
        ) || status(413) -> GenerationErrorKind.ContextLength

        has("unsupported image", "invalid image", "image format", "uploaded an unsupported") ->
            GenerationErrorKind.UnsupportedImage

        has(
            "content_policy", "content policy", "content filter", "moderation", "data_inspection",
            "safety", "risk content", "flagged",
        ) -> GenerationErrorKind.ContentModeration

        has("insufficient", "balance", "quota", "arrears", "not enough credit") ||
            status(402) -> GenerationErrorKind.Quota

        has("rate limit", "rate_limit", "too many requests", "throttl", "requests per", "slow down") ||
            status(429) -> GenerationErrorKind.RateLimited

        has(
            "invalid api key", "invalid_api_key", "unauthorized", "authentication",
            "incorrect api key", "api key is invalid", "no api key",
        ) || status(401) || status(403) -> GenerationErrorKind.Auth

        has("timeout", "timed out", "deadline exceeded", "sockettimeout") ->
            GenerationErrorKind.Timeout

        else -> null
    }
}

@StringRes
internal fun generationErrorSummaryRes(kind: GenerationErrorKind): Int = when (kind) {
    GenerationErrorKind.ContextLength -> UiR.string.generation_error_context_length
    GenerationErrorKind.UnsupportedImage -> UiR.string.generation_error_unsupported_image
    GenerationErrorKind.ContentModeration -> UiR.string.generation_error_content_moderation
    GenerationErrorKind.Quota -> UiR.string.generation_error_quota
    GenerationErrorKind.RateLimited -> UiR.string.generation_error_rate_limited
    GenerationErrorKind.Auth -> UiR.string.generation_error_auth
    GenerationErrorKind.Timeout -> UiR.string.generation_error_timeout
}

/**
 * `java.io.IOException: ` / `javax.net.ssl.SSLHandshakeException: ` 这类前缀。
 * 要求前缀是「点分小写包名 + 大驼峰类名 + Exception/Error + 冒号空格」，避免把正文里
 * 恰好带冒号的普通错误（`HTTP 429: rate limited`）误当类名砍掉。
 */
private val JAVA_EXCEPTION_PREFIX =
    Regex("""^(?:[a-z][a-zA-Z0-9_]*\.)+[A-Z][a-zA-Z0-9_]*(?:Exception|Error): """)
