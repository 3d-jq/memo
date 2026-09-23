package com.psyche.memo.ui.chat

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import com.psyche.memo.provider.workspace.WorkspaceTools
import com.psyche.memo.ui.toolSchemaIconFor
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

    /**
     * 「设置 → 工具描述」那份映射（`toolSchemaIconFor`，上游 `toolSchemaIconFor L15-63`）
     * 是**另一个**函数 —— 只改聊天卡片会让设置里仍然全是扳手，用户 2026-09-23
     * 「工具描述里面的图标怎么没有变呀」就是漏了它。两个界面列的是同一批工具，
     * 这里把「图标一致 + 不落兜底」都钉住。
     */
    @Test
    fun theSettingsCatalogUsesTheSameIconsAndNeverFallsBackToWrench() {
        val missing = mustHaveOwnIcon.filter { toolSchemaIconFor(it) == Lucide.Wrench }
        assertTrue(
            "这些工具在「设置 → 工具描述」里还是默认的 Wrench：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
        val mismatched = mustHaveOwnIcon.filter { toolSchemaIconFor(it) != toolIconFor(it) }
        assertTrue(
            "这两个界面的图标应当一致，不一致的在下面：\n" + mismatched.joinToString("\n"),
            mismatched.isEmpty(),
        )
    }
}
