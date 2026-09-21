package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果附带的图片 —— `ToolCallPart` payload 的 `images` 键（本工程新增）。
 *
 * 这条链决定「工作区读到的图片重开对话还在不在」：payload 是落库的 JSON，写进去
 * 解不出来图就没了；反过来，**没有图片时不能凭空多出一个键**（老 payload 的形状
 * 必须原样保持，备份/对比才不会漂）。
 */
class ToolCallPartImagesTest {

    private fun encode(images: List<ToolImage>) = ToolCallPart.encode(
        id = "c1",
        name = "workspace_read_file",
        arguments = JsonObject(mapOf("path" to JsonPrimitive("/workspace/a.png"))),
        content = JsonPrimitive("""{"path":"/workspace/a.png"}"""),
        server = false,
        images = images,
    )

    @Test
    fun `images survive an encode-decode round trip`() {
        val part = encode(
            listOf(
                ToolImage(uri = "/files/tool_images/1_a.png", mime = "image/png"),
                ToolImage(uri = "/files/tool_images/2_b.jpg", mime = null),
            ),
        )
        val payload = ToolCallPart.decode(part.payloadJson)!!
        assertEquals(2, payload.images.size)
        assertEquals("/files/tool_images/1_a.png", payload.images[0].uri)
        assertEquals("image/png", payload.images[0].mime)
        assertEquals("/files/tool_images/2_b.jpg", payload.images[1].uri)
        assertEquals(null, payload.images[1].mime)
    }

    @Test
    fun `payloads without images keep the original shape`() {
        val json = Json.parseToJsonElement(encode(emptyList()).payloadJson).jsonObject
        assertFalse("没有图片时不该出现 images 键", json.containsKey("images"))
    }

    @Test
    fun `missing or malformed images decode to an empty list`() {
        val old = """{"id":"c1","name":"t","arguments":{},"content":"x","server":false}"""
        assertTrue(ToolCallPart.decode(old)!!.images.isEmpty())

        val malformed = """
            {"id":"c1","name":"t","arguments":{},"content":"x","server":false,
             "images":[{"mime":"image/png"},{"uri":"/ok.png"}]}
        """.trimIndent()
        val decoded = ToolCallPart.decode(malformed)!!
        // 没有 uri 的条目直接丢掉，剩下的照常解出来。
        assertEquals(listOf("/ok.png"), decoded.images.map { it.uri })
    }

    @Test
    fun `image entries are plain uri and mime pairs`() {
        val json = Json.parseToJsonElement(encode(listOf(ToolImage("/x.png", "image/png"))).payloadJson)
            .jsonObject
        val first = (json["images"]!! as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("/x.png", first["uri"]!!.jsonPrimitive.content)
        assertEquals("image/png", first["mime"]!!.jsonPrimitive.content)
    }
}
