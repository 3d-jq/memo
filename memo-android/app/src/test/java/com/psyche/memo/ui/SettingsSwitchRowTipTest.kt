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
 * SettingsSwitchRow 的 tip/subtitle 语义（照抄 display_settings_page.dart
 * _iosSwitchRow）：subtitle 是裸排副标题；tip 不裸排——行尾 MemoryTipIcon
 * 图标点击后才在行下方展开完整提示。锁住该交互防止退回"裸提示词直接显示"。
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
    fun tipIconSecondTapCollapsesTip() {
        val tip = "Toggle tip"
        compose.setContent {
            MaterialTheme {
                SettingsSwitchRow(icon = Lucide.Sun, label = "Row", tip = tip, value = false, onToggle = {})
            }
        }
        compose.onNodeWithContentDescription(tip).performClick()
        compose.onNodeWithText(tip).assertIsDisplayed()
        compose.onNodeWithContentDescription(tip).performClick()
        compose.onNodeWithText(tip).assertDoesNotExist()
    }

    @Test
    fun subtitleRendersBareImmediately() {
        val subtitle = "Bare subtitle"
        compose.setContent {
            MaterialTheme {
                SettingsSwitchRow(
                    icon = Lucide.Sun,
                    label = "Row",
                    subtitle = subtitle,
                    value = true,
                    onToggle = {},
                )
            }
        }
        compose.onNodeWithText(subtitle).assertIsDisplayed()
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
