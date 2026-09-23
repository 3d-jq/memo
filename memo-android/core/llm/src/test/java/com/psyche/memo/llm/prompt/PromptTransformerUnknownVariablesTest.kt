package com.psyche.memo.llm.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未知变量**不许静默**（dsh 的 fail loud 柔性版，见 `docs/ENGINEERING_HARNESS.md` §2）。
 *
 * 上游行为是「未知 `{xxx}` 原样留着」—— 手滑写 `{cur_dtae}` 就会把这段字面量直接发给模型，
 * 谁都不知道。这套 `{...}` 语法与 JSON 花括号同形，抛异常会误伤合法提示词，所以改成
 * 「保留行为 + 交出一份清单让调用方告警」。
 */
class PromptTransformerUnknownVariablesTest {

    private val known = PromptTransformer.supportedKeys()

    @Test
    fun `typos are reported`() {
        assertEquals(
            listOf("{cur_dtae}"),
            PromptTransformer.unknownPlaceholders("今天是 {cur_dtae}，请总结", known),
        )
        // 支持的变量不报，只有真的不认识的那个才报
        assertEquals(
            listOf("{foo}"),
            PromptTransformer.unknownPlaceholders("{foo} {assistant_name}", known),
        )
    }

    @Test
    fun `supported variables are not reported`() {
        val text = "你是 {assistant_name}，用户叫 {nickname}，现在是 {cur_datetime}"
        assertTrue(PromptTransformer.unknownPlaceholders(text, known).isEmpty())
    }

    /** 反例：消息模板的 `{{...}}` 与 JSON 花括号都不能被误报（否则告警会被噪音淹没）。 */
    @Test
    fun `message template and json braces are not reported`() {
        assertTrue(
            PromptTransformer.unknownPlaceholders("{{message}} {{role}}", known).isEmpty(),
        )
        assertTrue(
            PromptTransformer.unknownPlaceholders("""{"name": "a", "n": 1}""", known).isEmpty(),
        )
        assertTrue(PromptTransformer.unknownPlaceholders("", known).isEmpty())
    }

    @Test
    fun `the supported key set is the single source the ui mirrors`() {
        assertEquals(12, known.size)
        assertTrue("{{...}} 不是系统提示词的变量", known.none { it.startsWith("{{") })
    }
}
