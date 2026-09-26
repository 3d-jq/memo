package com.psyche.memo.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 长用户消息的折叠容器（用户 2026-09-25「用户输入的内容比较多，发送到界面的时候可以收起和
 * 展开那种效果」+「在左下角显示收起和展开，可以点击」）。
 *
 * 三条必须钉住的行为：**短消息完全不受影响**（不出现控件、也不裁剪）、**只有真溢出才出现
 * 「展开」**、点一下原地把剩余内容放出来（不跳页、不弹层）。Robolectric 默认英文资源，
 * 所以断言用 "Expand" / "Collapse"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollapsibleUserBubbleTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * 12 行：稳过 8 行折叠线，展开后整行控件仍在 Robolectric 默认视口（470px）内 ——
     * 顶出视口的话 performClick 点不到，assertIsDisplayed 也会因几何出界而失败。
     */
    private val longText = (1..12).joinToString("\n") { "第 $it 行，够长的一段用户输入。" }

    /** 折叠态由外部持有 —— 测试里也要是真·可观察状态，否则点「展开」不会重组合。 */
    private val expanded: MutableState<Boolean> = mutableStateOf(false)

    private fun show(content: String) {
        expanded.value = false
        compose.setContent {
            val open = remember { expanded }
            Box(Modifier.testTag("root")) {
                CollapsibleUserBubble(
                    expanded = open.value,
                    onToggle = { open.value = !open.value },
                    textColor = Color.Black,
                ) {
                    Text(content)
                }
            }
        }
    }

    private fun rootHeight(): Float =
        compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot.height

    @Test
    fun collapseLineIsEightUserTextLines() {
        assertEquals(8, USER_BUBBLE_COLLAPSE_LINES)
    }

    @Test
    fun overflowNeedsMoreThanTheCap() {
        assertFalse("封顶为 0（极端字号）时一律不折", userBubbleOverflows(1_000f, 0f))
        assertFalse("刚好等于封顶不算溢出", userBubbleOverflows(500f, 500f))
        assertFalse("半像素以内的测量误差不折", userBubbleOverflows(500.4f, 500f))
        assertTrue("超过封顶才折", userBubbleOverflows(501f, 500f))
    }

    @Test
    fun shortMessageGetsNoToggleAndNoClipping() {
        show("就一句话。")
        compose.onNodeWithText("Expand").assertDoesNotExist()
        compose.onNodeWithText("就一句话。").assertIsDisplayed()
    }

    @Test
    fun longMessageCollapsesAndExpandsInPlace() {
        show(longText)
        compose.onNodeWithText("Expand").assertIsDisplayed()
        val collapsedHeight = rootHeight()

        compose.onNodeWithText("Expand").performClick()

        compose.onNodeWithText("Collapse").assertIsDisplayed()
        val expandedHeight = rootHeight()
        assertTrue(
            "expanding must reveal the rest: collapsed=$collapsedHeight expanded=$expandedHeight",
            expandedHeight > collapsedHeight + 100f,
        )

        compose.onNodeWithText("Collapse").performClick()
        compose.onNodeWithText("Expand").assertIsDisplayed()
        assertEquals("collapsing returns to the clipped height", collapsedHeight, rootHeight(), 1f)
    }
}
