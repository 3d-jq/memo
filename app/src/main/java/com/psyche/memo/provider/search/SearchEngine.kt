package com.psyche.memo.provider.search

import com.psyche.memo.data.model.AnySearchOptions
import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.DoubaoOptions
import com.psyche.memo.data.model.ExaOptions
import com.psyche.memo.data.model.FirecrawlOptions
import com.psyche.memo.data.model.GrokOptions
import com.psyche.memo.data.model.JinaOptions
import com.psyche.memo.data.model.LinkUpOptions
import com.psyche.memo.data.model.MetasoOptions
import com.psyche.memo.data.model.OllamaOptions
import com.psyche.memo.data.model.ParallelOptions
import com.psyche.memo.data.model.PerplexityOptions
import com.psyche.memo.data.model.QueritOptions
import com.psyche.memo.data.model.StepFunOptions
import com.psyche.memo.data.model.TinyFishOptions
import com.psyche.memo.data.model.YouSearchOptions
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
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
            is ExaOptions -> exa(query, options, common)
            is LinkUpOptions -> linkUp(query, options, common)
            is MetasoOptions -> metaso(query, options, common)
            is OllamaOptions -> ollama(query, options, common)
            is JinaOptions -> jina(query, options, common)
            is PerplexityOptions -> perplexity(query, options, common)
            is QueritOptions -> querit(query, options, common)
            is StepFunOptions -> stepFun(query, options, common)
            is FirecrawlOptions -> firecrawl(query, options, common)
            is TinyFishOptions -> tinyFish(query, options, common)
            is AnySearchOptions -> anySearch(query, options, common)
            is DoubaoOptions -> doubao(query, options, common)
            is ParallelOptions -> parallel(query, options, common)
            is YouSearchOptions -> you(query, options, common)
            is GrokOptions -> grok(query, options, common)
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

    // ---- second batch: exa / linkup / metaso / ollama / jina / perplexity /
    //      querit / stepfun / firecrawl / tinyfish / anysearch / doubao /
    //      parallel / you / grok ----

    private fun exa(query: String, options: ExaOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("query", query)
            put("numResults", common.resultSize)
            putJsonObject("contents") { put("text", true) }
        }
        val response = postJson(
            options.resolvedUrl,
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseExa(response, common.resultSize)
            ?: throw SearchException("Exa search failed: invalid response")
    }

    private fun linkUp(query: String, options: LinkUpOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("q", query)
            put("depth", "standard")
            put("outputType", "sourcedAnswer")
            put("includeImages", "false")
        }
        val response = postJson(
            "https://api.linkup.so/v1/search",
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseLinkUp(response, common.resultSize)
            ?: throw SearchException("LinkUp search failed: invalid response")
    }

    private fun metaso(query: String, options: MetasoOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("q", query)
            put("scope", "webpage")
            put("size", common.resultSize)
            put("includeSummary", false)
        }
        val response = postJson(
            "https://metaso.cn/api/v1/search",
            mapOf(
                "Authorization" to "Bearer ${effectiveKey(options)}",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseMetaso(response, common.resultSize)
            ?: throw SearchException("Metaso search failed: invalid response")
    }

    private fun ollama(query: String, options: OllamaOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("query", query)
            put("max_results", common.resultSize.coerceIn(1, 10))
        }
        val response = postJson(
            "https://ollama.com/api/web_search",
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseOllama(response, common.resultSize)
            ?: throw SearchException("Ollama search failed: invalid response")
    }

    private fun jina(query: String, options: JinaOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject { put("q", query) }
        val response = postJson(
            "https://s.jina.ai/",
            mapOf(
                "Authorization" to "Bearer ${effectiveKey(options)}",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
            body,
            maxOf(15000, common.timeout),
        )
        return SearchProviderParsers.parseJina(response, common.resultSize)
            ?: throw SearchException("Jina search failed: invalid response")
    }

    private fun perplexity(query: String, options: PerplexityOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("query", query)
            put("max_results", common.resultSize.coerceIn(1, 20))
            options.country?.trim()?.takeIf { it.isNotEmpty() }?.let { put("country", it) }
            options.searchDomainFilter?.takeIf { it.isNotEmpty() }?.let { list ->
                put("search_domain_filter", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } })
            }
            options.maxTokensPerPage?.let { put("max_tokens_per_page", it) }
        }
        val response = postJson(
            "https://api.perplexity.ai/search",
            mapOf("Authorization" to "Bearer ${effectiveKey(options)}", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parsePerplexity(response, common.resultSize)
            ?: throw SearchException("Perplexity search failed: invalid response")
    }

    private fun querit(query: String, options: QueritOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        if (key.isEmpty()) throw SearchException("Querit API key is required")
        val body = buildJsonObject {
            put("query", query)
            put("count", common.resultSize)
            SearchProviderParsers.queritFilters(options)?.let { put("filters", it) }
        }
        val response = postJson(
            "https://api.querit.ai/v1/search",
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        val parsed = SearchProviderParsers.parseQuerit(response, common.resultSize)
        parsed.error?.let { throw SearchException(it) }
        return SearchResult(items = parsed.items)
    }

    private fun stepFun(query: String, options: StepFunOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        if (key.isEmpty()) throw SearchException("StepFun API key is required")
        val body = buildJsonObject {
            put("query", query)
            put("n", common.resultSize.coerceIn(1, 20))
            options.category.trim().takeIf { it.isNotEmpty() }?.let { put("category", it) }
        }
        val response = postJson(
            options.resolvedUrl,
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json; charset=utf-8"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseStepFun(response, common.resultSize)
            ?: throw SearchException("StepFun search failed: invalid response")
    }

    private fun firecrawl(query: String, options: FirecrawlOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        val body = buildJsonObject {
            put("query", query)
            put("limit", common.resultSize.coerceIn(1, 100))
            if (options.sources.isNotEmpty()) {
                put("sources", buildJsonArray { options.sources.forEach { add(buildJsonObject { put("type", it) }) } })
            }
            if (options.categories.isNotEmpty()) {
                put("categories", buildJsonArray { options.categories.forEach { add(buildJsonObject { put("type", it) }) } })
            }
            options.country.trim().takeIf { it.isNotEmpty() }?.let { put("country", it) }
            options.location.trim().takeIf { it.isNotEmpty() }?.let { put("location", it) }
        }
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/json"
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        val response = postJson(options.resolvedUrl, headers, body, common.timeout)
        return SearchProviderParsers.parseFirecrawl(response, common.resultSize)
            ?: throw SearchException("Firecrawl search failed: invalid response")
    }

    private fun tinyFish(query: String, options: TinyFishOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        if (key.isEmpty()) throw SearchException("TinyFish API key is required")
        val params = LinkedHashMap<String, String>()
        params["query"] = query
        options.location.trim().takeIf { it.isNotEmpty() }?.let { params["location"] = it }
        options.language.trim().takeIf { it.isNotEmpty() }?.let { params["language"] = it }
        options.includeDomains.trim().takeIf { it.isNotEmpty() }?.let { params["include_domains"] = it }
        options.excludeDomains.trim().takeIf { it.isNotEmpty() }?.let { params["exclude_domains"] = it }
        val url = options.resolvedUrl + "?" + params.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val response = get(url, mapOf("X-API-Key" to key), common.timeout)
        return SearchProviderParsers.parseTinyFish(response, common.resultSize)
            ?: throw SearchException("TinyFish search failed: invalid response")
    }

    private fun anySearch(query: String, options: AnySearchOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        val body = buildJsonObject {
            put("query", query)
            put("max_results", common.resultSize.coerceIn(1, 20))
            put("format", "json")
        }
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/json"
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        val response = postJson(options.resolvedUrl, headers, body, common.timeout)
        val parsed = SearchProviderParsers.parseAnySearch(response, common.resultSize)
        parsed.error?.let { throw SearchException(it) }
        return SearchResult(items = parsed.items)
    }

    private fun doubao(query: String, options: DoubaoOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        if (key.isEmpty()) throw SearchException("Doubao API key is required")
        val body = buildJsonObject {
            put("Query", query)
            put("SearchType", "web")
            put("Count", common.resultSize.coerceIn(1, 50))
            putJsonObject("Filter") { put("NeedUrl", true) }
        }
        val response = postJson(
            "https://open.feedcoopapi.com/search_api/web_search",
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json; charset=utf-8"),
            body,
            common.timeout,
        )
        val parsed = SearchProviderParsers.parseDoubao(response)
        parsed.error?.let { throw SearchException(it) }
        return SearchResult(items = parsed.items)
    }

    private fun parallel(query: String, options: ParallelOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("objective", query)
            put("search_queries", buildJsonArray { add(JsonPrimitive(query)) })
            put("mode", options.mode)
        }
        val response = postJson(
            "https://api.parallel.ai/v1/search",
            mapOf("x-api-key" to effectiveKey(options), "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseParallel(response, common.resultSize)
            ?: throw SearchException("Parallel search failed: invalid response")
    }

    private fun you(query: String, options: YouSearchOptions, common: SearchCommonOptions): SearchResult {
        val body = buildJsonObject {
            put("query", query)
            put("count", common.resultSize)
            if (options.contentMode == YouSearchOptions.HIGHLIGHTS_MODE) {
                putJsonObject("extraction") { put("extraction_mode", "highlights") }
            }
        }
        val response = postJson(
            "https://ydc-index.io/v1/search",
            mapOf("X-API-Key" to effectiveKey(options), "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseYou(response, common.resultSize)
            ?: throw SearchException("You.com search failed: invalid response")
    }

    private fun grok(query: String, options: GrokOptions, common: SearchCommonOptions): SearchResult {
        val key = effectiveKey(options).trim()
        if (key.isEmpty()) throw SearchException("Grok API key is required")
        val body = buildJsonObject {
            put("model", options.resolvedModel)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", options.resolvedSystemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", query)
                })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject { put("type", "web_search") })
                add(buildJsonObject { put("type", "x_search") })
            })
            put("store", false)
            put("stream", false)
            options.reasoningEffort.trim().takeIf { it.isNotEmpty() }?.let { effort ->
                putJsonObject("reasoning") { put("effort", effort) }
            }
        }
        val response = postJson(
            options.resolvedUrl,
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
            body,
            common.timeout,
        )
        return SearchProviderParsers.parseGrok(response, common.resultSize)
            ?: throw SearchException("Grok search failed: invalid response")
    }
}
