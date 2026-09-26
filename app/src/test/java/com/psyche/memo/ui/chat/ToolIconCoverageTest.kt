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
            // 浏览器整族同理（spec §12.1 拆成 13 颗独立工具）：加一颗就红在这里，不会漏配图标。
            com.psyche.memo.provider.browser.BrowserTools.ALL_TOOL_NAMES.toList() +
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
     * 记忆全族**不许共用一个图标**（用户 2026-09-23「记忆工具里的图标也改一下吧，很多也一样呀」）：
     * 上游把它们都画成 `bookHeart`，所以这是一处**有意偏离上游**的映射，改在
     * `toolIconFor` 里（设置页那份已经改成它的别名）。遗留名跟随各自的现代同名工具 ——
     * 老会话里的工具卡还得能正确显示。
     */
    @Test
    fun theMemoryFamilyHasOneIconPerAction() {
        val modern = listOf(
            "memory_read", "memory_update", "memory_search_profile",
            "memory_edit", "memory_delete", "update_user_profile",
        )
        val icons = modern.map { toolIconFor(it) }
        assertEquals("记忆工具应当一个动作一个图标：$modern", modern.size, icons.toSet().size)
        // 遗留名 = 现代名的别名。
        assertEquals(toolIconFor("memory_update"), toolIconFor("create_memory"))
        assertEquals(toolIconFor("memory_edit"), toolIconFor("edit_memory"))
        assertEquals(toolIconFor("memory_delete"), toolIconFor("delete_memory"))
        // 两个界面同源。
        modern.forEach { name ->
            assertEquals("设置页图标应与聊天一致：$name", toolIconFor(name), toolSchemaIconFor(name))
        }
    }

    /**
     * 浏览器族**一颗动作一个图标 + 一行标题**（spec §12.1 拆工具的全部理由：九种动作挤在
     * 同一张「内置浏览器」卡上，看不出模型这一轮到底做了什么 —— 图标或标题再撞车，
     * 那份可读性就等于还回去了）。写法照上面「记忆族一动作一图标」那条。
     */
    @Test
    fun theBrowserFamilyHasOneIconAndOneTitlePerAction() {
        val names = com.psyche.memo.provider.browser.BrowserTools.ALL_TOOL_NAMES.toList()
        val icons = names.map { toolIconFor(it) }
        assertEquals("浏览器工具应当一个动作一个图标：$names", names.size, icons.toSet().size)
        names.forEach { name ->
            assertEquals("两个界面同源：$name", toolIconFor(name), toolSchemaIconFor(name))
        }
        // 标题：每个名字都要在自己的那份表里，且两两不同（漏一条会落到默认的「调用工具 X」，
        // 撞车就又是「看不出做了什么」）。
        val titles = names.map { BROWSER_TITLE_RES[it] }
        assertTrue(
            "这些浏览器工具没有自己的标题：${names.filterIndexed { i, _ -> titles[i] == null }}",
            titles.none { it == null },
        )
        assertEquals("浏览器工具应当一个动作一行标题：$names", names.size, titles.toSet().size)
    }

    /**
     * 「设置 → 工具描述」那份映射（`toolSchemaIconFor`）是**另一个**函数 —— 只改聊天卡片
     * 会让设置里仍然全是扳手，用户 2026-09-23「工具描述里面的图标怎么没有变呀」就是漏了它。
     * 现在它是 `toolIconFor` 的别名；这条断言防止有人再抄一份表出来。
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
