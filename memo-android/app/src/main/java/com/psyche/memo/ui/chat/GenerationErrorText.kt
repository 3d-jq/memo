package com.psyche.memo.ui.chat

/**
 * 终止失败时进气泡的那行错误文案。
 *
 * 原版（`chat_actions.dart:2605`）取的是 `e.toString()`：Dart 侧长这样
 * `HttpException: HTTP 429: {...}, uri = https://…`；Kotlin 侧同样取 `toString()` 会
 * 多一层 **Java 异常类名** —— `java.io.IOException: HTTP 429 {...}`，读起来像崩溃日志
 * （用户 2026-09-16「先怎么还是会裸出 java.io.IOException: HTTP 429 这样的报错呀」）。
 *
 * 规则（只做减法，**不发明文案、不吞信息**）：
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
 * `java.io.IOException: ` / `javax.net.ssl.SSLHandshakeException: ` 这类前缀。
 * 要求前缀是「点分小写包名 + 大驼峰类名 + Exception/Error + 冒号空格」，避免把正文里
 * 恰好带冒号的普通错误（`HTTP 429: rate limited`）误当类名砍掉。
 */
private val JAVA_EXCEPTION_PREFIX =
    Regex("""^(?:[a-z][a-zA-Z0-9_]*\.)+[A-Z][a-zA-Z0-9_]*(?:Exception|Error): """)
