package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.ImagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成类工具的结果 JSON → 助手消息产物 part（用户 2026-09-17「生成结果再开一个输出…
 * 好割裂呀」后改成并进同一轮）。这里钉住三条：
 * 图片 → `ImagePart`（MIME 按扩展名）、视频 → `FilePart`（video/mp4 + 文件名）、
 * 解析不出产物 → 空表（**不**顺手丢掉工具卡，也不抛）。
 */
class GeneratedMediaPartsTest {

    @Test
    fun `image result becomes image parts with the right mime`() {
        val parts = generatedMediaParts(
            """{"type":"image_generation_result","status":"succeeded","paths":["/a/gen_1.png","/a/gen_2.webp","/a/gen_3.jpg"]}""",
        )
        assertEquals(3, parts.size)
        assertEquals(
            listOf("/a/gen_1.png", "/a/gen_2.webp", "/a/gen_3.jpg"),
            parts.filterIsInstance<ImagePart>().map { it.uri },
        )
        assertEquals(
            listOf("image/png", "image/webp", "image/jpeg"),
            parts.filterIsInstance<ImagePart>().map { it.mime },
        )
    }

    @Test
    fun `video result becomes a file part carrying the file name`() {
        val parts = generatedMediaParts(
            """{"type":"video_generation_result","status":"succeeded","path":"/v/gen_9.mp4"}""",
        )
        val file = parts.single() as FilePart
        assertEquals("/v/gen_9.mp4", file.uri)
        assertEquals("gen_9.mp4", file.name)
        assertEquals("video/mp4", file.mime)
    }

    @Test
    fun `images come first when both are present`() {
        val parts = generatedMediaParts(
            """{"paths":["/a/1.png"],"path":"/v/1.mp4"}""",
        )
        assertTrue(parts.first() is ImagePart)
        assertTrue(parts.last() is FilePart)
    }

    @Test
    fun `nothing to attach yields an empty list`() {
        assertEquals(0, generatedMediaParts("""{"type":"image_generation_result","paths":[]}""").size)
        assertEquals(0, generatedMediaParts("""{"status":"failed"}""").size)
        // 解析失败 / 不是对象 / 空串 —— 一律空表，不抛（工具 JSON 自己已经把错误讲清楚了）。
        assertEquals(0, generatedMediaParts("not json").size)
        assertEquals(0, generatedMediaParts("[1,2,3]").size)
        assertEquals(0, generatedMediaParts("").size)
    }

    @Test
    fun `unknown extensions fall back to png`() {
        val parts = generatedMediaParts("""{"paths":["/a/noext","/a/x.bin"]}""")
        assertEquals(listOf("image/png", "image/png"), parts.filterIsInstance<ImagePart>().map { it.mime })
    }

    /**
     * 图表工具（render_chart）的产物是 SVG —— MIME 要认出来，聊天侧才会用等比卡片
     * 而不是 112dp 缩略块（否则坐标轴被裁掉）。
     */
    @Test
    fun `svg products carry the svg mime`() {
        val parts = generatedMediaParts("""{"type":"chart_result","paths":["/a/gen_1.svg"]}""")
        assertEquals("image/svg+xml", (parts.single() as ImagePart).mime)
    }
}
