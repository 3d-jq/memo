package com.psyche.memo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.psyche.memo.ui.R as UiR

/**
 * 「同屏二级页」的**系统返回**（用户 2026-09-16「记忆界面 → 提示词模板，点返回没有
 * 回到上一级，直接回到主设置界面」）。
 *
 * 这些页面是叠在宿主页面上的一层 composable，不是新的导航目的地。它们的返回箭头是好的，
 * 但系统返回键如果没人拦，就会直接 pop 掉宿主的整条路由 —— 在记忆设置里表现为「掉回主
 * 设置页」，在对话里打开 HTML 预览则表现为「退出对话」。修法是统一的
 * [OverlayBackHandler]，这里锁住两个用户实测过的入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayBackNavigationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `the memory prompt editor closes instead of popping the memory settings route`() {
        var closed = 0
        compose.setContent {
            MaterialTheme {
                MemoryPromptEditOverlay(
                    container = container,
                    entry = PromptEntry(
                        titleRes = UiR.string.memory_prompt_edit_rules_title,
                        subtitleRes = UiR.string.memory_prompt_edit_rules_subtitle,
                        kind = MemoryPromptKind.RULES,
                    ),
                    onClose = { closed += 1 },
                )
            }
        }

        pressBack()

        assertEquals(1, closed)
    }

    @Test
    fun `the html preview closes instead of popping the conversation route`() {
        var back = 0
        compose.setContent {
            MaterialTheme {
                com.psyche.memo.ui.chat.HtmlPreviewScreen(
                    request = com.psyche.memo.ui.chat.HtmlPreviewRequest(
                        content = "<b>hi</b>",
                        rawHtml = true,
                    ),
                    onBack = { back += 1 },
                )
            }
        }

        pressBack()

        assertEquals(1, back)
    }
}
