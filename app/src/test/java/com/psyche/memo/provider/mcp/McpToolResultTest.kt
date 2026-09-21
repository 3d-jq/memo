package com.psyche.memo.provider.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `flattenMcpToolResult`（mcp_tool_service.dart `_flattenToolResult` L248-324）。
 *
 * Memo 过去只取 text 块，image / audio / resource 全丢，而且空结果会把整坨 JSON-RPC
 * 塞给模型、isError 会抛成 execution_error —— 三条都不是上游行为，这里逐条锁住。
 */
class McpToolResultTest {

    private fun obj(text: String): JsonObject =
        Json.parseToJsonElement(text).let { it as JsonObject }

    private fun flatten(result: JsonObject, saved: (String, String) -> String? = { _, _ -> null }) =
        flattenMcpToolResult(result, saved)

    @Test
    fun `text blocks keep their order and the result is trimmed`() {
        val out = flatten(
            obj("""{"content":[{"type":"text","text":"第一块"},{"type":"text","text":"  "},{"type":"text","text":"第二块"}]}"""),
        )
        assertEquals("第一块\n第二块", out)
    }

    @Test
    fun `an image block with data becomes an inline markdown line at its position`() {
        val out = flatten(
            obj("""{"content":[{"type":"text","text":"看这张"},{"type":"image","mimeType":"image/png","data":"aGVsbG8="}]}"""),
        ) { _, _ -> "/data/files/tool_images/mcp_img_1.png" }
        assertEquals("看这张\n![](/data/files/tool_images/mcp_img_1.png)", out)
    }

    @Test
    fun `an image block without data falls back to its url`() {
        val out = flatten(
            obj("""{"content":[{"type":"image","mimeType":"image/png","url":"https://x.test/a.png"}]}"""),
        )
        assertEquals("![](https://x.test/a.png)", out)
    }

    @Test
    fun `a destination with a space is wrapped in angle brackets`() {
        val out = flatten(
            obj("""{"content":[{"type":"image","mimeType":"image/png","url":"https://x.test/my pic.png"}]}"""),
        )
        assertEquals("![](<https://x.test/my pic.png>)", out)
    }

    @Test
    fun `an image the app could not save contributes nothing`() {
        val out = flatten(
            obj("""{"content":[{"type":"text","text":"只有正文"},{"type":"image","mimeType":"image/png","data":"!!bad!!"}]}"""),
        )
        assertEquals("只有正文", out)
    }

    @Test
    fun `a resource uses its text, else one summary line`() {
        assertEquals(
            "正文内容",
            flatten(obj("""{"content":[{"type":"resource","resource":{"uri":"file:///a","text":"正文内容"}}]}""")),
        )
        // 老版平铺格式也要认（models.dart:229-235 的 else 分支）。
        assertEquals(
            "resource: file:///a",
            flatten(obj("""{"content":[{"type":"resource","uri":"file:///a"}]}""")),
        )
    }

    @Test
    fun `audio and unknown types inline pretty json rather than disappearing`() {
        val out = flatten(
            obj("""{"content":[{"type":"audio","mimeType":"audio/wav","data":"AAAA"}]}"""),
        )
        // 美化 JSON（Dart 的 JsonEncoder.withIndent('  ')），键序按原块。
        assertEquals("{\n  \"type\": \"audio\",\n  \"mimeType\": \"audio/wav\",\n  \"data\": \"AAAA\"\n}", out)
    }

    @Test
    fun `a resource_link becomes a resource line via its uri`() {
        val out = flatten(
            obj("""{"content":[{"type":"resource_link","uri":"file:///note.md","name":"note"}]}"""),
        )
        assertEquals("resource: file:///note.md", out)
    }

    @Test
    fun `isError text is returned as the tool result, not thrown`() {
        val out = flatten(
            obj("""{"isError":true,"content":[{"type":"text","text":"上游说参数不对"}]}"""),
        )
        assertEquals("上游说参数不对", out)
    }

    @Test
    fun `an empty result is empty, never a json dump`() {
        assertEquals("", flatten(obj("""{"content":[],"meta":{"x":1}}""")))
    }

    @Test
    fun `a missing content array yields nothing`() {
        assertEquals("", flatten(obj("""{"isError":true}""")))
    }

    @Test
    fun `private-use-area markers are stripped from text`() {
        val out = flatten(obj("""{"content":[{"type":"text","text":"前后"}]}"""))
        assertEquals("前后", out)
    }
}
