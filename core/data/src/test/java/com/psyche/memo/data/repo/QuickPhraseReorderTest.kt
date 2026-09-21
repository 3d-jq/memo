package com.psyche.memo.data.repo

import com.psyche.memo.data.model.QuickPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port coverage of QuickPhraseProvider._reorderInMemory. */
class QuickPhraseReorderTest {

    private fun global(id: String) = QuickPhrase(id = id, title = id, content = id, isGlobal = true)
    private fun scoped(id: String, assistantId: String) =
        QuickPhrase(id = id, title = id, content = id, isGlobal = false, assistantId = assistantId)

    @Test
    fun `reorders the global subset in place`() {
        val list = listOf(global("a"), scoped("x", "a1"), global("b"), global("c"))
        val result = QuickPhraseRepository.reorderSubset(list, oldIndex = 0, newIndex = 2, assistantId = null)!!
        assertEquals(listOf("b", "x", "c", "a"), result.map { it.id })
    }

    @Test
    fun `reorders an assistant subset without moving other rows`() {
        val list = listOf(scoped("x1", "a1"), global("g"), scoped("x2", "a1"), scoped("y", "a2"))
        val result = QuickPhraseRepository.reorderSubset(list, oldIndex = 0, newIndex = 1, assistantId = "a1")!!
        assertEquals(listOf("x2", "g", "x1", "y"), result.map { it.id })
    }

    @Test
    fun `out of range or empty subsets are no-ops`() {
        val list = listOf(global("a"))
        assertNull(QuickPhraseRepository.reorderSubset(list, 0, 5, null))
        assertNull(QuickPhraseRepository.reorderSubset(list, 5, 0, null))
        assertNull(QuickPhraseRepository.reorderSubset(list, 0, 0, "missing-assistant"))
    }

    @Test
    fun `json shape matches the upstream model`() {
        val phrase = scoped("x", "a1")
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val text = json.encodeToString(QuickPhrase.serializer(), phrase)
        assertEquals(
            """{"id":"x","title":"x","content":"x","isGlobal":false,"assistantId":"a1"}""",
            text,
        )
    }
}
