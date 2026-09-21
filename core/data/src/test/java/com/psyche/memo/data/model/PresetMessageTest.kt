package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** preset_message.dart decodeList/encodeList — the assistant payload contract. */
class PresetMessageTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun elements(text: String): List<JsonElement> = json.parseToJsonElement(text).jsonArray.toList()

    @Test
    fun decodesFlutterPresetList() {
        val list = PresetMessage.decodeList(
            elements("""[{"id":"a","role":"user","content":"hi"},{"id":"b","role":"assistant","content":"hello"}]"""),
        )
        assertEquals(listOf("a" to "user", "b" to "assistant"), list.map { it.id to it.role })
        assertEquals(listOf("hi", "hello"), list.map { it.content })
    }

    @Test
    fun nonAssistantRoleFallsBackToUser() {
        val list = PresetMessage.decodeList(elements("""[{"id":"a","role":"system","content":"x"}]"""))
        assertEquals("user", list.single().role)
    }

    @Test
    fun missingIdGetsAFreshUuid() {
        val payload = """[{"role":"user","content":"x"}]"""
        val first = PresetMessage.decodeList(elements(payload)).single()
        val second = PresetMessage.decodeList(elements(payload)).single()
        assertTrue(first.id.isNotBlank())
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun dropsEntriesThatAreNotObjects() {
        val list = PresetMessage.decodeList(elements("""["nope",42,{"id":"a","role":"user","content":"x"}]"""))
        assertEquals(listOf("a"), list.map { it.id })
    }

    @Test
    fun encodeListWritesTheSameThreeKeys() {
        val encoded = PresetMessage.encodeList(listOf(PresetMessage(id = "a", role = "assistant", content = "hi")))
        assertEquals("""{"id":"a","role":"assistant","content":"hi"}""", encoded.single().toString())
    }
}
