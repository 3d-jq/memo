package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.ui.snackbar.SnackbarManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for "the model this chat sends with" — the chain is
 * conversation -> assistant -> global default (resolveChatModel,
 * model_display_helper.dart L44-58), and a chain that resolves nothing must
 * refuse the send instead of guessing a provider (ChatActionResult.noModel,
 * chat_actions.dart L1193).
 *
 * 用户实测过的两个回归都在这里锁住：
 *  1. 新建会话（draft，没有会话行）必须沿用助手/全局的模型 —— 否则"新建对话后
 *     模型要重新选"；
 *  2. 偏好里存的是 JSON 文本（带引号），解包漏了会让 provider 变成 `"Zhipu AI`，
 *     模型选择器就再也匹配不上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatModelWiringTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun insertAssistant(provider: String?, model: String?): Assistant {
        val assistant = Assistant(
            id = java.util.UUID.randomUUID().toString(),
            name = "wiring-test",
            chatModelProvider = provider,
            chatModelId = model,
        )
        container.assistantStore.update(assistant)
        container.setCurrentAssistant(assistant.id)
        return assistant
    }

    private fun draftConversation(): Conversation {
        // 新建会话是 draft：ChatViewModel.ensureConversationRow 之前库里没有行。
        return Conversation.create(title = "新对话")
    }

    @Test
    fun `new conversation inherits the assistant model`() {
        insertAssistant("Zhipu AI", "glm-5.3-flash")
        val vm = ChatViewModel(container, draftConversation().id)

        assertEquals("Zhipu AI", vm.selectedProviderId.value)
        assertEquals("glm-5.3-flash", vm.selectedModelId.value)
    }

    @Test
    fun `new conversation falls back to the stored default model`() {
        // 真机形态：preference_rows 里存的是带引号的 JSON 字符串。
        container.preferenceRepository.writeJson(
            DefaultModelPrefs.SELECTED_MODEL_V1,
            "\"Zhipu AI::glm-5.3-flash\"",
        )
        insertAssistant(null, null)
        val vm = ChatViewModel(container, draftConversation().id)

        assertEquals("Zhipu AI", vm.selectedProviderId.value)
        assertEquals("glm-5.3-flash", vm.selectedModelId.value)
    }

    @Test
    fun `assistant model wins over the stored default model`() {
        container.preferenceRepository.writeJson(
            DefaultModelPrefs.SELECTED_MODEL_V1,
            "\"OpenAI::gpt-4o\"",
        )
        insertAssistant("Zhipu AI", "glm-5.3-flash")
        val vm = ChatViewModel(container, draftConversation().id)

        assertEquals("Zhipu AI", vm.selectedProviderId.value)
        assertEquals("glm-5.3-flash", vm.selectedModelId.value)
    }

    @Test
    fun `nothing resolves leaves the model empty instead of guessing a provider`() {
        insertAssistant(null, null)
        val vm = ChatViewModel(container, draftConversation().id)

        assertEquals("", vm.selectedProviderId.value)
        assertEquals("", vm.selectedModelId.value)
    }

    @Test
    fun `send without a model warns and keeps the draft`() {
        insertAssistant(null, null)
        val vm = ChatViewModel(container, draftConversation().id)
        vm.input.value = "hello"
        val queuedBefore = SnackbarManager.activeCount

        vm.send()

        // no_model（chat_actions.dart L1192）：不发请求、不改状态、草稿留在输入框。
        assertEquals("hello", vm.input.value)
        assertTrue(vm.messages.value.isEmpty())
        assertEquals(queuedBefore + 1, SnackbarManager.activeCount)
        assertTrue(
            SnackbarManager.activeMessages.contains(
                context.getString(com.psyche.memo.ui.R.string.home_page_please_select_model),
            ),
        )
    }
}
