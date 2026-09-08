package com.psyche.memo.llm.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Port coverage of the multimodal content builders. */
class MessageContentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(content: String? = null, parts: List<String> = emptyList()) =
        LlmMessage(role = "user", content = content, parts = parts)

    private fun imagePayload(uri: String, mime: String? = null): String =
        buildString {
            append("{\"uri\":\"").append(uri).append('"')
            if (mime != null) append(",\"mime\":\"").append(mime).append('"')
            append('}')
        }

    @Test
    fun `text-only messages keep the plain string shape`() {
        val openAi = MessageContent.openAiContent(msg("hello"))
        assertTrue(openAi is JsonPrimitive)
        assertEquals("hello", (openAi as JsonPrimitive).content)

        val claude = MessageContent.claudeContent(msg("hello"))
        assertTrue(claude is JsonPrimitive)

        val gemini = MessageContent.geminiParts(msg("hello"))
        assertEquals(1, gemini.size)
        assertEquals("hello", ((gemini[0] as JsonObject)["text"] as JsonPrimitive).content)
    }

    @Test
    fun `data uris inline as base64 for every provider`() {
        val uri = "data:image/jpeg;base64,QUJD"
        val m = msg("look", listOf(imagePayload(uri, "image/jpeg")))

        val openAi = MessageContent.openAiContent(m) as JsonArray
        assertEquals("text", ((openAi[0] as JsonObject)["type"] as JsonPrimitive).content)
        val imageUrl = (openAi[1] as JsonObject)["image_url"] as JsonObject
        assertEquals(uri, (imageUrl["url"] as JsonPrimitive).content)

        val claude = MessageContent.claudeContent(m) as JsonArray
        val source = (claude[1] as JsonObject)["source"] as JsonObject
        assertEquals("base64", (source["type"] as JsonPrimitive).content)
        assertEquals("image/jpeg", (source["media_type"] as JsonPrimitive).content)
        assertEquals("QUJD", (source["data"] as JsonPrimitive).content)

        val gemini = MessageContent.geminiParts(m)
        val inline = (gemini[1] as JsonObject)["inline_data"] as JsonObject
        assertEquals("image/jpeg", (inline["mime_type"] as JsonPrimitive).content)
        assertEquals("QUJD", (inline["data"] as JsonPrimitive).content)
    }

    @Test
    fun `local files are read and base64 encoded`() {
        val file = tmp.newFile("pic.png")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val m = msg("x", listOf(imagePayload(file.toURI().toString(), "image/png")))

        val claude = MessageContent.claudeContent(m) as JsonArray
        val source = (claude[1] as JsonObject)["source"] as JsonObject
        assertEquals("AQID", (source["data"] as JsonPrimitive).content)
    }

    @Test
    fun `remote urls stay urls for openai and become text for claude`() {
        val m = msg("x", listOf(imagePayload("https://cdn.example/a.png", "image/png")))

        val openAi = MessageContent.openAiContent(m) as JsonArray
        val imageUrl = (openAi[1] as JsonObject)["image_url"] as JsonObject
        assertEquals("https://cdn.example/a.png", (imageUrl["url"] as JsonPrimitive).content)

        val claude = MessageContent.claudeContent(m) as JsonArray
        assertEquals("text", ((claude[1] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("https://cdn.example/a.png", ((claude[1] as JsonObject)["text"] as JsonPrimitive).content)

        val gemini = MessageContent.geminiParts(m)
        val fileData = (gemini[1] as JsonObject)["file_data"] as JsonObject
        assertEquals("https://cdn.example/a.png", (fileData["file_uri"] as JsonPrimitive).content)
    }

    @Test
    fun `duplicate sources collapse and file parts are skipped`() {
        val m = msg(
            "x",
            listOf(
                imagePayload("data:image/png;base64,QQ==", "image/png"),
                imagePayload("data:image/png;base64,QQ==", "image/png"),
                """{"uri":"/tmp/report.pdf","name":"report.pdf","mime":"application/pdf"}""",
            ),
        )
        assertEquals(1, MessageContent.imagesOf(m).size)
        val openAi = MessageContent.openAiContent(m) as JsonArray
        assertEquals(2, openAi.size) // text + one image
    }

    @Test
    fun `mime inference prefers explicit then data uri then extension`() {
        assertEquals("image/webp", MessageContent.mimeFor("/a/b.webp", "image/webp"))
        assertEquals("image/png", MessageContent.mimeFor("data:image/png;base64,QQ==", null))
        assertEquals("image/jpeg", MessageContent.mimeFor("/a/b.JPG", null))
        assertEquals("application/pdf", MessageContent.mimeFor("/a/b.pdf", null))
        assertEquals("image/png", MessageContent.mimeFor("/a/unknown.bin", null))
    }
}
