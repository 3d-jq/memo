package com.psyche.memo.provider.search

import com.psyche.memo.data.model.LinkUpOptions
import com.psyche.memo.data.model.TavilyOptions
import com.psyche.memo.data.model.ZhipuOptions
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of search_service_usage_service.dart. */
class SearchUsageServiceTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

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

    // ---------------------------------------------------------- 网络路径

    @Test
    fun `fetch hits the usage endpoint with the bearer key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"account":{"plan_usage":107,"plan_limit":1000}}"""))
        val info = SearchUsageService.fetch(
            TavilyOptions(id = "t", apiKey = " tvly-test ", url = server.url("/search").toString()),
            OkHttpClient(),
        )
        assertEquals(893.0, info.remaining, 0.001)
        assertEquals(107.0, info.used!!, 0.001)
        val recorded = server.takeRequest()
        assertEquals("/usage", recorded.path)
        assertEquals("Bearer tvly-test", recorded.getHeader("Authorization"))
    }

    @Test
    fun `fetch reports the http status instead of throwing a bare executor error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"rate limited"}"""))
        val error = runCatching {
            SearchUsageService.fetch(
                TavilyOptions(id = "t", apiKey = "k", url = server.url("/search").toString()),
                OkHttpClient(),
            )
        }.exceptionOrNull()
        assertTrue(error is SearchUsageService.UsageException)
        assertTrue(error!!.message!!.contains("429"))
    }

    /**
     * 回归守卫（2026-09-16）：`fetch` 内部是 OkHttp 的阻塞 `execute()`。它必须是
     * `suspend`（自己切 `Dispatchers.IO`）—— 曾经是普通函数，调用点写在
     * `rememberCoroutineScope().launch { }` 里，真机点「查询用量」必炸
     * `NetworkOnMainThreadException`（设备日志 REQ 22-28 全是这个错）。
     * 反射断言：JVM 侧 suspend 函数最后一个参数是 Continuation。
     */
    @Test
    fun `fetch is suspend so it cannot block the main thread`() {
        val method = SearchUsageService::class.java.declaredMethods.first { it.name == "fetch" }
        val last = method.parameterTypes.last().name
        assertEquals("kotlin.coroutines.Continuation", last)
    }
}
