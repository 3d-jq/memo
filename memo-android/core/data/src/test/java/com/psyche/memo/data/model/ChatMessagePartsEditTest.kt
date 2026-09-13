package com.psyche.memo.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `chat_edit_assistant_keep_thinking_tool_cards_v1` 用到的 parts 裁剪
 * （chat_message.dart:237-245 `partsWithoutThinkingAndToolCards`）。
 *
 * 注意：part 类型不是 data class，相等性即引用相等 —— 裁剪只做过滤，应当原样
 * 返回同一个实例。
 */
class ChatMessagePartsEditTest {

    @Test
    fun dropsReasoningAndToolPartsOnly() {
        val text = TextPart("hello")
        val reasoning = ReasoningPart("think")
        val tool = ToolCallPart("""{"toolName":"web_search","id":"t1"}""")
        val image = ImagePart(uri = "file:///a.png")
        val out = ChatMessage.partsWithoutThinkingAndToolCards(listOf(text, reasoning, tool, image))
        assertEquals(listOf<MessagePart>(text, image), out)
    }

    @Test
    fun keepsEverythingElseIntact() {
        val text = TextPart("a")
        val image = ImagePart(uri = "file:///a.png")
        val parts = listOf<MessagePart>(text, image)
        assertEquals(parts, ChatMessage.partsWithoutThinkingAndToolCards(parts))
    }

    @Test
    fun replacedTextStillRewritesTheFirstTextPartAfterTrimming() {
        val parts = listOf<MessagePart>(TextPart("old"), ReasoningPart("think"), TextPart("tail"))
        val trimmed = ChatMessage.partsWithoutThinkingAndToolCards(parts)
        val rewritten = ChatMessage.partsWithReplacedText(trimmed, "new")
        assertEquals(1, rewritten.size)
        assertEquals("new", (rewritten[0] as TextPart).text)
        assertTrue(rewritten.none { it is ReasoningPart })
    }
}
