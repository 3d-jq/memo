package com.psyche.memo.provider.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 按会话持有的浏览器实例。**同时只允许一个活动实例**：Android 的 CookieManager /
 * WebStorage 是 app 全局的，做不到两个会话各留一套登录态，只能做到
 * 「新会话看不到上一个会话留下的东西」（关的时候就地清干净，见 [BrowserSession.close]）。
 */
class BrowserSessionStore(private val appContext: Context) {

    private var holder: Pair<String, BrowserSession>? = null

    /**
     * [session.close] 会挂起并让出 Main（[Dispatchers.Main] 非 immediate），此时 `holder`
     * 还没置 null；不加锁的话另一会话的 [sessionFor] 能插进来，第一支恢复时把 holder 覆盖成
     * 自己的新实例 ⇒ 两个活 WebView 并存，那个被顶掉的再没人关（cookie jar 却仍是同一份）。
     */
    private val mutex = Mutex()

    suspend fun sessionFor(conversationId: String): BrowserSession = mutex.withLock {
        withContext(Dispatchers.Main) {
            holder?.let { (id, session) ->
                // 命中但已 closed 的实例视为不存在：Task 8 的「清空并关闭」曾直接 close() 而
                // 不动 store，尸体留在 holder 里会让这个会话之后每颗动作都 RENDERER_GONE。
                if (id == conversationId && !session.isClosed) return@withContext session
                session.close()
                holder = null
            }
            BrowserSession.createOnMain(appContext).also { holder = conversationId to it }
        }
    }

    /** 界面用它拿当前实例来接管；没有或已关就 null（不创建，也不把尸体递出去重挂）。 */
    fun peek(conversationId: String): BrowserSession? =
        holder?.takeIf { it.first == conversationId && !it.second.isClosed }?.second

    /**
     * 「+」面板那行「查看页面」的**唯一**判据（spec §12.5）：本会话有活着的浏览器实例。
     *
     * 走 [peek] 而不是直接读 `holder`：尸体（`isClosed`）也算 holder 里的一项，而遮罩拿不到
     * 实例就只能落回旗标 —— 判据与取值必须是同一条式子，否则会出现「行在、点了没页面」。
     * 纯内存读：不建实例、不查库、不读偏好，可以在组合期调用。
     */
    fun hasLiveSession(conversationId: String): Boolean = peek(conversationId) != null

    /** 「清空浏览器数据」/关掉全局开关。 */
    suspend fun closeAll() = mutex.withLock {
        withContext(Dispatchers.Main) {
            holder?.second?.close()
            holder = null
        }
    }
}
