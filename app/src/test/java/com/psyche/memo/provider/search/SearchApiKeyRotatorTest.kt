package com.psyche.memo.provider.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port coverage of search_api_key_rotator.dart. */
class SearchApiKeyRotatorTest {

    @After
    fun tearDown() = SearchApiKeyRotator.reset()

    @Test
    fun `rotation pool trims and dedupes primary plus extras`() {
        assertEquals(
            listOf("a", "b", "c"),
            SearchApiKeyRotator.rotationPool(" a ", listOf("b", " a ", "", "c")),
        )
        assertEquals(emptyList<String>(), SearchApiKeyRotator.rotationPool("  ", listOf(" ", "")))
    }

    @Test
    fun `select round-robins across the pool`() {
        val picked = (1..5).map { SearchApiKeyRotator.select("svc", "a", listOf("b", "c")) }
        assertEquals(listOf("a", "b", "c", "a", "b"), picked)
    }

    @Test
    fun `single key pool never advances the cursor`() {
        assertEquals("only", SearchApiKeyRotator.select("svc2", "only", emptyList()))
        assertEquals("only", SearchApiKeyRotator.select("svc2", "only", emptyList()))
        assertEquals("only", SearchApiKeyRotator.select("svc2", "only", listOf(" only ")))
    }

    @Test
    fun `empty pool returns the raw primary`() {
        assertEquals("", SearchApiKeyRotator.select("svc3", "", emptyList()))
    }

    @Test
    fun `cursors are per service id`() {
        assertEquals("a", SearchApiKeyRotator.select("s1", "a", listOf("b")))
        assertEquals("a", SearchApiKeyRotator.select("s2", "a", listOf("b")))
        assertEquals("b", SearchApiKeyRotator.select("s1", "a", listOf("b")))
    }

    @Test
    fun `parseBatch splits on whitespace commas and semicolons`() {
        assertEquals(
            listOf("k1", "k2", "k3", "k4"),
            SearchApiKeyRotator.parseBatch("k1, k2; k3\n\tk4,, k2"),
        )
    }

    @Test
    fun `mask keeps the first and last four characters`() {
        assertEquals("••••••••", SearchApiKeyRotator.mask("short"))
        assertEquals("abcd••••wxyz", SearchApiKeyRotator.mask("abcdefghwxyz"))
    }
}
