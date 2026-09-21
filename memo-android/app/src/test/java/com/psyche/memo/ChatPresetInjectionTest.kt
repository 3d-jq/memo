package com.psyche.memo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.PresetMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 预设对话注入（home_view_model.dart L993-1023）：新会话（`injectPresets = true`）
 * 把助手的 `presetMessages` 作为**真实消息**落库。
 *
 * 回归点：`ChatViewModel` 类体里曾经又声明了一个 `private val injectPresets = false`，
 * 屏蔽了构造参数 → `if (injectPresets)` 恒假，预设对话**从来没注入过**（用户 2026-09-13
 * 问「提示词这个部分可以用吧」时查出来的）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPresetInjectionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun insertAssistant(presets: List<PresetMessage>): Assistant {
        val assistant = Assistant(
            id = java.util.UUID.randomUUID().toString(),
            name = "preset-test",
            chatModelProvider = "Zhipu AI",
            chatModelId = "glm-5.3-flash",
            presetMessages = PresetMessage.encodeList(presets),
        )
        container.assistantStore.update(assistant)
        container.setCurrentAssistant(assistant.id)
        return assistant
    }

    /** init 里的注入是异步的（viewModelScope + Dispatchers.IO），等它落库。 */
    private fun awaitMessages(conversationId: String, count: Int, timeoutMs: Long = 30_000): List<Pair<String, String>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val rows = container.messageDao.getAllForConversation(conversationId)
            if (rows.size >= count) return rows.map { it.role to it.content }
            Thread.sleep(20)
        }
        return container.messageDao.getAllForConversation(conversationId).map { it.role to it.content }
    }

    @Test
    fun `new conversation persists the assistant preset turns in order`() {
        insertAssistant(
            listOf(
                PresetMessage(id = "1", role = "user", content = "你好"),
                PresetMessage(id = "2", role = "assistant", content = "在的，有什么需要？"),
                PresetMessage(id = "3", role = "user", content = ""), // 空内容跳过
            ),
        )
        val conversation = Conversation.create(title = "新对话")

        ChatViewModel(container, conversation.id, injectPresets = true)

        val rows = awaitMessages(conversation.id, 2)
        assertEquals(
            listOf("user" to "你好", "assistant" to "在的，有什么需要？"),
            rows,
        )
        // 会话本体也一起落库（原版 addMessage 的行为）。
        assertEquals(conversation.id, container.conversationDao.get(conversation.id)?.id)
    }

    @Test
    fun `opening an existing conversation never re-injects the presets`() {
        insertAssistant(listOf(PresetMessage(id = "1", role = "user", content = "你好")))
        val conversation = Conversation.create(title = "旧对话")
        container.conversationDao.insert(conversation)
        container.messageDao.insert(
            com.psyche.memo.data.model.ChatMessage(
                id = com.psyche.memo.data.model.ChatMessage.newId(),
                role = "user",
                parts = listOf<MessagePart>(com.psyche.memo.data.model.TextPart("已有消息")),
                timestamp = 1,
                conversationId = conversation.id,
                groupId = "g",
                messageOrder = 0,
            ),
        )

        ChatViewModel(container, conversation.id, injectPresets = true)

        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        val rows = container.messageDao.getAllForConversation(conversation.id)
        assertEquals(1, rows.size)
        assertEquals("已有消息", rows.single().content)
    }

    @Test
    fun `without the new-conversation flag nothing is inserted`() {
        insertAssistant(listOf(PresetMessage(id = "1", role = "user", content = "你好")))
        val conversation = Conversation.create(title = "新对话")

        ChatViewModel(container, conversation.id, injectPresets = false)

        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(150)
        assertTrue(container.messageDao.getAllForConversation(conversation.id).isEmpty())
    }
}
