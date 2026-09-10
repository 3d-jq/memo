package com.psyche.memo.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「思考卡展开态」的正确语义（stream_controller.dart 771/776/1231-1255）：
 *  1. 新出现的 segment 才赋初始展开态（`!autoCollapse`）；
 *  2. 已存在的 segment 保留其当前 expanded —— 用户在流式过程中的手动点击不被覆盖；
 *  3. 流结束且开启「自动折叠思考」时，已结束的 segment 折起，关闭时原样保留。
 *
 * 回归背景：此前 `encodeSegments` 每次增量都把所有 segment 重算成 `!autoCollapse`，
 * 用户手动折叠的思考卡在冷启动后又被展开。
 */
class ReasoningSegmentExpandedTest {

    private fun seg(expanded: Boolean, finishedAt: Long? = null) =
        ReasoningSegment(startAt = 0, finishedAt = finishedAt, expanded = expanded)

    // ---- applyInitialExpanded：新段给初始值，老段保留 ----

    @Test
    fun applyInitialExpanded_newSegmentGetsInitial() {
        val seen = mutableSetOf<Int>()
        val out = ReasoningSegmentCodec.applyInitialExpanded(
            listOf(seg(expanded = false)),
            seen,
            initialExpanded = true,
        )
        assertTrue(out[0].expanded)
        assertEquals(setOf(0), seen)
    }

    @Test
    fun applyInitialExpanded_existingSegmentKeepsToggledState() {
        val seen = mutableSetOf(0)
        // 用户已把第 0 段手动折起（expanded=false），即便初始态是 true 也不应被覆盖。
        val out = ReasoningSegmentCodec.applyInitialExpanded(
            listOf(seg(expanded = false)),
            seen,
            initialExpanded = true,
        )
        assertFalse(out[0].expanded)
    }

    @Test
    fun applyInitialExpanded_onlyNewTailSegmentIsInitialized() {
        val seen = mutableSetOf(0, 1)
        val out = ReasoningSegmentCodec.applyInitialExpanded(
            listOf(seg(false), seg(true, finishedAt = 5), seg(true)),
            seen,
            initialExpanded = false,
        )
        assertFalse(out[0].expanded) // 老段保留
        assertTrue(out[1].expanded) // 老段保留（用户展开）
        assertFalse(out[2].expanded) // 新段 = !autoCollapse = false
        assertEquals(setOf(0, 1, 2), seen)
    }

    @Test
    fun applyInitialExpanded_stableAcrossRepeatCalls() {
        val seen = mutableSetOf<Int>()
        val first = ReasoningSegmentCodec.applyInitialExpanded(
            listOf(seg(false)),
            seen,
            initialExpanded = true,
        )
        // 用户折起
        val toggled = first.map { it.copy(expanded = false) }
        // 第二次编码（下一个增量）不得把它改回 true
        val second = ReasoningSegmentCodec.applyInitialExpanded(toggled, seen, initialExpanded = true)
        assertFalse(second[0].expanded)
    }

    // ---- collapseFinishedSegments：结束态自动折叠 ----

    @Test
    fun collapseFinishedSegments_collapsesFinishedWhenAutoOn() {
        val out = ReasoningSegmentCodec.collapseFinishedSegments(
            listOf(seg(true, finishedAt = 1), seg(true, finishedAt = null)),
            autoCollapse = true,
        )
        assertFalse(out[0].expanded) // 已结束 → 折起
        assertTrue(out[1].expanded) // 未结束 → 保持
    }

    @Test
    fun collapseFinishedSegments_keepsEverythingWhenAutoOff() {
        val out = ReasoningSegmentCodec.collapseFinishedSegments(
            listOf(seg(true, finishedAt = 1), seg(true, finishedAt = 2)),
            autoCollapse = false,
        )
        assertTrue(out[0].expanded)
        assertTrue(out[1].expanded)
    }

    /** 端到端：新段初始展开 → 用户折起 → 结束自动折叠后仍为折起（不弹回展开）。 */
    @Test
    fun endToEnd_userCollapseSurvivesFinish() {
        val seen = mutableSetOf<Int>()
        var segments = ReasoningSegmentCodec.applyInitialExpanded(
            listOf(seg(false)),
            seen,
            initialExpanded = true,
        )
        // 用户点击折起
        segments = segments.map { it.copy(expanded = false) }
        // 流结束：补 finishedAt + 自动折叠
        segments = segments.map { it.copy(finishedAt = 42) }
        segments = ReasoningSegmentCodec.collapseFinishedSegments(segments, autoCollapse = true)
        assertFalse(segments[0].expanded)
    }

    // ------------------------------------------------------------------
    // finishLastOpenSegment — "思考阶段结束"的唯一动作
    // (stream_controller.dart L853 工具调用 / L1232 正文到达 / L1280 流结束 /
    //  L1339 取消 / L1355 出错 / finishReasoningIfNeeded 兜底)
    // ------------------------------------------------------------------

    @Test
    fun finishLastOpenSegment_stampsFinishedAtAndCollapses() {
        val (out, changed) = ReasoningSegmentCodec.finishLastOpenSegment(
            listOf(seg(expanded = true, finishedAt = null)),
            now = 1000,
            autoCollapse = true,
        )
        assertTrue(changed)
        assertEquals(1000L, out[0].finishedAt)
        assertFalse(out[0].expanded)
    }

    @Test
    fun finishLastOpenSegment_keepsExpandedWhenAutoCollapseOff() {
        val (out, changed) = ReasoningSegmentCodec.finishLastOpenSegment(
            listOf(seg(expanded = true, finishedAt = null)),
            now = 1000,
            autoCollapse = false,
        )
        assertTrue(changed)
        assertEquals(1000L, out[0].finishedAt)
        assertTrue("用户手动展开得以保留", out[0].expanded)
    }

    @Test
    fun finishLastOpenSegment_isIdempotentOnClosedSegment() {
        val input = listOf(seg(expanded = false, finishedAt = 7))
        val (out, changed) = ReasoningSegmentCodec.finishLastOpenSegment(
            input,
            now = 1000,
            autoCollapse = true,
        )
        assertFalse(changed)
        assertEquals("时间戳不被改写", 7L, out[0].finishedAt)
    }

    @Test
    fun finishLastOpenSegment_onlyTouchesTheLastSegment() {
        val input = listOf(
            seg(expanded = true, finishedAt = null),
            seg(expanded = true, finishedAt = null),
        )
        val (out, changed) = ReasoningSegmentCodec.finishLastOpenSegment(input, now = 5, autoCollapse = true)
        assertTrue(changed)
        assertEquals(2, out.size)
        assertTrue("第一段不在末尾，保持原样", out[0].expanded)
        assertNull(out[0].finishedAt)
        assertEquals(5L, out[1].finishedAt)
        assertFalse(out[1].expanded)
    }

    @Test
    fun finishLastOpenSegment_emptyListIsNoOp() {
        val (out, changed) = ReasoningSegmentCodec.finishLastOpenSegment(emptyList(), now = 5, autoCollapse = true)
        assertFalse(changed)
        assertTrue(out.isEmpty())
    }

    /** 端到端（本轮修复的回归点）：工具调用开始时就折叠，而不是整轮回复结束后。 */
    @Test
    fun endToEnd_toolCallStartFoldsSegmentImmediately() {
        // 流式思考中：段是打开且展开的
        var segments = mutableListOf(seg(expanded = true, finishedAt = null))
        // 工具调用开始 → 关闭并折起
        val (closed, changed) = ReasoningSegmentCodec.finishLastOpenSegment(
            segments,
            now = 100,
            autoCollapse = true,
        )
        assertTrue(changed)
        segments = closed.toMutableList()
        // 之后又来了新一段思考（工具之间）
        segments.add(seg(expanded = true, finishedAt = null))
        assertEquals(2, segments.size)
        assertFalse("第一段已折叠", segments[0].expanded)
        assertEquals(100L, segments[0].finishedAt)
        assertNull("第二段仍在流式中", segments[1].finishedAt)
    }
}
