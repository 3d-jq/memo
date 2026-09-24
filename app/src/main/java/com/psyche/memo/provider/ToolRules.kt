package com.psyche.memo.provider

/**
 * 工具纪律 —— 本工程新增的系统提示词块（思路照 deepseek-harness 的提示词工程）。
 *
 * 用户 2026-09-23：「还有出现大模型说做了，他根本没有做的问题」—— 模型会把**没调用工具**
 * 的动作说成做完了（DeepSeek 系尤其常见）。这一块讲两件事：**证据**（工具成功返回才算
 * 做了）、**失败如实**（报错就报错，别当成功、别原地重试），再加按本轮工具列表生成的
 * **路由句**（「查日期用 `get_time_info` 而不是凭记忆」这类一句版）。
 *
 * 两个硬约束：
 *  1. 只在**这一轮确实递了工具**时注入（[blockFor] 收空列表返回 null）—— 没工具时这些都是白占上下文；
 *  2. 路由句**按名字门控**：`workspace_*` 那几条只在助手真绑了工作区、名字进了工具列表时才出现。
 *     这是 harness 自己那条纪律（提示词只能陈述运行时会执行的事）—— 告诉模型一颗它列表里
 *     没有的工具，比不告诉更糟。
 */
internal object ToolRules {

    /** 与具体工具无关的三条纪律。 */
    private val BASE = """
        **Tool use**
        - Only report an action as done if a tool call in this turn returned success. Never describe an intended or assumed result as if it already happened.
        - If a tool call fails, say what failed and what the error said; do not silently retry the same call, and do not present the intended result as the outcome.
        - If the task needs a capability that is not in your tool list, say so plainly and tell the user what would be needed, instead of improvising a result."""
        .trimIndent()

    /** 一条路由句 + 它需要的工具名（**全部**在列才注入）。 */
    private class Rule(val requires: List<String>, val text: String)

    private val ROUTES: List<Rule> = listOf(
        Rule(
            listOf(searchName),
            "- For anything time-sensitive or newer than your knowledge cutoff, call `$searchName` " +
                "instead of answering from memory.",
        ),
        Rule(
            listOf(timeName),
            "- For the current date, weekday or time call `$timeName`; never infer it from memory " +
                "or from earlier turns.",
        ),
        Rule(
            listOf(locationName),
            "- When the answer depends on where the user is, call `$locationName` instead of " +
                "asking which city.",
        ),
        Rule(
            listOf(calculateName),
            "- For arithmetic beyond a single step, call `$calculateName` rather than working it " +
                "out in your head.",
        ),
        Rule(
            listOf(askUserName),
            "- When a request is ambiguous in a way that changes the result, call `$askUserName` " +
                "rather than guessing silently.",
        ),
        Rule(
            listOf(workspaceRead, workspaceGlob, workspaceGrep),
            "- To look around the sandbox use `$workspaceRead`, `$workspaceGlob` and `$workspaceGrep`; " +
                "reserve `$workspaceShell` for work that genuinely needs a shell.",
        ),
        Rule(
            listOf(workspaceEdit, workspaceWrite),
            "- Use `$workspaceEdit` to change part of an existing file; use `$workspaceWrite` only " +
                "to create or fully replace one.",
        ),
        Rule(
            listOf(memoryRead, memoryUpdate),
            "- Persist durable facts with `$memoryUpdate` instead of restating them each reply, and " +
                "call `$memorySearch` before adding so the same fact is not stored twice.",
        ),
        Rule(
            listOf(chatSearch),
            "- Use `$chatSearch` to recall what was said in past conversations rather than saying " +
                "you don't know.",
        ),
        Rule(
            listOf(generateImage),
            "- For an image the user asks you to draw, call `$generateImage` rather than describing it.",
        ),
        Rule(
            listOf(generateVideo),
            "- For a video the user asks for, call `$generateVideo` rather than describing one.",
        ),
        Rule(
            listOf(renderVisual, renderMermaid),
            "- Use `$renderVisual` for a chart and `$renderMermaid` for a diagram instead of " +
                "drawing either in text.",
        ),
    )

    /** 本轮没有工具 → null（不注入）；有工具 → 三条纪律 + 命中的路由句。 */
    fun blockFor(offeredNames: Collection<String>): String? {
        if (offeredNames.isEmpty()) return null
        val routes = ROUTES.filter { rule -> rule.requires.all { it in offeredNames } }
        if (routes.isEmpty()) return BASE
        return buildString {
            appendLine(BASE)
            routes.forEach { appendLine(it.text) }
        }.trimEnd()
    }

    /** 加新工具时改这里；`ToolRulesTest` 会钉住这些名字与实现处一致。 */
    private const val searchName = com.psyche.memo.provider.search.SearchToolService.TOOL_NAME
    private const val timeName = com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.TIME_INFO
    private const val locationName =
        com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.CURRENT_LOCATION
    private const val calculateName = com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.CALCULATE
    private const val askUserName = com.psyche.memo.ui.chat.AskUserToolNames.ASK_USER
    private const val workspaceRead = com.psyche.memo.provider.workspace.WorkspaceTools.READ_FILE
    private const val workspaceWrite = com.psyche.memo.provider.workspace.WorkspaceTools.WRITE_FILE
    private const val workspaceEdit = com.psyche.memo.provider.workspace.WorkspaceTools.EDIT_FILE
    private const val workspaceShell = com.psyche.memo.provider.workspace.WorkspaceTools.SHELL
    private const val workspaceGlob = com.psyche.memo.provider.workspace.WorkspaceTools.GLOB
    private const val workspaceGrep = com.psyche.memo.provider.workspace.WorkspaceTools.GREP
    private const val memoryRead = MemoryTools.MEMORY_READ
    private const val memoryUpdate = MemoryTools.MEMORY_UPDATE
    private const val memorySearch = MemoryTools.MEMORY_SEARCH_PROFILE
    private const val chatSearch = MemoryTools.CHAT_SEARCH
    private const val generateImage = com.psyche.memo.provider.generation.GenerationTools.GENERATE_IMAGE
    private const val generateVideo = com.psyche.memo.provider.generation.GenerationTools.GENERATE_VIDEO
    private const val renderVisual = com.psyche.memo.provider.chart.VisualTools.TOOL_NAME
    private const val renderMermaid = com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME
}
