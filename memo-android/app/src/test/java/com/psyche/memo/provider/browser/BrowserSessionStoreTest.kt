package com.psyche.memo.provider.browser

import com.psyche.memo.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

        val second = s.sessionFor("c2")
        assertNotSame("换会话必须换新实例", first, second)
        assertNull("上一个会话的实例已被关掉并清了凭据", s.peek("c1"))
        assertSame(second, s.peek("c2"))

        s.closeAll()
        assertNull(s.peek("c2"))
    }
}
