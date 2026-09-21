package com.psyche.memo.provider.search

import com.psyche.memo.data.model.QueritOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response-mapper coverage for the second provider batch. */
class SearchProviderParsersTest {

    @Test
    fun `exa maps results`() {
        val result = SearchProviderParsers.parseExa(
            """{"results":[{"title":"T","url":"https://e","text":"X"}]}""",
            10,
        )!!
        assertEquals("https://e", result.items[0].url)
        assertEquals("X", result.items[0].text)
    }

    @Test
    fun `linkup keeps answer and sources`() {
        val result = SearchProviderParsers.parseLinkUp(
            """{"answer":"A","sources":[{"name":"N","url":"https://l","snippet":"S"}]}""",
            10,
        )!!
        assertEquals("A", result.answer)
        assertEquals("N", result.items[0].title)
    }

    @Test
    fun `metaso maps webpages`() {
        val result = SearchProviderParsers.parseMetaso(
            """{"webpages":[{"title":"T","link":"https://m","snippet":"S"}]}""",
            10,
        )!!
        assertEquals("https://m", result.items[0].url)
    }

    @Test
    fun `ollama maps content`() {
        val result = SearchProviderParsers.parseOllama(
            """{"results":[{"title":"T","url":"https://o","content":"C"}]}""",
            10,
        )!!
        assertEquals("C", result.items[0].text)
    }

    @Test
    fun `jina accepts data or results key`() {
        val viaData = SearchProviderParsers.parseJina(
            """{"data":[{"title":"T","url":"https://j","description":"D"}]}""", 10,
        )!!
        assertEquals("D", viaData.items[0].text)
        val viaResults = SearchProviderParsers.parseJina(
            """{"results":[{"title":"T","url":"https://j","description":"D"}]}""", 10,
        )!!
        assertEquals(1, viaResults.items.size)
    }

    @Test
    fun `perplexity flattens nested result lists`() {
        val result = SearchProviderParsers.parsePerplexity(
            """{"results":[[{"title":"A","url":"u1","snippet":"s1"}],[{"title":"B","url":"u2","snippet":"s2"}]]}""",
            10,
        )!!
        assertEquals(listOf("u1", "u2"), result.items.map { it.url })
    }

    @Test
    fun `querit surfaces error_code and joins snippets`() {
        val error = SearchProviderParsers.parseQuerit("""{"error_code":401,"error_msg":"bad key"}""", 10)
        assertEquals("API request failed: 401 bad key", error.error)

        val ok = SearchProviderParsers.parseQuerit(
            """{"error_code":200,"results":{"result":[{"title":"","url":"https://q","snippet":"s1","snippets":["s1","s2"]}]}}""",
            10,
        )
        assertNull(ok.error)
        assertEquals("https://q", ok.items[0].title)
        assertEquals("s1\n\ns2", ok.items[0].text)
    }

    @Test
    fun `querit filters follow the upstream shape`() {
        val filters = SearchProviderParsers.queritFilters(
            QueritOptions(
                id = "q", apiKey = "k",
                sitesInclude = "a.com, b.com", sitesExclude = "c.com",
                timeRange = "d1", countries = "cn", languages = "zh",
            ),
        ) as JsonObject
        val sites = filters["sites"] as JsonObject
        assertEquals(2, (sites["include"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(filters.containsKey("timeRange"))
        assertTrue(filters.containsKey("geo"))
        assertTrue(filters.containsKey("languages"))
    }

    @Test
    fun `stepfun prefers snippet then content`() {
        val result = SearchProviderParsers.parseStepFun(
            """{"results":[{"title":"T","url":"u","snippet":"S"},{"title":"T2","url":"u2","content":"C"}]}""",
            10,
        )!!
        assertEquals("S", result.items[0].text)
        assertEquals("C", result.items[1].text)
    }

    @Test
    fun `firecrawl reads data wrapper and merges news when web is short`() {
        val result = SearchProviderParsers.parseFirecrawl(
            """{"data":{"web":[{"title":"W","url":"u1","description":"d1"}],"news":[{"title":"N","url":"u2","snippet":"d2"}]}}""",
            5,
        )!!
        assertEquals(listOf("u1", "u2"), result.items.map { it.url })
    }

    @Test
    fun `tinyfish maps snippet`() {
        val result = SearchProviderParsers.parseTinyFish(
            """{"results":[{"title":"T","url":"u","snippet":"S"}]}""", 10,
        )!!
        assertEquals("S", result.items[0].text)
    }

    @Test
    fun `anysearch surfaces code errors and falls back to content`() {
        val error = SearchProviderParsers.parseAnySearch("""{"code":400,"message":"bad","request_id":"r1"}""", 10)
        assertEquals("API request failed: bad (request_id: r1)", error.error)

        val ok = SearchProviderParsers.parseAnySearch(
            """{"code":0,"data":{"results":[{"title":"T","url":"u","snippet":"","content":"C"}]}}""",
            10,
        )
        assertNull(ok.error)
        assertEquals("C", ok.items[0].text)
    }

    @Test
    fun `doubao surfaces metadata errors and reads capitalized fields`() {
        val error = SearchProviderParsers.parseDoubao(
            """{"ResponseMetadata":{"Error":{"Message":"denied"}}}""",
        )
        assertEquals("denied", error.error)

        val ok = SearchProviderParsers.parseDoubao(
            """{"Result":{"WebResults":[{"Title":"T","Url":"https://d","Summary":"S","Content":"C"}]}}""",
        )
        assertNull(ok.error)
        assertEquals("https://d", ok.items[0].url)
        assertEquals("S", ok.items[0].text)
    }

    @Test
    fun `parallel joins excerpts`() {
        val result = SearchProviderParsers.parseParallel(
            """{"results":[{"title":"T","url":"u","excerpts":["a","b"]}]}""", 10,
        )!!
        assertEquals("a\n\nb", result.items[0].text)
    }

    @Test
    fun `you merges web and news and prefers highlights`() {
        val result = SearchProviderParsers.parseYou(
            """{"results":{"web":[{"title":"W","url":"u1","contents":{"highlights":["h1","h2"]}}],
                "news":[{"title":"N","url":"u2","snippets":["s1"]}]}}""",
            10,
        )!!
        assertEquals(listOf("u1", "u2"), result.items.map { it.url })
        assertEquals("h1\n\nh2", result.items[0].text)
        assertEquals("s1", result.items[1].text)
    }

    @Test
    fun `grok reads output text and dedupes citations`() {
        val body = """
            {"output":[{"type":"message","role":"assistant","content":[
              {"type":"output_text","text":"answer",
               "annotations":[{"type":"url_citation","url":"https://a","title":"A"}]}]}],
             "citations":[{"type":"url_citation","url":"https://a","title":"A"},
                          {"type":"url_citation","url":"https://b","title":""}]}
        """.trimIndent()
        val result = SearchProviderParsers.parseGrok(body, 10)!!
        assertEquals("answer", result.answer)
        assertEquals(listOf("https://a", "https://b"), result.items.map { it.url })
        assertEquals("https://b", result.items[1].title)
    }

    @Test
    fun `invalid json yields null`() {
        assertNull(SearchProviderParsers.parseExa("{oops", 5))
        assertNull(SearchProviderParsers.parseGrok("[]", 5))
    }
}
