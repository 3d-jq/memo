package com.psyche.memo.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 对话里的「打断」——待答的问询与待审批的工具调用。
 *
 * 上游（kelivo / RikkaHub）把这两样内联渲染在对话流里的工具卡上；用户 2026-09-14
 * 实测后要求改到**输入栏的位置**（照参考实现的底部面板：「这个应该出现在输入框那个
 * 位置…体验更加友好」）。所以：
 *
 *  - 待答/待审批时，底部显示对应面板，**聊天输入栏暂时藏起来**；
 *  - 对话里的工具卡只留一行状态（「等待你的回复…」/「等待审批」），不再内联交互控件；
 *  - 作答/审批完，底部自动变回输入栏（面板只在 pending 时存在）。
 */
internal sealed interface ChatInterruption {
    /** 待答的问询（[AskUserRequest] 的 questions 已经归一化过）。 */
    data class AskUser(val request: AskUserRequest) : ChatInterruption

    /** 待审批的工具调用。 */
    data class Approval(val request: ToolApprovalRequest) : ChatInterruption
}

/**
 * 当前该显示哪个打断面板 —— 只看**本会话**的请求，问询优先（它在等一个回答才能继续，
 * 审批只是放行/拒绝）。都没有时返回 null（调用方据此显示正常的输入栏）。
 */
internal fun currentChatInterruption(
    askUser: Collection<AskUserRequest>,
    approval: Collection<ToolApprovalRequest>,
    conversationId: String?,
): ChatInterruption? {
    val scoped = conversationId?.trim().orEmpty()
    fun belongsToConversation(id: String?): Boolean {
        val candidate = id?.trim().orEmpty()
        // 没有会话归属的请求（临时会话/工具没带会话）也算当前会话的。
        return candidate.isEmpty() || scoped.isEmpty() || candidate == scoped
    }

    askUser.firstOrNull { belongsToConversation(it.conversationId) }
        ?.let { return ChatInterruption.AskUser(it) }
    approval.firstOrNull { belongsToConversation(it.conversationId) }
        ?.let { return ChatInterruption.Approval(it) }
    return null
}

/** 底部打断面板（问询 / 审批二选一）。 */
@Composable
internal fun ChatInterruptionPanel(
    interruption: ChatInterruption,
    askUser: AskUserInteractionService?,
    approval: ToolApprovalService?,
    conversationId: String?,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        when (interruption) {
            is ChatInterruption.AskUser -> AskUserPanel(
                request = interruption.request,
                askUser = askUser,
                // × 等价于取消这次提问（服务以 tool_error 'cancelled' 结束）。
                onClose = { askUser?.cancel(interruption.request.toolCallId) },
            )

            is ChatInterruption.Approval -> ToolApprovalPanel(
                request = interruption.request,
                approval = approval,
                conversationId = conversationId,
            )
        }
    }
}
