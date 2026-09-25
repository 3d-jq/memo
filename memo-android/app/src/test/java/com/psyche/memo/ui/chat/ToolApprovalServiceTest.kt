package com.psyche.memo.ui.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tool_approval_service.dart 的 1:1 行为测试：scoped/unscoped 键、兜底匹配、
 * 批准/拒绝/取消的完成语义与通知快照。
 */
class ToolApprovalServiceTest {

    private val emptyArgs: JsonObject = JsonObject(emptyMap())

    @Test
    fun requestApproval_registersPendingAndNotifies() {
        val service = ToolApprovalService()
        val deferred = service.requestApproval(
            toolCallId = "call-1",
            toolName = "calendar_create",
            arguments = emptyArgs,
            conversationId = "conv-1",
        )
        assertFalse(deferred.isCompleted)
        assertTrue(service.hasPending)
        assertEquals(1, service.pendingRequests.value.size)
        val req = service.pendingRequests.value[0]
        assertEquals("call-1", req.toolCallId)
        assertEquals("calendar_create", req.toolName)
        assertEquals("conv-1", req.conversationId)
        assertTrue(service.isPending("call-1", "conv-1"))
        // 无会话参数时，任意会话的同 id 都算 pending。
        assertTrue(service.isPending("call-1"))
    }

    @Test
    fun approve_completesApproved() = runBlocking {
        val service = ToolApprovalService()
        val deferred = service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        service.approve("call-1", conversationId = "conv-1")
        assertTrue(deferred.isCompleted)
        val result = deferred.await()
        assertTrue(result.approved)
        assertNull(result.denyReason)
        assertFalse(service.hasPending)
    }

    @Test
    fun deny_completesDeniedWithReason() = runBlocking {
        val service = ToolApprovalService()
        val deferred = service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        service.deny("call-1", reason = "user said no", conversationId = "conv-1")
        assertTrue(deferred.isCompleted)
        val result = deferred.await()
        assertFalse(result.approved)
        assertEquals("user said no", result.denyReason)
        assertFalse(service.hasPending)
    }

    @Test
    fun duplicateRequest_returnsSameCompleter() {
        val service = ToolApprovalService()
        val first = service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        val second = service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        assertEquals(first, second)
        assertEquals(1, service.pendingRequests.value.size)
    }

    @Test
    fun pendingFor_prefersExactScopedMatch() {
        val service = ToolApprovalService()
        service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        service.requestApproval("call-1", "tool", emptyArgs, "conv-2")
        assertEquals("conv-1", service.pendingFor("call-1", "conv-1")?.conversationId)
        assertEquals("conv-2", service.pendingFor("call-1", "conv-2")?.conversationId)
    }

    @Test
    fun pendingFor_unscopedRequestIsFailSafeFallback() {
        val service = ToolApprovalService()
        // conv-1 有带会话的请求，另有一个 unscoped 同 id 请求 → 查其他会话时
        // 只可能命中 unscoped 兜底。
        service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        service.requestApproval("call-1", "tool", emptyArgs, null)
        val fallback = service.pendingFor("call-1", "conv-2")
        assertEquals(null, fallback?.conversationId)
    }

    @Test
    fun pendingFor_otherConversationNeverMatched() {
        val service = ToolApprovalService()
        service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        assertNull(service.pendingFor("call-1", "conv-2"))
        // 两个带会话的请求且无 unscoped → 不匹配第三个会话。
        service.requestApproval("call-2", "tool", emptyArgs, "conv-2")
        assertNull(service.pendingFor("call-2", "conv-3"))
    }

    @Test
    fun unscopedRequests_stayIndependent() {
        val service = ToolApprovalService()
        service.requestApproval("call-1", "tool", emptyArgs, null)
        service.requestApproval("call-2", "tool", emptyArgs, null)
        assertEquals(2, service.pendingRequests.value.size)
        // matches.length == 1 → approve 各自命中。
        service.approve("call-1")
        service.approve("call-2")
        assertEquals(0, service.pendingRequests.value.size)
    }

    @Test
    fun approve_unscopedRemovesExactlyOne() {
        val service = ToolApprovalService()
        service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        service.requestApproval("call-1", "tool", emptyArgs, null)
        service.approve("call-1", conversationId = null)
        // 只移除 unscoped 那条；conv-1 的仍在。
        assertEquals(1, service.pendingRequests.value.size)
        assertEquals("conv-1", service.pendingRequests.value[0].conversationId)
    }

    @Test
    fun cancelAll_deniesEverything() = runBlocking {
        val service = ToolApprovalService()
        val a = service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        val b = service.requestApproval("call-2", "tool", emptyArgs, null)
        service.cancelAll()
        assertTrue(a.isCompleted)
        assertTrue(b.isCompleted)
        assertEquals("cancelled", a.await().denyReason)
        assertEquals("cancelled", b.await().denyReason)
        assertFalse(service.hasPending)
        assertEquals(0, service.pendingRequests.value.size)
    }

    @Test
    fun cancelForConversation_keepsOtherConversations() = runBlocking {
        val service = ToolApprovalService()
        val a = service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        val b = service.requestApproval("call-2", "tool", emptyArgs, "conv-2")
        val c = service.requestApproval("call-3", "tool", emptyArgs, null)
        service.cancelForConversation("conv-1")
        assertTrue(a.isCompleted)
        assertEquals("cancelled", a.await().denyReason)
        // 同会话 + 未记录会话被取消；其他会话的请求继续等待。
        assertTrue(c.isCompleted)
        assertFalse(b.isCompleted)
        assertTrue(service.isPending("call-2", "conv-2"))
    }

    @Test
    fun emptyToolCallId_isNotPending() {
        val service = ToolApprovalService()
        service.requestApproval("call-1", "tool", emptyArgs, "conv-1")
        assertNull(service.pendingFor("", "conv-1"))
        assertFalse(service.isPending("", "conv-1"))
    }

    /**
     * 工作区详情页把某个工具的审批开关关掉 → 该工具**已弹出**的待审批直接放行，
     * 别让它继续拦着这一轮生成（用户 2026-09-16「我关闭了确认 为什么还有确认呀」）。
     * 只放行同名的，其他工具/其他会话的请求原样等着。
     */
    /**
     * 「释放之后再要同一个 id」必须拿到**新**的 deferred。
     *
     * 同键重复请求会返回**已存在**的 completer（上游语义，见 [ToolApprovalService.requestApproval]），
     * 所以一旦上一轮的等待方死了而 pending 没被清掉，这一轮的 `.await()` 就挂在一个
     * 永远没人应答的旧对象上 —— 那条会话从此所有工具都不执行，换新对话才好
     * （用户 2026-09-25）。本用例钉住的是修复成立的前提：**每条终止路径都要释放**，
     * 释放之后同 id 重新请求要能拿到干净的 deferred。
     */
    @Test
    fun releasedRequest_freesTheKeyForAFreshCompleter() = runBlocking {
        val service = ToolApprovalService()
        val first = service.requestApproval("tool-1", "workspace_shell", emptyArgs, "conv-1")
        // 没释放之前，同一个键只会拿到旧 deferred（旧等待方已死 ⇒ 新调用永远挂住）。
        assertSame(first, service.requestApproval("tool-1", "workspace_shell", emptyArgs, "conv-1"))

        service.cancelForConversation("conv-1")
        assertTrue(first.isCompleted)

        val second = service.requestApproval("tool-1", "workspace_shell", emptyArgs, "conv-1")
        assertNotSame(first, second)
        assertFalse(second.isCompleted)
        service.approve("tool-1", conversationId = "conv-1")
        assertTrue(second.await().approved)
    }

    @Test
    fun approvePendingForTool_releasesOnlyThatTool() = runBlocking {
        val service = ToolApprovalService()
        val shell = service.requestApproval("call-1", "workspace_shell", emptyArgs, "conv-1")
        val shell2 = service.requestApproval("call-2", "workspace_shell", emptyArgs, "conv-2")
        val write = service.requestApproval("call-3", "workspace_write_file", emptyArgs, "conv-1")

        assertEquals(2, service.approvePendingForTool("workspace_shell"))

        assertTrue(shell.await().approved)
        assertTrue(shell2.await().approved)
        assertFalse(write.isCompleted)
        assertEquals(1, service.pendingRequests.value.size)
        assertEquals("workspace_write_file", service.pendingRequests.value.single().toolName)
        // 没有该工具的待审批时是 no-op。
        assertEquals(0, service.approvePendingForTool("workspace_shell"))
        assertEquals(1, service.pendingRequests.value.size)
    }
}
