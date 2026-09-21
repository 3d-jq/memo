package com.psyche.memo.llm.provider

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response

/**
 * 非 2xx 响应的失败异常 —— `HTTP <code> <响应体>`。
 *
 * 原版 `chat_api_service.dart` / 三个 provider（`openai_provider.dart:669`、
 * `claude_official.dart:393`、`google_common.dart:763`）抛的都是
 * `HttpException('HTTP ${statusCode}: $errorBody')`：**状态码后面一定带响应体**。
 * 我们三处**流式**路径此前只抛 `IOException("HTTP ${code}")`，于是 429 / 5xx 的真正原因
 * （限流说明、余额不足、模型名错、上下文超长…全在 body 里）被丢掉，用户只看到
 * 「java.io.IOException: HTTP 429」这种既裸又无从下手的文案（用户 2026-09-16
 * 「怎么还是会裸出 java.io.IOException: HTTP 429 这样的报错呀」）。
 *
 * 响应体截到 [ERROR_BODY_LIMIT]：有些网关 429 会回一整页 HTML，整页塞进气泡既没意义
 * 又把消息撑爆；截断后仍保留最前面的关键信息。
 */
internal suspend fun httpFailure(response: Response): IOException {
    val body = runCatching {
        withContext(Dispatchers.IO) { response.body?.string() }
    }.getOrNull()
    val detail = body?.trim().orEmpty().take(ERROR_BODY_LIMIT)
    return IOException("HTTP ${response.code}" + if (detail.isEmpty()) "" else " $detail")
}

/** 错误体保留的最大字符数（见 [httpFailure]）。 */
private const val ERROR_BODY_LIMIT = 500
