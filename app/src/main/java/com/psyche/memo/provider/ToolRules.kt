package com.psyche.memo.provider

/**
 * 工具纪律 —— 本工程新增的系统提示词块（照 deepseek-harness 的提示词工程思路做的）。
 *
 * 用户 2026-09-23：「还有出现大模型说做了，他根本没有做的问题」—— 模型会把**没调用工具**
 * 的动作说成做完了（DeepSeek 系尤其常见）。这一块只讲三件事：**证据**（工具成功返回才算
 * 做了）、**失败如实**（报错就报错，别当成功）、**能力没有就直说**（工具列表里没有的，
 * 说明白缺什么，别自己编一个结果）。
 *
 * 只在**这一轮确实递了工具**时注入（`buildSystemPromptParts(hasTools = …)`）——
 * 没有工具时这三条只是白占上下文。
 *
 * 与 deepseek-harness 的关系：那边把同类约束做在 `guard/`（repeat-tool-reminder，
 * 连续重复调用提醒）与 skill 目录的收尾文案里；Memo 是单条系统提示词，取它的表述方式，
 * 不搬它的插件机制。
 */
internal val TOOL_RULES_BLOCK: String = """
**Tool use**
- Only report an action as done if a tool call in this turn returned success. Never describe an intended or assumed result as if it already happened.
- If a tool call fails, say what failed and what the error said; do not silently retry the same call, and do not present the intended result as the outcome.
- If the task needs a capability that is not in your tool list, say so plainly and tell the user what would be needed, instead of improvising a result.
""".trimIndent()
