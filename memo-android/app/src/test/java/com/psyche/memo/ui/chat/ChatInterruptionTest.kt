package com.psyche.memo.ui.chat

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部打断面板"该显示谁"的判据。
 *
 * 2026-09-25 用户「工具的权限审批全部去掉」⇒ 审批那半整块拆除，面板只剩**模型提问**
 * 一条通道（`ask_user_input_v0`）。仍然钉住两件事：**没人等的孤儿请求绝不能顶掉输入栏**
 * （见 [orphan]，那次的事故是"工作区工具传了参数，工具就全部问题，换新对话才好"），
 * 以及**跨会话不串台**（另一个会话的提问不许弹到当前会话上）。
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

    /** 面板存在的唯一场景是「这一轮生成正挂在等回答上」，所以默认按在跑调用。 */
    private fun interrupt(
        askUser: List<AskUserRequest> = emptyList(),
        conversationId: String?,
        generating: Boolean = true,
    ) = currentChatInterruption(
        askUser = askUser,
        conversationId = conversationId,
        generating = generating,
    )

    @Test
    fun `nothing pending shows no panel`() {
        assertNull(interrupt(conversationId = "conv-1"))
    }

    @Test
    fun `pending question shows the panel`() {
        val result = interrupt(askUser = listOf(ask("c1", "conv-1")), conversationId = "conv-1")
        assertTrue(result is ChatInterruption.AskUser)
        assertEquals("c1", (result as ChatInterruption.AskUser).request.toolCallId)
    }

    @Test
    fun `requests from other conversations are ignored`() {
        assertNull(
            interrupt(askUser = listOf(ask("c1", "conv-2")), conversationId = "conv-1"),
        )
    }

    @Test
    fun `unscoped requests belong to whatever conversation is asking`() {
        // 临时会话 / 工具没带会话 id 时（conversationId == null）照样要能弹出面板。
        assertTrue(
            interrupt(askUser = listOf(ask("c1", null)), conversationId = "conv-1")
                is ChatInterruption.AskUser,
        )
        assertTrue(
            interrupt(askUser = listOf(ask("c1", "conv-9")), conversationId = null)
                is ChatInterruption.AskUser,
        )
    }

    /**
     * 没有生成在跑，就没有人在等这个答案 —— 此时**绝不能**用面板顶掉输入栏。
     *
     * 泄漏路径：等待方协程已经随生成终止消失，pending 却留在容器级的表里；面板只看这张表，
     * 于是那条会话的输入栏被一张永远没人应答的卡顶掉，而新会话不受影响。
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
        // 生成在跑时照旧弹出（这才是面板存在的唯一场景）。
        assertTrue(
            interrupt(
                askUser = listOf(ask("c1", "conv-1")),
                conversationId = "conv-1",
                generating = true,
            ) is ChatInterruption.AskUser,
        )
    }
}
