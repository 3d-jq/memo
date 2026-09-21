package com.psyche.memo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「二级界面返回不是上一个界面，直接返回主设置界面」（用户 2026-09-15，点名日志
 * 那些界面）。
 *
 * 根因：日志页的文件详情是**同屏 overlay**（不是新的导航目的地，只是叠在列表上的
 * 一层），它自己的返回箭头能回到列表，但**系统返回键没人拦** → 直接 pop 掉整个日志
 * 路由，掉回主设置页。修法是把 [OverlayScaffold] 里加 `BackHandler`，这样文件页 /
 * 快照页 / 上下文日志页这些同款二级页一次性都覆盖到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogOverlayBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `system back closes the overlay instead of leaving the screen`() {
        var closed = 0
        compose.setContent {
            MaterialTheme {
                OverlayScaffold(title = "request log", onClose = { closed += 1 }) {}
            }
        }

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, closed)
    }
}
