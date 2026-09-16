package com.psyche.memo.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psyche.memo.ChatViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 气泡内自动重试倒计时（用户 2026-09-16「出错还是裸http显示」）：
 * `RetryCountdownHint` 按 `auto_retry_countdown` 文案渲染
 * 「N 秒后重试 (attempt/maxRetries)」，attempt/maxRetries 来自
 * RetryPending 事件，剩余秒数由 retryAtMs 绝对时刻倒推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryCountdownHintTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setStatus(attempt: Int, maxRetries: Int, inMs: Long) =
        ChatViewModel.UiMessage.RetryStatus(
            attempt = attempt,
            maxRetries = maxRetries,
            retryAtMs = System.currentTimeMillis() + inMs,
        )

    @Test
    fun showsCountdownWithAttemptAndMaxRetries() {
        // 禁自动推进：否则 waitForIdle 会把 5s 动画跑完，文本直接变 0s。
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Surface {
                    RetryCountdownHint(status = setStatus(2, 3, 5_000L))
                }
            }
        }
        compose.waitForIdle()
        // 起始 5.0s → ceil = 5（Robolectric 默认 locale 是 en，
        // 文案用默认资源 "Ns until retry (a/m)"）。
        compose.onNodeWithText("5s until retry (2/3)").assertExists()
    }

    @Test
    fun overdueShowsZeroSeconds() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    // retryAtMs 已过（负剩余）→ 显示 0。
                    RetryCountdownHint(status = setStatus(1, 3, -1_000L))
                }
            }
        }
        compose.onNodeWithText("0s until retry (1/3)").assertExists()
    }

    @Test
    fun countdownTicksDownToOneSecondGranularity() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Surface {
                    RetryCountdownHint(status = setStatus(3, 3, 2_600L))
                }
            }
        }
        compose.waitForIdle()
        // 2.6s → ceil = 3。
        compose.onNodeWithText("3s until retry (3/3)").assertExists()
        // 等 1s：剩余 ~1.6s → ceil = 2。
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithText("2s until retry (3/3)").assertExists()
    }
    @Test
    fun countdownNeverShowsOnAFinishedMessage() {
        // 终止路径（正常结束/停止/失败）都会清 retryStatus；这里再钉住第二道保险：
        // 消息不在流式中就绝不显示倒计时（否则会停一个「N 秒后重试」在已结束的消息上）。
        assertTrue(shouldShowRetryCountdown(setStatus(2, 3, 5_000L), isStreaming = true))
        assertTrue(!shouldShowRetryCountdown(setStatus(2, 3, 5_000L), isStreaming = false))
        assertTrue(!shouldShowRetryCountdown(null, isStreaming = true))
        assertTrue(!shouldShowRetryCountdown(null, isStreaming = false))
    }
}