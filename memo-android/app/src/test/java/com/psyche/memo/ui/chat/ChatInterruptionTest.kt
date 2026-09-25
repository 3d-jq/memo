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
 * 判错的后果很直接：跨会话串台（另一个会话的提问弹到当前会话上）、明明有问询在等
 * 回答却先显示审批、或者**没人等的孤儿请求把输入栏永久顶掉**（见 [orphan]）。
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

    /** 面板存在的唯一场景是「这一轮生成正挂在等回答上」，所以默认按在跑调用。 */
    private fun interrupt(
        askUser: List<AskUserRequest> = emptyList(),
        approval: List<ToolApprovalRequest> = emptyList(),
        conversationId: String?,
        generating: Boolean = true,
    ) = currentChatInterruption(
        askUser = askUser,
        approval = approval,
        conversationId = conversationId,
        generating = generating,
    )

    @Test
    fun `nothing pending shows no panel`() {
        assertNull(interrupt(conversationId = "conv-1"))
    }

    @Test
    fun `ask user wins over approval`() {
        val result = interrupt(
            askUser = listOf(ask("c1", "conv-1")),
            approval = listOf(approval("c2", "conv-1")),
            conversationId = "conv-1",
        )
        assertTrue(result is ChatInterruption.AskUser)
        assertEquals("c1", (result as ChatInterruption.AskUser).request.toolCallId)
    }

    @Test
    fun `requests from other conversations are ignored`() {
        val result = interrupt(
            askUser = listOf(ask("c1", "conv-2")),
            approval = listOf(approval("c2", "conv-2")),
            conversationId = "conv-1",
        )
        assertNull(result)
    }

    @Test
    fun `unscoped requests belong to whatever conversation is asking`() {
        // 临时会话 / 工具没带会话 id 时（conversationId == null）照样要能弹出面板。
        val nullScope = interrupt(
            askUser = listOf(ask("c1", null)),
            conversationId = "conv-1",
        )
        assertTrue(nullScope is ChatInterruption.AskUser)

        val noConversation = interrupt(
            approval = listOf(approval("c2", "conv-9")),
            conversationId = null,
        )
        assertTrue(noConversation is ChatInterruption.Approval)
    }

    @Test
    fun `approval is shown when no ask user request is pending`() {
        val result = interrupt(
            askUser = listOf(ask("c1", "conv-2")),
            approval = listOf(approval("c2", "conv-1")),
            conversationId = "conv-1",
        )
        assertTrue(result is ChatInterruption.Approval)
        assertEquals("c2", (result as ChatInterruption.Approval).request.toolCallId)
    }

    /**
     * 没有生成在跑，就没有人在等这个答案 —— 此时**绝不能**用面板顶掉输入栏。
     *
     * 泄漏路径（用户 2026-09-25「工作区工具传了参数，工具就全部问题，换新对话才好」）：
     * 审批挂起时离开聊天页（`viewModelScope` 被取消）或前台服务超时裸 `cancel()`，等待方
     * 已经死了，pending 却留在容器级的表里；面板只看这张表，于是那条会话的输入栏被一张
     * 永远没人应答的审批卡永久顶掉，而新会话不受影响。
     */
    @Test
    fun orphan() {
        assertNull(
            interrupt(
                askUser = listOf(ask("c1", "conv-1")),
                conversationId = "conv-1",
                generating = false,
            ),
        )
        assertNull(
            interrupt(
                approval = listOf(approval("c2", "conv-1")),
                conversationId = "conv-1",
                generating = false,
            ),
        )
        // 生成在跑时照旧弹出（这才是面板存在的唯一场景）。
        assertTrue(
            interrupt(
                approval = listOf(approval("c2", "conv-1")),
                conversationId = "conv-1",
                generating = true,
            ) is ChatInterruption.Approval,
        )
    }
}
