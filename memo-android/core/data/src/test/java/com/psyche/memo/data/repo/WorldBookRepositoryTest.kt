package com.psyche.memo.data.repo

import com.psyche.memo.data.model.WorldBook
import com.psyche.memo.data.model.WorldBookEntry
import com.psyche.memo.data.model.WorldBookInjectionPosition
import com.psyche.memo.data.model.WorldBookInjectionRole
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port coverage of WorldBookStore / world_book_page.dart import + export helpers. */
class WorldBookRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun entry(id: String = "e1") = WorldBookEntry(id = id, name = id, content = "c")

    @Test
    fun `assistantKey falls back to the global key`() {
        assertEquals("__global__", WorldBookRepository.assistantKey(null))
        assertEquals("__global__", WorldBookRepository.assistantKey("  "))
        assertEquals("a1", WorldBookRepository.assistantKey(" a1 "))
    }

    @Test
    fun `parses the rikkahub export wrapper`() {
        val text = """{"version":1,"type":"lorebook","data":{"id":"b1","name":"Lore","entries":[]}}"""
        val book = WorldBookRepository.parseImportedBook(json, text)!!
        assertEquals("b1", book.id)
        assertEquals("Lore", book.name)
    }

    @Test
    fun `parses a flat book object and rejects anything else`() {
        val flat = WorldBookRepository.parseImportedBook(json, """{"id":"b1","entries":[{"id":"e1"}]}""")!!
        assertEquals(listOf("e1"), flat.entries.map { it.id })

        assertNull(WorldBookRepository.parseImportedBook(json, """{"id":"b1"}"""))
        assertNull(WorldBookRepository.parseImportedBook(json, "not json"))
        assertNull(WorldBookRepository.parseImportedBook(json, "[1,2]"))
    }

    @Test
    fun `normalizes blank and colliding ids`() {
        val incoming = WorldBook(
            id = "b1",
            entries = listOf(entry(""), entry("e2"), entry("e2")),
        )
        var seq = 0
        val result = WorldBookRepository.normalizeImportedBook(incoming, setOf("b1")) { "new-${seq++}" }
        assertEquals("new-0", result.id)
        assertEquals(listOf("new-1", "e2", "new-2"), result.entries.map { it.id })
    }

    @Test
    fun `keeps ids that do not collide`() {
        val incoming = WorldBook(id = "b9", entries = listOf(entry("e9")))
        val result = WorldBookRepository.normalizeImportedBook(incoming, setOf("b1")) { "unused" }
        assertEquals("b9", result.id)
        assertEquals(listOf("e9"), result.entries.map { it.id })
    }

    @Test
    fun `export json carries the lorebook wrapper`() {
        val book = WorldBook(id = "b1", name = "Lore", entries = listOf(entry("e1")))
        val text = WorldBookRepository.toExportJson(book)
        assertEquals(true, text.contains(""""version": 1"""))
        assertEquals(true, text.contains(""""type": "lorebook""""))
        assertEquals(true, text.contains(""""id": "b1""""))
    }

    @Test
    fun `safe file name strips path characters and caps the length`() {
        assertEquals("a_b", WorldBookRepository.safeFileName("a/b"))
        assertEquals("lorebook", WorldBookRepository.safeFileName("   "))
        assertEquals(80, WorldBookRepository.safeFileName("x".repeat(120)).length)
    }

    @Test
    fun `enum wire spelling and unknown-value fallback match the dart model`() {
        val text = json.encodeToString(
            WorldBookEntry.serializer(),
            entry().copy(position = WorldBookInjectionPosition.TOP_OF_CHAT, role = WorldBookInjectionRole.ASSISTANT),
        )
        assertEquals(true, text.contains(""""position":"TOP_OF_CHAT""""))
        assertEquals(true, text.contains(""""role":"ASSISTANT""""))

        val decoded = json.decodeFromString(
            WorldBookEntry.serializer(),
            """{"id":"e1","position":"NOPE","role":"NOPE"}""",
        )
        assertEquals(WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT, decoded.position)
        assertEquals(WorldBookInjectionRole.USER, decoded.role)
    }
}
