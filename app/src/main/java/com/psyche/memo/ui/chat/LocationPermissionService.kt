package com.psyche.memo.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 工具执行期申请运行时权限的那根「挂起 → UI 回答」通道（现在是定位在用）。
 *
 * 形状照同族的 [AskUserInteractionService]（审批服务已整块拆除）：执行器挂起
 * 等一个 [CompletableDeferred]，Compose 侧（ChatContent）观察 [pending] 去弹系统权限框，
 * 把结果 [resolve] 回来 —— 因为只有界面手里才有 ActivityResultRegistry。
 *
 * 与那两个不同的是**必须自带超时**：后台生成时页面上根本没有 ChatContent，没人回答的
 * 等待不能把那条生成永久挂住。
 */
class LocationPermissionService(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val lock = Any()
    private var waiter: CompletableDeferred<Boolean>? = null

    private val _pending = MutableStateFlow(false)

    /** UI 侧观察：为 true 时去弹系统权限框。 */
    val pending: StateFlow<Boolean> = _pending

    /** 执行器入口：等待界面把授权结果交回来；没人回答就按「未授权」收尾。 */
    suspend fun awaitGrant(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(lock) {
            // 上一次的等待者还没结掉（例如超时瞬间又来一次调用）：先按未授权结掉，
            // 否则它永远等不到 resolve。
            waiter?.complete(false)
            waiter = deferred
        }
        _pending.value = true
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            val current = synchronized(lock) {
                val held = waiter
                if (held === deferred) waiter = null
                held
            }
            if (current === deferred) {
                _pending.value = false
                deferred.complete(false)
            }
        }
    }

    /** 界面侧交答案。没有等待者时（已经超时收尾了）静默忽略。 */
    fun resolve(granted: Boolean) {
        val pending = synchronized(lock) {
            val held = waiter
            waiter = null
            held
        } ?: return
        _pending.value = false
        pending.complete(granted)
    }

    companion object {
        /** 权限框等人点，给 90 秒；再久就当没授权。 */
        const val DEFAULT_TIMEOUT_MS = 90_000L
    }
}
