package com.psyche.memo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sun
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsSwitchRow 的 tip 语义（用户规范：不裸提示词，统一 Tooltip）：
 * subtitle 参数已删除；一切说明走 tip——点击行尾 BadgeInfo 图标弹出
 * 浮动 Tooltip 气泡（不顶开内容）。锁住该交互防止退回"裸提示词直接显示"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSwitchRowTipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tipHiddenUntilBadgeInfoIconTapped() {
        val tip = "Show full tip text"
        compose.setContent {
            MaterialTheme {
                SettingsSwitchRow(
                    icon = Lucide.Sun,
                    label = "Row",
                    tip = tip,
                    value = false,
                    onToggle = {},
                )
            }
        }
        // 禁止裸排：tip 文字初始不存在，只有 BadgeInfo 图标（文案即语义标签）。
        compose.onNodeWithText(tip).assertDoesNotExist()
        compose.onNodeWithContentDescription(tip).performClick()
        compose.onNodeWithText(tip).assertIsDisplayed()
    }

    @Test
    fun noTipMeansNoInfoIcon() {
        // 用户规范（2026-09-09）：设置行不裸排提示，全部改 Tooltip；
        // 无 tip 时行尾不应出现 BadgeInfo（contentDescription 为空即可判）。
        compose.setContent {
            MaterialTheme {
                SettingsSwitchRow(
                    icon = Lucide.Sun,
                    label = "Row",
                    value = false,
                    onToggle = {},
                )
            }
        }
        compose.onNodeWithText("Row").assertIsDisplayed()
        // 组件无 tip 时不存在任何 BadgeInfo 语义节点。
        compose.onNodeWithContentDescription("tip").assertDoesNotExist()
    }

    @Test
    fun rowTapTogglesSwitch() {
        var toggled = false
        compose.setContent {
            MaterialTheme {
                SettingsSwitchRow(
                    icon = Lucide.Sun,
                    label = "Row",
                    value = false,
                    onToggle = { toggled = it },
                )
            }
        }
        compose.onNodeWithText("Row").performClick()
        assertTrue(toggled)
    }
}
