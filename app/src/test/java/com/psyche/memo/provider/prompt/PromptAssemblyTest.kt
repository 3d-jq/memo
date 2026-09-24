package com.psyche.memo.provider.prompt

import com.psyche.memo.common.logging.ContextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词装配（照 deepseek-harness：**顺序带 + 唯一渲染路径**，见
 * `docs/ENGINEERING_HARNESS.md` §1）。
 *
 * 这两个病是真实的：顺序原先靠代码先后（隐式），而且有两处各自 `joinToString("\n\n")`
 * （两条渲染路径）——「说明里说一套、实际发出去一套」的温床。
 */
class PromptAssemblyTest {

    private fun part(source: ContextSource, text: String) = source to text

    @Test
    fun `sections are ordered by band, persona first and tool guidance after it`() {
        // 故意乱序喂进去（模拟调用方加入顺序变化）
        val text = assembleSystemPrompt(
            listOf(
                part(ContextSource.instructionInjection, "注入项"),
                part(ContextSource.skillPrompt, "技能清单"),
                part(ContextSource.toolRules, "工具纪律"),
                part(ContextSource.systemPrompt, "人设"),
            ),
        )
        assertEquals("人设\n\n工具纪律\n\n技能清单\n\n注入项", text)
    }

    @Test
    fun `same band keeps insertion order`() {
        val text = assembleSystemPrompt(
            listOf(
                part(ContextSource.memoryRules, "长期记忆规则"),
                part(ContextSource.memoryRules, "过往回忆规则"),
            ),
        )
        assertEquals("长期记忆规则\n\n过往回忆规则", text)
    }

    @Test
    fun `blank sections never reach the model`() {
        val text = assembleSystemPrompt(
            listOf(
                part(ContextSource.systemPrompt, "  人设  "),
                part(ContextSource.skillPrompt, "   "),
                part(ContextSource.memoryRules, ""),
            ),
        )
        assertEquals("人设", text)
    }

    /** 反例：会话内容不是系统提示词的 section，误用必须**大声失败**（不许静默通过）。 */
    @Test
    fun `using a non-prompt source as a section fails loud`() {
        assertThrows(IllegalStateException::class.java) {
            promptOrderOf(ContextSource.chatHistory)
        }
        assertThrows(IllegalStateException::class.java) {
            promptOrderOf(ContextSource.toolResult)
        }
        // 也证明它确实在装配路径上抛
        assertThrows(IllegalStateException::class.java) {
            assembleSystemPrompt(listOf(part(ContextSource.toolCall, "x")))
        }
    }

    @Test
    fun `every prompt section has a band and the bands are distinct`() {
        val promptSources = listOf(
            ContextSource.systemPrompt,
            ContextSource.toolRules,
            ContextSource.memoryRules,
            ContextSource.searchPrompt,
            ContextSource.skillPrompt,
            ContextSource.workspace,
            ContextSource.instructionInjection,
        )
        val orders = promptSources.map { promptOrderOf(it) }
        assertEquals("带位必须互不相同（否则顺序不可预期）", orders.size, orders.toSet().size)
        assertTrue(
            "人设必须在最前、工具纪律必须在人设之后（照 dsh 的顺序带）",
            promptOrderOf(ContextSource.systemPrompt) < promptOrderOf(ContextSource.toolRules),
        )
        assertTrue(
            "用户注入项放最后",
            promptOrderOf(ContextSource.instructionInjection) > promptOrderOf(ContextSource.workspace),
        )
    }
}
