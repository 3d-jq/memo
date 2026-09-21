package com.psyche.memo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of search_service_editor_page._configurationFields. */
class SearchConfigFieldsTest {

    @Test
    fun `bing and kelivo have no configuration fields`() {
        assertTrue(searchConfigFields("bing_local").isEmpty())
        assertTrue(searchConfigFields("kelivo").isEmpty())
    }

    @Test
    fun `tavily needs an api key and offers a custom url`() {
        val fields = searchConfigFields("tavily")
        assertEquals(listOf("apiKey", "url"), fields.map { it.key })
        assertTrue(fields[0].required)
        assertTrue(fields[0].obscure)
        assertTrue(fields[0].multiKeyAfter)
        assertFalse(fields[1].required)
        assertEquals("https://api.tavily.com/search", fields[1].hint)
    }

    @Test
    fun `searxng requires the instance url only`() {
        val fields = searchConfigFields("searxng")
        assertEquals(
            listOf("url", "engines", "language", "username", "password"),
            fields.map { it.key },
        )
        assertTrue(fields[0].required)
        assertTrue(fields[4].obscure)
        assertTrue(fields.none { it.multiKeyAfter })
    }

    @Test
    fun `brave exposes mode dropdown and token budget`() {
        val fields = searchConfigFields("brave")
        assertEquals(listOf("apiKey", "mode", "maximumNumberOfTokens"), fields.map { it.key })
        assertEquals(
            listOf("web", "llmContext"),
            fields[1].dropdown?.map { it.first },
        )
        assertTrue(fields[2].number)
    }

    @Test
    fun `grok carries model reasoning url and system prompt`() {
        val fields = searchConfigFields("grok")
        assertEquals(
            listOf("apiKey", "model", "reasoningEffort", "customUrl", "systemPrompt"),
            fields.map { it.key },
        )
        assertEquals(6, fields.last().maxLines)
    }

    @Test
    fun `serper and querit keep their locale filters`() {
        assertEquals(listOf("apiKey", "gl", "hl", "tbs", "page"), searchConfigFields("serper").map { it.key })
        assertEquals(
            listOf("apiKey", "sitesInclude", "sitesExclude", "timeRange", "countries", "languages"),
            searchConfigFields("querit").map { it.key },
        )
    }

    @Test
    fun `every picker type has a display name and default service`() {
        for (type in SearchServiceUi.PROVIDER_TYPES) {
            val service = SearchServiceUi.defaultService(type, "id-$type")
            assertEquals(type, SearchServiceUi.typeOf(service))
            assertTrue(SearchServiceUi.nameRes(service) != 0)
            assertTrue(SearchServiceUi.brandOf(service).isNotEmpty())
        }
    }
}
