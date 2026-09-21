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
import com.psyche.memo.ui.chat.headerAssistantId
import com.psyche.memo.ui.chat.ChatContent

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
        // `useAssistantName`（助手编辑页「使用助手名字」）打开时名字行显示助手名
        // —— CMW:2809-2813 的三元；关着时显示模型名，见下一个用例。
        val bound = Assistant(
            id = "bound-assistant",
            name = "会话助手",
            useAssistantAvatar = true,
            useAssistantName = true,
        )
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
                    // 工作区入口（home 路由 → HomeScreen → 这里）现在没有默认值，
                    // 漏传会直接编译不过 —— 见 HomeScreen 的 onOpenWorkspaces 注释。
                    onOpenWorkspaces = {},
                    onOpenGenerationServices = {},
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

    @Test
    fun `without use assistant name the header shows the model name`() {
        val bound = Assistant(id = "model-name-assistant", name = "会话助手")
        container.assistantStore.update(bound)
        container.setCurrentAssistant(bound.id)

        val conv = Conversation.create(title = "Model name", assistantId = bound.id)
        container.conversationDao.insert(conv)
        insertAssistantMessage(conv.id, modelId = "glm-5.3-flash", providerId = "Zhipu AI")

        compose.setContent { MaterialTheme { chatContent(conv.id) } }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("glm-5.3-flash").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("会话助手").assertCountEquals(0)
    }

    @Test
    fun `display show model name off hides the whole name line`() {
        val bound = Assistant(id = "no-name-assistant", name = "会话助手")
        container.assistantStore.update(bound)
        container.setCurrentAssistant(bound.id)
        container.preferenceRepository.writeJson("display_show_model_name_v1", "0")

        val conv = Conversation.create(title = "No name", assistantId = bound.id)
        container.conversationDao.insert(conv)
        insertAssistantMessage(conv.id, modelId = "glm-5.3-flash", providerId = "Zhipu AI")

        compose.setContent { MaterialTheme { chatContent(conv.id) } }

        // 正文出现即说明这一行渲染完了；名字行（模型名与助手名）都不该在。
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("hi").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("glm-5.3-flash").assertCountEquals(0)
        compose.onAllNodesWithText("会话助手").assertCountEquals(0)
    }

    @Test
    fun `display show provider in chat message appends the provider name`() {
        val bound = Assistant(id = "provider-assistant", name = "会话助手")
        container.assistantStore.update(bound)
        container.setCurrentAssistant(bound.id)
        container.preferenceRepository.writeJson("display_show_provider_in_chat_message_v1", "1")

        val conv = Conversation.create(title = "Provider suffix", assistantId = bound.id)
        container.conversationDao.insert(conv)
        insertAssistantMessage(conv.id, modelId = "glm-5.3-flash", providerId = "Zhipu AI")

        compose.setContent { MaterialTheme { chatContent(conv.id) } }

        // 供应商投影是**异步**读的（rememberLoaded），所以要等后缀出现。
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("glm-5.3-flash | Zhipu AI")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("glm-5.3-flash | Zhipu AI").assertExists()
    }

    // ---- helpers ----

    private fun insertAssistantMessage(conversationId: String, modelId: String, providerId: String) {
        container.messageDao.insert(
            ChatMessage(
                id = ChatMessage.newId(),
                role = "assistant",
                parts = listOf(TextPart("hi")),
                timestamp = System.currentTimeMillis(),
                conversationId = conversationId,
                modelId = modelId,
                providerId = providerId,
                groupId = ChatMessage.newId(),
                version = 0,
                messageOrder = 0,
            ),
        )
    }

    @androidx.compose.runtime.Composable
    private fun chatContent(conversationId: String) {
        ChatContent(
            container = container,
            conversationId = conversationId,
            onOpenDrawer = {},
            onNew = {},
            onOpenWorkspaces = {},
            onOpenGenerationServices = {},
        )
    }
}
