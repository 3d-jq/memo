package com.psyche.memo.llm.prompt

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.llm.client.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `message_builder_service.applyContextLimit`（L2139-2166）的移植覆盖：保留系统消息 +
 * 最近 N 条、丢掉裁剪后开头的悬空 tool 消息。
 */
class ApplyContextLimitTest {

    private fun msg(role: String, content: String = role) = LlmMessage(role = role, content = content)

    @Test
    fun `disabled leaves the list untouched`() {
        val messages = mutableListOf(msg("system"), msg("user"), msg("assistant"))
        applyContextLimit(messages, enabled = false, size = 1)
        assertEquals(listOf("system", "user", "assistant"), messages.map { it.role })
    }

    @Test
    fun `zero size leaves the list untouched`() {
        val messages = mutableListOf(msg("user"), msg("assistant"))
        applyContextLimit(messages, enabled = true, size = 0)
        assertEquals(2, messages.size)
    }

    @Test
    fun `keeps the system message plus the newest n`() {
        val messages = mutableListOf(msg("system"), msg("user"), msg("assistant"), msg("user"), msg("assistant"))
        applyContextLimit(messages, enabled = true, size = 2)
        assertEquals(listOf("system", "user", "assistant"), messages.map { it.role })
        // 留下的是**最近**两条。
        assertEquals(listOf("user", "assistant"), messages.drop(1).map { it.content })
    }

    @Test
    fun `without a leading system message the tail count starts at zero`() {
        val messages = mutableListOf(msg("user"), msg("assistant"), msg("user"))
        applyContextLimit(messages, enabled = true, size = 1)
        assertEquals(1, messages.size)
        assertEquals("user", messages.first().role)
    }

    @Test
    fun `dangling tool results at the new head are dropped`() {
        // 裁点落在 assistant(tool_calls) + tool 结果中间：留下的开头两条 tool 必须丢掉
        // （原版就是这么处理悬空 tool 消息的；那条 assistant 调用已经在窗口之外）。
        val messages = mutableListOf(
            msg("system"),
            msg("user"),
            msg("assistant"),
            msg("tool", "call-1"),
            msg("tool", "call-2"),
            msg("user"),
        )
        applyContextLimit(messages, enabled = true, size = 3)
        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertTrue(messages.none { it.role == "tool" })
    }

    @Test
    fun `a complete tool triplet never loses its tool results`() {
        val messages = mutableListOf(
            msg("system"),
            msg("user"),
            msg("assistant"),
            msg("tool", "call-1"),
            msg("user"),
        )
        applyContextLimit(messages, enabled = true, size = 4)
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), messages.map { it.role })
    }

    @Test
    fun `size is clamped to the assistant bounds`() {
        val messages = mutableListOf(msg("system"))
        messages.addAll((1..5).map { msg("user", "m$it") })
        // 0 / 负数 = 没开（原版 `> 0` 的闸门），不动列表。
        applyContextLimit(messages, enabled = true, size = -3)
        assertEquals(6, messages.size)
        // 下限夹到 1。
        applyContextLimit(messages, enabled = true, size = 0)
        assertEquals(6, messages.size)
        applyContextLimit(messages, enabled = true, size = 1)
        assertEquals(listOf("system", "user"), messages.map { it.role })
        // 上限夹到 MaxContextMessageSize：比列表长就整段保留。
        applyContextLimit(messages, enabled = true, size = Assistant.MaxContextMessageSize + 100)
        assertEquals(2, messages.size)
    }

    @Test
    fun `a list shorter than the limit is untouched`() {
        val messages = mutableListOf(msg("system"), msg("user"))
        applyContextLimit(messages, enabled = true, size = 64)
        assertEquals(2, messages.size)
    }
}
