package com.psyche.memo.ui.chat

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行期申请定位权限的那根「挂起 → UI 回答」通道。
 *
 * 形状跟 [ToolApprovalService]/[AskUserInteractionService] 一致：执行器挂起等一个
 * deferred，Compose 侧观察 [LocationPermissionService.pending] 去弹系统权限框，结果回填。
 * 关键约束是**它绝不能永远挂着** —— 后台生成时页面上没有 ChatContent，没人回答就必须
 * 自己收尾，否则那条生成会卡死。
 */
class LocationPermissionServiceTest {

    @Test
    fun pendingIsPublishedWhileWaitingAndClearedAfterResolve() {
        val service = LocationPermissionService(timeoutMs = 5_000)
        val seen = mutableListOf<Boolean>()

        runBlocking {
            val waiter = async { service.awaitGrant() }
            while (!service.pending.value) { yield() }
            seen += service.pending.value
            service.resolve(true)
            assertEquals(true, waiter.await())
            seen += service.pending.value
        }

        assertEquals(listOf(true, false), seen)
    }

    /** 没人回答（后台生成、界面不在）⇒ 超时后按「未授权」收尾。 */
    @Test
    fun unansweredRequestTimesOutAsDenied() {
        val service = LocationPermissionService(timeoutMs = 40)
        val started = System.currentTimeMillis()
        val granted = runBlocking { service.awaitGrant() }
        assertFalse(granted)
        assertFalse(service.pending.value)
        assertTrue(System.currentTimeMillis() - started >= 40)
    }

    /** 两次调用不串台：resolve 只结当前那一个等待者，第二次要重新问。 */
    @Test
    fun resolveOnlyAnswersTheCurrentWaiter() {
        val service = LocationPermissionService(timeoutMs = 5_000)
        runBlocking {
            val first = async { service.awaitGrant() }
            while (!service.pending.value) { yield() }
            service.resolve(false)
            assertEquals(false, first.await())

            val second = async { service.awaitGrant() }
            while (!service.pending.value) { yield() }
            service.resolve(true)
            assertEquals(true, second.await())
        }
    }

    /** 重复 resolve（系统框回调可能来两次）不能把后一个等待者提前结掉。 */
    @Test
    fun lateResolveWithoutWaiterIsIgnored() {
        val service = LocationPermissionService(timeoutMs = 5_000)
        runBlocking {
            service.resolve(true) // 没人等：应被忽略且不抛
            val waiter = async { service.awaitGrant() }
            while (!service.pending.value) { yield() }
            service.resolve(true)
            assertEquals(true, waiter.await())
        }
    }
}
