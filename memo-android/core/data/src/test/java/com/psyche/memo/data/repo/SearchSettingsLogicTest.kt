package com.psyche.memo.data.repo

import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.TavilyOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of the search-settings codec + clamping rules. */
class SearchSettingsLogicTest {

    @Test
    fun `missing or empty raw falls back to the default option`() {
        val a = SearchSettingsLogic.parseServices(null)
        val b = SearchSettingsLogic.parseServices("")
        assertEquals(1, a.size)
        assertTrue(a[0] is BingLocalOptions)
        assertEquals("default", a[0].id)
        assertTrue(b[0] is BingLocalOptions)
    }

    @Test
    fun `invalid json falls back to the default option`() {
        val parsed = SearchSettingsLogic.parseServices("{not json")
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is BingLocalOptions)
    }

    @Test
    fun `empty array falls back to the default option`() {
        val parsed = SearchSettingsLogic.parseServices("[]")
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is BingLocalOptions)
    }

    @Test
    fun `roundtrip preserves order and types`() {
        val services = listOf<SearchServiceOptions>(
            TavilyOptions(id = "t", apiKey = "k", extraApiKeys = listOf("k2")),
            BingLocalOptions(id = "b"),
        )
        val encoded = SearchSettingsLogic.encodeServices(services)
        val decoded = SearchSettingsLogic.parseServices(encoded)
        assertEquals(listOf("t", "b"), decoded.map { it.id })
        assertTrue(decoded[0] is TavilyOptions)
        assertTrue(decoded[1] is BingLocalOptions)
    }

    @Test
    fun `selection clamps into the list`() {
        assertEquals(0, SearchSettingsLogic.clampSelected(5, 0))
        assertEquals(0, SearchSettingsLogic.clampSelected(-1, 3))
        assertEquals(2, SearchSettingsLogic.clampSelected(9, 3))
        assertEquals(1, SearchSettingsLogic.clampSelected(1, 3))
    }

    @Test
    fun `common options parse tolerates junk and keeps defaults`() {
        assertEquals(SearchCommonOptions(), SearchSettingsLogic.parseCommon("junk"))
        assertEquals(SearchCommonOptions(), SearchSettingsLogic.parseCommon(null))
        val custom = SearchSettingsLogic.parseCommon("""{"resultSize":25,"timeout":12000}""")
        assertEquals(25, custom.resultSize)
        assertEquals(12000, custom.timeout)
    }

    @Test
    fun `preference ints accept json numbers and quoted numbers`() {
        assertEquals(3, SearchSettingsLogic.parseInt("3", 0))
        assertEquals(3, SearchSettingsLogic.parseInt("\"3\"", 0))
        assertEquals(7, SearchSettingsLogic.parseInt("junk", 7))
        assertEquals(7, SearchSettingsLogic.parseInt(null, 7))
    }
}
