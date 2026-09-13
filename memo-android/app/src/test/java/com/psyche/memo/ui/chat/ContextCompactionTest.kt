package com.psyche.memo.ui.chat

import com.psyche.memo.ChatViewModel
import com.psyche.memo.common.SessionCompaction
import com.psyche.memo.data.model.CompactionPart
import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * 上下文压缩（opencode 机制）在 UI 侧的映射：检查点窗口 + 消息序列化。
 */
class ContextCompactionTest {

    private fun message(
        id: String,
        order: Int,
        role: String = "user",
        parts: List<MessagePart> = listOf(TextPart(id)),
    ) = ChatViewModel.UiMessage(
        id = id,
        role = role,
        parts = parts,
        isStreaming = false,
        messageOrder = order,
    )

    private fun checkpoint(id: String, order: Int, boundary: Int) = message(
        id = id,
        order = order,
        role = "user",
        parts = listOf(TextPart("summary"), CompactionPart("summary", "recent", boundary)),
    )

    @Test
    fun `window without a checkpoint is the whole conversation`() {
        val messages = listOf(message("m0", 0), message("m1", 1, role = "assistant"))
        assertEquals(listOf("m0", "m1"), compactionWindow(messages).map { it.id })
    }

    @Test
    fun `window keeps only the newest checkpoint and everything after its boundary`() {
        val messages = listOf(
            message("m0", 0),
            message("m1", 1, role = "assistant"),
            checkpoint("cp1", 2, boundary = 1),
            message("m2", 3),
            checkpoint("cp2", 4, boundary = 3),
            message("m3", 5),
            message("m4", 6, role = "assistant"),
        )
        assertEquals(listOf("cp2", "m3", "m4"), compactionWindow(messages).map { it.id })
    }

    @Test
    fun `window is empty-safe`() {
        assertTrue(compactionWindow(emptyList()).isEmpty())
    }

    @Test
    fun `checkpoint messages are not serialized as conversation`() {
        assertNull(compactionEntry(checkpoint("cp", 0, 0)))
    }

    @Test
    fun `entry serializes text reasoning and tool calls`() {
        val tool = ToolCallPart.encode(
            id = "t1",
            name = "read",
            arguments = kotlinx.serialization.json.buildJsonObject {
                put("path", JsonPrimitive("a"))
            },
            content = JsonPrimitive("file body"),
            server = false,
        )
        val entry = compactionEntry(
            message(
                "m0",
                0,
                role = "assistant",
                parts = listOf(TextPart("ok"), ReasoningPart("think"), tool),
            ),
        )!!
        assertEquals("assistant", entry.role)
        assertEquals(
            "[Assistant]: ok\n[Assistant reasoning]: think\n" +
                "[Assistant tool call]: read({\"path\":\"a\"})\n[Tool result]: file body",
            SessionCompaction.serialize(entry),
        )
    }

    @Test
    fun `entry describes attachments without their payloads`() {
        val entry = compactionEntry(
            message(
                "m0",
                0,
                parts = listOf(
                    TextPart("look"),
                    ImagePart(uri = "file:///a.png", mime = "image/png"),
                    FilePart(uri = "file:///b.pdf", name = "b.pdf", mime = "application/pdf"),
                ),
            ),
        )!!
        assertEquals(
            listOf("image/png: file:///a.png", "application/pdf: b.pdf"),
            entry.attachments,
        )
    }

    @Test
    fun `entries without any readable content are skipped`() {
        assertNull(compactionEntry(message("m0", 0, parts = emptyList())))
        // 只有图片的消息仍然可序列化（opencode 的 `[Attached …]` 行）。
        val imageOnly = compactionEntry(
            message("m1", 1, parts = listOf(ImagePart(uri = "file:///a.png", mime = "image/png"))),
        )!!
        assertEquals("", imageOnly.text)
        assertEquals(listOf("image/png: file:///a.png"), imageOnly.attachments)
    }

    @Test
    fun `usage bar colours grade by occupancy`() {
        val cs = androidx.compose.material3.lightColorScheme()
        assertEquals(cs.primary, contextUsageColor(0f, cs, auto = true))
        assertEquals(cs.primary, contextUsageColor(0.69f, cs, auto = true))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE0A02A), contextUsageColor(0.7f, cs, auto = true))
        assertEquals(cs.error, contextUsageColor(0.91f, cs, auto = true))
        // 自动压缩关掉时是灰的（不误导成"快满了"）。
        assertEquals(cs.onSurfaceVariant.copy(alpha = 0.4f), contextUsageColor(1f, cs, auto = false))
    }
}
