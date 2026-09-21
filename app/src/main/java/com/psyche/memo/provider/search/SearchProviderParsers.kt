package com.psyche.memo.provider.search

import com.psyche.memo.data.model.SearchResult
import com.psyche.memo.data.model.SearchResultItem
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Response mappers for the second batch of search providers (exa / linkup /
 * metaso / ollama / jina / perplexity / querit / stepfun / firecrawl /
 * tinyfish / anysearch / doubao / parallel / you / grok). Pure functions so
 * fixtures can cover them; each mirrors its Dart counterpart field-for-field.
 *
 * KelivoOptions has no mapper: its engine talks to the upstream-hosted
 * endpoint with a built-in token, which the port rules exclude.
 */
object SearchProviderParsers {

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement.arr(): JsonArray? = this as? JsonArray
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun parse(body: String): JsonObject? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).obj() }.getOrNull()

    private fun item(title: String, url: String, text: String) =
        SearchResultItem(title = title, url = url, text = text)

    // ---- exa: results[] { title, url, text } ----
    fun parseExa(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("url") ?: "", o.str("text") ?: "")
        } ?: return SearchResult(items = emptyList())
        return SearchResult(items = items)
    }

    // ---- linkup: answer + sources[] { name, url, snippet } ----
    fun parseLinkUp(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["sources"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("name") ?: "", o.str("url") ?: "", o.str("snippet") ?: "")
        } ?: emptyList()
        return SearchResult(answer = root.str("answer"), items = items)
    }

    // ---- metaso: webpages[] { title, link, snippet } ----
    fun parseMetaso(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["webpages"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("link") ?: "", o.str("snippet") ?: "")
        } ?: emptyList()
        return SearchResult(items = items)
    }

    // ---- ollama: results[] { title, url, content } ----
    fun parseOllama(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("url") ?: "", o.str("content") ?: "")
        } ?: emptyList()
        return SearchResult(items = items)
    }

    // ---- jina: data|results[] { title, url, description } ----
    fun parseJina(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val list = root["data"]?.arr() ?: root["results"]?.arr() ?: return SearchResult(items = emptyList())
        val items = list.take(limit).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("url") ?: "", o.str("description") ?: "")
        }
        return SearchResult(items = items)
    }

    // ---- perplexity: results[] possibly nested, items { title, url, snippet } ----
    fun parsePerplexity(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val results = root["results"]?.arr() ?: return SearchResult(items = emptyList())
        val flat = ArrayList<JsonObject>()
        for (el in results) {
            when {
                el is JsonArray -> for (sub in el) sub.obj()?.let { flat.add(it) }
                el is JsonObject -> flat.add(el)
            }
        }
        val items = flat.take(limit).map { o ->
            item(o.str("title") ?: "", o.str("url") ?: "", o.str("snippet") ?: "")
        }
        return SearchResult(items = items)
    }

    // ---- querit: error_code + results.result[] { title, url, snippet/snippets } ----
    data class QueritResult(val error: String?, val items: List<SearchResultItem>)

    fun parseQuerit(body: String, limit: Int): QueritResult {
        val root = parse(body) ?: return QueritResult("invalid response", emptyList())
        val code = root.int("error_code")
        if (code != null && code != 200) {
            val message = root.str("error_msg") ?: "Unknown error"
            return QueritResult("API request failed: $code $message", emptyList())
        }
        val results = root["results"]?.obj()?.get("result")?.arr() ?: return QueritResult(null, emptyList())
        val items = results.take(limit).map { el ->
            val o = el.obj() ?: JsonObject(emptyMap())
            val snippet = (o.str("snippet") ?: "").trim()
            val extras = (o["snippets"]?.arr() ?: o["sentence"]?.arr())
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() && it != snippet }
                ?: emptyList()
            val text = (listOf(snippet).filter { it.isNotEmpty() } + extras).joinToString("\n\n")
            val url = o.str("url") ?: ""
            val title = (o.str("title") ?: "").trim().ifEmpty { url }
            item(title, url, text)
        }
        return QueritResult(null, items)
    }

    /** Querit filters object — `_buildFilters`. */
    fun queritFilters(options: com.psyche.memo.data.model.QueritOptions): JsonObject? {
        fun split(value: String) = value.split(Regex("[\\n,]")).map { it.trim() }.filter { it.isNotEmpty() }
        val includes = split(options.sitesInclude)
        val excludes = split(options.sitesExclude)
        val countries = split(options.countries)
        val languages = split(options.languages)
        val date = options.timeRange.trim()
        val out = LinkedHashMap<String, JsonElement>()
        if (includes.isNotEmpty() || excludes.isNotEmpty()) {
            val sites = LinkedHashMap<String, JsonElement>()
            if (includes.isNotEmpty()) sites["include"] = JsonArray(includes.map { JsonPrimitive(it) })
            if (excludes.isNotEmpty()) sites["exclude"] = JsonArray(excludes.map { JsonPrimitive(it) })
            out["sites"] = JsonObject(sites)
        }
        if (date.isNotEmpty()) out["timeRange"] = JsonObject(mapOf("date" to JsonPrimitive(date)))
        if (countries.isNotEmpty()) {
            out["geo"] = JsonObject(
                mapOf(
                    "countries" to JsonObject(
                        mapOf("include" to JsonArray(countries.map { JsonPrimitive(it) })),
                    ),
                ),
            )
        }
        if (languages.isNotEmpty()) {
            out["languages"] = JsonObject(mapOf("include" to JsonArray(languages.map { JsonPrimitive(it) })))
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    // ---- stepfun: results[] { title, url, snippet|content } ----
    fun parseStepFun(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("url") ?: "", o.str("snippet") ?: o.str("content") ?: "")
        } ?: emptyList()
        return SearchResult(items = items)
    }

    // ---- firecrawl: data ?? payload, web then news ----
    fun parseFirecrawl(body: String, limit: Int): SearchResult? {
        val payload = parse(body) ?: return null
        val data = payload["data"]?.obj() ?: payload
        val items = ArrayList<SearchResultItem>()
        fun addWeb(list: JsonArray?) {
            if (list == null) return
            for (el in list) {
                val o = el.obj() ?: continue
                items.add(
                    item(
                        o.str("title") ?: "",
                        o.str("url") ?: "",
                        o.str("description") ?: o.str("snippet") ?: o.str("markdown") ?: "",
                    ),
                )
                if (items.size >= limit) return
            }
        }
        addWeb(data["web"]?.arr())
        if (items.size < limit) addWeb(data["news"]?.arr())
        return SearchResult(items = items.take(limit))
    }

    // ---- tinyfish: results[] { title, url, snippet } ----
    fun parseTinyFish(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("url") ?: "", o.str("snippet") ?: "")
        } ?: emptyList()
        return SearchResult(items = items)
    }

    // ---- anysearch: code + data ?? payload, results[] { title, url, snippet|content } ----
    data class AnySearchResult(val error: String?, val items: List<SearchResultItem>)

    fun parseAnySearch(body: String, limit: Int): AnySearchResult {
        val payload = parse(body) ?: return AnySearchResult("invalid response", emptyList())
        val code = payload.int("code")
        if (code != null && code != 0) {
            val message = payload.str("message") ?: "Unknown API error"
            val requestId = (payload.str("request_id") ?: "").trim()
            val suffix = if (requestId.isEmpty()) "" else " (request_id: $requestId)"
            return AnySearchResult("API request failed: $message$suffix", emptyList())
        }
        val data = payload["data"]?.obj() ?: payload
        val items = data["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val snippet = (o.str("snippet") ?: "").trim()
            item(
                o.str("title") ?: "",
                o.str("url") ?: "",
                snippet.ifEmpty { o.str("content") ?: "" },
            )
        } ?: emptyList()
        return AnySearchResult(null, items)
    }

    // ---- doubao: ResponseMetadata.Error + Result.WebResults ----
    data class DoubaoResult(val error: String?, val items: List<SearchResultItem>)

    fun parseDoubao(body: String): DoubaoResult {
        val root = parse(body) ?: return DoubaoResult("invalid response", emptyList())
        val metadata = root["ResponseMetadata"]?.obj()
        val error = metadata?.get("Error")?.obj()
        if (error != null) {
            return DoubaoResult(error.str("Message") ?: error.str("Code") ?: "API error", emptyList())
        }
        val result = root["Result"]?.obj() ?: return DoubaoResult("API response missing Result", emptyList())
        val webResults = result["WebResults"]?.arr() ?: return DoubaoResult(null, emptyList())
        val items = ArrayList<SearchResultItem>()
        for (el in webResults) {
            val o = el.obj() ?: continue
            val url = (o.str("Url") ?: "").trim()
            if (url.isEmpty()) continue
            val summary = o.str("Summary") ?: ""
            val content = o.str("Content") ?: ""
            items.add(
                item(
                    o.str("Title") ?: "",
                    url,
                    when {
                        summary.trim().isNotEmpty() -> summary
                        content.trim().isNotEmpty() -> content
                        else -> o.str("Snippet") ?: ""
                    },
                ),
            )
        }
        return DoubaoResult(null, items)
    }

    // ---- parallel: results[] { title, url, excerpts[] } ----
    fun parseParallel(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val items = root["results"]?.arr()?.take(limit)?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val excerpts = o["excerpts"]?.arr()
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.trim().isNotEmpty() }
                ?.joinToString("\n\n")
                ?: ""
            item(o.str("title") ?: "", o.str("url") ?: "", excerpts)
        } ?: emptyList()
        return SearchResult(items = items)
    }

    // ---- you: results.web + results.news, contents.highlights|snippets|description ----
    fun parseYou(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val results = root["results"]?.obj() ?: return SearchResult(items = emptyList())
        val merged = ArrayList<JsonElement>()
        results["web"]?.arr()?.let { merged.addAll(it) }
        results["news"]?.arr()?.let { merged.addAll(it) }
        val items = merged.take(limit).mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            item(o.str("title") ?: "", o.str("url") ?: "", youText(o))
        }
        return SearchResult(items = items)
    }

    private fun youText(result: JsonObject): String {
        fun joinTexts(value: JsonElement?): String = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
            is JsonPrimitive -> value.content.trim()
            else -> ""
        }
        val contents = result["contents"]?.obj()
        val highlights = joinTexts(contents?.get("highlights"))
        if (highlights.isNotEmpty()) return highlights
        val snippets = joinTexts(result["snippets"])
        if (snippets.isNotEmpty()) return snippets
        return result.str("description") ?: ""
    }

    // ---- grok: output[].content[].output_text + citations ----
    fun parseGrok(body: String, limit: Int): SearchResult? {
        val root = parse(body) ?: return null
        val output = root["output"]?.arr() ?: emptyList()
        val message = output.mapNotNull { it.obj() }
            .firstOrNull { it.str("type") == "message" && it.str("role") == "assistant" }
        val content = message?.get("content")?.arr() ?: emptyList()
        val textContent = content.mapNotNull { it.obj() }.firstOrNull { it.str("type") == "output_text" }

        val items = ArrayList<SearchResultItem>()
        val seen = HashSet<String>()
        fun addCitations(citations: JsonElement?) {
            val list = citations?.arr() ?: return
            for (citation in list) {
                val parsed = citationItem(citation) ?: continue
                if (!seen.add(parsed.url)) continue
                items.add(parsed)
                if (items.size >= limit) return
            }
        }
        addCitations(root["citations"])
        if (items.size < limit) addCitations(textContent?.get("annotations"))

        return SearchResult(answer = textContent?.str("text"), items = items)
    }

    private fun citationItem(citation: JsonElement): SearchResultItem? {
        if (citation is JsonPrimitive) {
            val url = citation.content.trim()
            if (url.isEmpty()) return null
            return item(url, url, "")
        }
        val o = citation.obj() ?: return null
        if (o.str("type") != "url_citation") return null
        val url = (o.str("url") ?: "").trim()
        if (url.isEmpty()) return null
        val title = (o.str("title") ?: "").trim().ifEmpty { url }
        return item(title, url, "")
    }
}
