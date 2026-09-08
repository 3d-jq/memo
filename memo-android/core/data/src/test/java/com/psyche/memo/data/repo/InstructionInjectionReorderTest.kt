package com.psyche.memo.data.repo

import com.psyche.memo.data.model.InstructionInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port coverage of InstructionInjectionProvider.reorderWithinGroup. */
class InstructionInjectionReorderTest {

    private fun item(id: String, group: String = "") =
        InstructionInjection(id = id, title = id, prompt = id, group = group)

    @Test
    fun `reorders only the matching group`() {
        val list = listOf(item("a", "g1"), item("b", "g2"), item("c", "g1"), item("d", "g2"))
        val result = InstructionInjectionRepository.reorderGroupSubset(list, "g1", 0, 1)!!
        // removeAt(0) then insert(1) puts the moved item after the next one.
        assertEquals(listOf("c", "b", "a", "d"), result.map { it.id })
    }

    @Test
    fun `ungrouped entries form their own group`() {
        val list = listOf(item("a"), item("b", "g"), item("c"))
        val result = InstructionInjectionRepository.reorderGroupSubset(list, "  ", 0, 1)!!
        assertEquals(listOf("c", "b", "a"), result.map { it.id })
    }

    @Test
    fun `out of range and missing groups are no-ops`() {
        val list = listOf(item("a", "g"))
        assertNull(InstructionInjectionRepository.reorderGroupSubset(list, "g", 0, 5))
        assertNull(InstructionInjectionRepository.reorderGroupSubset(list, "g", 5, 0))
        assertNull(InstructionInjectionRepository.reorderGroupSubset(list, "missing", 0, 0))
    }

    @Test
    fun `assistant key mirrors the store`() {
        assertEquals("", InstructionInjectionRepository.assistantKey(null))
        assertEquals("", InstructionInjectionRepository.assistantKey("  "))
        assertEquals("a1", InstructionInjectionRepository.assistantKey("a1"))
    }

    @Test
    fun `json shape matches the upstream model`() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        assertEquals(
            """{"id":"x","title":"t","prompt":"p","group":"g"}""",
            json.encodeToString(InstructionInjection.serializer(), InstructionInjection("x", "t", "p", "g")),
        )
    }
}
