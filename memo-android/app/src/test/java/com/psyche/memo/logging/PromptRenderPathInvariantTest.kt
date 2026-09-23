package com.psyche.memo.logging

import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.provider.prompt.assembleSystemPrompt
import com.psyche.memo.provider.prompt.orderedPromptParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh 的「**model-visible ⟺ logged**」不变式：凡进入模型请求的东西，都必须能从上下文日志
 * 重建 —— 具体到系统提示词：**日志里拼出来的字符串，必须与实际发出去的一字不差**，
 * 而且按标签长度切出来的段落必须等于渲染用的段落。
 *
 * 这不是空谈：2026-09-23 实测就是漂的 —— 请求侧按带位排序（`assembleSystemPrompt`），
 * 日志侧还是老的 `parts.joinToString("\n\n")`（不排序、不去空白）。
 */
class PromptRenderPathInvariantTest {

    private val messy = listOf(
        ContextSource.instructionInjection to "注入项",
        ContextSource.skillPrompt to "  技能清单  ",
        ContextSource.toolRules to "工具纪律",
        ContextSource.systemPrompt to "人设",
        ContextSource.memoryRules to "   ",
    )

    private fun sliceByTags(joined: String, tags: List<com.psyche.memo.common.logging.ContextTag>): List<String> {
        var offset = 0
        return tags.map { tag ->
            val next = offset + tag.length
            joined.substring(offset, next).also { offset = next }
        }
    }

    @Test
    fun `the logged system message equals the request system message`() {
        val assembler = ContextLogAssembler
        assertEquals(
            assembleSystemPrompt(messy),
            assembler.joinSystemParts(messy),
        )
    }

    @Test
    fun `slicing by tags reproduces exactly the rendered parts`() {
        val assembler = ContextLogAssembler
        val joined = assembler.joinSystemParts(messy)
        val tags = assembler.systemMessageTags(messy)
        val rendered = orderedPromptParts(messy)

        assertEquals("标签数量必须等于渲染后的段数", rendered.size, tags.size)
        // 约定（与既有实现一致）：除第一段外，每段**自己拥有**它前面的 "\n\n" ——
        // 所以切片后要把那 2 个字符摘掉才是段落文本。
        val slices = sliceByTags(joined, tags)
        assertEquals(
            "按标签切片（摘掉段首分隔符）必须还原每一段文本",
            rendered.map { it.second },
            slices.mapIndexed { index, slice -> if (index == 0) slice else slice.drop(2) },
        )
        assertTrue(
            "非首段的分隔符必须是 exactly \"\n\n\"",
            slices.drop(1).all { it.startsWith("\n\n") },
        )
        assertEquals(
            "每一段的来源标签也要对上",
            rendered.map { it.first },
            tags.map { it.source },
        )
        assertEquals("切片总长必须覆盖整串", joined.length, tags.sumOf { it.length })
    }

    @Test
    fun `appending to an existing system message keeps the same band order`() {
        val assembler = ContextLogAssembler
        val appended = assembler.joinedAppending("已有系统消息", messy)
        assertEquals(
            "已有系统消息\n\n" + assembleSystemPrompt(messy),
            appended,
        )
    }
}
