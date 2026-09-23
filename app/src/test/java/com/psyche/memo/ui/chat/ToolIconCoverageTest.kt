package com.psyche.memo.ui.chat

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import com.psyche.memo.provider.workspace.WorkspaceTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个**我们自己提供的**工具都要有自己的图标 —— 不许落到兜底的 `Lucide.Wrench`。
 *
 * 用户 2026-09-22「工具加上对应图标吧，现在图标都用一样的，体验不好」：工作区那四个工具
 * （read/write/edit/shell）当时没有映射，全落到 `Wrench`，而工作区正是他每天在用的东西。
 * 这条守卫把"新增工具忘了配图标"变成机械可查的失败 —— 加新工具时它会红。
 */
class ToolIconCoverageTest {

    private val mustHaveOwnIcon: List<String> =
        // 工作区整族直接取自 ALL_TOOL_NAMES：再加工具时这里不用改，但图标必须补。
        WorkspaceTools.ALL_TOOL_NAMES.toList() +
            listOf(
                com.psyche.memo.provider.chart.VisualTools.TOOL_NAME,
                com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME,
                com.psyche.memo.provider.generation.GenerationTools.GENERATE_IMAGE,
                com.psyche.memo.provider.generation.GenerationTools.GENERATE_VIDEO,
                com.psyche.memo.provider.SkillTools.USE_SKILL,
            )

    @Test
    fun ourToolsDoNotFallBackToTheDefaultWrench() {
        val offenders = mustHaveOwnIcon.filter { toolIconFor(it) == Lucide.Wrench }
        assertTrue(
            "这些工具还落在默认的 Wrench 图标上，请在 toolIconFor 里给它们各自配一个：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theIconsAreActuallyDistinctWithinTheWorkspaceFamily() {
        val icons = WorkspaceTools.ALL_TOOL_NAMES.map { toolIconFor(it) }
        assertEquals(
            "工作区每个工具都应该有自己的图标，出现了重复：",
            WorkspaceTools.ALL_TOOL_NAMES.size,
            icons.toSet().size,
        )
    }
}
