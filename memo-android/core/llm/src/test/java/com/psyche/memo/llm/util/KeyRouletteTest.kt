package com.psyche.memo.llm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KeyRouletteTest {

    /** 每次取用都推进一秒，保证 LRU 的时间戳严格有序、不落在同一毫秒。 */
    private fun tickingRoulette(file: File): KeyRoulette {
        var tick = 1_000L
        return KeyRoulette.lru(file) { tick += 1_000L; tick }
    }

    @Test
    fun picksNeverUsedKeyFirst() {
        val roulette = KeyRoulette.lru(testFile())
        assertEquals("k1", roulette.next(listOf("k1", "k2", "k3"), "openai"))
        assertEquals("k2", roulette.next(listOf("k1", "k2", "k3"), "openai"))
        assertEquals("k3", roulette.next(listOf("k1", "k2", "k3"), "openai"))
    }

    @Test
    fun leastRecentlyUsedWinsAfterAllUsed() {
        val roulette = tickingRoulette(testFile())
        roulette.next(listOf("a", "b"), "p")
        roulette.next(listOf("a", "b"), "p")
        // "a" was used first => least recently used => next pick is "a"
        assertEquals("a", roulette.next(listOf("a", "b"), "p"))
        assertEquals("b", roulette.next(listOf("a", "b"), "p"))
    }

    @Test
    fun providerIsolation() {
        val roulette = KeyRoulette.lru(testFile())
        assertEquals("x", roulette.next(listOf("x", "y"), "openai"))
        // Different provider has independent LRU state.
        assertEquals("p", roulette.next(listOf("p", "q"), "anthropic"))
    }

    @Test
    fun persistsAcrossInstances() {
        val file = testFile()
        val first = tickingRoulette(file)
        assertEquals("k1", first.next(listOf("k1", "k2"), "openai"))
        val second = tickingRoulette(file)
        // k1 was used; LRU picks k2 next — persistence works.
        assertEquals("k2", second.next(listOf("k1", "k2"), "openai"))
    }

    @Test
    fun emptyKeysReturnsEmpty() {
        val roulette = KeyRoulette.lru(testFile())
        assertEquals("", roulette.next(emptyList(), "openai"))
        assertEquals("", roulette.next(listOf("  ", "\t"), "openai"))
    }

    @Test
    fun dedupesAndTrims() {
        val file = testFile()
        val roulette = KeyRoulette.lru(file)
        assertEquals("k1", roulette.next(listOf(" k1 ", "k1", " k2 "), "openai"))
        assertEquals("k2", roulette.next(listOf(" k1 ", "k1", " k2 "), "openai"))
        // Selecting keys persisted the cache file.
        assertTrue(file.exists())
    }

    private fun testFile(): File {
        val dir = Files.createTempDirectory("keyroulette").toFile()
        return File(dir, "lru_key_roulette.json")
    }
}
