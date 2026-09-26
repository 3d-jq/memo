package com.psyche.memo.provider.browser

import com.psyche.memo.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import android.view.View
import android.webkit.CookieManager
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * 两个会话并发各留一套登录态在原生侧做不到），换会话时上一个必须被关掉 —— 但**不清凭据**（2026-09-26 改口：清只发生在
 * `closeAll`，也就是用户明确按下的「清空并关闭 / 关掉总开关」；换会话清登录态会把用户直接锁在门外）。
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
        // 2026-09-26 改口（用户「让他给 DeepSeek 发信息，他说老是返回登录状态」）：
        // **换会话只销毁 WebView，绝不清登录态**。原生那份 jar 是 app 全局的，"就地清"
        // 换不来"每会话各留一套登录"，只会把所有对话的登录一起抹掉，用户永远停在登录页。
        assertTrue(
            "换会话之后登录态必须还在（清只发生在 closeAll）",
            cookies.getCookie("https://a/").orEmpty().contains("k=v"),
        )
        assertNull("上一个会话不再可寻", s.peek("c1"))
        assertSame(second, s.peek("c2"))

        s.closeAll()
        assertNull(s.peek("c2"))
        assertTrue("closeAll 也是真关实例，不只是摘引用", second.isClosed)
        assertFalse(
            "closeAll 默认连凭据一起清 —— 这是用户那两颗钮（「清空并关闭」/ 关掉总开关）唯一的清点",
            cookies.getCookie("https://a/").orEmpty().contains("k=v"),
        )
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

    /**
     * Fix round 2 finding 1 的回归：**close() 之后 detach() 仍会进来**。
     *
     * 两条真路径：① Task 8 的「清空并关闭」钮 `scope.launch { session.close() }` 后同步
     * `onBack()` —— close 走 `withContext(Dispatchers.Main)`（非 immediate ⇒ post 到队列），
     * `DisposableEffect.onDispose` 与 `AndroidView.onRelease` 的 detach 排在它后面 ⇒ destroy 先、
     * detach 后；② 正在看的会话被 `sessionFor(别的会话)` 抢掉销毁，随后它自己的 onDispose 再
     * detach。平台契约是「destroy() 之后不得再调用本类的任何其它方法」，而 measure/layout 正是
     * 「其它方法」——未修时每次多进来的 detach 都对尸体补一发视口还原。
     *
     * **断言层是实测选出来的**（Robolectric 4.13，本机探针实证，细节在 task-5-report 的
     * Fix round 2）：`ShadowWebView.destroy` 是 no-op，destroy 之后 measure/layout **不会真抛**，
     * `layout()` 也被 shadow 成 no-op（`view.right` 恒 0）——brief 里「断言不抛」与
     * 「measuredWidth 真被平台改掉」这两条在 JVM 上都观测不到。唯一透到真代码的是
     * `View.measure`（measuredWidth/Height 按 EXACTLY spec 被设置），所以钉**可观测事实**：
     * closed 之后 detach 不许再把视口重设回 1280×1600。先用 360×640 的 spec 把 measuredWidth
     * 弄脏（非空闸：未修实现这条也过，红要红在 detach 把它铺回去那一刻）。「真机必炸」这条
     * 只有真机能证。
     */
    @Test
    fun detachingAfterCloseMustNotResetTheViewportAgain() = runBlocking {
        fun dirtyPhoneViewport(session: BrowserSession) {
            session.activeWebView.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            // 非空闸：measure 的效果必须真能被观测到，否则后面的断言全是恒真
            assertEquals(360, session.activeWebView.measuredWidth)
            assertEquals(640, session.activeWebView.measuredHeight)
        }

        val session = store().sessionFor("c1")
        session.close()

        // 路径 ①：close 之后裸 detach（launch{close()} + 同步 onBack 的排队顺序）
        dirtyPhoneViewport(session)
        session.detach()
        assertEquals(
            "closed 之后 detach 不许再重设离屏视口（destroy 后的 measure/layout 真机直接炸）",
            360, session.activeWebView.measuredWidth,
        )

        // 路径 ②：attachTo 临时容器再 detach（AndroidView.onRelease）——removeView 是父容器
        // 的操作、合法且必要；要闸掉的只有视口还原
        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        session.attachTo(host)
        dirtyPhoneViewport(session)
        session.detach()
        assertNull("detach 仍要把视图从父容器摘掉（被闸掉的只是 measure/layout）", session.activeWebView.parent)
        assertEquals(
            "closed 之后 detach 不许再重设离屏视口",
            360, session.activeWebView.measuredWidth,
        )
    }

    /**
     * `hasLiveSession` 这条判据本身（spec §12.5；2026-09-26 之后它**不再决定「+」面板那行在不在**，
     * 只决定那一行写「查看页面」还是「打开浏览器」—— 入口常驻，判据只剩全局开关，见
     * `ChatContent` 的 `browserEntryAvailable`）。
     *
     * 三条都要钉：没实例 ⇒ false；有活实例 ⇒ true；**已关的尸体 ⇒ false**。最后一条是它的全部难度：
     * 判据必须与遮罩取实例用**同一条式子**（[BrowserSessionStore.peek]），直接读 `holder` 会让
     * 「写着查看页面、点开却没页面」成为真路径（那一行点下去要挂的就是 `peek` 拿到的那一枚）。
     *
     * 另一条边界：同一 holder 里换会话（只留一枚）不许让别的会话也报 true。
     */
    @Test
    fun theViewPageEntryAppearsOnlyForALiveSessionOfThisConversation() = runBlocking {
        val s = store()
        assertFalse("没有实例 ⇒ 那一行该写「打开浏览器」", s.hasLiveSession("c1"))

        val session = s.sessionFor("c1")
        assertTrue("建出实例 ⇒ 那一行改口成「查看页面」", s.hasLiveSession("c1"))
        assertFalse(
            "判据按会话分：c1 有实例不代表 c2 也能查看页面",
            s.hasLiveSession("c2"),
        )

        session.close()
        assertFalse(
            "尸体不算活着 —— 判据说没页面、peek 就得真没页面，两者不许各说一套",
            s.hasLiveSession("c1"),
        )
    }
}
