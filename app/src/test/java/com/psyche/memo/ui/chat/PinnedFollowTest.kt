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
