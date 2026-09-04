package com.psyche.memo.llm.provider

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ListModelsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun openaiListsModels() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"object":"list","data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}"""),
        )
        val client = OpenAiChatCompletionsClient(httpClient = OkHttpClient())
        val models = client.listModels(server.url("/").toString(), "sk-test")
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models.map { it.id })
        val req = server.takeRequest()
        assertTrue(req.path!!.endsWith("/models"))
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test
    fun openaiListModelsEmptyOnMissingData() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}"""))
        val client = OpenAiChatCompletionsClient(httpClient = OkHttpClient())
        assertTrue(client.listModels(server.url("/").toString(), "sk-test").isEmpty())
    }

    @Test
    fun claudeReturnsEmpty() = runBlocking {
        val client = ClaudeClient(httpClient = OkHttpClient())
        assertTrue(client.listModels("https://api.anthropic.com", "k").isEmpty())
    }

    @Test
    fun geminiListsModels() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"models":[{"name":"models/gemini-2.0-flash"},{"name":"models/gemini-1.5-pro"}]}"""),
        )
        val client = GeminiClient(httpClient = OkHttpClient())
        val models = client.listModels(server.url("/").toString(), "key1")
        assertEquals(listOf("gemini-2.0-flash", "gemini-1.5-pro"), models.map { it.id })
    }
}
