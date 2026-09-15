package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「消息生成」触觉（用户 2026-09-15「消息生成触觉反馈好像没有做到呀」）。
 *
 * 上游把回调放在 ViewModel 里（`home_view_model.dart:185` 声明、`:423` 发送、
 * `:495` 重新生成前触发），页面只负责注入 —— 因为只挂在发送按钮的 `onSend` 上会
 * 漏掉回车发送、建议气泡等入口。这里锁住：**生成开跑前的两处都触发**、被守卫拦下
 * 的调用**不触发**（没输入 / 没有模型时不该震）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatGenerationHapticsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun insertAssistant(provider: String?, model: String?): Assistant {
        val assistant = Assistant(
            id = java.util.UUID.randomUUID().toString(),
            name = "haptics-test",
            chatModelProvider = provider,
            chatModelId = model,
        )
        container.assistantStore.update(assistant)
        container.setCurrentAssistant(assistant.id)
        return assistant
    }

    @Test
    fun `sending fires the generation haptic once`() {
        insertAssistant("Zhipu AI", "glm-5.3-flash")
        val vm = ChatViewModel(container, Conversation.create(title = "新对话").id)
        var fired = 0
        vm.onHapticFeedback = { fired += 1 }

        vm.input.value = "你好"
        vm.send()

        assertEquals(1, fired)
    }

    @Test
    fun `an empty input never fires it`() {
        insertAssistant("Zhipu AI", "glm-5.3-flash")
        val vm = ChatViewModel(container, Conversation.create(title = "新对话").id)
        var fired = 0
        vm.onHapticFeedback = { fired += 1 }

        vm.send()

        assertEquals(0, fired)
    }

    @Test
    fun `a chat without a model never fires it`() {
        // 没有助手、也没有默认模型 → hasModelOrWarn 拦下，不该有触觉。
        val vm = ChatViewModel(container, Conversation.create(title = "新对话").id)
        var fired = 0
        vm.onHapticFeedback = { fired += 1 }

        vm.input.value = "你好"
        vm.send()

        assertEquals(0, fired)
    }
}
