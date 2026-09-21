package com.psyche.memo.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * tool_approval_service.dart 的 1:1 移植。ChangeNotifier 通知改为 StateFlow
 * 快照（pendingRequests），供 Compose 层 collect；其余行为与源码逐行对应。
 */
data class ToolApprovalResult(
    val approved: Boolean,
    val denyReason: String? = null,
) {
    companion object {
        fun approved(): ToolApprovalResult = ToolApprovalResult(approved = true)
        fun denied(reason: String? = null): ToolApprovalResult =
            ToolApprovalResult(approved = false, denyReason = reason)
    }
}

/** tool_approval_service.dart ToolApprovalRequest。 */
class ToolApprovalRequest(
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val conversationId: String?,
    internal val completer: CompletableDeferred<ToolApprovalResult>,
)

/**
 * 管理需要用户确认的工具调用的审批状态。storage 以 (scope, toolCallId) 为键，
 * 让两个共享 `round-0:tool-1` 之类占位 id 的会话各自持有独立的 Completer。
 */
class ToolApprovalService {

    private data class PendingKey(val scope: String, val toolCallId: String)

    private val pending = LinkedHashMap<PendingKey, ToolApprovalRequest>()
    private var unscopedSeq = 0

    private val _pendingRequests = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())

    /** pendingRequests 快照（tool_approval_service.dart 54-56）。 */
    val pendingRequests: StateFlow<List<ToolApprovalRequest>> = _pendingRequests

    /** 是否存在待审批请求（tool_approval_service.dart 58-59）。 */
    val hasPending: Boolean get() = pending.isNotEmpty()

    /**
     * 检查某个工具调用是否待审批。省略 [conversationId] 时任意会话的同 id
     * 都算；提供时仅该会话（或 unscoped 兜底）匹配。
     */
    fun isPending(toolCallId: String, conversationId: String? = null): Boolean {
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isEmpty()) {
            return pending.values.any { it.toolCallId == toolCallId }
        }
        return pendingFor(toolCallId = toolCallId, conversationId = conversationId) != null
    }

    /**
     * 按会话与工具调用 id 查待审批请求。优先精确 (conversationId, toolCallId)；
     * 不存在时以同 toolCallId 的 unscoped 请求兜底；绝不返回其他会话的请求。
     */
    fun pendingFor(
        toolCallId: String,
        conversationId: String? = null,
    ): ToolApprovalRequest? {
        if (toolCallId.isEmpty()) return null
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isNotEmpty()) {
            val scoped = pending[scopedKey(scopedId, toolCallId)]
            if (scoped != null) return scoped
            return findUnscoped(toolCallId)
        }
        val matches = pending.values.filter { it.toolCallId == toolCallId }
        if (matches.size == 1) return matches.single()
        return findUnscoped(toolCallId)
    }

    /**
     * 请求审批。返回的 deferred 在用户批准/拒绝时完成；新请求应传
     * [conversationId]，null 会话存到唯一 unscoped 键下，避免两个会话互覆。
     */
    fun requestApproval(
        toolCallId: String,
        toolName: String,
        arguments: JsonObject,
        conversationId: String? = null,
    ): CompletableDeferred<ToolApprovalResult> {
        val key = storageKey(conversationId, toolCallId)
        val existing = pending[key]
        if (existing != null) return existing.completer
        val completer = CompletableDeferred<ToolApprovalResult>()
        pending[key] = ToolApprovalRequest(
            toolCallId = toolCallId,
            toolName = toolName,
            arguments = arguments,
            conversationId = storedConversationId(conversationId),
            completer = completer,
        )
        notifyPending()
        return completer
    }

    /** 批准一个待审批调用。 */
    fun approve(toolCallId: String, conversationId: String? = null) {
        val req = takePending(toolCallId = toolCallId, conversationId = conversationId)
        if (req != null && !req.completer.isCompleted) {
            req.completer.complete(ToolApprovalResult.approved())
        }
        notifyPending()
    }

    /** 拒绝一个待审批调用，可带原因。 */
    fun deny(toolCallId: String, reason: String? = null, conversationId: String? = null) {
        val req = takePending(toolCallId = toolCallId, conversationId = conversationId)
        if (req != null && !req.completer.isCompleted) {
            req.completer.complete(ToolApprovalResult.denied(reason))
        }
        notifyPending()
    }

    /**
     * 放行某个工具**正挂着**的全部待审批请求，返回放行条数。
     *
     * 用在「用户在工作区详情页把某个工具的审批开关关掉」那一刻：屏上那个面板是在开关
     * 关掉**之前**建出来的，用户的本意是「这个工具以后不用问我」，已经弹出来的那一个
     * 不该继续拦着（用户 2026-09-16「我关闭了确认 为什么还有确认呀」）。按工具名匹配
     * （工具名是全局常量，同名工具属于同一个助手的同一个工作区，够用）。
     */
    fun approvePendingForTool(toolName: String): Int {
        val matches = pending.values.filter { it.toolName == toolName }
        if (matches.isEmpty()) return 0
        for (req in matches) {
            pending.entries.removeAll { it.value === req }
            if (!req.completer.isCompleted) {
                req.completer.complete(ToolApprovalResult.approved())
            }
        }
        notifyPending()
        return matches.size
    }

    /** 取消全部待审批（如流被取消时）。 */
    fun cancelAll() {
        for (req in pending.values) {
            if (!req.completer.isCompleted) {
                req.completer.complete(ToolApprovalResult.denied("cancelled"))
            }
        }
        pending.clear()
        notifyPending()
    }

    /**
     * 取消属于 [conversationId] 的待审批。未记录会话的请求一并取消
     * （防止工具 handler 被卡死），但其他会话的审批继续等待。
     */
    fun cancelForConversation(conversationId: String) {
        val toCancel = pending.values.filter {
            it.conversationId == null || it.conversationId == conversationId
        }
        if (toCancel.isEmpty()) return
        for (req in toCancel) {
            pending.entries.removeAll { it.value === req }
            if (!req.completer.isCompleted) {
                req.completer.complete(ToolApprovalResult.denied("cancelled"))
            }
        }
        notifyPending()
    }

    private fun takePending(
        toolCallId: String,
        conversationId: String?,
    ): ToolApprovalRequest? {
        val scopedId = conversationId?.trim().orEmpty()
        if (scopedId.isNotEmpty()) {
            val scoped = pending.remove(scopedKey(scopedId, toolCallId))
            if (scoped != null) return scoped
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

    private fun findUnscoped(toolCallId: String): ToolApprovalRequest? {
        val matches = pending.values.filter {
            it.toolCallId == toolCallId &&
                (it.conversationId == null || it.conversationId?.isEmpty() == true)
        }
        if (matches.size == 1) return matches.single()
        return null
    }

    private fun removeUnscoped(toolCallId: String): ToolApprovalRequest? {
        val matches = pending.entries.filter {
            it.value.toolCallId == toolCallId &&
                (it.value.conversationId == null || it.value.conversationId?.isEmpty() == true)
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
