package com.psyche.memo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式跟随的两条契约（照 dsh 的测试写法，见 `.stepcode/plans/session-01a0ce17-*.md` §附 5）：
 *
 * 1. **贴着底 + 内容继续长高 ⇒ 继续贴底**（长高那一帧自己校正，抖动活不过一帧）；
 * 2. **读者离开底部 ⇒ 长高绝不把他拽回来**。
 *
 * 另加判定表里其余几个门（手指在屏上 / 正在滚动 / 自动滚动关 / 已贴底不重复滚 / 宽限窗口）。
 */
class PinnedFollowTest {

    private fun pin(
        hasMessages: Boolean = true,
        following: Boolean = true,
        autoScrollEnabled: Boolean = true,
        pointerDown: Boolean = false,
        streaming: Boolean = true,
        graceActive: Boolean = false,
        gapPx: Float = 60f,
        tolerancePx: Float = 30f,
    ) = PinnedFollow.shouldPinToBottom(
        hasMessages = hasMessages,
        following = following,
        autoScrollEnabled = autoScrollEnabled,
        pointerDown = pointerDown,
        streaming = streaming,
        graceActive = graceActive,
        gapPx = gapPx,
        tolerancePx = tolerancePx,
    )

    @Test
    fun `pinned reader keeps following as content grows`() {
        // 契约 1：贴着底时，尾部每长高一点就再贴一次
        assertTrue(pin(gapPx = 65f))
        assertTrue(pin(gapPx = 130f))
    }

    @Test
    fun `a reader who scrolled away is never dragged back`() {
        // 契约 2：following=false（用户往上滑走）→ 无论长多高都不碰
        assertFalse(pin(following = false, gapPx = 65f))
        assertFalse(pin(following = false, gapPx = 4000f))
    }

    @Test
    fun `already at the bottom does not re-pin`() {
        // 已在容差内 → 不再滚，否则「滚一次又触发一次」自激
        assertFalse(pin(gapPx = 10f))
        assertFalse(pin(gapPx = 0f))
        assertFalse(pin(gapPx = -3f))
        // 量不出可见项（空列表/极端情况）也不动
        assertFalse(pin(gapPx = Float.MAX_VALUE))
    }

    @Test
    fun `a detached reader is never dragged back`() {
        assertFalse(pin(following = false))
    }

    @Test
    fun `the usual gates still hold`() {
        assertFalse("手指在屏上绝不程序化滚动", pin(pointerDown = true))
        // 惯性滚动**不是**跟随循环的门（自己的 dispatchRawDelta 会把它置真 ⇒ 会自锁）；
        // 用户在滑动/惯性时由 following=false 把关（见函数的注释与下面的断言）。
        assertTrue("程序化滚动期间继续追底（否则追不到底）", pin())
        assertFalse("自动回到底部关掉后不跟随", pin(autoScrollEnabled = false))
        assertFalse("没有消息不动", pin(hasMessages = false))
    }

    @Test
    fun `follow only during streaming or the finish grace window`() {
        assertTrue(pin(streaming = true, graceActive = false))
        assertTrue(pin(streaming = false, graceActive = true))
        assertFalse("既不在流式、也不在宽限期 → 不贴", pin(streaming = false, graceActive = false))
    }

    /**
     * 跟随的**零点必须是物理底部**，不是「最后一条 item 的底边」。
     *
     * 用户 2026-09-24：「大模型输出结束后，底部还有多余空间，可以再往上滑一下」。根因是老
     * 判据量 `gap = 最后可见 item 底边 − 视口底`，`gap ≤ 10dp` 就停手；可列表底部还有
     * `contentPadding.bottom` 那 16dp 属于滚动范围 —— 于是每次停在离 `maxScrollExtent`
     * 还差 10+16=26dp 的地方，那一截就是看得见的空白（Agora 不留：跟随目标就是物理底部的
     * 哨兵，`MessageList` 的 `AbsoluteBottomSentinelKey` + 误差 ≤2dp 才算落定）。
     */
    @Test
    fun `the follow zero point is the physical bottom, not the last item edge`() {
        val padding = 48f // 16dp @ 3x
        // 老停手点（gap=10dp=30px）按物理底部算还差 78px
        assertEquals(78f, PinnedFollow.distanceToBottomPx(30f, padding), 0f)
        // 真到底：哨兵底边 = 视口底 − padding
        assertEquals(0f, PinnedFollow.distanceToBottomPx(-padding, padding), 0f)
        // 量不到可见项时原样传 MAX_VALUE，不许被 padding 拉成一个看起来正常的数
        assertEquals(Float.MAX_VALUE, PinnedFollow.distanceToBottomPx(Float.MAX_VALUE, padding), 0f)
    }

    @Test
    fun `the old stop point still has to travel`() {
        val padding = 48f
        val settle = 6f // 2dp @ 3x
        // 必须继续追：离物理底部还有 78px（老代码在这里就停手了）
        assertTrue(pin(gapPx = PinnedFollow.distanceToBottomPx(30f, padding), tolerancePx = settle))
        // 收手：已经在物理底部的 2dp 内
        assertFalse(pin(gapPx = PinnedFollow.distanceToBottomPx(-45f, padding), tolerancePx = settle))
        assertFalse(pin(gapPx = PinnedFollow.distanceToBottomPx(-48f, padding), tolerancePx = settle))
    }

    /**
     * 收敛容差必须**严于**「恢复跟随 / 键盘钉底」的判据，否则刚到底那一刻
     * `tailAtBottom` 与跟随循环互相拉扯（到底 → 判成不在底部 → 恢复跟随 → 又动一下）。
     */
    @Test
    fun `settle tolerance is tighter than the resume gate`() {
        assertTrue(PinnedFollow.SETTLE_TOLERANCE_DP < PinnedFollow.RESUME_TOLERANCE_DP)
    }

    /** 锚点下标：额外项必须与 LazyColumn 的 gating 逐条对齐（三个组合都钉住）。 */
    @Test
    fun `sentinel index counts every extra list item`() {
        assertEquals(5, PinnedFollow.bottomAnchorIndexFor(5, compactionProgress = false, streamingIndicator = false))
        assertEquals(6, PinnedFollow.bottomAnchorIndexFor(5, compactionProgress = true, streamingIndicator = false))
        assertEquals(6, PinnedFollow.bottomAnchorIndexFor(5, compactionProgress = false, streamingIndicator = true))
        // 这是原实现漏掉的那一格：压缩进度行与流式提示项同时存在 → +2
        assertEquals(7, PinnedFollow.bottomAnchorIndexFor(5, compactionProgress = true, streamingIndicator = true))
    }
}
