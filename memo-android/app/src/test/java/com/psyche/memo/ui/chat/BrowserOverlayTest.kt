package com.psyche.memo.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
