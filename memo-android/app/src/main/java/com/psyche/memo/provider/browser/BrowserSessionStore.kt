package com.psyche.memo.provider.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按会话持有的浏览器实例。**同时只允许一个活动实例**：Android 的 CookieManager /
 * WebStorage 是 app 全局的，做不到两个会话各留一套登录态，只能做到
 * 「新会话看不到上一个会话留下的东西」（关的时候就地清干净，见 [BrowserSession.close]）。
 */
class BrowserSessionStore(private val appContext: Context) {

    private var holder: Pair<String, BrowserSession>? = null

    suspend fun sessionFor(conversationId: String): BrowserSession = withContext(Dispatchers.Main) {
        holder?.let { (id, session) ->
            if (id == conversationId) return@withContext session
            session.close()
            holder = null
        }
        BrowserSession.createOnMain(appContext).also { holder = conversationId to it }
    }

    /** 界面用它拿当前实例来接管；没有就 null（不创建）。 */
    fun peek(conversationId: String): BrowserSession? =
        holder?.takeIf { it.first == conversationId }?.second

    /** 「清空浏览器数据」/关掉全局开关。 */
    suspend fun closeAll() = withContext(Dispatchers.Main) {
        holder?.second?.close()
        holder = null
    }
}
