package com.psyche.memo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.TextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 消息头的助手归属（用户实测：「新建对话聊天后 助手对应的名字，头像没有及时显示」）。
 *
 * 规则：会话行绑定的助手优先；新建会话是 draft（还没有会话行）时回落到当前助手 ——
 * 对应原版 `currentConversation.assistantId`（draft 在内存里就带着 assistantId）。
 * 修之前是一次性 `LaunchedEffect(conversationId)` 读库，draft 那一刻读到 null 就再也
 * 不重读 ⇒ 聊起来后头部一直显示兜底名"助手" + 模型图标。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatHeaderAssistantTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    // ---- 纯规则 ----

    @Test
    fun `conversation assistant wins over the current one`() {
        assertEquals("conv-assistant", headerAssistantId("conv-assistant", "current-assistant"))
    }

    @Test
    fun `draft without a conversation row falls back to the current assistant`() {
        // draft：conversationDao.get(id) 为 null（或 assistantId 为空）——
        // 修之前这里返回 null ⇒ 头部一直显示兜底的"助手"。
        assertEquals("current-assistant", headerAssistantId(null, "current-assistant"))
        assertEquals("current-assistant", headerAssistantId("", "current-assistant"))
    }

    @Test
    fun `no assistant at all resolves to null`() {
        assertNull(headerAssistantId(null, null))
        assertNull(headerAssistantId("", ""))
        assertNull(headerAssistantId("", null))
    }

    // ---- 渲染：会话行绑定的助手名必须出现在消息头 ----

    @Test
    fun `message header shows the conversation's assistant name`() {
        val bound = Assistant(id = "bound-assistant", name = "会话助手", useAssistantAvatar = true)
        container.assistantStore.update(bound)
        val other = Assistant(id = "other-assistant", name = "别的助手")
        container.assistantStore.update(other)
        // 当前助手是另一个：头部必须跟会话绑定的那个。
        container.setCurrentAssistant(other.id)

        val conv = Conversation.create(title = "Header test", assistantId = bound.id)
        container.conversationDao.insert(conv)
        container.messageDao.insert(
            ChatMessage(
                id = ChatMessage.newId(),
                role = "assistant",
                parts = listOf(TextPart("hi")),
                timestamp = System.currentTimeMillis(),
                conversationId = conv.id,
                groupId = "header-test-group",
                version = 0,
                messageOrder = 0,
            ),
        )

        compose.setContent {
            MaterialTheme {
                ChatContent(
                    container = container,
                    conversationId = conv.id,
                    onOpenDrawer = {},
                    onNew = {},
                )
            }
        }
        // 消息是异步从库里读出来的（ChatViewModel.refreshTail），等它出现。
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("会话助手").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("会话助手").assertExists()
        compose.onAllNodesWithText("别的助手").assertCountEquals(0)
    }
}
