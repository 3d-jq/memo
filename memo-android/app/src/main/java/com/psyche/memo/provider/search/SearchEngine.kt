package com.psyche.memo.provider.search

import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.BochaOptions
import com.psyche.memo.data.model.BraveOptions
import com.psyche.memo.data.model.DuckDuckGoOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchResult
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.SearXNGOptions
import com.psyche.memo.data.model.SerperOptions
import com.psyche.memo.data.model.TavilyOptions
import com.psyche.memo.data.model.ZhipuOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Thrown for any provider-side failure; the tool turns it into tool JSON. */
class SearchException(message: String) : Exception(message)

interface SearchEngine {
    suspend fun search(
        query: String,
        options: SearchServiceOptions,
        common: SearchCommonOptions,
    ): SearchResult
}

/**
 * HTTP search dispatch — port of the ported subset of
 * core/services/search/providers (per-service files). Providers are added incrementally;
 * an unported type reports an honest error (mirrors ToolHandler's approach).
 */
class HttpSearchEngine(private val client: OkHttpClient) : SearchEngine {

    override suspend fun search(
        query: String,
        options: SearchServiceOptions,
        common: SearchCommonOptions,
    ): SearchResult = withContext(Dispatchers.IO) {
        when (options) {
            is BingLocalOptions -> bingLocal(query, options, common)
            is TavilyOptions -> tavily(query, options, common)
            is SearXNGOptions -> searxng(query, options, common)
            is BraveOptions -> brave(query, options, common)
            is SerperOptions -> serper(query, options, common)
            is BochaOptions -> bocha(query, options, common)
            is ZhipuOptions -> zhipu(query, options, common)
            is DuckDuckGoOptions -> duckduckgo(query, options, common)
            else -> throw SearchException(
                "Search service type '${options.toJson()["type"]}' has no engine on this platform.",
            )
        }
    }

    private fun effectiveKey(options: SearchServiceOptions): String =
        SearchApiKeyRotator.select(options.id, options.primaryApiKey, options.extraApiKeys)

    private fun execute(request: Request, timeoutMs: Int): String {
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs.toLong().coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SearchException("API request failed: ${response.code}")
                }
                body
            }
        } catch (e: SearchException) {
            throw e
        } catch (e: IOException) {
            throw SearchException(e.message ?: e.toString())
        }
    }

    private fun get(url: String, headers: Map<String, String>, timeoutMs: Int): String =
        execute(
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build(),
            timeoutMs,
        )

    private fun postJson(url: String, headers: Map<String, String>, body: JsonObject, timeoutMs: Int): String =
        execute(
            Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
            timeoutMs,
        )

    private fun bingLocal(query: String, options: BingLocalOptions, common: SearchCommonOptions): SearchResult {
        val url = "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = get(
            url,
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept-Language" to options.acceptLanguage,
            ),
            common.timeout,
        )
        return SearchParsers.parseBingHtml(html, common.resultSize)
    }

    private fun tavily(query: String, options: TavilyOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("query", query)
            put("max_results", common.resultSize)
        }
        val response = postJson(
            options.resolvedUrl,
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchParsers.parseTavily(response)
            ?: throw SearchException("Tavily search failed: invalid response")
    }

    private fun searxng(query: String, options: SearXNGOptions, common: SearchCommonOptions): SearchResult {
        if (options.url.isEmpty()) throw SearchException("SearXNG URL cannot be empty")
        val base = options.url.trim().trimEnd('/')
        var url = "$base/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json"
        if (options.engines.isNotEmpty()) {
            url += "&engines=${java.net.URLEncoder.encode(options.engines, "UTF-8")}"
        }
        if (options.language.isNotEmpty()) {
            url += "&language=${java.net.URLEncoder.encode(options.language, "UTF-8")}"
        }
        val headers = HashMap<String, String>()
        if (options.username.isNotEmpty() && options.password.isNotEmpty()) {
            val auth = Base64.getEncoder().encodeToString("${options.username}:${options.password}".toByteArray())
            headers["Authorization"] = "Basic $auth"
        }
        val response = get(url, headers, common.timeout)
        return SearchParsers.parseSearxng(response, common.resultSize)
            ?: throw SearchException("SearXNG search failed: invalid response")
    }

    private fun brave(query: String, options: BraveOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options)
        return if (options.mode == BraveOptions.LLM_CONTEXT_MODE) {
            val body = buildJsonObject {
                put("q", query)
                put("count", common.resultSize)
                put("maximum_number_of_urls", common.resultSize)
                put("maximum_number_of_tokens", options.maximumNumberOfTokens)
            }
            val response = postJson(
                "https://api.search.brave.com/res/v1/llm/context",
                mapOf(
                    "X-Subscription-Token" to key,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body,
                common.timeout,
            )
            SearchParsers.parseBraveLlmContext(response, common.resultSize)
                ?: throw SearchException("Brave search failed: invalid response")
        } else {
            val url = "https://api.search.brave.com/res/v1/web/search" +
                "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=${common.resultSize}"
            val response = get(
                url,
                mapOf("Accept" to "application/json", "X-Subscription-Token" to key),
                common.timeout,
            )
            SearchParsers.parseBraveWeb(response)
                ?: throw SearchException("Brave search failed: invalid response")
        }
    }

    private fun serper(query: String, options: SerperOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("q", query)
            options.gl.trim().takeIf { it.isNotEmpty() }?.let { put("gl", it) }
            options.hl.trim().takeIf { it.isNotEmpty() }?.let { put("hl", it) }
            options.tbs.trim().takeIf { it.isNotEmpty() }?.let { put("tbs", it) }
            if (options.page > 1) put("page", options.page)
        }
        val response = postJson(
            "https://google.serper.dev/search",
            mapOf("X-API-KEY" to effectiveKey(options), "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchParsers.parseSerper(response, common.resultSize)
            ?: throw SearchException("Serper search failed: invalid response")
    }

    private fun bocha(query: String, options: BochaOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("query", query)
            options.freshness?.takeIf { it.isNotEmpty() }?.let { put("freshness", it) }
            put("summary", options.summary)
            put("count", common.resultSize)
            options.include?.takeIf { it.isNotEmpty() }?.let { put("include", it) }
            options.exclude?.takeIf { it.isNotEmpty() }?.let { put("exclude", it) }
        }
        val response = postJson(
            "https://api.bochaai.com/v1/web-search",
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        val parsed = SearchParsers.parseBocha(response, common.resultSize)
        parsed.error?.let { throw SearchException(it) }
        return SearchResult(items = parsed.items)
    }

    private fun zhipu(query: String, options: ZhipuOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("search_query", query)
            put("search_engine", "search_std")
            put("count", common.resultSize)
        }
        val response = postJson(
            "https://open.bigmodel.cn/api/paas/v4/web_search",
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchParsers.parseZhipu(response)
            ?: throw SearchException("Zhipu search failed: invalid response")
    }

    private fun duckduckgo(query: String, options: DuckDuckGoOptions, common: SearchCommonOptions): SearchResult {
        val region = options.region.trim().ifEmpty { "us-en" }
        val url = "https://duckduckgo.com/html/" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&kl=${java.net.URLEncoder.encode(region, "UTF-8")}"
        val html = get(
            url,
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
            ),
            common.timeout,
        )
        return SearchParsers.parseDuckDuckGoHtml(html, common.resultSize)
    }
}
