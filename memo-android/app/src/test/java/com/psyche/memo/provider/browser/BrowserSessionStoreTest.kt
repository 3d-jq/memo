package com.psyche.memo.provider.browser

import com.psyche.memo.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import android.webkit.CookieManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 只钉策略：**同时只有一个活动实例**（Android 的 CookieManager / WebStorage 是 app 全局，
 * 两个会话并发各留一套登录态在原生侧做不到），换会话时上一个必须被关掉并清凭据。
 *
 * Robolectric 下 `WebView` 是 shadow，`evaluateJavascript` 不会回调，所以这里不测导航。
 * cookie 断言能落地是因为 Robolectric 的 `CookieManager.getInstance()` 返回的
 * `RoboCookieManager` 是**真存储**（setCookie/getCookie/removeAllCookies 有真行为）；
 * `WebStorage.deleteAllData` 的 shadow 没有可查询的存储，造不出断言，不硬测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserSessionStoreTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun store() = BrowserSessionStore(ApplicationProvider.getApplicationContext())

    @Test
    fun openingAnotherConversationDropsThePreviousInstance() = runBlocking {
        val s = store()
        val first = s.sessionFor("c1")
        assertSame("同一会话复用同一个实例", first, s.sessionFor("c1"))

        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        cookies.setCookie("https://a/", "k=v")
        assertTrue("样例 cookie 要真存进去了（否则下面的断言是空闸）",
            cookies.getCookie("https://a/").orEmpty().contains("k=v"))

        val second = s.sessionFor("c2")
        assertNotSame("换会话必须换新实例", first, second)
        assertTrue("上一个会话的实例真的被关掉了", first.isClosed)
        assertFalse("换会话必须把 app 全局 cookie 清掉",
            cookies.getCookie("https://a/").orEmpty().contains("k=v"))
        assertNull("上一个会话不再可寻", s.peek("c1"))
        assertSame(second, s.peek("c2"))

        s.closeAll()
        assertNull(s.peek("c2"))
        assertTrue("closeAll 也是真关实例，不只是摘引用", second.isClosed)
    }

    /**
     * Important-1 的回归：close() 可能由 store 之外的路径落到实例上（Task 8 的「清空并
     * 关闭」按钮曾经就是）。store 不许依赖「没人这么干」的承诺：closed 的实例视为不存在，
     * 否则同会话再拿到的就是尸体，之后每颗动作都 `RENDERER_GONE`，这个会话永久不可用。
     */
    @Test
    fun closedInstanceIsNeverHandedBackAgain() = runBlocking {
        val s = store()
        val first = s.sessionFor("c1")
        first.close()
        assertNull("closed 的实例不许再被 peek 交出去", s.peek("c1"))
        val fresh = s.sessionFor("c1")
        assertNotSame("同会话在旧实例被关后必须重建", first, fresh)
        assertFalse("重建出来的实例是活的", fresh.isClosed)
    }
}
