package com.psyche.memo.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「思考卡展开态」的正确语义（stream_controller.dart 771/776/1331-1369）：
 *  1. 新出现的 segment 才赋初始展开态（`!autoCollapse`）；
 *  2. 之后的增量编码保留用户当前的 expanded —— 用户点击展开/折叠不被覆盖；
 *  3. 该段「结束转变」（finishedAt 从 null 变有值）那一次，采用流式侧传来的折叠结果；
 *  4. 流结束且开启「自动折叠思考」时，已结束的 segment 折起，关闭时原样保留。
 *
 * 回归背景：此前 `encodeSegments` 每次增量都把所有 segment 重算成 `!autoCollapse`，
 * 用户手动折叠的思考卡在冷启动后又被展开；之后改成「按下标是否出现过」赋初值，但
 * 老段仍沿用流式 handler 重建出来的 expanded，于是**思考中点击展开会被下一个增量
 * 打回**（用户实测：「在输出思考的时候点击那卡片是不能展开的会打架」）。
 */
class ReasoningSegmentExpandedTest {

    private fun seg(expanded: Boolean, finishedAt: Long? = null) =
        ReasoningSegment(startAt = 0, finishedAt = finishedAt, expanded = expanded)

    // ---- resolveExpanded：新段给初始值，其余以权威态为准 ----

    @Test
    fun resolveExpanded_newSegmentGetsInitial() {
        val state = mutableMapOf<Int, Boolean>()
        val out = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = false)),
            previous = emptyList(),
            state = state,
            initialExpanded = true,
        )
        assertTrue(out[0].expanded)
        assertEquals(mapOf(0 to true), state)
    }

    @Test
    fun resolveExpanded_existingSegmentKeepsStoredState() {
        val state = mutableMapOf(0 to false)
        // 权威态说「用户已折起」，传入值（handler 重建）说 true → 以权威态为准。
        val out = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = true)),
            previous = listOf(seg(expanded = false)),
            state = state,
            initialExpanded = true,
        )
        assertFalse(out[0].expanded)
        assertEquals(mapOf(0 to false), state)
    }

    /** 用户点击展开 → 下一个增量（handler 重建出 expanded=false）不得打回。 */
    @Test
    fun resolveExpanded_userExpandSurvivesNextDelta() {
        val state = mutableMapOf<Int, Boolean>()
        val first = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = false)),
            previous = emptyList(),
            state = state,
            initialExpanded = false,
        )
        assertFalse(first[0].expanded)
        // 用户点击（ViewModel 侧把翻转后的值写进权威态）
        state[0] = true
        // 下一个增量：handler 仍然给出它自己那份 expanded=false
        val second = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = false)),
            previous = first,
            state = state,
            initialExpanded = false,
        )
        assertTrue("用户展开必须保留", second[0].expanded)
    }

    /** 该段结束转变那一次：采用流式侧传入的折叠结果（Dart T1/T2 的 autoCollapse）。 */
    @Test
    fun resolveExpanded_finishTransitionAdoptsIncomingCollapse() {
        val state = mutableMapOf(0 to true) // 用户此刻是展开的
        val out = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = false, finishedAt = 9)),
            previous = listOf(seg(expanded = true)),
            state = state,
            initialExpanded = false,
        )
        assertFalse("结束转变应折叠", out[0].expanded)
        assertEquals(mapOf(0 to false), state)
    }

    /** 已经结束的段再收到折叠（流结束的兜底折叠）不得覆盖用户之后的展开。 */
    @Test
    fun resolveExpanded_alreadyFinishedSegmentKeepsUserToggle() {
        val state = mutableMapOf(0 to true)
        val out = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = false, finishedAt = 9)),
            previous = listOf(seg(expanded = true, finishedAt = 9)),
            state = state,
            initialExpanded = false,
        )
        assertTrue(out[0].expanded)
    }

    @Test
    fun resolveExpanded_seededStateKeepsPersistedValues() {
        // 续写：权威态用库里读出的 segment 初始化 → 落库的展开态原样保留。
        val persisted = listOf(seg(expanded = true, finishedAt = 5), seg(expanded = true))
        val state = persisted.mapIndexed { index, s -> index to s.expanded }.toMap(LinkedHashMap())
        val out = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(expanded = false, finishedAt = 5), seg(expanded = false)),
            previous = persisted,
            state = state,
            initialExpanded = false,
        )
        assertTrue(out[0].expanded)
        assertTrue(out[1].expanded)
    }

    @Test
    fun resolveExpanded_onlyNewTailSegmentIsInitialized() {
        val state = mutableMapOf(0 to false, 1 to true)
        val previous = listOf(seg(false), seg(true, finishedAt = 5))
        val out = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(false), seg(true, finishedAt = 5), seg(true)),
            previous = previous,
            state = state,
            initialExpanded = false,
        )
        assertFalse(out[0].expanded) // 老段保留
        assertTrue(out[1].expanded) // 老段保留（用户展开）
        assertFalse(out[2].expanded) // 新段 = !autoCollapse = false
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
        val state = mutableMapOf<Int, Boolean>()
        var segments = ReasoningSegmentCodec.resolveExpanded(
            listOf(seg(false)),
            previous = emptyList(),
            state = state,
            initialExpanded = true,
        )
        // 用户点击折起（ViewModel 侧写权威态）
        state[0] = false
        // 流结束：补 finishedAt + 自动折叠
        segments = segments.map { it.copy(finishedAt = 42) }
        segments = ReasoningSegmentCodec.collapseFinishedSegments(segments, autoCollapse = true)
        segments = ReasoningSegmentCodec.resolveExpanded(
            segments,
            previous = listOf(seg(true)),
            state = state,
            initialExpanded = true,
        )
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
