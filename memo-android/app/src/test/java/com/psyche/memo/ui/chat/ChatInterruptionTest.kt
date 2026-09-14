package com.psyche.memo.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部打断面板"该显示谁"的判据（用户 2026-09-14：问询与审批都要挪到输入栏位置）。
 *
 * 判错的后果很直接：跨会话串台（另一个会话的提问弹到当前会话上），或者明明有问询在等
 * 回答却先显示审批、把用户卡在错误的操作上。
 */
class ChatInterruptionTest {

    private fun ask(toolCallId: String, conversationId: String?) = AskUserRequest(
        toolCallId = toolCallId,
        questions = listOf(
            AskUserQuestion(
                id = "q1",
                question = "选一个",
                kind = AskUserQuestionKind.Single,
                options = listOf("A", "B"),
            ),
        ),
        conversationId = conversationId,
        completer = CompletableDeferred(),
    )

    private fun approval(toolCallId: String, conversationId: String?) = ToolApprovalRequest(
        toolCallId = toolCallId,
        toolName = "workspace_shell",
        arguments = JsonObject(emptyMap()),
        conversationId = conversationId,
        completer = CompletableDeferred(),
    )

    @Test
    fun `nothing pending shows no panel`() {
        assertNull(currentChatInterruption(emptyList(), emptyList(), "conv-1"))
    }

    @Test
    fun `ask user wins over approval`() {
        val result = currentChatInterruption(
            askUser = listOf(ask("c1", "conv-1")),
            approval = listOf(approval("c2", "conv-1")),
            conversationId = "conv-1",
        )
        assertTrue(result is ChatInterruption.AskUser)
        assertEquals("c1", (result as ChatInterruption.AskUser).request.toolCallId)
    }

    @Test
    fun `requests from other conversations are ignored`() {
        val result = currentChatInterruption(
            askUser = listOf(ask("c1", "conv-2")),
            approval = listOf(approval("c2", "conv-2")),
            conversationId = "conv-1",
        )
        assertNull(result)
    }

    @Test
    fun `unscoped requests belong to whatever conversation is asking`() {
        // 临时会话 / 工具没带会话 id 时（conversationId == null）照样要能弹出面板。
        val nullScope = currentChatInterruption(
            askUser = listOf(ask("c1", null)),
            approval = emptyList(),
            conversationId = "conv-1",
        )
        assertTrue(nullScope is ChatInterruption.AskUser)

        val noConversation = currentChatInterruption(
            askUser = emptyList(),
            approval = listOf(approval("c2", "conv-9")),
            conversationId = null,
        )
        assertTrue(noConversation is ChatInterruption.Approval)
    }

    @Test
    fun `approval is shown when no ask user request is pending`() {
        val result = currentChatInterruption(
            askUser = listOf(ask("c1", "conv-2")),
            approval = listOf(approval("c2", "conv-1")),
            conversationId = "conv-1",
        )
        assertTrue(result is ChatInterruption.Approval)
        assertEquals("c2", (result as ChatInterruption.Approval).request.toolCallId)
    }
}
