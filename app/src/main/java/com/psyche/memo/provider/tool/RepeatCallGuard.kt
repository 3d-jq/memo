package com.psyche.memo.provider.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 连续重复调用检测 —— 照 deepseek-harness 的 `guard/repeat-tool-reminder`
 * （`packages/guard/repeat-tool-reminder/src/index.ts`）。
 *
 * 它在 `tools/post-execute` 上**只观察、不否决**：同一颗工具、参数逐字相同的调用连着
 * 到第 3 次给一句温和提醒，到 5、8 次点名工具 + 连击次数 + 参数摘要并要求换路。
 * 参数**深排序后**再比（模型每轮重排 key 是常态，内容相同就要认成同一次），摘要**截断**
 * （写文件的正文会整段骑进下一次请求，而循环恰恰发生在这种工具上），但比较永远用完整串。
 *
 * 为什么有效而「请不要重复调用」的提示词没用：这是**运行时**在每轮真的往里塞一句上下文，
 * 模型不响应也躲不开。计数在 post-execute（被拒绝、被中断的调用同样在原地打转，
 * 那正是要打断的循环）。
 *
 * Memo 没有插件总线，所以它是一个**每轮生成一个新实例**的普通类：用户真的发了新消息
 * ⇒ 新的 `runGenerationLoop` ⇒ 计数自然归零（跨用户发言的重复不是死循环，上游
 * `agent/pre-step` 也正是这么重置的）。
 */
class RepeatCallGuard(
    thresholds: List<Int> = DEFAULT_THRESHOLDS,
    private val previewChars: Int = DEFAULT_PREVIEW_CHARS,
) {

    init {
        // 配错当场炸（上游 validateThresholds 的 fail-loud）：空表或阈值 1 会让第一次
        // 正常调用就被提醒，那比没有提醒更糟。
        require(thresholds.isNotEmpty()) { "RepeatCallGuard: thresholds must not be empty" }
        require(thresholds.all { it >= 2 }) { "RepeatCallGuard: every threshold must be >= 2" }
        require(previewChars >= 1) { "RepeatCallGuard: previewChars must be >= 1" }
    }

    private val sorted = thresholds.sorted()
    private val thresholdSet = sorted.toSet()

    /** 上一次被观察到的调用身份（工具名 + 规范参数）与它已连续出现的次数。 */
    private var key: String? = null
    private var count = 0

    /**
     * 记下一次**已执行**的调用；命中阈值时返回要递给模型的提醒文本，否则 null。
     *
     * 提醒不否决任何东西 —— 模型照样看到那次调用的真实结果，只是多一句「你在原地打转」。
     */
    fun observe(tool: String, arguments: String?): String? {
        val canonical = canonicalize(arguments)
        val next = JsonArray(listOf(JsonPrimitive(tool), JsonPrimitive(canonical))).toString()
        count = if (next == key) count + 1 else 1
        key = next
        if (count !in thresholdSet) return null
        return if (count == sorted.first()) {
            GENTLE_REMINDER
        } else {
            detailedReminder(tool, count, canonical, previewChars)
        }
    }

    companion object {

        /** 与上游一致：温和 → 点名 → 更硬。 */
        val DEFAULT_THRESHOLDS = listOf(3, 5, 8)

        const val DEFAULT_PREVIEW_CHARS = 500

        /** 首个阈值的温和提醒（上游 GENTLE_REMINDER 原文）。 */
        const val GENTLE_REMINDER: String =
            "You are repeating the exact same tool call with identical arguments. " +
            "Carefully analyze the previous result before calling again: if the task is " +
            "not complete, try a different approach or different arguments instead of " +
            "repeating the call."

        /**
         * 参数规范形：深排序键后序列化（上游 `canonicalize`）。解析不了的（模型写坏的
         * JSON、根本没有参数）原样参与比较 —— 上游对畸形参数也是落到原始字符串，
         * 绝不因为解析失败就漏判重复。
         */
        fun canonicalize(arguments: String?): String {
            if (arguments.isNullOrBlank()) return "null"
            return runCatching { sortKeys(Json.parseToJsonElement(arguments)).toString() }
                .getOrDefault(arguments)
        }

        /** 点名式提醒（上游 detailedReminder：工具名、连击次数、参数摘要逐项列出）。 */
        fun detailedReminder(
            tool: String,
            count: Int,
            canonicalArguments: String,
            cap: Int = DEFAULT_PREVIEW_CHARS,
        ): String = "Repeated tool call detected:\n" +
            "- tool: $tool\n" +
            "- consecutive_calls: $count\n" +
            "- arguments: ${preview(canonicalArguments, cap)}\n" +
            "The repeated calls are not making progress. Do not call this tool with " +
            "these exact arguments again. Inspect the latest result and choose a " +
            "different action, different arguments, or finish the task if enough " +
            "evidence has been gathered."

        /** 只截**给模型看的那一份**，比较用完整串。 */
        fun preview(canonical: String, cap: Int = DEFAULT_PREVIEW_CHARS): String =
            if (canonical.length <= cap) canonical
            else canonical.take(cap) + "… (+${canonical.length - cap} more chars)"

        private fun sortKeys(value: JsonElement): JsonElement = when (value) {
            is JsonArray -> JsonArray(value.map(::sortKeys))
            is JsonObject -> JsonObject(
                value.entries.sortedBy { it.key }.associate { (key, value) -> key to sortKeys(value) },
            )
            else -> value
        }
    }
}
