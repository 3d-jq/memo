package com.psyche.memo.provider

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具纪律块（本工程新增，思路照 deepseek-harness）：
 *
 * 1. **没递工具就整块不注入** —— 那些规则对纯聊天只会占上下文；
 * 2. **路由句按名字门控** —— 告诉模型一颗它工具列表里没有的工具，比不告诉更糟
 *    （它会去调用，然后拿到 `execution_error`）。harness 的纪律是「提示词只能陈述
 *    运行时会执行的事」，这条测试就是它的机器判据；
 * 3. 句子里写的工具名必须与实现处的常量同源（改工具名不许悄悄留下旧名字）。
 */
class ToolRulesTest {

    private val workspaceAll = com.psyche.memo.provider.workspace.WorkspaceTools.ALL_TOOL_NAMES.toList()

    @Test
    fun `no tools means no block at all`() {
        assertNull(com.psyche.memo.provider.ToolRules.blockFor(emptyList()))
    }

    /** 只有通用纪律：工具列表里没有任何被路由覆盖的工具时也不该空。 */
    @Test
    fun `an unrelated tool still gets the honesty rules`() {
        val block = com.psyche.memo.provider.ToolRules.blockFor(listOf("mcp__whatever__do"))!!
        assertTrue(block.contains("Only report an action as done if a tool call"))
        assertTrue(block.contains("not in your tool list"))
        assertTrue("不该出现没递出的工具名", !block.contains("workspace_"))
        assertTrue(!block.contains("get_time_info"))
    }

    @Test
    fun `routing lines appear only when their tools are offered`() {
        val time = com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.TIME_INFO
        val single = com.psyche.memo.provider.ToolRules.blockFor(listOf(time))!!
        assertTrue(single.contains(time))
        assertTrue("只递了一颗工具，别的工作区路由句不能出现", !single.contains("workspace_"))

        // 工作区「怎么看」的三颗齐了才有那条
        val partial = com.psyche.memo.provider.ToolRules.blockFor(
            listOf(
                com.psyche.memo.provider.workspace.WorkspaceTools.READ_FILE,
                com.psyche.memo.provider.workspace.WorkspaceTools.GLOB,
            ),
        )!!
        assertTrue(!partial.contains("To look around the sandbox"))
        val full = com.psyche.memo.provider.ToolRules.blockFor(workspaceAll)!!
        assertTrue(full.contains("To look around the sandbox"))
        assertTrue(full.contains(com.psyche.memo.provider.workspace.WorkspaceTools.GREP))
        assertTrue(full.contains(com.psyche.memo.provider.workspace.WorkspaceTools.SHELL))
        assertTrue(full.contains("workspace_edit_file"))
    }

    /** 名字与实现处同源：句子里出现的每个工具名，都必须是某个工具面真的定义的常量。 */
    @Test
    fun `every named tool exists in the catalogs`() {
        val known = buildSet {
            addAll(com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.all)
            addAll(com.psyche.memo.provider.MemoryTools.ALL_TOOL_NAMES)
            addAll(com.psyche.memo.provider.SkillTools.ALL_TOOL_NAMES)
            addAll(com.psyche.memo.provider.workspace.WorkspaceTools.ALL_TOOL_NAMES)
            addAll(com.psyche.memo.provider.generation.GenerationTools.ALL_TOOL_NAMES)
            add(com.psyche.memo.provider.search.SearchToolService.TOOL_NAME)
            add(com.psyche.memo.provider.chart.VisualTools.TOOL_NAME)
            add(com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME)
        }
        val block = com.psyche.memo.provider.ToolRules.blockFor(known.toList())!!
        // 全量递给模型时，每条路由句都该在（漏一条=那条能力没人引导）
        listOf(
            com.psyche.memo.provider.search.SearchToolService.TOOL_NAME,
            com.psyche.memo.provider.LocationTool.TOOL_NAME,
            com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.TIME_INFO,
            com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.CALCULATE,
            com.psyche.memo.ui.chat.AskUserToolNames.ASK_USER,
            MemoryTools.MEMORY_UPDATE,
            MemoryTools.CHAT_SEARCH,
            com.psyche.memo.provider.generation.GenerationTools.GENERATE_IMAGE,
            com.psyche.memo.provider.generation.GenerationTools.GENERATE_VIDEO,
            com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME,
        ).forEach { name ->
            assertTrue("路由句里缺了 $name", block.contains(name))
        }
    }
}
