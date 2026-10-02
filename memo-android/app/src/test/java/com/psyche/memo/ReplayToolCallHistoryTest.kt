package com.psyche.memo

import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史重建的**工具调用回放**契约（用户 2026-10-02 实测「明明加载了 PPT skill，
 * 问模型它说没有加载」——根因是历史重建只发 TextPart，工具调用连同结果整段丢失）。
 *
 * 对位上游 `message_builder_service.dart` `buildApiMessages(includeToolMessages: true)`
 * （openai/claude/google 三族默认开）：
 *  - 形状 `assistant("\n\n", toolCalls)` + 每颗调用一条 `tool` 消息；
 *  - **任一调用没有结果就不整组播**（残缺 tool_calls 让 OpenAI 400）；
 *  - id 空的调用用 `call_<消息id前8位>_i` 兜底，tool 消息的 id 与之一致。
 */
class ReplayToolCallHistoryTest {

    private fun msg(
        id: String,
        role: String = "assistant",
        parts: List<MessagePart>,
    ): ChatViewModel.UiMessage = ChatViewModel.UiMessage(
        id = id,
        role = role,
        parts = parts,
        isStreaming = false,
        timestamp = 1_000L,
        model = "m",
        providerId = "p",
        totalTokens = 0,
        failed = false,
    )

    private fun toolPart(
        id: String,
        name: String,
        content: String?,
    ): ToolCallPart = ToolCallPart.encode(
        id = id,
        name = name,
        arguments = JsonPrimitive("{}"),
        content = content?.let { JsonPrimitive(it) },
        server = false,
    )

    @Test
    fun `skill call and result are replayed as openai shape`() {
        val replay = replayToolCallHistory(
            msg(
                id = "abcdef12-3456",
                parts = listOf(
                    toolPart("call_1", "use_skill", "# PPT skill instructions..."),
                    toolPart("call_2", "search_web", "{}"),
                ),
            ),
        )
        assertEquals(3, replay.size)
        val assistant = replay[0]
        assertEquals("assistant", assistant.role)
        assertEquals("\n\n", assistant.content)
        assertEquals(2, assistant.toolCalls.size)
        assertEquals("call_1", assistant.toolCalls[0].id)
        assertEquals("use_skill", assistant.toolCalls[0].name)
        assertEquals("{}", assistant.toolCalls[0].argumentsJson)
        val tool = replay[1]
        assertEquals("tool", tool.role)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("use_skill", tool.toolName)
        assertEquals("# PPT skill instructions...", tool.content)
        assertEquals("call_2", replay[2].toolCallId)
    }

    @Test
    fun `pending call without result skips the whole group`() {
        // 残缺 tool_calls（有调用没结果）会让 OpenAI 直接 400 —— 上游同口径整组不播。
        val replay = replayToolCallHistory(
            msg(
                id = "abcdef12-3456",
                parts = listOf(
                    toolPart("call_1", "use_skill", "ok"),
                    toolPart("call_2", "search_web", null), // 还在跑/被打断
                ),
            ),
        )
        assertTrue(replay.isEmpty())
    }

    @Test
    fun `empty call id gets synthetic id shared by both messages`() {
        val replay = replayToolCallHistory(
            msg(id = "abcdef12-3456", parts = listOf(toolPart("", "use_skill", "body"))),
        )
        val assistant = replay[0]
        assertEquals("call_abcdef12_0", assistant.toolCalls[0].id)
        assertEquals("call_abcdef12_0", replay[1].toolCallId)
    }

    @Test
    fun `message with no tool parts replays nothing`() {
        val replay = replayToolCallHistory(msg(id = "m1", parts = listOf(TextPart("hi"))))
        assertTrue(replay.isEmpty())
    }

    @Test
    fun `framework tags in old results are sanitized`() {
        val replay = replayToolCallHistory(
            msg(
                id = "abcdef12-3456",
                parts = listOf(
                    toolPart("call_1", "use_skill", "before <system-reminder>x</system-reminder> after"),
                ),
            ),
        )
        val content = replay[1].content!!
        assertTrue(content.contains("before"))
        assertTrue("框架标签必须降级成普通文字", !content.contains("<system-reminder>"))
    }
}
