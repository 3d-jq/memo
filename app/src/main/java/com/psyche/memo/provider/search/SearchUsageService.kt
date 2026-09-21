package com.psyche.memo.provider.search

import com.psyche.memo.data.model.LinkUpOptions
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.TavilyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Port of search_service_usage_service.dart — credit/usage lookup for the two
 * providers that expose one (Tavily / LinkUp). Response parsing is pure so
 * fixtures cover it; the fetch retries once on connection-level failures with
 * the upstream 200ms / 600ms backoff.
 *
 * **[fetch] 是 `suspend` 且自己切到 `Dispatchers.IO` 的**：它内部是
 * OkHttp 的阻塞 `execute()`。以前它是个普通函数、调用点直接写在
 * `rememberCoroutineScope().launch { … }`（= 主线程）里 —— 真机上点「查询用量」
 * 必炸 `NetworkOnMainThreadException`（2026-09-16 设备日志：`[REQ 22] GET
 * https://api.tavily.com/usage` → `[RES 22] error=NetworkOnMainThreadException`，
 * 用户连点 6 次都这样）。Dart 侧是 async http，本来就不在主线程上。
 * 别把它改回普通函数、也别让调用点在主线程直接调它。
 */
object SearchUsageService {

    data class UsageInfo(val remaining: Double, val used: Double? = null, val limit: Double? = null)

    class UsageException(message: String) : Exception(message)

    fun supports(options: SearchServiceOptions): Boolean =
        options is TavilyOptions || options is LinkUpOptions

    suspend fun fetch(
        options: SearchServiceOptions,
        client: OkHttpClient,
        timeoutMs: Int = 10_000,
    ): UsageInfo = withContext(Dispatchers.IO) {
        val url: String
        val key: String
        when (options) {
            is TavilyOptions -> {
                url = tavilyUsageUrl(options.resolvedUrl)
                key = options.apiKey.trim()
            }
            is LinkUpOptions -> {
                url = "https://api.linkup.so/v1/credits/balance"
                key = options.apiKey.trim()
            }
            else -> throw UsageException("Usage query is not supported for this search provider")
        }

        var lastError: Exception? = null
        val delays = longArrayOf(200, 600)
        for (attempt in 0..delays.size) {
            if (attempt > 0) {
                // delay()（不是 Thread.sleep）：既不占用线程，也只在这个 IO 上下文里等。
                delay(delays[attempt - 1])
            }
            try {
                val request = Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()
                val call = client.newCall(request)
                call.timeout().timeout(timeoutMs.toLong().coerceAtLeast(1000L), java.util.concurrent.TimeUnit.MILLISECONDS)
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw UsageException("Usage request failed (HTTP ${response.code})")
                    }
                    return@withContext when (options) {
                        is TavilyOptions -> parseTavilyUsage(body)
                            ?: throw UsageException("The provider returned an invalid usage response")
                        else -> parseLinkUpUsage(body)
                            ?: throw UsageException("The provider returned an invalid usage response")
                    }
                }
            } catch (e: UsageException) {
                throw e
            } catch (e: IOException) {
                // Connection-level failures retry; anything else surfaces.
                val message = (e.message ?: "").lowercase()
                val retryable = message.contains("timeout") ||
                    message.contains("connection") ||
                    message.contains("reset") ||
                    message.contains("handshake") ||
                    message.contains("socket")
                lastError = e
                if (!retryable || attempt == delays.size) {
                    throw UsageException(e.toString())
                }
            }
        }
        throw UsageException(lastError?.toString() ?: "usage request failed")
    }

    /** Tavily: swap the trailing `search` segment for `usage`. */
    fun tavilyUsageUrl(resolvedUrl: String): String {
        val uri = runCatching { java.net.URI(resolvedUrl) }.getOrNull()
            ?: return "https://api.tavily.com/usage"
        val segments = uri.path?.split("/")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
        if (segments.isEmpty()) {
            segments.add("usage")
        } else if (segments.last() == "search") {
            segments[segments.size - 1] = "usage"
        } else {
            segments.add("usage")
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port/${segments.joinToString("/")}"
    }

    /** Tavily usage body: account.plan_usage/plan_limit, else key.usage/key.limit. */
    fun parseTavilyUsage(body: String): UsageInfo? {
        val root = parse(body) ?: return null
        var used: Double? = null
        var limit: Double? = null
        val account = root["account"] as? JsonObject
        if (account != null) {
            used = num(account["plan_usage"])
            limit = num(account["plan_limit"])
        }
        if (used == null || limit == null) {
            val key = root["key"] as? JsonObject
            if (key != null) {
                used = num(key["usage"])
                limit = num(key["limit"])
            }
        }
        if (used == null || limit == null) return null
        return UsageInfo(remaining = (limit - used).coerceAtLeast(0.0), used = used, limit = limit)
    }

    /** LinkUp credits: { balance }. */
    fun parseLinkUpUsage(body: String): UsageInfo? {
        val root = parse(body) ?: return null
        val balance = num(root["balance"]) ?: return null
        return UsageInfo(remaining = balance)
    }

    private fun parse(body: String): JsonObject? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun num(el: kotlinx.serialization.json.JsonElement?): Double? =
        (el as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
}
