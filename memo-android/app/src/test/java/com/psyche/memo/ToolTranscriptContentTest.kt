package com.psyche.memo

import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.JsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用轮次回传给模型的 **assistant 正文**（用户 2026-09-16「为什么我看他经常输出重复
 * 正文呀 是结果反馈没有做好吗」）。
 *
 * 原版 `chat_completions_api.dart:125-151` `_buildAssistantToolCallMessage`：正文取
 * **本轮响应自己**的 `msg['content']`（空则归一成 `"\n\n"`），`_buildAssistantToolCallMessage`
 * 的两个调用点（`:777`/`:953`）都把当前响应的 content 传进去。
 *
 * 我们曾经传 `allParts`（**跨轮累计**的全文）⇒ 第 2 轮起的 transcript 里，assistant 轮带着
 * 第 1 轮的正文，模型看到自己旧答案被重新喂一遍，就经常把它复述出来 —— 这就是"重复正文"。
 */
class ToolTranscriptContentTest {

    private fun text(s: String): MessagePart = TextPart(s)

    @Test
    fun `assistant turn carries only this round's text`() {
        val roundParts: List<MessagePart> = listOf(
            ReasoningPart("想想"),
            text("第一段"),
            ToolCallPart.encode("t1", "workspace_shell", JsonObject(emptyMap()), null, false),
            text("第二段"),
        )

        assertEquals("第一段第二段", ChatViewModel.assistantToolCallTranscriptContent(roundParts))
    }

    @Test
    fun `an empty round normalizes to a blank line like the original`() {
        // Dart `:132-136`：content 为空（或空列表）时用 "\n\n"。
        assertEquals("\n\n", ChatViewModel.assistantToolCallTranscriptContent(emptyList()))
        assertEquals(
            "\n\n",
            ChatViewModel.assistantToolCallTranscriptContent(
                listOf(ReasoningPart("只有思考"), ToolCallPart.encode("t1", "x", JsonObject(emptyMap()), null, false)),
            ),
        )
    }

    /**
     * 回归守卫：transcript 的正文**不许**取自 `allParts`（跨轮累计），必须是本轮的 parts。
     * 这一条把"重复正文"那个 bug 钉死 —— 谁再把 allParts 传进去就会红。
     */
    @Test
    fun `the transcript never uses the cumulative parts of earlier rounds`() {
        val src = File("src/main/java/com/psyche/memo/ChatViewModel.kt")
        assertTrue("expected ${src.absolutePath} to exist", src.isFile)

        val offenders = src.readLines().withIndex().filter { (_, line) ->
            line.contains("content = allParts") ||
                (line.contains("allParts.filterIsInstance<TextPart>()") &&
                    !line.contains("TtsPlayer") && // TTS 自动朗读整条回复，用累计全文是对的
                    !line.contains("val text = allParts"))
        }.map { (i, line) -> "${i + 1}: ${line.trim()}" }

        // 只允许 TTS 那一处（`val text = allParts...` + TtsPlayer.speak）用累计全文。
        assertTrue(
            "工具调用的 transcript 正文不能取 allParts（会重复正文）；只能取本轮 roundHandler.parts：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
