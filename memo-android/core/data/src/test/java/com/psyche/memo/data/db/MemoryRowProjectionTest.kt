package com.psyche.memo.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `memory_entry_rows` CHECK constraints only accept a well-formed projection,
 * so [MemoryEntryRowDao.Row.fromPayload] repairs what a stored payload may
 * carry: an assistant scope without an owner, an unknown type/status from a
 * newer build, an `updatedAt` before `createdAt`. The payload itself is kept
 * verbatim — it stays authoritative on read.
 */
class MemoryRowProjectionTest {

    private fun payload(json: String) =
        MemoryEntryRowDao.Row.fromPayload(id = "mem_1", payload = json, sortOrder = 0)

    @Test
    fun `global entry keeps its columns and normalizes content`() {
        val row = payload(
            """{"id":"mem_1","scope":"global","type":"identity","status":"active",""" +
                """"content":"  Prefers   Dark Mode ","createdAt":10,"updatedAt":20}""",
        )
        assertEquals("global", row.scope)
        assertNull(row.assistantId)
        assertEquals("identity", row.type)
        assertEquals("active", row.status)
        // `content` mirrors the payload; only `content_normalized` is folded.
        assertEquals("  Prefers   Dark Mode ", row.content)
        assertEquals("prefers dark mode", row.contentNormalized)
        assertEquals(10L, row.entryCreatedAt)
        assertEquals(20L, row.entryUpdatedAt)
    }

    @Test
    fun `assistant scope keeps its owner`() {
        val row = payload(
            """{"scope":"assistant","assistantId":"a1","type":"workflow","status":"archived","content":"x",""" +
                """"createdAt":5,"updatedAt":6}""",
        )
        assertEquals("assistant", row.scope)
        assertEquals("a1", row.assistantId)
        assertEquals("archived", row.status)
    }

    @Test
    fun `assistant scope without an owner degrades to global`() {
        val row = payload("""{"scope":"assistant","type":"voice","content":"x","createdAt":5,"updatedAt":6}""")
        assertEquals("global", row.scope)
        assertNull(row.assistantId)
    }

    @Test
    fun `unknown type and status fall back to the enum defaults`() {
        val row = payload("""{"scope":"global","type":"telepathy","status":"someday","content":"x","createdAt":5,"updatedAt":6}""")
        assertEquals("identity", row.type)
        assertEquals("active", row.status)
    }

    @Test
    fun `updated before created is floored at created`() {
        val row = payload("""{"scope":"global","type":"identity","content":"x","createdAt":30,"updatedAt":10}""")
        assertEquals(30L, row.entryUpdatedAt)
    }

    @Test
    fun `malformed payload still projects a writable row`() {
        val row = payload("not json")
        assertEquals("global", row.scope)
        assertNull(row.assistantId)
        assertEquals("identity", row.type)
        assertEquals("active", row.status)
        assertEquals("", row.content)
        assertEquals(0L, row.entryCreatedAt)
        assertEquals(0L, row.entryUpdatedAt)
        assertEquals("not json", row.payload)
    }

    @Test
    fun `legacy assistant memory projects the owner column`() {
        val row = AssistantMemoryRowDao.Row.fromPayload(
            id = "3",
            payload = """{"id":3,"assistantId":"a9","content":"hi"}""",
            sortOrder = 2,
        )
        assertEquals("3", row.id)
        assertEquals("a9", row.assistantId)
        assertEquals(2, row.sortOrder)
    }
}
