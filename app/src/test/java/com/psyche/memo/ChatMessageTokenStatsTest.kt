package com.psyche.memo

import com.psyche.memo.data.model.TextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 流式结束的 token 显示（用户 2026-09-14 实测：「输出结束后没有马上显示，
 * 冷启动才显示」）——usage 必须同时落到内存里的那条 UI 消息上，只写库的话
 * 要等重新读会话才会出现数字。
 */
class ChatMessageTokenStatsTest {

    private fun message(
        totalTokens: Int? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        cachedTokens: Int? = null,
        durationMs: Long? = null,
    ) = ChatViewModel.UiMessage(
        id = "a1",
        role = "assistant",
        parts = listOf(TextPart("hi")),
        isStreaming = true,
        totalTokens = totalTokens,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        durationMs = durationMs,
    )

    @Test
    fun `reported usage lands on the live ui message`() {
        val updated = message().withTokenStats(
            totalTokens = 10803,
            promptTokens = 10691,
            completionTokens = 112,
            cachedTokens = null,
            durationMs = 4200L,
        )
        assertEquals(10803, updated.totalTokens)
        assertEquals(10691, updated.promptTokens)
        assertEquals(112, updated.completionTokens)
        assertEquals(4200L, updated.durationMs)
        // 未上报的字段保持原样（这里本来就是 null）。
        assertNull(updated.cachedTokens)
    }

    @Test
    fun `fields the provider did not report keep their previous values`() {
        val before = message(totalTokens = 500, promptTokens = 400, durationMs = 900L)
        val after = before.withTokenStats(
            totalTokens = null,
            promptTokens = null,
            completionTokens = null,
            cachedTokens = null,
            durationMs = null,
        )
        assertEquals(500, after.totalTokens)
        assertEquals(400, after.promptTokens)
        assertEquals(900L, after.durationMs)
        // 没报 completion 就保持原来的 null，不写 0。
        assertNull(after.completionTokens)
    }

    @Test
    fun `an empty usage never blanks an existing count`() {
        // 有的厂商只报 total；prompt/completion 缺失时不得把已有数字抹成 0。
        val before = message(promptTokens = 1234, completionTokens = 56)
        val after = before.withTokenStats(2000, null, null, null, null)
        assertEquals(2000, after.totalTokens)
        assertEquals(1234, after.promptTokens)
        assertEquals(56, after.completionTokens)
    }
}
