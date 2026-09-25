package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.provider.LocalToolExecutors
import com.psyche.memo.provider.LocationTool
import com.psyche.memo.provider.MemoryTools
import com.psyche.memo.provider.SkillTools
import com.psyche.memo.provider.chart.MermaidTools
import com.psyche.memo.provider.chart.VisualTools
import com.psyche.memo.provider.generation.GenerationTools
import com.psyche.memo.provider.prompt.assembleSystemPrompt
import com.psyche.memo.provider.search.SearchToolService
import com.psyche.memo.provider.workspace.WorkspaceTools
import com.psyche.memo.ui.chat.AskUserToolNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **系统提示词回放网**（学 deepseek-harness 的 fixture 纪律：提示词里只许陈述运行时
 * 真会执行的规则，所以任何一句改动都必须看得见）。
 *
 * 系统提示词由七八个来源拼成（助手原文、工具纪律、记忆规则、搜索引用句、技能目录、
 * 工作区句、指令注入），每一处都能单独改，改完没人发现 —— 直到模型行为变了才回溯。
 * 这里把「给定助手 + 给定工具名单 ⇒ 交出去的那一整段文本」钉成
 * `app/src/test/resources/prompts/system-prompt-golden.txt`：
 * 任何一块文案、顺序、门控条件的变化都会让这条断言红，逼改动的人显式更新那份基线。
 *
 * 场景是刻意选全的：记忆开、过往回忆开、搜索开、工具名单给满（每条路由句都要出现），
 * 这样基线覆盖到的文案面最大。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemPromptGoldenTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 与真实请求同形：所有 Memo 侧有执行器的工具名都递出去。 */
    private val allOffered = listOf(
        SearchToolService.TOOL_NAME,
        LocalToolExecutors.TIME_INFO,
        LocalToolExecutors.CALCULATE,
        LocationTool.TOOL_NAME,
        AskUserToolNames.ASK_USER,
        SkillTools.USE_SKILL,
        MemoryTools.MEMORY_UPDATE,
        MemoryTools.MEMORY_SEARCH_PROFILE,
        MemoryTools.CHAT_SEARCH,
        GenerationTools.GENERATE_IMAGE,
        GenerationTools.GENERATE_VIDEO,
        VisualTools.TOOL_NAME,
        MermaidTools.TOOL_NAME,
    ) + WorkspaceTools.ALL_TOOL_NAMES.sorted()

    private fun viewModelWith(assistant: Assistant): ChatViewModel {
        val container = AppContainerImpl(context)
        container.assistantStore.update(assistant)
        container.setCurrentAssistant(assistant.id)
        return ChatViewModel(container, Conversation.create(title = "golden").id)
    }

    private fun golden(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("prompts/system-prompt-golden.txt"),
    ) { "缺少系统提示词基线 app/src/test/resources/prompts/system-prompt-golden.txt" }
        .reader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `the whole assembled system prompt matches the fixture`() {
        val assistant = Assistant(
            id = "assistant-golden",
            name = "小满",
            chatModelProvider = "DeepSeek",
            chatModelId = "deepseek-chat",
            // 刻意不含 {...} 变量：日期/时间会让基线每天漂移，变量替换由
            // SystemPromptVariablesTest 单独钉。
            systemPrompt = "你是一个有帮助的助手。",
            enableMemory = true,
            allowPastConversationRecall = true,
            searchEnabled = true,
        )
        val parts = runBlocking {
            viewModelWith(assistant).buildSystemPromptParts(assistant, allOffered)
        }
        assertEquals(
            listOf(
                ContextSource.systemPrompt,
                ContextSource.toolRules,
                ContextSource.memoryRules,
                ContextSource.memoryRules,
                ContextSource.searchPrompt,
            ),
            parts.map { it.first },
        )
        assertEquals(golden(), assembleSystemPrompt(parts))
    }

    @Test
    fun `no tools offered means no tool discipline block`() {
        val assistant = Assistant(
            id = "assistant-golden-bare",
            name = "小满",
            chatModelProvider = "DeepSeek",
            chatModelId = "deepseek-chat",
            systemPrompt = "你是一个有帮助的助手。",
        )
        val parts = runBlocking {
            viewModelWith(assistant).buildSystemPromptParts(assistant, emptyList())
        }
        assertFalse(
            "一颗工具都没递，就不许出现任何工具句（告诉模型列表里没有的工具，比不告诉更糟）",
            parts.any { it.first == ContextSource.toolRules },
        )
        assertEquals(
            listOf(ContextSource.systemPrompt),
            parts.map { it.first },
        )
    }
}
