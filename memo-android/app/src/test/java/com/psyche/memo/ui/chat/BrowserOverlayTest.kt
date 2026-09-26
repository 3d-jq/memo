package com.psyche.memo.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.provider.browser.BrowserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 接管的**成对性**：遮罩在 → `userControls` 为真（模型必须被挡住）；遮罩走 → 必须交还。
 *
 * 漏了 `onDispose` 里那句 `release()`，就是「用户看过一眼页面，助手从此永远用不了浏览器」——
 * 真机上要复现得先让模型再调一次，所以这条只能靠单测钉住。Robolectric 的资源是英文，
 * 按钮文案按默认 `values/strings.xml` 断言。
 *
 * 「清空并关闭」由遮罩**委托给调用方**（[BrowserOverlay.onClearAndClose]）：真正派发
 * `browserSessions.closeAll()` 的是 `ChatContent`，它必须落在容器级 `appScope` 上而不是
 * 页面作用域（Task 7 那条裁决：页面组合一离开就取消 `close()`，会留下未 destroy 的 WebView
 * 与未清的 cookie jar）。所以这里注入一颗计数器，钉的就是「委托发生了、而且没有替用户走
 * 交还那半件事」。closeAll 真清 cookie 的证据在 `BrowserSessionStoreTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    /** 遮罩是否挂着 —— 就是 `ChatContent.browserOverlayOpen` 那个旗标。 */
    private var open by mutableStateOf(false)
    private var backCalls = 0
    private var clearCalls = 0

    /**
     * 用 `createOnMain` 而不是 `create()`：这条测试挂的是 `createComposeRule`，它自己接管
     * Main 调度器，再叠 `MainDispatcherRule` 或 `runBlocking { withContext(Main) }` 都会
     * 自锁死（Compose 测试线程就是主 Looper 线程）。
     */
    private fun newSession(): BrowserSession =
        BrowserSession.createOnMain(ApplicationProvider.getApplicationContext())

    /** 挂上遮罩，接线形状与 `ChatContent` 一致：`onBack` 负责把旗标落下来（交还由 onDispose 做）。 */
    private fun mountOverlay(session: BrowserSession) {
        backCalls = 0
        clearCalls = 0
        compose.setContent {
            if (open) {
                BrowserOverlay(
                    session = session,
                    onBack = {
                        backCalls++
                        open = false
                    },
                    onClearAndClose = { clearCalls++ },
                )
            }
        }
    }

    /**
     * 宿主自己把遮罩摘掉（真实路径：`ChatContent` 那一层的旗标落下来 —— 换会话、退出对话页）。
     *
     * 不能按字面写成第二次 `compose.setContent { }`：这一版 Compose 测试规则直接抛
     * `Cannot call setContent twice per test!`。所以销毁只能靠状态驱动，而 `waitForIdle()`
     * 负责把这一帧推进去（组合外对 `mutableStateOf` 的赋值要过一遍全局快照的通知机制）。
     */
    private fun unmountOverlay() {
        open = false
        compose.waitForIdle()
    }

    @Test
    fun overlayBlocksTheModelWhileOpenAndHandsBackOnDispose() {
        val session = newSession()
        open = true
        mountOverlay(session)
        assertTrue("遮罩组合完，模型就该被挡住", session.userControls)

        unmountOverlay()
        assertFalse("遮罩销毁后必须交还", session.userControls)
        assertEquals("自己销毁不许替用户走 onBack", 0, backCalls)
    }

    @Test
    fun handBackButtonClosesTheOverlayAndFreesTheModel() {
        val session = newSession()
        open = true
        mountOverlay(session)
        assertTrue(session.userControls)

        compose.onNodeWithText("Hand back").performClick()
        compose.waitForIdle()
        assertEquals("点「交还」要走 onBack", 1, backCalls)
        assertEquals("「交还」不清数据：页面与登录态都得留着", 0, clearCalls)
        assertFalse("交还后模型要能继续操作", session.userControls)
    }

    /**
     * spec §6 要求遮罩顶部显示**当前页面标题**，不是固定的「浏览器」。
     *
     * 兜底串也要有：`snapshot` 为 null（还没 find 过）或标题空白时回落 `browser_overlay_title`。
     */
    @Test
    fun overlayTitleShowsTheCurrentPageTitleAndFallsBackWhenThereIsNone() {
        val session = newSession()
        open = true
        mountOverlay(session)
        // 没 find 过 ⇒ `snapshot == null` ⇒ 回落兜底串。
        compose.onNodeWithText("Browser").assertExists()

        session.publishSnapshot(
            com.psyche.memo.provider.browser.BrowserPageSnapshot(
                generation = 1,
                url = "https://example.com",
                title = "Example Domain",
                elements = emptyList(),
            ),
        )
        // 标题读的是普通字段（不是 State），所以要靠一次重组才跟上 —— 生产里 ChatContent 每有
        // 消息/流式状态变化都会重组，接管期间这一下必然发生；测试里就自己摘掉再挂回来。
        open = false
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
        compose.onNodeWithText("Example Domain").assertExists()
        compose.onAllNodesWithText("Browser").assertCountEquals(0)
    }

    @Test
    fun clearAndCloseFreesTheModelToo() {
        val session = newSession()
        open = true
        mountOverlay(session)
        // 不先确认真的挂上了，后面那句 assertFalse 就是恒真（遮罩根本没组合过当然不挡模型）。
        assertTrue(session.userControls)

        compose.onNodeWithText("Clear and close").performClick()
        compose.waitForIdle()
        assertEquals("清理动作必须委托给调用方（容器作用域），不在遮罩里自己关", 1, clearCalls)
        assertEquals(1, backCalls)
        assertFalse("清空并关闭之后模型不再被挡", session.userControls)
    }

    /**
     * 标签条（spec §12.2/§12.3）：一枚标签一张芯片，点芯片 = 换活动标签。
     *
     * 这条钉的是「界面与模型看的是同一份状态」：用户点第二张芯片之后，会话那边的活动标签必须
     * **真的**换过去（`tabsSnapshot` 里 active 的那一条跟着变），否则用户在界面上翻到第二页、
     * 模型还在替他操作第一页 —— 接管功能最不能出现的分裂就是这个。
     * 界面读的是 StateFlow，所以不需要像标题那条一样手动摘挂一次来逼重组。
     */
    @Test
    fun tabStripSwitchesTheActiveTabTheSessionIsDriving() {
        val session = newSession()
        assertTrue("先造出第二枚标签", session.newTabFromUi())
        open = true
        mountOverlay(session)

        // 空白标签没有标题也没有 url ⇒ 芯片落到序号上（1 / 2）。
        //
        // **performOnClick 而不是 performClick**：Robolectric 的文本度量是退化的（字形宽度按 0 算），
        // 指针注入落在芯片几何中心 —— 那正是 × 的位置，测出来的是「关掉这枚标签」而不是「切到
        // 这一枚」。手指落点的几何关系在 JVM 上证不了（真机验收里有一条就是它）；这里钉的是接线：
        // 芯片的点击必须送到 `selectTabFromUi`。`clickable` 会把子节点的文本合并进自己的语义，
        // 所以 `onNodeWithText` 找到的就是那颗可点的芯片本体。
        compose.onNode(hasClickAction() and hasText("2")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(
            "点第二张芯片必须把会话的活动标签也换过去",
            1,
            session.tabsSnapshot.value.first { it.active }.index,
        )
        assertEquals("切换不许顺带关掉任何标签", 2, session.tabInfos().size)

        // × 那颗送到 `closeTabFromUi`：关掉第一枚之后只剩第二枚，且它仍然顶着新的下标 0。
        compose.onAllNodesWithContentDescription("Close tab")[0].performClick()
        compose.waitForIdle()
        assertEquals(1, session.tabInfos().size)
        assertEquals(0, session.tabInfos().single().index)
    }

    /**
     * 地址栏被挡下时要**在界面上说**（spec §12.3）。
     *
     * Robolectric 里 `WebView` 是 shadow，导航不会真落地，所以这两条只能断言「拦截判据 + 提示」
     * 这一半；「真按 https:// 输入的地址会不会跳」归真机验收（spec §12.6 第 4 步），不在这里假称。
     */
    @Test
    fun addressBarBlocksPlainHttpAndSaysWhy() {
        val session = newSession()
        open = true
        mountOverlay(session)

        compose.onNode(hasSetTextAction()).performTextInput("file:///sdcard/secret.txt")
        compose.onNodeWithText("Go").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Only http and https addresses can be opened").assertIsDisplayed()
    }

    /** 用户输入 `example.com` 是想访问这个站，不是想报错：没写协议就补 https（主流浏览器一致）。
     *  被挡的那条现在是 `file://` / `javascript:` 这类本地与代码 scheme（spec §15：明文 http 放开了）。 */
    @Test
    fun addressBarTreatsABareHostAsHttps() {
        val session = newSession()
        open = true
        mountOverlay(session)

        compose.onNode(hasSetTextAction()).performTextInput("example.com")
        compose.onNodeWithText("Go").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Only http and https addresses can be opened").assertDoesNotExist()
    }
}
