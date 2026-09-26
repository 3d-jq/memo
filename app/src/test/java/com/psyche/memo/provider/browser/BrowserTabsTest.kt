package com.psyche.memo.provider.browser

import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 多标签基座（spec §12.2）的**真身**语义。Robolectric 里 `WebView` 是 shadow、
 * `evaluateJavascript` 不回调，所以这里一律不测导航 —— 测的是那些「只有真会话才存在」的不变量：
 *
 * 1. **发号器按会话而不是按标签**（[BrowserSession.nextEpoch]）：会话内任何一枚标签拿到的号
 *    都不重复，且切回一枚旧标签拿到的也是**新**号。跨标签 index 别名（模型拿着 A 标签的第 5 号
 *    点到了 B 标签的第 5 个元素）就死在这条上 —— 若各标签自己 `++`，两边迟早同号。
 * 2. 切标签作废快照、关**非活动**标签不作废（页面根本没换）。
 * 3. 会话**恰有一枚活动标签**：关到空就补一枚空白的。
 * 4. [BrowserSession.MAX_TABS] 是硬上界。
 * 5. 已关的会话挂不上视图（`attachTo` 那道闸 + 列表里留尸体而不清空，
 *    见 [BrowserSession.close] 的注释）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserTabsTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun session(): BrowserSession =
        BrowserSession.createOnMain(ApplicationProvider.getApplicationContext())

    @Test
    fun everyTabGetsADistinctEpochEvenAfterSwitchingBack() {
        val s = session()
        val firstEpoch = s.generation

        assertTrue("新建标签要成功", s.newTabFromUi())
        val secondEpoch = s.generation
        assertNotEquals("新标签不能沿用旧标签的号", firstEpoch, secondEpoch)

        assertTrue("切回第一枚", s.selectTabFromUi(0))
        val backToFirst = s.generation
        assertNotEquals("切回来也是新号：旧 index 一律作废", secondEpoch, backToFirst)
        assertNotEquals(
            "会话级发号器不许回头用第一次那个号（否则跨标签的 index 会撞车）",
            firstEpoch, backToFirst,
        )
    }

    @Test
    fun switchingTabsDropsTheSnapshotAndClosingAnIdleTabDoesNot() {
        val s = session()
        s.publishSnapshot(
            BrowserPageSnapshot(generation = s.generation, url = "https://a", title = "A", elements = listOf(
                BrowserElement(1, "a", "link", "next", null, null, null, "a", Bounds(0, 0, 10, 10)),
            )),
        )

        s.newTabFromUi()
        assertNull("活动标签换了 ⇒ 上一枚的 index 清单必须作废", s.snapshot)

        val epochBefore = s.generation
        s.publishSnapshot(
            BrowserPageSnapshot(generation = epochBefore, url = "https://b", title = "B", elements = emptyList()),
        )
        assertTrue("关掉非活动的那一枚", s.closeTabFromUi(0))
        assertEquals("页面根本没换，不许无端作废模型的把手", epochBefore, s.generation)
        assertEquals(1, s.tabInfos().size)
        assertTrue(s.tabInfos().single().active)
    }

    @Test
    fun closingTheActiveTabHandsOverToASuccessorWithAFreshEpoch() {
        val s = session()
        s.newTabFromUi()
        val epochBefore = s.generation

        assertTrue(s.closeTabFromUi(1))

        assertEquals(1, s.tabInfos().size)
        assertTrue("继任者必须是活动标签", s.tabInfos().single().active)
        assertNotEquals("换了一页看 ⇒ 换代次", epochBefore, s.generation)
    }

    @Test
    fun closingTheLastTabLeavesOneBlankTabInsteadOfAnEmptySession() {
        val s = session()
        val epochBefore = s.generation
        assertTrue(s.closeTabFromUi(0))

        assertEquals("会话不许没有标签（网关每一颗动作都委托给活动标签）", 1, s.tabInfos().size)
        assertTrue(s.tabInfos().single().active)
        assertEquals("", s.tabInfos().single().url)
        assertNotEquals(epochBefore, s.generation)
    }

    @Test
    fun tabCapStopsCreatingMoreWebViews() {
        val s = session()
        repeat(BrowserSession.MAX_TABS - 1) {
            assertTrue("第 ${it + 2} 枚该建得出来", s.newTabFromUi())
        }
        assertEquals(BrowserSession.MAX_TABS, s.tabInfos().size)
        assertTrue(
            "到上界就不许再造了（每枚是一个真 WebView）",
            !s.newTabFromUi(),
        )
        assertEquals(BrowserSession.MAX_TABS, s.tabInfos().size)
    }

    @Test
    fun outOfRangeSwitchAndCloseDoNothing() {
        val s = session()
        val epochBefore = s.generation

        assertTrue("越界编号不落", !s.selectTabFromUi(3))
        assertTrue(!s.closeTabFromUi(3))
        assertEquals(1, s.tabInfos().size)
        assertEquals("越界动作不许换代次", epochBefore, s.generation)
    }

    @Test
    fun closedSessionRefusesToMountATab() {
        val s = session()
        runBlocking { s.close() }

        val host = FrameLayout(ApplicationProvider.getApplicationContext())
        s.attachTo(host)
        assertEquals(
            "已关的会话绝不许把（已 destroy 的）WebView 挂回层级 —— 真机必炸，而这一路会因为" +
                "别的会话抢走实例而发生（见 BrowserSessionStore.sessionFor）",
            0, host.childCount,
        )
        assertTrue("界面看到的清单应当是空的", s.tabsSnapshot.value.isEmpty())
        assertTrue(!s.newTabFromUi())
        assertTrue(!s.selectTabFromUi(0))
    }

    /**
     * 接管态的兜底（spec §12.4）：遮罩销毁时两笔「等用户」的请求都要了结。
     *
     * 这里能证的只有「没有请求时不崩、旗标仍是 null」这一半 —— 真的 `JsResult` 与文件回调
     * 都要 WebView 主动回调才拿得到，Robolectric 的 shadow 不会弹弹窗，所以「卡住的 JS」
     * 那一半归真机验收（PORTING §5.69 的清单里）。
     */
    @Test
    fun droppingInteractionsWithoutAnyPendingRequestIsHarmless() {
        val s = session()
        s.dropPendingInteractions()
        assertNull(s.jsDialog.value)
        assertNull(s.fileRequest.value)
    }
}
