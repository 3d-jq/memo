package com.psyche.memo.provider.prompt

import com.psyche.memo.common.logging.ContextSource

/**
 * 系统提示词装配 —— 照 deepseek-harness 的 `SystemPrompt.assemble`（见
 * `docs/ENGINEERING_HARNESS.md` §1「顺序带」与「唯一渲染路径」）。
 *
 * 修的两个病：
 *  1. **顺序是隐式的**（靠代码先后）→ 现在每个 section 按 [PromptOrder] 的带位排序，
 *     与 dsh 一致：身份/人设在前，**工具指导在后**；
 *  2. **有两条渲染路径**（两处各自 `joinToString("\n\n")`）→ 收成 [assembleSystemPrompt]
 *     一个出口，装配顺序与分隔符只有一处定义。
 *
 * 顺序带照 dsh：`persona` < `tool guidance` < …，用户自定义注入放最后（最贴近对话）。
 */
object PromptOrder {
    /** 助手系统提示词（人设/角色）—— dsh 的 `deployment:persona`（order 0）。 */
    const val PERSONA = 0

    /** 工具纪律：只有这一轮真递了工具才注入。 */
    const val TOOL_RULES = 100

    /** 记忆规则（长期记忆 / 过往回忆）。 */
    const val MEMORY_RULES = 110

    /** 内置搜索的行为约定。 */
    const val SEARCH = 120

    /** 可用技能清单。 */
    const val SKILLS = 130

    /** 沙箱工作区说明（shell 就绪才注入）。 */
    const val WORKSPACE = 140

    /** 用户自己配的注入项：放最后，最贴近对话。 */
    const val INSTRUCTION_INJECTION = 900
}

/**
 * 每个 section 的带位。
 *
 * **未登记的 source 直接抛**（dsh：misconfiguration fails loud）—— 把会话内容
 * （`chatHistory` / `toolResult` …）误当系统提示词 section 是装配错误，不该静默通过。
 */
fun promptOrderOf(source: ContextSource): Int = when (source) {
    ContextSource.systemPrompt -> PromptOrder.PERSONA
    ContextSource.toolRules -> PromptOrder.TOOL_RULES
    ContextSource.memoryRules -> PromptOrder.MEMORY_RULES
    ContextSource.searchPrompt -> PromptOrder.SEARCH
    ContextSource.skillPrompt -> PromptOrder.SKILLS
    ContextSource.workspace -> PromptOrder.WORKSPACE
    ContextSource.instructionInjection -> PromptOrder.INSTRUCTION_INJECTION
    else -> error(
        "ContextSource.$source 不是系统提示词的 section；" +
            "要把它当 section 用，先在 promptOrderOf 里给它一个带位",
    )
}

/**
 * **系统提示词的唯一渲染路径**：按带位稳定排序 → 去空段 → `\n\n` 拼接。
 *
 * 稳定排序保证同带位内保持调用方加入顺序（可预期、可断言）。
 */
fun assembleSystemPrompt(parts: List<Pair<ContextSource, String>>): String {
    // 显式校验：未登记的 source 一律大声失败。
    // **不能只靠排序时顺手发现** —— Kotlin 对「集合且 size ≤ 1」的序列会跳过排序，
    // 那时比较器/选择器根本不跑，校验就静默失效了（这个坑是测试帮我逮到的）。
    parts.forEach { promptOrderOf(it.first) }
    return parts.asSequence()
        .filter { it.second.isNotBlank() }
        .sortedBy { promptOrderOf(it.first) }
        .joinToString("\n\n") { it.second.trim() }
}
