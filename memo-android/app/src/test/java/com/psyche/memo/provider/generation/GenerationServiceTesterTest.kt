package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 生成服务的「测试连接」（自研功能）：打 `GET {base}/models` 验地址与 key，
 * **不发真实生成请求**（那要花钱还要等）。这里钉住四种结果。
 */
class GenerationServiceTesterTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun service() = GenerationService(
        id = "s",
        kind = GenerationKind.IMAGE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = "gpt-image-1",
    )

    @Test
    fun `a reachable endpoint reports the model count`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"a"},{"id":"b"}]}"""))
        val result = GenerationServiceTester.test(service(), client)
        assertEquals(GenerationServiceTester.Result.Ok(2), result)
        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a keyword-less endpoint still counts as reachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        assertEquals(
            GenerationServiceTester.Result.ReachableWithoutModels,
            GenerationServiceTester.test(service(), client),
        )
        server.enqueue(MockResponse().setResponseCode(405))
        assertEquals(
            GenerationServiceTester.Result.ReachableWithoutModels,
            GenerationServiceTester.test(service(), client),
        )
    }

    @Test
    fun `an http error carries the status and body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val result = GenerationServiceTester.test(service(), client)
        assertTrue(result is GenerationServiceTester.Result.Failed)
        val message = (result as GenerationServiceTester.Result.Failed).message
        assertTrue(message, message.contains("401"))
        assertTrue(message, message.contains("bad key"))
    }

    @Test
    fun `a dead host fails with the io message`() = runBlocking {
        val dead = service().copy(baseUrl = "http://127.0.0.1:1")
        val result = GenerationServiceTester.test(dead, client, timeoutMs = 1_000)
        assertTrue(result is GenerationServiceTester.Result.Failed)
    }

    @Test
    fun `model counting tolerates both shapes and garbage`() {
        assertEquals(2, GenerationServiceTester.countModels("""{"data":[{},{}]}"""))
        assertEquals(3, GenerationServiceTester.countModels("""[{},{},{}]"""))
        assertEquals(0, GenerationServiceTester.countModels("""{"object":"list"}"""))
        assertEquals(0, GenerationServiceTester.countModels("not json"))
    }
}
