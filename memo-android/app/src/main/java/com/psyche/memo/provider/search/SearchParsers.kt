package com.psyche.memo.provider.search

import com.psyche.memo.data.model.SearchResult
import com.psyche.memo.data.model.SearchResultItem
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Response mappers for the ported search providers — pure functions over the
 * vendor payloads so they unit-test against fixtures without a network.
 * Each mirrors its Dart counterpart field-for-field.
 */
object SearchParsers {

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement.arr(): JsonArray? = this as? JsonArray
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    fun parseJson(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).obj() }.getOrNull()

    // ---- JSON providers ----

    /** Tavily: { answer?, results[] { title, url, content } } */
    fun parseTavily(body: String): SearchResult? {
        val root = parseJson(body) ?: return null
        val items = root["results"]?.arr()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SearchResultItem(
                title = o.str("title") ?: "",
                url = o.str("url") ?: "",
                text = o.str("content") ?: "",
            )
        } ?: return null
        return SearchResult(answer = root.str("answer"), items = items)
    }

    /** SearXNG: { results[] { title, url, content } } */
    fun parseSearxng(body: String, limit: Int): SearchResult? {
        val root = parseJson(body) ?: return null
        val items = root["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SearchResultItem(
                title = o.str("title") ?: "",
                url = o.str("url") ?: "",
                text = o.str("content") ?: "",
            )
        } ?: return null
        return SearchResult(items = items)
    }

    /** Brave web: { web: { results[] { title, url, description } } } */
    fun parseBraveWeb(body: String): SearchResult? {
        val root = parseJson(body) ?: return null
        val results = root["web"]?.obj()?.get("results")?.arr() ?: return SearchResult(items = emptyList())
        val items = results.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SearchResultItem(
                title = o.str("title") ?: "",
                url = o.str("url") ?: "",
                text = o.str("description") ?: "",
            )
        }
        return SearchResult(items = items)
    }

    /** Brave llm/context: grounding.generic[] { title, url, snippets[] } */
    fun parseBraveLlmContext(body: String, limit: Int): SearchResult? {
        val root = parseJson(body) ?: return null
        val generic = root["grounding"]?.obj()?.get("generic")?.arr() ?: return SearchResult(items = emptyList())
        val items = generic.take(limit).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val snippets = o["snippets"]?.arr()
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("\n\n")
                ?: ""
            SearchResultItem(
                title = o.str("title") ?: "",
                url = o.str("url") ?: "",
                text = snippets,
            )
        }
        return SearchResult(items = items)
    }

    /** Serper: { organic[] { title, link, snippet } } */
    fun parseSerper(body: String, limit: Int): SearchResult? {
        val root = parseJson(body) ?: return null
        val organic = root["organic"]?.arr() ?: return SearchResult(items = emptyList())
        val items = organic.take(limit).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SearchResultItem(
                title = o.str("title") ?: "",
                url = o.str("link") ?: "",
                text = o.str("snippet") ?: "",
            )
        }
        return SearchResult(items = items)
    }

    /** Bocha: { code, data: { webPages: { value[] { name, url, summary|snippet } } } } */
    fun parseBocha(body: String, limit: Int): BochaResult {
        val root = parseJson(body) ?: return BochaResult(null, null, emptyList())
        val code = root.int("code")
        if (code != 200) return BochaResult(code, "API error code: $code", emptyList())
        val value = root["data"]?.obj()?.get("webPages")?.obj()?.get("value")?.arr()
            ?: return BochaResult(code, null, emptyList())
        val items = value.take(limit).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SearchResultItem(
                title = o.str("name") ?: "",
                url = o.str("url") ?: "",
                text = (o.str("summary") ?: o.str("snippet") ?: ""),
            )
        }
        return BochaResult(code, null, items)
    }

    data class BochaResult(val code: Int?, val error: String?, val items: List<SearchResultItem>)

    /** Zhipu: { search_result[] { title, link, content } } */
    fun parseZhipu(body: String): SearchResult? {
        val root = parseJson(body) ?: return null
        val results = root["search_result"]?.arr() ?: return SearchResult(items = emptyList())
        val items = results.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SearchResultItem(
                title = o.str("title") ?: "",
                url = o.str("link") ?: "",
                text = o.str("content") ?: "",
            )
        }
        return SearchResult(items = items)
    }

    // ---- HTML providers ----

    /** Bing local scrape: li.b_algo → h2 / h2 > a[href] / .b_caption p, .b_algoSlug */
    fun parseBingHtml(html: String, limit: Int): SearchResult {
        val doc = Jsoup.parse(html)
        val items = ArrayList<SearchResultItem>()
        for (element in doc.select("li.b_algo").take(limit)) {
            val titleElement = element.selectFirst("h2") ?: continue
            val linkElement = element.selectFirst("h2 > a") ?: continue
            val snippetElement = element.selectFirst(".b_caption p, .b_algoSlug")
            items.add(
                SearchResultItem(
                    title = titleElement.text().trim(),
                    url = linkElement.attr("href"),
                    text = snippetElement?.text()?.trim() ?: "",
                ),
            )
        }
        return SearchResult(items = items)
    }

    /** DuckDuckGo html endpoint: .result → .result__a / .result__url / .result__snippet */
    fun parseDuckDuckGoHtml(html: String, limit: Int): SearchResult {
        val doc: Document = Jsoup.parse(html)
        val items = ArrayList<SearchResultItem>()
        for (result in doc.select(".result").take(limit)) {
            val titleElement = result.selectFirst(".result__a")
            val urlElement = result.selectFirst(".result__url")
            val snippetElement = result.selectFirst(".result__snippet")
            val title = titleElement?.text()?.trim() ?: ""
            val rawUrl = titleElement?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
                ?: urlElement?.text()?.trim()
                ?: ""
            val url = resolveDuckDuckGoUrl(rawUrl)
            val snippet = snippetElement?.text()?.trim() ?: ""
            if (title.isEmpty() && url.isEmpty() && snippet.isEmpty()) continue
            items.add(SearchResultItem(title = title, url = url, text = snippet))
        }
        return SearchResult(items = items)
    }

    /** ddg redirect unwrap: //duckduckgo.com/l/?uddg=<target> → target */
    fun resolveDuckDuckGoUrl(raw: String): String {
        if (raw.isEmpty()) return raw
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching {
            val uri = java.net.URI(normalized)
            val query = uri.rawQuery ?: return@runCatching normalized
            for (pair in query.split("&")) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                if (pair.substring(0, idx) == "uddg") {
                    return@runCatching java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }
            normalized
        }.getOrDefault(normalized)
    }

    // ---- URL normalization (SearchToolService._normalizeUrl) ----

    private val schemeRe = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return u
        if ((u.startsWith("\"") && u.endsWith("\"")) || (u.startsWith("'") && u.endsWith("'"))) {
            u = u.substring(1, u.length - 1).trim()
        }
        if (u.isEmpty()) return u
        if (u.startsWith("//")) return "https:$u"
        if (!schemeRe.containsMatchIn(u)) return "https://$u"
        return u
    }
}
