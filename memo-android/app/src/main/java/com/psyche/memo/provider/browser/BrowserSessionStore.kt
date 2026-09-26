package com.psyche.memo.provider.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 按会话持有的浏览器实例。**同时只允许一个活动实例**（内存考虑：一枚 WebView 几十 MB）。
 *
 * **登录态是 app 全局的、跨会话保留**（2026-09-26 改，见 [closeAll] 与 [BrowserSession.close]）：
 * Android 的 CookieManager / WebStorage 本来就做不到"每个对话各留一套登录"，v1 那套
 * 「换会话就地清干净」只会把所有对话的登录一起抹掉 —— 用户实测的症状是
 * 「让他给 DeepSeek 发信息，他说老是返回登录状态」。清数据从此只有一处入口：
 * 用户明确按下「清空并关闭」或关掉总开关。
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
                // 换会话**只销毁 WebView，不清登录态**（2026-09-26 改，见 [BrowserSession.close]）：
                // 原生 cookie jar 是 app 全局的，"就地清"做不到"每会话各留一套登录态"，
                // 只会把所有对话的登录一起抹掉 —— 用户拿到的就是"永远停在登录页"。
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

    /**
     * 关掉活动实例。**默认连凭据一起清** —— 这一颗只对应用户那两个动作：
     * 遮罩里的「清空并关闭」与设置页那颗总开关关掉。`clearSiteData = false` 只留给
     * 「换会话顺手关一下」这类内部路径（见 [sessionFor]）。
     *
     * 清的是 **app 全局**那一份 cookie jar 与 WebStorage：原生侧没有"按会话分"的入口
     * （`WebStorage.getInstance()` 是唯一入口，公开 android.jar 里没有带 Context 的重载），
     * 所以清就是全清 —— 这也是为什么它只能由用户明确点，不能发生在换会话的时候。
     */
    suspend fun closeAll(clearSiteData: Boolean = true) = mutex.withLock {
        withContext(Dispatchers.Main) {
            holder?.second?.close()
            holder = null
            if (clearSiteData) wipeSiteData()
        }
    }

    private fun wipeSiteData() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }
}
