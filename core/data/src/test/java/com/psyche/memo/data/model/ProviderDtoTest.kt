package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun providerConfigRoundTripsCoreFields() {
        val original = """{"id":"openai","enabled":true,"name":"OpenAI","apiKey":"sk-abc","baseUrl":"https://api.openai.com","providerType":"openai","models":["gpt-4o"]}"""
        val config = ProviderConfig.fromJsonString(json, original)
        assertEquals("openai", config.id)
        assertTrue(config.enabled)
        assertEquals("sk-abc", config.apiKey)
        assertEquals("https://api.openai.com", config.baseUrl)
        assertEquals(listOf("gpt-4o"), config.models)
    }

    @Test
    fun multiKeyPicksFirstEnabled() {
        val original = """{"id":"openai","name":"OpenAI","apiKey":"sk-main","multiKeyEnabled":true,"apiKeys":[{"order":0,"key":"sk-1","enabled":true},{"order":1,"key":"sk-2","enabled":false}]}"""
        val config = ProviderConfig.fromJsonString(json, original)
        assertEquals("sk-1", config.effectiveApiKey())
    }

    @Test
    fun providerClassifiesKind() {
        val anthropic = ProviderConfig.fromJsonString(json, """{"id":"claude","name":"Claude","providerType":"claude"}""")
        assertEquals("anthropic", anthropic.classifiedKind())
        val google = ProviderConfig.fromJsonString(json, """{"id":"gemini","name":"Gemini","providerType":"google"}""")
        assertEquals("gemini", google.classifiedKind())
        val openai = ProviderConfig.fromJsonString(json, """{"id":"openai","name":"OpenAI"}""")
        assertEquals("openai", openai.classifiedKind())
    }

    @Test
    fun unknownProviderFieldsAreTolerated() {
        val original = """{"id":"openai","name":"OpenAI","apiKey":"k","baseUrl":"u","futureField":{"x":1}}"""
        val config = ProviderConfig.fromJsonString(json, original)
        assertEquals("OpenAI", config.name)
        assertTrue(config.baseUrl == "u")
    }

    @Test
    fun assistantRoundTripsCoreFields() {
        val original = """{"id":"a1","name":"Helper","systemPrompt":"You are helpful.","chatModelProvider":"openai","chatModelId":"gpt-4o","mcpServerIds":["mcp1"],"localToolIds":["search_web"],"contextMessageSize":32}"""
        val assistant = Assistant.fromJsonString(json, original)
        assertEquals("Helper", assistant.name)
        assertEquals("You are helpful.", assistant.systemPrompt)
        assertEquals("openai", assistant.chatModelProvider)
        assertEquals(listOf("mcp1"), assistant.mcpServerIds)
        assertEquals(32, assistant.contextMessageSize)
    }

    @Test
    fun assistantToleratesMissingOptionalFields() {
        val original = """{"id":"a2","name":"Minimal"}"""
        val assistant = Assistant.fromJsonString(json, original)
        assertEquals("Minimal", assistant.name)
        assertEquals(64, assistant.contextMessageSize) // default
        assertTrue(assistant.mcpServerIds.isEmpty())
    }

    @Test
    fun messagePartPayloadRoundTrips() {
        val text = TextPart("hello")
        assertEquals("text", text.kind)
        assertEquals("hello", text.encodePayload())
        val image = ImagePart(uri = "file:///a/b.png", mime = "image/png")
        assertEquals("image", image.kind)
        val back = ImagePart.fromPayload(image.encodePayload())
        assertEquals("file:///a/b.png", back.uri)
        assertEquals("image/png", back.mime)
        val file = FilePart(uri = "file:///c.pdf", name = "c.pdf")
        assertEquals("file", file.kind)
        val fileBack = FilePart.fromPayload(file.encodePayload())
        assertEquals("c.pdf", fileBack.name)
        assertEquals("file:///c.pdf", fileBack.uri)
    }

    @Test
    fun unknownPartIsLossless() {
        val unknown = UnknownPart(rawKind = "future_part", payload = """{"a":1}""")
        assertEquals("future_part", unknown.kind)
        assertEquals("""{"a":1}""", unknown.encodePayload())
    }

    @Test
    fun compactionPartRoundTripsThroughTheRow() {
        val part = CompactionPart(summary = "## Goal\n- ship it", recent = "[User]: hi", boundaryOrder = 7)
        assertEquals("compaction", part.kind)
        val row = MessagePart.fromRow(part.kind, part.encodePayload())
        assertTrue(row is CompactionPart)
        assertEquals("## Goal\n- ship it", (row as CompactionPart).summary)
        assertEquals("[User]: hi", row.recent)
        assertEquals(7, row.boundaryOrder)
    }

    @Test
    fun compactionPartToleratesBrokenPayloads() {
        val part = CompactionPart.fromPayload("not json")
        assertEquals("", part.summary)
        assertEquals("", part.recent)
        assertEquals(-1, part.boundaryOrder)
    }
}
