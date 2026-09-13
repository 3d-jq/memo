package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.Conversation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统提示词的 `{...}` 变量接线（`injectSystemPrompt` L1569-1597）：助手编辑页
 * 「可用变量」列了 12 个，UI 有、文档也有，但 2026-09-13 之前**没有任何地方替换**
 * （用户问「为什么不用那个可用变量呀 是没做吗？」时查出来的）。
 *
 * 这里锁住上游那条链路：`buildSystemPromptParts` 交出来的系统提示词必须是替换后的
 * 文本（而不是原样带着 `{nickname}`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemPromptVariablesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun insertAssistant(systemPrompt: String): Assistant {
        val assistant = Assistant(
            id = java.util.UUID.randomUUID().toString(),
            name = "小满",
            chatModelProvider = "Zhipu AI",
            chatModelId = "glm-5.3-flash",
            systemPrompt = systemPrompt,
        )
        container.assistantStore.update(assistant)
        container.setCurrentAssistant(assistant.id)
        return assistant
    }

    @Test
    fun `system prompt variables are substituted before the request`() = runBlocking {
        container.preferenceRepository.writeJson("user_name", "\"老大\"")
        val assistant = insertAssistant(
            "你是{assistant_name}，主人是{nickname}。今天 {cur_date}，现在 {cur_datetime}，" +
                "用的模型是 {model_id}，时区 {timezone}，电量 {battery_level}。",
        )
        val vm = ChatViewModel(container, Conversation.create(title = "变量").id)

        val systemPart = vm.buildSystemPromptParts(assistant)
            .first { it.first == ContextSource.systemPrompt }
            .second

        assertTrue(systemPart.contains("你是小满，主人是老大。"))
        assertTrue(systemPart.contains("用的模型是 glm-5.3-flash"))
        // 时间/时区/电量这些平台值只要求「替换掉了」，不锁具体值。
        assertFalse(systemPart.contains("{cur_date}"))
        assertFalse(systemPart.contains("{cur_datetime}"))
        assertFalse(systemPart.contains("{timezone}"))
        assertFalse(systemPart.contains("{battery_level}"))
        assertFalse(systemPart.contains("{"))
    }

    @Test
    fun `unknown variables and template braces stay verbatim`() = runBlocking {
        val assistant = insertAssistant("嗨 {nickname}，{unknown_var} 保留，{{ message }} 是消息模板语法")
        val vm = ChatViewModel(container, Conversation.create(title = "变量2").id)

        val systemPart = vm.buildSystemPromptParts(assistant)
            .first { it.first == ContextSource.systemPrompt }
            .second

        assertTrue(systemPart.contains("{unknown_var}"))
        assertTrue(systemPart.contains("{{ message }}"))
    }

    @Test
    fun `a prompt without braces is passed through untouched`() = runBlocking {
        val assistant = insertAssistant("就是个普通提示词")
        val vm = ChatViewModel(container, Conversation.create(title = "变量3").id)
        val systemPart = vm.buildSystemPromptParts(assistant)
            .first { it.first == ContextSource.systemPrompt }
            .second
        assertTrue(systemPart == "就是个普通提示词")
    }
}
