package com.psyche.memo.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** ask_user_interaction_service.dart AskUserToolNames。 */
object AskUserToolNames {
    const val ASK_USER = "ask_user_input_v0"
}

/** ask_user_interaction_service.dart AskUserInvalidRequestException。 */
class AskUserInvalidRequestException(message: String) : Exception(message)

/** ask_user_interaction_service.dart AskUserRequest。 */
class AskUserRequest(
    val toolCallId: String,
    val questions: List<AskUserQuestion>,
    val conversationId: String?,
    internal val completer: CompletableDeferred<AskUserResult>,
)

/**
 * ask_user_interaction_service.dart 的 1:1 移植。ChangeNotifier 通知改为
 * StateFlow 快照（pendingRequests）；[AskUserResult] 复用 AskUserCard.kt 的
 * 序列化实现（answer/error 载荷与源码 toJsonString 逐字节一致）。
 */
class AskUserInteractionService {

    private val pending = LinkedHashMap<String, AskUserRequest>()

    private val _pendingRequests = MutableStateFlow<Map<String, AskUserRequest>>(emptyMap())

    /** pendingRequests 快照（ask_user_interaction_service.dart 132）。 */
    val pendingRequests: StateFlow<Map<String, AskUserRequest>> = _pendingRequests

    fun isPending(toolCallId: String): Boolean = pending.containsKey(toolCallId)

    /**
     * 发起提问请求。问题归一化后为空则同步抛 [AskUserInvalidRequestException]；
     * 键为去空格后的 toolCallId，空 id 落到 `ask_user_input_v0_<epochMicros>`。
     */
    fun requestAnswer(
        toolCallId: String,
        arguments: JsonObject,
        conversationId: String? = null,
    ): CompletableDeferred<AskUserResult> {
        val questions = normalizeAskUserQuestions(arguments)
        if (questions.isEmpty()) {
            throw AskUserInvalidRequestException(
                "questions must contain at least one question",
            )
        }
        val completer = CompletableDeferred<AskUserResult>()
        val key = toolCallId.trim().ifEmpty {
            "ask_user_input_v0_${System.currentTimeMillis() * 1000}"
        }
        pending[key] = AskUserRequest(
            toolCallId = key,
            questions = questions,
            conversationId = conversationId,
            completer = completer,
        )
        notifyPending()
        return completer
    }

    /** 提交某次提问的答案，完成对应 deferred。 */
    fun answer(toolCallId: String, answers: Map<String, AskUserAnswerValue>) {
        val request = pending.remove(toolCallId)
        if (request != null && !request.completer.isCompleted) {
            request.completer.complete(AskUserResult.answer(answers))
        }
        notifyPending()
    }

    /**
     * 取消单次提问（底部问询面板右上角的 ×）—— 与 [cancelAll]/[cancelForConversation]
     * 同语义：以 tool_error 'cancelled' 结束，模型据此知道用户不答了、可以继续。
     */
    fun cancel(toolCallId: String) {
        val request = pending.remove(toolCallId) ?: return
        if (!request.completer.isCompleted) {
            request.completer.complete(
                AskUserResult.error(
                    error = "cancelled",
                    message = "Ask user request was cancelled.",
                ),
            )
        }
        notifyPending()
    }

    /** 取消全部提问请求（completed with tool_error 'cancelled'）。 */
    fun cancelAll() {        for (request in pending.values) {
            if (!request.completer.isCompleted) {
                request.completer.complete(
                    AskUserResult.error(
                        error = "cancelled",
                        message = "Ask user request was cancelled.",
                    ),
                )
            }
        }
        pending.clear()
        notifyPending()
    }

    /**
     * 取消属于 [conversationId] 的提问请求。未记录会话的请求一并取消
     * （防止工具 handler 被卡死），但其他会话的请求继续等待。
     */
    fun cancelForConversation(conversationId: String) {
        val toCancel = pending.values.filter {
            it.conversationId == null || it.conversationId == conversationId
        }
        if (toCancel.isEmpty()) return
        for (request in toCancel) {
            pending.remove(request.toolCallId)
            if (!request.completer.isCompleted) {
                request.completer.complete(
                    AskUserResult.error(
                        error = "cancelled",
                        message = "Ask user request was cancelled.",
                    ),
                )
            }
        }
        notifyPending()
    }

    private fun notifyPending() {
        _pendingRequests.value = pending.toMap()
    }
}
