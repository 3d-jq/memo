package com.psyche.memo.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Search service options — port of lib/core/services/search/search_service.dart
 * (the `SearchServiceOptions` hierarchy). Each subclass serializes to the exact
 * upstream JSON shape (`type` discriminator + the fields that provider uses),
 * so configs written by either app decode identically.
 */
sealed class SearchServiceOptions {
    abstract val id: String

    /** JSON key `apiKeys` — extra keys that join the round-robin rotation. */
    open val extraApiKeys: List<String> get() = emptyList()

    abstract fun toJson(): JsonObject

    /** The primary API key for key-based services; empty for the rest. */
    val primaryApiKey: String
        get() = (toJson()["apiKey"] as? JsonPrimitive)?.content ?: ""

    companion object {
        const val BING_LOCAL = "bing_local"
        const val TAVILY = "tavily"
        const val EXA = "exa"
        const val ZHIPU = "zhipu"
        const val SEARXNG = "searxng"
        const val LINKUP = "linkup"
        const val BRAVE = "brave"
        const val METASO = "metaso"
        const val OLLAMA = "ollama"
        const val JINA = "jina"
        const val BOCHA = "bocha"
        const val PERPLEXITY = "perplexity"
        const val DUCKDUCKGO = "duckduckgo"
        const val SERPER = "serper"
        const val GROK = "grok"
        const val QUERIT = "querit"
        const val STEPFUN = "stepfun"
        const val FIRECRAWL = "firecrawl"
        const val TINYFISH = "tinyfish"
        const val ANYSEARCH = "anysearch"
        const val DOUBAO = "doubao"
        const val KELIVO = "kelivo"
        const val PARALLEL = "parallel"
        const val YOU = "you"

        /** `SearchServiceOptions.defaultOption` — Bing local scraping. */
        val defaultOption: SearchServiceOptions get() = BingLocalOptions(id = "default")

        fun parseExtraApiKeys(json: JsonObject): List<String> =
            (json["apiKeys"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        /** Factory dispatch — mirrors SearchServiceOptions.fromJson + getService. */
        fun fromJson(json: JsonObject): SearchServiceOptions {
            val type = (json["type"] as? JsonPrimitive)?.content ?: BING_LOCAL
            return when (type) {
                BING_LOCAL -> BingLocalOptions(
                    id = json.str("id") ?: "",
                    acceptLanguage = json.str("acceptLanguage") ?: "en-US,en;q=0.9",
                )
                TAVILY -> TavilyOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    url = json.str("url") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                EXA -> ExaOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    url = json.str("url") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                ZHIPU -> ZhipuOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                SEARXNG -> SearXNGOptions(
                    id = json.str("id") ?: "",
                    url = json.str("url") ?: "",
                    engines = json.str("engines") ?: "",
                    language = json.str("language") ?: "",
                    username = json.str("username") ?: "",
                    password = json.str("password") ?: "",
                )
                LINKUP -> LinkUpOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                BRAVE -> BraveOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    mode = BraveOptions.normalizeMode(json.str("mode")),
                    maximumNumberOfTokens = BraveOptions.normalizeMaximumNumberOfTokens(
                        (json["maximumNumberOfTokens"] as? JsonPrimitive)?.content,
                    ),
                    extraApiKeys = parseExtraApiKeys(json),
                )
                METASO -> MetasoOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                OLLAMA -> OllamaOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                JINA -> JinaOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                DUCKDUCKGO -> DuckDuckGoOptions(
                    id = json.str("id") ?: "",
                    region = json.str("region") ?: "us-en",
                )
                PERPLEXITY -> PerplexityOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    country = json.str("country"),
                    searchDomainFilter = (json["searchDomainFilter"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content },
                    maxTokensPerPage = (json["maxTokensPerPage"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    extraApiKeys = parseExtraApiKeys(json),
                )
                BOCHA -> BochaOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    freshness = json.str("freshness"),
                    summary = (json["summary"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
                    include = json.str("include"),
                    exclude = json.str("exclude"),
                    extraApiKeys = parseExtraApiKeys(json),
                )
                SERPER -> SerperOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    gl = json.str("gl") ?: "",
                    hl = json.str("hl") ?: "",
                    tbs = json.str("tbs") ?: "",
                    page = (json["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1,
                    extraApiKeys = parseExtraApiKeys(json),
                )
                GROK -> GrokOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    model = json.str("model") ?: GrokOptions.DEFAULT_MODEL,
                    reasoningEffort = json.str("reasoningEffort"),
                    customUrl = json.str("customUrl") ?: GrokOptions.DEFAULT_URL,
                    systemPrompt = json.str("systemPrompt") ?: GrokOptions.DEFAULT_SYSTEM_PROMPT,
                    extraApiKeys = parseExtraApiKeys(json),
                )
                QUERIT -> QueritOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    sitesInclude = json.str("sitesInclude") ?: "",
                    sitesExclude = json.str("sitesExclude") ?: "",
                    timeRange = json.str("timeRange") ?: "",
                    countries = json.str("countries") ?: "",
                    languages = json.str("languages") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                // Upstream accepts the legacy `step` alias.
                STEPFUN, "step" -> StepFunOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    url = json.str("url") ?: "",
                    category = json.str("category") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                FIRECRAWL -> FirecrawlOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    url = json.str("url") ?: "",
                    sources = (json["sources"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?.filter { it.isNotEmpty() }
                        ?: listOf("web"),
                    categories = (json["categories"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    country = json.str("country") ?: "",
                    location = json.str("location") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                TINYFISH -> TinyFishOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    url = json.str("url") ?: "",
                    location = json.str("location") ?: "",
                    language = json.str("language") ?: "",
                    includeDomains = json.str("includeDomains") ?: "",
                    excludeDomains = json.str("excludeDomains") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                ANYSEARCH -> AnySearchOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    url = json.str("url") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                DOUBAO -> DoubaoOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    extraApiKeys = parseExtraApiKeys(json),
                )
                KELIVO -> KelivoOptions(id = json.str("id") ?: "")
                PARALLEL -> ParallelOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    mode = ParallelOptions.normalizeMode(json.str("mode")),
                    extraApiKeys = parseExtraApiKeys(json),
                )
                YOU -> YouSearchOptions(
                    id = json.str("id") ?: "",
                    apiKey = json.str("apiKey") ?: "",
                    contentMode = YouSearchOptions.normalizeContentMode(json.str("contentMode")),
                    extraApiKeys = parseExtraApiKeys(json),
                )
                else -> BingLocalOptions(id = json.str("id") ?: "")
            }
        }

        internal fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }
}

private fun baseFields(type: String, id: String): MutableMap<String, kotlinx.serialization.json.JsonElement> =
    mutableMapOf("type" to JsonPrimitive(type), "id" to JsonPrimitive(id))

private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.putKey(
    key: String,
    value: String,
    omitEmpty: Boolean = false,
) {
    if (omitEmpty && value.isEmpty()) return
    this[key] = JsonPrimitive(value)
}

private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.putKeys(keys: List<String>) {
    if (keys.isNotEmpty()) {
        this["apiKeys"] = buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } }
    }
}

private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.toObj(): JsonObject =
    JsonObject(this.toMap())

class BingLocalOptions(
    override val id: String,
    val acceptLanguage: String = "en-US,en;q=0.9",
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(BING_LOCAL, id).apply {
        putKey("acceptLanguage", acceptLanguage)
    }.toObj()
}

class TavilyOptions(
    override val id: String,
    val apiKey: String,
    val url: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val resolvedUrl: String get() = url.trim().ifEmpty { DEFAULT_URL }

    override fun toJson(): JsonObject = baseFields(TAVILY, id).apply {
        putKey("apiKey", apiKey)
        putKey("url", url.trim())
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.tavily.com/search"
    }
}

class ExaOptions(
    override val id: String,
    val apiKey: String,
    val url: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val resolvedUrl: String get() = url.trim().ifEmpty { DEFAULT_URL }

    override fun toJson(): JsonObject = baseFields(EXA, id).apply {
        putKey("apiKey", apiKey)
        putKey("url", url.trim())
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.exa.ai/search"
    }
}

class ZhipuOptions(
    override val id: String,
    val apiKey: String,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(ZHIPU, id).apply {
        putKey("apiKey", apiKey)
        putKeys(extraApiKeys)
    }.toObj()
}

class SearXNGOptions(
    override val id: String,
    val url: String,
    val engines: String = "",
    val language: String = "",
    val username: String = "",
    val password: String = "",
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(SEARXNG, id).apply {
        putKey("url", url)
        putKey("engines", engines)
        putKey("language", language)
        putKey("username", username)
        putKey("password", password)
    }.toObj()
}

class LinkUpOptions(
    override val id: String,
    val apiKey: String,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(LINKUP, id).apply {
        putKey("apiKey", apiKey)
        putKeys(extraApiKeys)
    }.toObj()
}

class BraveOptions(
    override val id: String,
    val apiKey: String,
    val mode: String = DEFAULT_MODE,
    val maximumNumberOfTokens: Int = DEFAULT_MAXIMUM_NUMBER_OF_TOKENS,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(BRAVE, id).apply {
        putKey("apiKey", apiKey)
        putKey("mode", mode)
        this["maximumNumberOfTokens"] = JsonPrimitive(maximumNumberOfTokens)
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val WEB_MODE = "web"
        const val LLM_CONTEXT_MODE = "llmContext"
        const val DEFAULT_MODE = WEB_MODE
        val MODES = listOf(WEB_MODE, LLM_CONTEXT_MODE)
        const val DEFAULT_MAXIMUM_NUMBER_OF_TOKENS = 8192
        const val MIN_MAXIMUM_NUMBER_OF_TOKENS = 1024
        const val MAX_MAXIMUM_NUMBER_OF_TOKENS = 32768

        fun normalizeMode(value: String?): String {
            val mode = (value ?: "").trim()
            return if (mode in MODES) mode else DEFAULT_MODE
        }

        fun normalizeMaximumNumberOfTokens(value: Any?): Int {
            val parsed = when (value) {
                is Int -> value
                else -> value?.toString()?.trim()?.toIntOrNull()
            } ?: return DEFAULT_MAXIMUM_NUMBER_OF_TOKENS
            return parsed.coerceIn(MIN_MAXIMUM_NUMBER_OF_TOKENS, MAX_MAXIMUM_NUMBER_OF_TOKENS)
        }

        /** Empty input is valid and later defaults; out-of-range values are not. */
        fun isValidMaximumNumberOfTokensInput(value: String?): Boolean {
            val text = value?.trim() ?: ""
            if (text.isEmpty()) return true
            val tokens = text.toIntOrNull() ?: return false
            return tokens in MIN_MAXIMUM_NUMBER_OF_TOKENS..MAX_MAXIMUM_NUMBER_OF_TOKENS
        }
    }
}

class MetasoOptions(
    override val id: String,
    val apiKey: String,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(METASO, id).apply {
        putKey("apiKey", apiKey)
        putKeys(extraApiKeys)
    }.toObj()
}

class OllamaOptions(
    override val id: String,
    val apiKey: String,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(OLLAMA, id).apply {
        putKey("apiKey", apiKey)
        putKeys(extraApiKeys)
    }.toObj()
}

class JinaOptions(
    override val id: String,
    val apiKey: String,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(JINA, id).apply {
        putKey("apiKey", apiKey)
        putKeys(extraApiKeys)
    }.toObj()
}

class DuckDuckGoOptions(
    override val id: String,
    val region: String = "us-en",
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(DUCKDUCKGO, id).apply {
        putKey("region", region)
    }.toObj()
}

class PerplexityOptions(
    override val id: String,
    val apiKey: String,
    val country: String? = null,
    val searchDomainFilter: List<String>? = null,
    val maxTokensPerPage: Int? = null,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(PERPLEXITY, id).apply {
        putKey("apiKey", apiKey)
        country?.let { this["country"] = JsonPrimitive(it) }
        searchDomainFilter?.let { list ->
            this["searchDomainFilter"] = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }
        }
        maxTokensPerPage?.let { this["maxTokensPerPage"] = JsonPrimitive(it) }
        putKeys(extraApiKeys)
    }.toObj()
}

class BochaOptions(
    override val id: String,
    val apiKey: String,
    val freshness: String? = null,
    val summary: Boolean = true,
    val include: String? = null,
    val exclude: String? = null,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(BOCHA, id).apply {
        putKey("apiKey", apiKey)
        freshness?.let { this["freshness"] = JsonPrimitive(it) }
        this["summary"] = JsonPrimitive(summary)
        include?.let { this["include"] = JsonPrimitive(it) }
        exclude?.let { this["exclude"] = JsonPrimitive(it) }
        putKeys(extraApiKeys)
    }.toObj()
}

class SerperOptions(
    override val id: String,
    val apiKey: String,
    val gl: String = "",
    val hl: String = "",
    val tbs: String = "",
    val page: Int = 1,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(SERPER, id).apply {
        putKey("apiKey", apiKey)
        putKey("gl", gl.trim())
        putKey("hl", hl.trim())
        putKey("tbs", tbs.trim())
        this["page"] = JsonPrimitive(page)
        putKeys(extraApiKeys)
    }.toObj()
}

class GrokOptions(
    override val id: String,
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    reasoningEffort: String? = null,
    val customUrl: String = DEFAULT_URL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val reasoningEffort: String =
        reasoningEffort
            ?: if (model.trim().isEmpty() || model.trim() == DEFAULT_MODEL) DEFAULT_REASONING_EFFORT else ""

    val resolvedUrl: String get() = customUrl.trim().ifEmpty { DEFAULT_URL }
    val resolvedModel: String get() = model.trim().ifEmpty { DEFAULT_MODEL }
    val resolvedSystemPrompt: String get() = systemPrompt.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }

    override fun toJson(): JsonObject = baseFields(GROK, id).apply {
        putKey("apiKey", apiKey)
        putKey("model", model.trim())
        putKey("reasoningEffort", reasoningEffort.trim())
        putKey("customUrl", customUrl.trim())
        putKey("systemPrompt", systemPrompt)
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.x.ai/v1/responses"
        const val DEFAULT_MODEL = "grok-4.5"
        const val DEFAULT_REASONING_EFFORT = "low"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations."
    }
}

class QueritOptions(
    override val id: String,
    val apiKey: String,
    val sitesInclude: String = "",
    val sitesExclude: String = "",
    val timeRange: String = "",
    val countries: String = "",
    val languages: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(QUERIT, id).apply {
        putKey("apiKey", apiKey)
        putKey("sitesInclude", sitesInclude.trim())
        putKey("sitesExclude", sitesExclude.trim())
        putKey("timeRange", timeRange.trim())
        putKey("countries", countries.trim())
        putKey("languages", languages.trim())
        putKeys(extraApiKeys)
    }.toObj()
}

class StepFunOptions(
    override val id: String,
    val apiKey: String,
    val url: String = "",
    val category: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val resolvedUrl: String get() = url.trim().ifEmpty { DEFAULT_URL }

    override fun toJson(): JsonObject = baseFields(STEPFUN, id).apply {
        putKey("apiKey", apiKey)
        putKey("url", url.trim())
        putKey("category", category.trim())
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.stepfun.com/v1/search"
    }
}

class FirecrawlOptions(
    override val id: String,
    val apiKey: String,
    val url: String = "",
    val sources: List<String> = listOf("web"),
    val categories: List<String> = emptyList(),
    val country: String = "",
    val location: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val resolvedUrl: String get() = url.trim().ifEmpty { DEFAULT_URL }

    override fun toJson(): JsonObject = baseFields(FIRECRAWL, id).apply {
        putKey("apiKey", apiKey)
        putKey("url", url.trim())
        this["sources"] = buildJsonArray { sources.forEach { add(JsonPrimitive(it)) } }
        this["categories"] = buildJsonArray { categories.forEach { add(JsonPrimitive(it)) } }
        putKey("country", country.trim())
        putKey("location", location.trim())
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.firecrawl.dev/v2/search"
    }
}

class TinyFishOptions(
    override val id: String,
    val apiKey: String,
    val url: String = "",
    val location: String = "",
    val language: String = "",
    val includeDomains: String = "",
    val excludeDomains: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val resolvedUrl: String get() = url.trim().ifEmpty { DEFAULT_URL }

    override fun toJson(): JsonObject = baseFields(TINYFISH, id).apply {
        putKey("apiKey", apiKey)
        putKey("url", url.trim())
        putKey("location", location.trim())
        putKey("language", language.trim())
        putKey("includeDomains", includeDomains.trim())
        putKey("excludeDomains", excludeDomains.trim())
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.search.tinyfish.ai"
    }
}

class AnySearchOptions(
    override val id: String,
    val apiKey: String,
    val url: String = "",
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    val resolvedUrl: String get() = url.trim().ifEmpty { DEFAULT_URL }

    override fun toJson(): JsonObject = baseFields(ANYSEARCH, id).apply {
        putKey("apiKey", apiKey)
        putKey("url", url.trim())
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_URL = "https://api.anysearch.com/v1/search"
    }
}

class DoubaoOptions(
    override val id: String,
    val apiKey: String,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(DOUBAO, id).apply {
        putKey("apiKey", apiKey)
        putKeys(extraApiKeys)
    }.toObj()
}

class KelivoOptions(override val id: String) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(KELIVO, id).toObj()

    companion object {
        const val BUILT_IN_ID = "kelivo"
    }
}

class ParallelOptions(
    override val id: String,
    val apiKey: String,
    val mode: String = DEFAULT_MODE,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(PARALLEL, id).apply {
        putKey("apiKey", apiKey)
        putKey("mode", mode)
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val DEFAULT_MODE = "advanced"
        val MODES = listOf("advanced", "basic", "fast", "turbo")

        fun normalizeMode(value: String?): String {
            val mode = (value ?: "").trim()
            return if (mode in MODES) mode else DEFAULT_MODE
        }

        fun modeLabel(mode: String): String = when (mode) {
            "turbo" -> "Turbo"
            "fast" -> "Fast"
            "basic" -> "Basic"
            else -> "Advanced"
        }
    }
}

class YouSearchOptions(
    override val id: String,
    val apiKey: String,
    val contentMode: String = DEFAULT_CONTENT_MODE,
    override val extraApiKeys: List<String> = emptyList(),
) : SearchServiceOptions() {
    override fun toJson(): JsonObject = baseFields(YOU, id).apply {
        putKey("apiKey", apiKey)
        putKey("contentMode", contentMode)
        putKeys(extraApiKeys)
    }.toObj()

    companion object {
        const val HIGHLIGHTS_MODE = "highlights"
        const val SNIPPETS_MODE = "snippets"
        const val DEFAULT_CONTENT_MODE = HIGHLIGHTS_MODE
        val CONTENT_MODES = listOf(HIGHLIGHTS_MODE, SNIPPETS_MODE)

        fun normalizeContentMode(value: String?): String {
            val mode = (value ?: "").trim()
            return if (mode in CONTENT_MODES) mode else DEFAULT_CONTENT_MODE
        }
    }
}

/** Common search options — SearchCommonOptions (resultSize 10 / timeout 5000). */
data class SearchCommonOptions(
    val resultSize: Int = 10,
    val timeout: Int = 5000,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("resultSize", resultSize)
        put("timeout", timeout)
    }

    companion object {
        fun fromJson(json: JsonObject?): SearchCommonOptions {
            if (json == null) return SearchCommonOptions()
            return SearchCommonOptions(
                resultSize = (json["resultSize"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10,
                timeout = (json["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 5000,
            )
        }
    }
}

/** One search result item (SearchResultItem). */
data class SearchResultItem(
    val title: String,
    val url: String,
    val text: String,
    var id: String? = null,
    var index: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title)
        put("url", url)
        put("text", text)
        id?.let { put("id", it) }
        index?.let { put("index", it) }
    }
}

/** Search response (SearchResult). */
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
)
