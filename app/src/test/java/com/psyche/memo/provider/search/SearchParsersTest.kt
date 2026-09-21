package com.psyche.memo.provider.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response-mapper coverage for the ported search providers. */
class SearchParsersTest {

    @Test
    fun `tavily maps answer and results`() {
        val body = """
            {"answer":"42","results":[
              {"title":"A","url":"https://a.example","content":"alpha"},
              {"title":"B","url":"b.example","content":"beta"}]}
        """.trimIndent()
        val result = SearchParsers.parseTavily(body)!!
        assertEquals("42", result.answer)
        assertEquals(2, result.items.size)
        assertEquals("A", result.items[0].title)
        assertEquals("https://a.example", result.items[0].url)
        assertEquals("beta", result.items[1].text)
    }

    @Test
    fun `tavily without answer leaves it null`() {
        val result = SearchParsers.parseTavily("""{"results":[]}""")!!
        assertNull(result.answer)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `searxng limits results`() {
        val body = """
            {"results":[{"title":"1","url":"u1","content":"c1"},
                        {"title":"2","url":"u2","content":"c2"},
                        {"title":"3","url":"u3","content":"c3"}]}
        """.trimIndent()
        val result = SearchParsers.parseSearxng(body, 2)!!
        assertEquals(listOf("1", "2"), result.items.map { it.title })
    }

    @Test
    fun `brave web maps description`() {
        val body = """{"web":{"results":[{"title":"T","url":"https://t","description":"D"}]}}"""
        val result = SearchParsers.parseBraveWeb(body)!!
        assertEquals(1, result.items.size)
        assertEquals("D", result.items[0].text)
    }

    @Test
    fun `brave llm context joins snippets with blank lines`() {
        val body = """
            {"grounding":{"generic":[
              {"title":"T","url":"https://t","snippets":["one","two",""]}]}}
        """.trimIndent()
        val result = SearchParsers.parseBraveLlmContext(body, 10)!!
        assertEquals("one\n\ntwo", result.items[0].text)
    }

    @Test
    fun `serper maps organic entries`() {
        val body = """{"organic":[{"title":"T","link":"https://l","snippet":"S"}]}"""
        val result = SearchParsers.parseSerper(body, 10)!!
        assertEquals("https://l", result.items[0].url)
        assertEquals("S", result.items[0].text)
    }

    @Test
    fun `bocha surfaces non-200 code as an error`() {
        val parsed = SearchParsers.parseBocha("""{"code":403,"data":{}}""", 10)
        assertEquals(403, parsed.code)
        assertEquals("API error code: 403", parsed.error)
        assertTrue(parsed.items.isEmpty())
    }

    @Test
    fun `bocha prefers summary then snippet`() {
        val body = """
            {"code":200,"data":{"webPages":{"value":[
              {"name":"A","url":"u","summary":"S","snippet":"X"},
              {"name":"B","url":"u2","snippet":"Y"}]}}}
        """.trimIndent()
        val parsed = SearchParsers.parseBocha(body, 10)
        assertNull(parsed.error)
        assertEquals("S", parsed.items[0].text)
        assertEquals("Y", parsed.items[1].text)
    }

    @Test
    fun `zhipu maps search_result entries`() {
        val body = """{"search_result":[{"title":"T","link":"https://z","content":"C"}]}"""
        val result = SearchParsers.parseZhipu(body)!!
        assertEquals("https://z", result.items[0].url)
        assertEquals("C", result.items[0].text)
    }

    @Test
    fun `bing html scrape reads titles links and captions`() {
        val html = """
            <ol id="b_results">
              <li class="b_algo"><h2><a href="https://a.example">Alpha</a></h2>
                <div class="b_caption"><p>First snippet</p></div></li>
              <li class="b_algo"><h2><a href="https://b.example">Beta</a></h2>
                <div class="b_algoSlug">Second snippet</div></li>
              <li class="b_other"><h2><a href="https://ignored">No</a></h2></li>
            </ol>
        """.trimIndent()
        val result = SearchParsers.parseBingHtml(html, 10)
        assertEquals(2, result.items.size)
        assertEquals("Alpha", result.items[0].title)
        assertEquals("https://a.example", result.items[0].url)
        assertEquals("First snippet", result.items[0].text)
        assertEquals("Second snippet", result.items[1].text)
    }

    @Test
    fun `bing html respects the result limit`() {
        val html = (1..5).joinToString("") {
            """<li class="b_algo"><h2><a href="https://$it">T$it</a></h2></li>"""
        }
        assertEquals(2, SearchParsers.parseBingHtml(html, 2).items.size)
    }

    @Test
    fun `duckduckgo html unwraps uddg redirects`() {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Freal.example%2Fp%3Fx%3D1">Title</a>
              <span class="result__snippet">Snippet</span>
            </div>
        """.trimIndent()
        val result = SearchParsers.parseDuckDuckGoHtml(html, 10)
        assertEquals("https://real.example/p?x=1", result.items[0].url)
        assertEquals("Title", result.items[0].title)
        assertEquals("Snippet", result.items[0].text)
    }

    @Test
    fun `duckduckgo keeps plain links and skips empty rows`() {
        val html = """
            <div class="result"><a class="result__a" href="https://plain.example">P</a></div>
            <div class="result"></div>
        """.trimIndent()
        val result = SearchParsers.parseDuckDuckGoHtml(html, 10)
        assertEquals(1, result.items.size)
        assertEquals("https://plain.example", result.items[0].url)
    }

    @Test
    fun `normalize url adds https strips quotes and fixes protocol relative`() {
        assertEquals("https://example.com", SearchParsers.normalizeUrl("example.com"))
        assertEquals("https://example.com", SearchParsers.normalizeUrl("\"example.com\""))
        assertEquals("https://example.com", SearchParsers.normalizeUrl("//example.com"))
        assertEquals("http://example.com", SearchParsers.normalizeUrl("http://example.com"))
        assertEquals("", SearchParsers.normalizeUrl("   "))
    }

    @Test
    fun `invalid json yields null`() {
        assertNull(SearchParsers.parseTavily("{oops"))
        assertNull(SearchParsers.parseSearxng("[]", 5))
    }
}
