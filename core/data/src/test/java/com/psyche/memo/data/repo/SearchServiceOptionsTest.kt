package com.psyche.memo.data.repo

import com.psyche.memo.data.model.AnySearchOptions
import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.BochaOptions
import com.psyche.memo.data.model.BraveOptions
import com.psyche.memo.data.model.DoubaoOptions
import com.psyche.memo.data.model.DuckDuckGoOptions
import com.psyche.memo.data.model.ExaOptions
import com.psyche.memo.data.model.FirecrawlOptions
import com.psyche.memo.data.model.GrokOptions
import com.psyche.memo.data.model.JinaOptions
import com.psyche.memo.data.model.KelivoOptions
import com.psyche.memo.data.model.LinkUpOptions
import com.psyche.memo.data.model.MetasoOptions
import com.psyche.memo.data.model.OllamaOptions
import com.psyche.memo.data.model.ParallelOptions
import com.psyche.memo.data.model.PerplexityOptions
import com.psyche.memo.data.model.QueritOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.SearXNGOptions
import com.psyche.memo.data.model.SerperOptions
import com.psyche.memo.data.model.StepFunOptions
import com.psyche.memo.data.model.TavilyOptions
import com.psyche.memo.data.model.TinyFishOptions
import com.psyche.memo.data.model.YouSearchOptions
import com.psyche.memo.data.model.ZhipuOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port coverage of search_service.dart option classes: every type round-trips
 * through the upstream JSON shape (type discriminator + provider fields) and
 * the normalizers behave like their Dart counterparts.
 */
class SearchServiceOptionsTest {

    private fun roundtrip(options: SearchServiceOptions): SearchServiceOptions =
        SearchServiceOptions.fromJson(options.toJson())

    private fun str(json: JsonObject, key: String): String? =
        (json[key] as? JsonPrimitive)?.content

    @Test
    fun `bing local roundtrip and default accept language`() {
        val o = BingLocalOptions(id = "b1")
        assertEquals("bing_local", str(o.toJson(), "type"))
        assertEquals("en-US,en;q=0.9", str(o.toJson(), "acceptLanguage"))
        assertEquals(o.id, roundtrip(o).id)
    }

    @Test
    fun `tavily keeps url default resolution and extra keys`() {
        val o = TavilyOptions(id = "t1", apiKey = "k", extraApiKeys = listOf("k2", "k3"))
        val json = o.toJson()
        assertEquals("tavily", str(json, "type"))
        assertEquals("", str(json, "url"))
        assertEquals(TavilyOptions.DEFAULT_URL, o.resolvedUrl)
        val back = roundtrip(o) as TavilyOptions
        assertEquals(listOf("k2", "k3"), back.extraApiKeys)
        assertEquals(TavilyOptions.DEFAULT_URL, back.resolvedUrl)
    }

    @Test
    fun `exa zhipu linkup metaso ollama jina doubao are key based`() {
        val services = listOf(
            ExaOptions(id = "e", apiKey = "k"),
            ZhipuOptions(id = "z", apiKey = "k"),
            LinkUpOptions(id = "l", apiKey = "k"),
            MetasoOptions(id = "m", apiKey = "k"),
            OllamaOptions(id = "o", apiKey = "k"),
            JinaOptions(id = "j", apiKey = "k"),
            DoubaoOptions(id = "d", apiKey = "k"),
        )
        for (s in services) {
            val back = roundtrip(s)
            assertEquals(s::class, back::class)
            assertEquals("k", back.primaryApiKey)
        }
    }

    @Test
    fun `searxng roundtrip carries auth fields`() {
        val o = SearXNGOptions(
            id = "sx", url = "https://searx.example", engines = "google",
            language = "zh", username = "u", password = "p",
        )
        val back = roundtrip(o) as SearXNGOptions
        assertEquals("https://searx.example", back.url)
        assertEquals("google", back.engines)
        assertEquals("zh", back.language)
        assertEquals("u", back.username)
        assertEquals("p", back.password)
    }

    @Test
    fun `brave normalizes mode and clamps token budget`() {
        assertEquals(BraveOptions.WEB_MODE, BraveOptions.normalizeMode("nope"))
        assertEquals(BraveOptions.LLM_CONTEXT_MODE, BraveOptions.normalizeMode("llmContext"))
        assertEquals(8192, BraveOptions.normalizeMaximumNumberOfTokens(null))
        assertEquals(1024, BraveOptions.normalizeMaximumNumberOfTokens("1"))
        assertEquals(32768, BraveOptions.normalizeMaximumNumberOfTokens(999999))
        assertEquals(2048, BraveOptions.normalizeMaximumNumberOfTokens("2048"))
        assertTrue(BraveOptions.isValidMaximumNumberOfTokensInput(""))
        assertTrue(BraveOptions.isValidMaximumNumberOfTokensInput("1024"))
        assertTrue(!BraveOptions.isValidMaximumNumberOfTokensInput("100"))
        assertTrue(!BraveOptions.isValidMaximumNumberOfTokensInput("abc"))
    }

    @Test
    fun `duckduckgo region default and roundtrip`() {
        val o = DuckDuckGoOptions(id = "ddg")
        assertEquals("us-en", o.region)
        assertEquals("us-en", (roundtrip(o) as DuckDuckGoOptions).region)
    }

    @Test
    fun `perplexity optional fields are omitted when null`() {
        val o = PerplexityOptions(id = "p", apiKey = "k")
        assertTrue("country" !in o.toJson())
        assertTrue("searchDomainFilter" !in o.toJson())
        assertTrue("maxTokensPerPage" !in o.toJson())
        val full = PerplexityOptions(
            id = "p2", apiKey = "k", country = "US",
            searchDomainFilter = listOf("example.com"), maxTokensPerPage = 2048,
        )
        val back = roundtrip(full) as PerplexityOptions
        assertEquals("US", back.country)
        assertEquals(listOf("example.com"), back.searchDomainFilter)
        assertEquals(2048, back.maxTokensPerPage)
    }

    @Test
    fun `bocha summary defaults true and roundtrips`() {
        val o = BochaOptions(id = "b", apiKey = "k", freshness = "week", include = "qq.com")
        val back = roundtrip(o) as BochaOptions
        assertTrue(back.summary)
        assertEquals("week", back.freshness)
        assertEquals("qq.com", back.include)
    }

    @Test
    fun `serper trims locale fields`() {
        val o = SerperOptions(id = "s", apiKey = "k", gl = " us ", hl = " en ", tbs = " qdr:d ", page = 2)
        val json = o.toJson()
        assertEquals("us", str(json, "gl"))
        assertEquals("en", str(json, "hl"))
        assertEquals("qdr:d", str(json, "tbs"))
        assertEquals(2, (roundtrip(o) as SerperOptions).page)
    }

    @Test
    fun `grok defaults reasoning effort only for the default model`() {
        val o = GrokOptions(id = "g", apiKey = "k")
        assertEquals(GrokOptions.DEFAULT_REASONING_EFFORT, o.reasoningEffort)
        assertEquals(GrokOptions.DEFAULT_URL, o.resolvedUrl)
        val custom = GrokOptions(id = "g2", apiKey = "k", model = "grok-4-fast")
        assertEquals("", custom.reasoningEffort)
        val explicit = GrokOptions(id = "g3", apiKey = "k", model = "grok-4-fast", reasoningEffort = "high")
        assertEquals("high", explicit.reasoningEffort)
    }

    @Test
    fun `querit and tinyfish trim their optional fields`() {
        val q = QueritOptions(
            id = "q", apiKey = "k", sitesInclude = " a.com ", sitesExclude = " b.com ",
            timeRange = " day ", countries = " cn ", languages = " zh ",
        )
        val qb = roundtrip(q) as QueritOptions
        assertEquals("a.com", qb.sitesInclude)
        assertEquals("b.com", qb.sitesExclude)
        assertEquals("day", qb.timeRange)
        assertEquals("cn", qb.countries)
        assertEquals("zh", qb.languages)

        val t = TinyFishOptions(id = "t", apiKey = "k", url = " https://x ", location = " cn ")
        val tb = roundtrip(t) as TinyFishOptions
        assertEquals("https://x", tb.url)
        assertEquals("cn", tb.location)
        assertEquals("https://x", tb.resolvedUrl)
    }

    @Test
    fun `step alias decodes into stepfun`() {
        val legacy = JsonObject(
            mapOf(
                "type" to JsonPrimitive("step"),
                "id" to JsonPrimitive("legacy"),
                "apiKey" to JsonPrimitive("k"),
            ),
        )
        val parsed = SearchServiceOptions.fromJson(legacy)
        assertTrue(parsed is StepFunOptions)
        assertEquals("stepfun", str(parsed.toJson(), "type"))
    }

    @Test
    fun `firecrawl defaults sources to web`() {
        val o = FirecrawlOptions(id = "f", apiKey = "k")
        val back = roundtrip(o) as FirecrawlOptions
        assertEquals(listOf("web"), back.sources)
        assertTrue(back.categories.isEmpty())
        assertEquals(FirecrawlOptions.DEFAULT_URL, back.resolvedUrl)
    }

    @Test
    fun `anysearch kelivo parallel you roundtrip`() {
        assertEquals(AnySearchOptions.DEFAULT_URL, (roundtrip(AnySearchOptions(id = "a", apiKey = "k")) as AnySearchOptions).resolvedUrl)
        assertEquals("kelivo", str(KelivoOptions(id = "kelivo").toJson(), "type"))
        assertEquals("advanced", (roundtrip(ParallelOptions(id = "p", apiKey = "k")) as ParallelOptions).mode)
        assertEquals("turbo", ParallelOptions.normalizeMode("turbo"))
        assertEquals("Advanced", ParallelOptions.modeLabel("advanced"))
        assertEquals("highlights", (roundtrip(YouSearchOptions(id = "y", apiKey = "k")) as YouSearchOptions).contentMode)
        assertEquals("snippets", YouSearchOptions.normalizeContentMode("snippets"))
    }

    @Test
    fun `unknown type falls back to bing local`() {
        val unknown = JsonObject(
            mapOf(
                "type" to JsonPrimitive("brand_new_provider"),
                "id" to JsonPrimitive("x"),
            ),
        )
        val parsed = SearchServiceOptions.fromJson(unknown)
        assertTrue(parsed is BingLocalOptions)
        assertEquals("x", parsed.id)
    }

    @Test
    fun `extra api keys are trimmed and blank entries dropped`() {
        val json = JsonObject(
            mapOf(
                "type" to JsonPrimitive("tavily"),
                "id" to JsonPrimitive("t"),
                "apiKey" to JsonPrimitive("k"),
                "apiKeys" to kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive(" a "), JsonPrimitive(""), JsonPrimitive("b")),
                ),
            ),
        )
        val parsed = SearchServiceOptions.fromJson(json) as TavilyOptions
        assertEquals(listOf("a", "b"), parsed.extraApiKeys)
    }

    @Test
    fun `common options default and roundtrip`() {
        assertEquals(SearchCommonOptions(10, 5000), SearchCommonOptions.fromJson(null))
        val o = SearchCommonOptions(resultSize = 20, timeout = 9000)
        assertEquals(o, SearchCommonOptions.fromJson(o.toJson()))
    }
}
