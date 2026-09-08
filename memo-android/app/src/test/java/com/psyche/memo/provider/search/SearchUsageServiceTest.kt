package com.psyche.memo.provider.search

import com.psyche.memo.data.model.LinkUpOptions
import com.psyche.memo.data.model.TavilyOptions
import com.psyche.memo.data.model.ZhipuOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of search_service_usage_service.dart. */
class SearchUsageServiceTest {

    @Test
    fun `only tavily and linkup expose usage`() {
        assertTrue(SearchUsageService.supports(TavilyOptions(id = "t", apiKey = "k")))
        assertTrue(SearchUsageService.supports(LinkUpOptions(id = "l", apiKey = "k")))
        assertFalse(SearchUsageService.supports(ZhipuOptions(id = "z", apiKey = "k")))
    }

    @Test
    fun `tavily usage url swaps the search segment`() {
        assertEquals(
            "https://api.tavily.com/usage",
            SearchUsageService.tavilyUsageUrl("https://api.tavily.com/search"),
        )
        assertEquals(
            "https://proxy.example.com/tavily/usage",
            SearchUsageService.tavilyUsageUrl("https://proxy.example.com/tavily/search"),
        )
        assertEquals(
            "https://api.tavily.com/usage",
            SearchUsageService.tavilyUsageUrl("https://api.tavily.com"),
        )
        assertEquals(
            "https://api.tavily.com/custom/usage",
            SearchUsageService.tavilyUsageUrl("https://api.tavily.com/custom"),
        )
    }

    @Test
    fun `tavily account plan wins over key totals`() {
        val info = SearchUsageService.parseTavilyUsage(
            """{"account":{"plan_usage":120,"plan_limit":1000},"key":{"usage":1,"limit":2}}""",
        )!!
        assertEquals(880.0, info.remaining, 0.001)
        assertEquals(120.0, info.used!!, 0.001)
        assertEquals(1000.0, info.limit!!, 0.001)
    }

    @Test
    fun `tavily falls back to key totals and clamps negatives`() {
        val info = SearchUsageService.parseTavilyUsage(
            """{"key":{"usage":50,"limit":30}}""",
        )!!
        assertEquals(0.0, info.remaining, 0.001)
        assertNull(SearchUsageService.parseTavilyUsage("""{"key":{"usage":1}}"""))
        assertNull(SearchUsageService.parseTavilyUsage("{oops"))
    }

    @Test
    fun `linkup reads the balance`() {
        val info = SearchUsageService.parseLinkUpUsage("""{"balance":42.5}""")!!
        assertEquals(42.5, info.remaining, 0.001)
        assertNull(info.used)
        assertNull(SearchUsageService.parseLinkUpUsage("""{"balance":"nope"}"""))
    }
}
