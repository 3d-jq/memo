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
 * ask_user_interaction_service.dart 的移植。ChangeNotifier 通知改为
 * StateFlow 快照（pendingRequests）；[AskUserResult] 复用 AskUserCard.kt 的
 * 序列化实现（answer/error 载荷与源码 toJsonString 逐字节一致）。
 *
 * **一处有意偏离**：pending 以 `(scope, toolCallId)` 为键而不是裸 toolCallId ——
 * 与 [ToolApprovalService] 同一个理由（厂商不给 tool_call id 时解码器会造
 * `tool-1` 这种每轮都重复的占位 id，两条会话共用它会让后到的请求顶掉前一条的表项，
 * 前一条的等待方从此挂在一个哪儿都看不到的对象上）。快照因此是 List 而不是 Map。
 */
class AskUserInteractionService {

    private data class PendingKey(val scope: String, val toolCallId: String)

    private val pending = LinkedHashMap<PendingKey, AskUserRequest>()
    private var unscopedSeq = 0

    private val _pendingRequests = MutableStateFlow<List<AskUserRequest>>(emptyList())

    /** pendingRequests 快照（ask_user_interaction_service.dart 132）。 */
    val pendingRequests: StateFlow<List<AskUserRequest>> = _pendingRequests

    /** 省略 [conversationId] 时任意会话的同 id 都算；提供时只匹配该会话（或 unscoped 兜底）。 */
    fun isPending(toolCallId: String, conversationId: String? = null): Boolean {
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isEmpty()) {
            return pending.values.any { it.toolCallId == toolCallId }
        }
        return pendingFor(toolCallId, conversationId) != null
    }

    /** 优先精确 (conversationId, toolCallId)，其次同 id 的 unscoped 兜底；绝不返回别的会话的。 */
    private fun pendingFor(toolCallId: String, conversationId: String?): AskUserRequest? {
        if (toolCallId.isEmpty()) return null
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isNotEmpty()) {
            pending[scopedKey(scopedId, toolCallId)]?.let { return it }
            return findUnscoped(toolCallId)
        }
        val matches = pending.values.filter { it.toolCallId == toolCallId }
        if (matches.size == 1) return matches.single()
        return findUnscoped(toolCallId)
    }

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
        val id = toolCallId.trim().ifEmpty {
            "ask_user_input_v0_${System.currentTimeMillis() * 1000}"
        }
        val key = storageKey(conversationId, id)
        pending[key]?.let { return it.completer }
        val completer = CompletableDeferred<AskUserResult>()
        pending[key] = AskUserRequest(
            toolCallId = id,
            questions = questions,
            conversationId = storedConversationId(conversationId),
            completer = completer,
        )
        notifyPending()
        return completer
    }

    /** 提交某次提问的答案，完成对应 deferred。 */
    fun answer(
        toolCallId: String,
        answers: Map<String, AskUserAnswerValue>,
        conversationId: String? = null,
    ) {
        val request = takePending(toolCallId, conversationId)
        if (request != null && !request.completer.isCompleted) {
            request.completer.complete(AskUserResult.answer(answers))
        }
        notifyPending()
    }

    /**
     * 取消单次提问（底部问询面板右上角的 ×）—— 与 [cancelAll]/[cancelForConversation]
     * 同语义：以 tool_error 'cancelled' 结束，模型据此知道用户不答了、可以继续。
     */
    fun cancel(toolCallId: String, conversationId: String? = null) {
        val request = takePending(toolCallId, conversationId) ?: return
        if (!request.completer.isCompleted) {
            request.completer.complete(cancelledResult())
        }
        notifyPending()
    }

    /** 取消全部提问请求（completed with tool_error 'cancelled'）。 */
    fun cancelAll() {
        for (request in pending.values) {
            if (!request.completer.isCompleted) {
                request.completer.complete(cancelledResult())
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
            pending.entries.removeAll { it.value === request }
            if (!request.completer.isCompleted) {
                request.completer.complete(cancelledResult())
            }
        }
        notifyPending()
    }

    private fun cancelledResult(): AskUserResult = AskUserResult.error(
        error = "cancelled",
        message = "Ask user request was cancelled.",
    )

    private fun takePending(toolCallId: String, conversationId: String?): AskUserRequest? {
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isNotEmpty()) {
            pending.remove(scopedKey(scopedId, toolCallId))?.let { return it }
            return removeUnscoped(toolCallId)
        }
        val matches = pending.entries.filter { it.value.toolCallId == toolCallId }
        if (matches.size == 1) {
            val entry = matches.single()
            pending.remove(entry.key)
            return entry.value
        }
        return removeUnscoped(toolCallId)
    }

    private fun findUnscoped(toolCallId: String): AskUserRequest? =
        pending.values.filter {
            it.toolCallId == toolCallId && it.conversationId.isNullOrEmpty()
        }.singleOrNull()

    private fun removeUnscoped(toolCallId: String): AskUserRequest? {
        val matches = pending.entries.filter {
            it.value.toolCallId == toolCallId && it.value.conversationId.isNullOrEmpty()
        }
        if (matches.size != 1) return null
        pending.remove(matches.single().key)
        return matches.single().value
    }

    private fun storageKey(conversationId: String?, toolCallId: String): PendingKey {
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isNotEmpty()) return scopedKey(scopedId, toolCallId)
        return PendingKey(scope = "unscoped:${unscopedSeq++}", toolCallId = toolCallId)
    }

    private fun scopedKey(conversationId: String, toolCallId: String): PendingKey =
        PendingKey(scope = conversationId, toolCallId = toolCallId)

    private fun storedConversationId(conversationId: String?): String? {
        val trimmed = conversationId?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    private fun notifyPending() {
        _pendingRequests.value = pending.values.toList()
    }
}
