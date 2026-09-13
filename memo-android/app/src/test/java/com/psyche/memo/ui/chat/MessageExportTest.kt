package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.CompactionPart
import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port coverage of message_export_sheet.dart's block writer. */
class MessageExportTest {

    private fun message(role: String, parts: List<MessagePart>) =
        MessageExport.ExportMessage(role = role, parts = parts, timestamp = 0L)

    private fun export(
        message: MessageExport.ExportMessage,
        markdown: Boolean = true,
        includeThinking: Boolean = true,
        includeTools: Boolean = true,
    ): String = MessageExport.export(
        title = "T",
        messages = listOf(message),
        roleNameOf = { if (it.role == "user") "Me" else "AI" },
        timeOf = { "12:00:00" },
        thinkingLabel = "Thinking",
        includeThinking = includeThinking,
        includeTools = includeTools,
        markdown = markdown,
        imageLine = { "![image]($it)" },
    )

    @Test
    fun `markdown export carries the title, header and blocks`() {
        val text = export(message("user", listOf(TextPart("hello"))))
        assertTrue(text.startsWith("# T\n\n12:00:00 · Me\n\nhello\n\n\n---\n"))
    }

    @Test
    fun `plain text export drops the hash`() {
        val text = export(message("assistant", listOf(TextPart("hi"))), markdown = false)
        assertTrue(text.startsWith("T\n\n12:00:00 · AI\n\nhi\n"))
        assertTrue(!text.contains("# T"))
    }

    @Test
    fun `thinking blocks only appear when requested`() {
        val msg = message("assistant", listOf(ReasoningPart("think"), TextPart("answer")))
        val withThinking = export(msg)
        assertTrue(withThinking.contains("**Thinking**"))
        assertTrue(withThinking.contains("```text\nthink\n```"))
        val without = export(msg, includeThinking = false)
        assertTrue(!without.contains("Thinking"))
        assertTrue(without.contains("answer"))
    }

    @Test
    fun `tool cards and files render per format`() {
        val msg = message(
            "assistant",
            listOf(
                FilePart(uri = "u", name = "a.pdf", mime = "application/pdf"),
                ToolCallPart(payloadJson = """{"name":"search_web","content":"result text"}"""),
            ),
        )
        val md = export(msg)
        assertTrue(md.contains("- a.pdf  `(application/pdf)`"))
        assertTrue(md.contains("[search_web]\nresult text"))
        val txt = export(msg, markdown = false)
        assertTrue(txt.contains("- a.pdf (application/pdf)"))
        assertTrue(!export(msg, includeTools = false).contains("search_web"))
    }

    @Test
    fun `image lines come from the caller`() {
        val msg = message(
            "user",
            listOf(com.psyche.memo.data.model.ImagePart(uri = "file:///a.png")),
        )
        assertEquals(true, export(msg).contains("![image](file:///a.png)"))
        assertEquals(true, export(msg, markdown = false).contains("![image](file:///a.png)"))
    }

    @Test
    fun `compaction checkpoints never reach the export`() {
        // 摘要只给模型看（用户 2026-09-13）：导出里既没有它的小节结构，也没有落款行。
        val checkpoint = message(
            "user",
            listOf(
                TextPart("## Goal\n- secret summary"),
                CompactionPart("## Goal\n- secret summary", "recent", 3),
            ),
        )
        val text = MessageExport.export(
            title = "T",
            messages = listOf(message("user", listOf(TextPart("hello"))), checkpoint),
            roleNameOf = { if (it.role == "user") "Me" else "AI" },
            timeOf = { "12:00:00" },
            thinkingLabel = "Thinking",
            includeThinking = true,
            includeTools = true,
            markdown = true,
            imageLine = { "![image]($it)" },
        )
        assertTrue(text.contains("hello"))
        assertTrue(!text.contains("secret summary"))
        assertTrue(!text.contains("## Goal"))
    }
}
