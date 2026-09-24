package com.psyche.memo.provider.search

import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchResult
import com.psyche.memo.data.model.SearchResultItem
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.TavilyOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Port coverage of search_tool_service.dart executeSearch. */
class SearchToolServiceTest {

    private class FakeEngine(
        private val result: SearchResult? = null,
        private val error: Exception? = null,
    ) : SearchEngine {
        var lastQuery: String? = null
        override suspend fun search(
            query: String,
            options: SearchServiceOptions,
            common: SearchCommonOptions,
        ): SearchResult {
            lastQuery = query
            error?.let { throw it }
            return result!!
        }
    }

    private val service = TavilyOptions(id = "t", apiKey = "k")

    @Test
    fun `executeSearch stamps ids indexes and normalized urls`() = runBlocking {
        val engine = FakeEngine(
            SearchResult(
                answer = "because",
                items = listOf(
                    SearchResultItem("A", "example.com/a", "alpha"),
                    SearchResultItem("B", "https://b.example", "beta"),
                ),
            ),
        )
        val payload = Json.parseToJsonElement(
            SearchToolService.executeSearch("q", engine, service, SearchCommonOptions()),
        ) as JsonObject
        assertEquals("q", engine.lastQuery)
        assertEquals("because", (payload["answer"] as JsonPrimitive).content)
        val items = payload["items"] as JsonArray
        assertEquals(2, items.size)
        val first = items[0] as JsonObject
        assertEquals("https://example.com/a", (first["url"] as JsonPrimitive).content)
        assertEquals("1", (first["index"] as JsonPrimitive).content)
        val id = (first["id"] as JsonPrimitive).content
        assertEquals(6, id.length)
        val second = items[1] as JsonObject
        assertEquals("2", (second["index"] as JsonPrimitive).content)
        assertNotNull((second["id"] as JsonPrimitive).content)
    }

    @Test
    fun `engine failure becomes a search error payload`() = runBlocking {
        val engine = FakeEngine(error = SearchException("boom"))
        val payload = SearchToolService.executeSearch("q", engine, service, SearchCommonOptions())
        assertTrue(payload.contains("\"error\""))
        assertTrue(payload.contains("Search failed:"))
        assertTrue(payload.contains("boom"))
    }

    @Test
    fun `missing service reports the upstream message`() = runBlocking {
        val payload = SearchToolService.executeSearch("q", FakeEngine(), null, SearchCommonOptions())
        val obj = Json.parseToJsonElement(payload) as JsonObject
        // 上游那句原文（Dart 是 `{"error":"No search services configured"}`）现在落在
        // `message` 上 —— 规范形状里 `error` 是错误码。本工程给所有工具错误加了
        // `status` + 必带 `instruction`（见 PORTING §5.54），所以钉不变量而不是钉字节。
        assertEquals("No search services configured", obj["message"]!!.jsonPrimitive.content)
        assertEquals("tool_error", obj["type"]!!.jsonPrimitive.content)
        assertEquals("error", obj["status"]!!.jsonPrimitive.content)
        assertEquals("search_unavailable", obj["error"]!!.jsonPrimitive.content)
        assertEquals("search_web", obj["tool"]!!.jsonPrimitive.content)
        assertTrue(obj["instruction"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `tool definition carries the query parameter`() {
        val schema = Json.parseToJsonElement(SearchToolService.parametersJson()) as JsonObject
        assertEquals("object", (schema["type"] as JsonPrimitive).content)
        val required = schema["required"] as JsonArray
        assertEquals("query", (required[0] as JsonPrimitive).content)
        val properties = schema["properties"] as JsonObject
        assertNotNull(properties["query"])
        assertEquals("search_web", SearchToolService.TOOL_NAME)
    }

    @Test
    fun `system prompt documents the citation contract`() {
        // Original project prompts the bare `[cite:id]` marker; the renderer
        // turns it into a numbered capsule.
        assertTrue(SearchToolService.SYSTEM_PROMPT.contains("[cite:id]"))
        assertTrue(SearchToolService.SYSTEM_PROMPT.contains("<citations>"))
    }

    @Test
    fun `tool description documents the cite contract`() {
        assertTrue(SearchToolService.TOOL_DESCRIPTION.contains("[cite:id]"))
        assertFalse(SearchToolService.TOOL_DESCRIPTION.contains("[citation,domain]"))
    }

    @Test
    fun `citation prompts never ask for domain metadata`() {
        assertFalse(SearchToolService.SYSTEM_PROMPT.contains("[citation,domain]"))
    }
}
