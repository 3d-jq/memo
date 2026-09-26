package com.psyche.memo.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 对话里的「打断」——待答的问询（`ask_user_input_v0`）。
 *
 * 上游（kelivo / RikkaHub）把它内联渲染在对话流里的工具卡上；用户 2026-09-14 实测后
 * 要求改到**输入栏的位置**（「这个应该出现在输入框那个位置…体验更加友好」）。所以：
 *
 *  - 有待答问询时，底部显示面板，**聊天输入栏暂时藏起来**；
 *  - 对话里的工具卡只留一行状态（「等待你的回复…」），不再内联交互控件；
 *  - 作答完，底部自动变回输入栏（面板只在 pending 时存在）。
 *
 * 同族的一半已经没了：待审批的工具调用原来也走这里，2026-09-25 用户
 * 「工具的权限审批全部去掉」⇒ 审批服务、面板与工具卡上的审批控件整块拆除。
 */
internal sealed interface ChatInterruption {
    /** 待答的问询（[AskUserRequest] 的 questions 已经归一化过）。 */
    data class AskUser(val request: AskUserRequest) : ChatInterruption
}

/**
 * 当前该不该显示问询面板 —— 只看**本会话**的请求，都没有时返回 null（调用方据此
 * 显示正常的输入栏）。
 *
 * [generating] 是「这条会话现在真的有人在等」的判据：面板会**顶掉输入栏**，所以一个
 * 等待方已经死掉的孤儿请求绝不能把它换下来（用户 2026-09-25「工作区工具传了参数，
 * 工具就全部问题，换新对话才好」——挂起时离开聊天页 / 前台服务超时把生成协程取消，
 * pending 留在容器级的表里没人清）。正常终止路径已经会释放（ChatViewModel 的 finally
 * 与 destroy()），这一道是兜底：宁可少弹一次面板，也不能把会话锁死。
 */
internal fun currentChatInterruption(
    askUser: Collection<AskUserRequest>,
    conversationId: String?,
    generating: Boolean,
): ChatInterruption? {
    if (!generating) return null
    val scoped = conversationId?.trim().orEmpty()
    // 没有会话归属的请求（临时会话/工具没带会话）也算当前会话的。
    return askUser
        .firstOrNull {
            val candidate = it.conversationId?.trim().orEmpty()
            candidate.isEmpty() || scoped.isEmpty() || candidate == scoped
        }
        ?.let { ChatInterruption.AskUser(it) }
}

/** 底部问询面板（占输入栏位置）。 */
@Composable
internal fun ChatInterruptionPanel(
    interruption: ChatInterruption,
    askUser: AskUserInteractionService?,
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
                onClose = {
                    askUser?.cancel(
                        interruption.request.toolCallId,
                        conversationId = interruption.request.conversationId,
                    )
                },
            )
        }
    }
}
