package com.psyche.memo.provider.generation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 生成服务调用失败：message 已是可以直接显示给用户的一句话。 */
class GenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** `Authorization: Bearer <key>`（key 为空时不发，让服务端回 401）。 */
internal fun Request.Builder.auth(apiKey: String): Request.Builder = apply {
    val trimmed = apiKey.trim()
    if (trimmed.isNotEmpty()) header("Authorization", "Bearer $trimmed")
}

/** 单次调用的超时（阻塞式 OkHttp 调用本来就要有界）。 */
internal fun OkHttpClient.callWithTimeout(request: Request, timeoutMs: Long) =
    newCall(request).apply {
        timeout().timeout(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
    }

/**
 * 图片 / 视频生成共用的 HTTP 小工具（自研功能）。
 *
 * 两条规矩：
 *  1. **所有网络调用都是 `suspend` 且自带 `Dispatchers.IO`** —— 与
 *     `SearchUsageService` 那次「主线程发网络必炸 NetworkOnMainThreadException」
 *     的教训一致（见 PORTING §5.18①）；
 *  2. 非 2xx 一律抛 [GenerationException]，message 里带 **HTTP 状态码 + 响应体**
 *     （截断），不要把 OkHttp 的裸异常丢给用户。
 */
internal object GenerationHttp {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 生成接口大多同步等模型出图，给足时间；轮询/下载各自有更合适的值。 */
    const val DEFAULT_TIMEOUT_MS = 180_000L
    const val POLL_TIMEOUT_MS = 30_000L
    const val DOWNLOAD_TIMEOUT_MS = 300_000L

    private const val BODY_LIMIT = 400

    /** 带回退的可读错误：`HTTP 429 {"error":...}`。 */
    fun failure(what: String, response: Response): GenerationException {
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val detail = body.trim().take(BODY_LIMIT)
        return GenerationException(
            if (detail.isEmpty()) "$what failed (HTTP ${response.code})"
            else "$what failed (HTTP ${response.code}): $detail",
        )
    }

    fun ioFailure(what: String, error: IOException): GenerationException =
        GenerationException("$what failed: ${error.message ?: error.toString()}", error)

    fun parseObject(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw GenerationException("The provider returned an invalid response")
        }

    /** 错误信息里响应体的截断长度（顶层 `errorMessage()` 也用）。 */
    const val BODY_LIMIT_PUBLIC = BODY_LIMIT
}

// ---------------------------------------------------------------------------
// 顶层扩展（放在对象里的话，外部调用点必须 `with(GenerationHttp) { … }` ——
// 成员扩展函数没法 import，所以这些一律留在顶层）。
// ---------------------------------------------------------------------------

internal fun JsonObject.str(vararg keys: String): String? {
    for (key in keys) {
        val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

internal fun JsonObject.int(vararg keys: String): Int? {
    for (key in keys) {
        val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.bool(vararg keys: String): Boolean? {
    for (key in keys) {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: continue
        when (raw) {
            "true", "1", "yes" -> return true
            "false", "0", "no" -> return false
        }
    }
    return null
}

/** 从 `error` 字段里抠出一句人话（对象/字符串/数组三种形态都见过）。 */
internal fun JsonObject.errorMessage(): String? {
    val raw = this["error"] ?: this["failure_reason"] ?: this["message"]
    return when (raw) {
        null -> null
        is JsonPrimitive -> raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        else -> runCatching {
            val obj = raw.jsonObject
            obj.str("message", "detail", "reason") ?: obj.toString().take(GenerationHttp.BODY_LIMIT_PUBLIC)
        }.getOrElse {
            runCatching { raw.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
    }
}
