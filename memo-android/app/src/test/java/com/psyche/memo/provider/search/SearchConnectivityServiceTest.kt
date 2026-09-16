package com.psyche.memo.provider.search

import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.KelivoOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchResult
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.TavilyOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索服务连通性共享表 —— `settings_provider.dart` 的 `_searchConnection` /
 * `_initSearchConnectivityTests` / `_testSingleSearchService`（L2235-2269）。
 *
 * 用户 2026-09-16「启动时自动测试连接没有做吧」：设备库里那个开关是 1，但此前
 * 没有任何消费点（探测结果表还放在页面级 remember 里，离开页面就丢）。
 */
class SearchConnectivityServiceTest {

    private class FakeEngine(
        private val failure: Throwable? = null,
    ) : SearchEngine {
        data class Call(
            val query: String,
            val serviceId: String,
            val common: SearchCommonOptions,
        )

        val calls = mutableListOf<Call>()

        override suspend fun search(
            query: String,
            options: SearchServiceOptions,
            common: SearchCommonOptions,
        ): SearchResult {
            calls += Call(query, options.id, common)
            failure?.let { throw it }
            return SearchResult(items = emptyList())
        }
    }

    private fun service(engine: SearchEngine, list: List<SearchServiceOptions>) =
        SearchConnectivityService(
            scope = CoroutineScope(kotlin.coroutines.EmptyCoroutineContext),
            engine = engine,
            services = { list },
            common = { SearchCommonOptions(resultSize = 10, timeout = 5000) },
        )

    /**
     * `testAllOnLaunch` 是 fire-and-forget（原版 `unawaited`），所以断言前要等它
     * 的子协程跑完 —— `runBlocking` 只在**整个 block 结束**时才等子协程，写在
     * block 里的断言会跑在它们前面。
     */
    private suspend fun awaitStates(svc: SearchConnectivityService, count: Int) {
        kotlinx.coroutines.withTimeout(2_000) {
            while (svc.states.value.size < count) kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun `local engines are never probed`() {
        val svc = service(FakeEngine(), emptyList())
        assertTrue(svc.skipped(BingLocalOptions(id = "default")))
        assertTrue(svc.skipped(KelivoOptions(id = "k")))
        assertFalse(svc.skipped(TavilyOptions(id = "t", apiKey = "k")))
    }

    @Test
    fun `probe records success and failure`() = runBlocking {
        val engine = FakeEngine()
        val ok = service(engine, emptyList())
        assertEquals(true, ok.probe(TavilyOptions(id = "t", apiKey = "k")))
        assertEquals(mapOf("t" to true), ok.states.value)
        assertEquals("connectivity test", engine.calls.single().query)
        assertEquals("t", engine.calls.single().serviceId)
        assertEquals(10, engine.calls.single().common.resultSize)

        val bad = service(FakeEngine(failure = IllegalStateException("boom")), emptyList())
        assertEquals(false, bad.probe(TavilyOptions(id = "t", apiKey = "k")))
        assertEquals(mapOf("t" to false), bad.states.value)
    }

    @Test
    fun `a local engine is marked untested without a request`() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine, emptyList())
        assertNull(svc.probe(BingLocalOptions(id = "default")))
        assertNull(svc.states.value["default"])
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `manual probe uses the caller's common options`() = runBlocking {
        val engine = FakeEngine()
        val svc = service(engine, emptyList())
        svc.probe(
            TavilyOptions(id = "t", apiKey = "k"),
            SearchCommonOptions(resultSize = 1, timeout = 8000),
        )
        assertEquals(1, engine.calls.single().common.resultSize)
        assertEquals(8000, engine.calls.single().common.timeout)
    }

    @Test
    fun `launch probe covers every remote service and seeds local ones as untested`() = runBlocking {
        val engine = FakeEngine()
        val services: List<SearchServiceOptions> = listOf(
            BingLocalOptions(id = "bing"),
            KelivoOptions(id = "kelivo"),
            TavilyOptions(id = "t1", apiKey = "a"),
            TavilyOptions(id = "t2", apiKey = "b"),
        )
        val svc = SearchConnectivityService(
            scope = CoroutineScope(coroutineContext),
            engine = engine,
            services = { services },
            common = { SearchCommonOptions() },
        )
        svc.testAllOnLaunch()
        awaitStates(svc, 4)
        assertEquals(setOf("t1", "t2"), engine.calls.map { it.serviceId }.toSet())
        assertEquals(
            mapOf("bing" to null, "kelivo" to null, "t1" to true, "t2" to true),
            svc.states.value,
        )
    }

    @Test
    fun `a failing service is recorded as failed`() = runBlocking {
        val svc = SearchConnectivityService(
            scope = CoroutineScope(coroutineContext),
            engine = FakeEngine(failure = java.io.IOException("no network")),
            services = { listOf(TavilyOptions(id = "ok", apiKey = "a")) },
            common = { SearchCommonOptions() },
        )
        svc.testAllOnLaunch()
        awaitStates(svc, 1)
        assertEquals(mapOf("ok" to false), svc.states.value)
    }
}
