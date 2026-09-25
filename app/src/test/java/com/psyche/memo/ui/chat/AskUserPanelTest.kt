package com.psyche.memo.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 输入栏位置的问询面板（用户 2026-09-14「问问题…这个应该出现在输入框那个位置，
 * 你看这个就是在输入框那里显示的」+ 选定「一题一页 + 左右箭头 + 关闭 X」）。
 *
 * 面板是用户作答的唯一入口（内联工具卡的交互控件已按同一决定拿掉），所以这里钉住：
 * 一题一页能翻、**每题都答了才能提交**、提交后请求完成、× 上报关闭。
 *
 * 文案断言用**英文**（Robolectric 默认 locale = 默认资源），题目/选项是自己传进去的字面量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskUserPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val twoQuestions = Json.parseToJsonElement(
        """
        {"questions":[
          {"id":"q1","question":"颜色？","type":"single","options":["红","蓝"]},
          {"id":"q2","question":"大小？","type":"single","options":["大","小"]}
        ]}
        """.trimIndent(),
    ).jsonObject

    private fun request(service: AskUserInteractionService) =
        service.requestAnswer("c1", twoQuestions, conversationId = "conv-1")

    @Test
    fun pagerShowsOneQuestionAtATime() {
        val service = AskUserInteractionService()
        request(service)
        val pending = service.pendingRequests.value.single()

        compose.setContent {
            MaterialTheme {
                AskUserPanel(request = pending, askUser = service, onClose = {})
            }
        }

        compose.onNodeWithText("1/2").assertIsDisplayed()
        compose.onNodeWithText("颜色？").assertIsDisplayed()
        compose.onNodeWithText("大小？").assertDoesNotExist()

        compose.onNodeWithText("红").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("2/2").assertIsDisplayed()
        compose.onNodeWithText("大小？").assertIsDisplayed()
        compose.onNodeWithText("颜色？").assertDoesNotExist()

        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithText("颜色？").assertIsDisplayed()
    }

    @Test
    // `CompletableDeferred.getCompleted()` 仍是实验 API（编译零警告是门禁要求）。
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun submitNeedsEveryQuestionAnsweredAndThenCompletesTheRequest() {
        val service = AskUserInteractionService()
        val deferred = request(service)
        val pending = service.pendingRequests.value.single()

        compose.setContent {
            MaterialTheme {
                AskUserPanel(request = pending, askUser = service, onClose = {})
            }
        }

        // 只答了第一题 → 点提交不该发生任何事（请求仍在等）。
        compose.onNodeWithText("红").performClick()
        compose.onNodeWithText("Submit answer").performClick()
        compose.waitForIdle()
        assertFalse("还有一题没答，不该提交", deferred.isCompleted)
        assertEquals(1, service.pendingRequests.value.size)

        // 两题都答了 → 提交，请求完成、pending 清空。
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("小").performClick()
        compose.onNodeWithText("Submit answer").performClick()
        compose.waitForIdle()

        assertTrue(deferred.isCompleted)
        assertTrue(service.pendingRequests.value.isEmpty())
        // 载荷是 {"type":"ask_user_answer","answers":{...}}：两题都要在里面。
        val payload = deferred.getCompleted().jsonString
        assertTrue("第二题的答案要在载荷里：$payload", payload.contains("小"))
        assertTrue("第一题的答案也要在载荷里：$payload", payload.contains("红"))
    }

    @Test
    fun closeReportsDismissAndCancelEndsTheRequest() {
        val service = AskUserInteractionService()
        val deferred = request(service)
        val pending = service.pendingRequests.value.single()
        var closed = false

        compose.setContent {
            MaterialTheme {
                AskUserPanel(request = pending, askUser = service, onClose = { closed = true })
            }
        }

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertTrue("× 要上报关闭（取消由调用方执行）", closed)

        // 服务侧 cancel 语义：请求立刻结束、从 pending 移除（模型拿到 cancelled）。
        service.cancel("c1")
        assertTrue(deferred.isCompleted)
        assertTrue(service.pendingRequests.value.isEmpty())
    }
}
