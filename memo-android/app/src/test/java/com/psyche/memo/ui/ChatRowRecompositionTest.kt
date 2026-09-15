package com.psyche.memo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.TextPart
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 交互状态不该重渲染消息行。
 *
 * 用户 2026-09-15：「点击到底部这个按钮，对话界面会闪」「你可以看看人家原项目和 rikkhub
 * 代码，人家做的都很好，都没有什么卡顿问题」。两个项目里消息行都是**按引用跳过重组**的
 * （Flutter 的 `ListView.builder` 只重建脏 item；RikkaHub 的 `ChatMessage` 是 skippable
 * 的 composable）。我们这边靠 `app/compose_compiler_config.conf` 把
 * `UiMessage`/`ChatTimelineSettings`/`Assistant` 声明为 stable + `MessageRow` 全部实参
 * 稳定 —— 这个测试就是那条链的**回归判据**：碰一下列表（会翻 `pointerDown`/`following`/
 * `navVisible` 三个 ChatContent 状态）之后，`MessageRow` 的组合次数必须还是 0。
 *
 * 失败通常意味着某个实参又变成了 `unstable`（新的 `List`/`Map`/`Array` 入参、捕获它们的
 * lambda、或者把 `remember` 态当参数往下传），于是整屏消息行每帧重组合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRowRecompositionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
        ChatRecompositionProbe.reset()
    }

    private fun seedConversation(messages: Int): String {
        val conv = Conversation.create(title = "重组合测试")
        container.conversationDao.insert(conv)
        for (i in 0 until messages) {
            container.messageDao.insert(
                ChatMessage(
                    id = ChatMessage.newId(),
                    role = if (i % 2 == 0) "user" else "assistant",
                    parts = listOf<MessagePart>(TextPart("消息 $i")),
                    timestamp = 1_700_000_000_000_000L + i,
                    conversationId = conv.id,
                    groupId = "g$i",
                    version = 0,
                    messageOrder = i,
                ),
            )
        }
        return conv.id
    }

    private fun render(conversationId: String) {
        compose.setContent {
            MaterialTheme {
                ChatContent(
                    container = container,
                    conversationId = conversationId,
                    onOpenDrawer = {},
                    onNew = {},
                    onOpenWorkspaces = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ChatRecompositionProbe.messageRows > 0
        }
    }

    @Test
    fun `touching the timeline does not recompose message rows`() {
        val id = seedConversation(messages = 6)
        render(id)
        assertTrue("首帧应该已经组合过消息行", ChatRecompositionProbe.messageRows > 0)

        ChatRecompositionProbe.reset()
        val clickablesBefore = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        // 手指按下再抬起：ChatContent 会翻 pointerDown / following / navVisible，
        // 但那三个状态跟消息行的实参无关 —— 行不该重组合。
        compose.onNodeWithTag(CHAT_TIMELINE_TAG).performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()

        // 触摸真的生效了：按下时 `navVisible = true` → 导航面板滑入（+4 个可点节点）。
        assertTrue(
            "这次触摸没有触发导航面板，说明手势没落到列表上，后面的断言就没意义了",
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size > clickablesBefore,
        )
        assertTrue(
            "碰一下列表就重组合了 ${ChatRecompositionProbe.messageRows} 次消息行 —— " +
                "说明 MessageRow 的某个实参变 unstable（见 compose_compiler_config.conf）",
            ChatRecompositionProbe.messageRows == 0,
        )
    }
}
