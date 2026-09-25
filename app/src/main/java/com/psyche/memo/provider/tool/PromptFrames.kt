package com.psyche.memo.provider.tool

/**
 * 外部内容进上下文时的**框架标签消毒**（学 deepseek-harness 的注入纪律）。
 *
 * Memo 用几个保留标签给模型划边界：`<system-reminder>`（重复调用提醒）、
 * `<conversation-checkpoint>`（压缩摘要）、`<available_skills>` / `<user_memory>` /
 * `<user_profile>`（记忆与技能）、`<citations>`（搜索引用）。这些标签**只该由我们自己**
 * 写出来。可是一条搜索结果、一个工作区文件、一段 MCP 返回值里只要原样带着
 * `</user_memory>` 或 `<system-reminder>…`，模型就会把它当框架指令读 —— 那是提示词注入
 * 的入口，而且不需要任何权限：让模型"读"一个网页就够了。
 *
 * 所以只处理保留标签、只把开头的 `<` 换成 `&lt;`：
 * - 不转义全部 `<` —— 代码、HTML、数学式是工具结果的主要内容，全转了就没人看得懂；
 * - 模型仍能看到这段文本"长什么样"，只是它不再拥有框架语义。
 */
object PromptFrames {

    /** 我们自己保留的框架标签（不带尖括号）。 */
    val RESERVED = listOf(
        "system-reminder",
        "conversation-checkpoint",
        "available_skills",
        "user_memory",
        "user_memory_update",
        "user_profile",
        "citations",
    )

    /**
     * `<` / `</` 后紧跟保留名，且名字后面是空白、`>`、`/` 或结尾（避免把
     * `<citation>` 之类不相干的词误伤）。
     */
    private val RESERVED_OPEN = Regex(
        "<(/?(?:" + RESERVED.joinToString("|") { Regex.escape(it) + "(?=[\\s>/]|$)" } + "))",
        RegexOption.IGNORE_CASE,
    )

    /** 把文本里伪装的框架标签降级成普通文字。 */
    fun sanitize(text: String): String =
        RESERVED_OPEN.replace(text) { match -> "&lt;" + match.value.substring(1) }
}
