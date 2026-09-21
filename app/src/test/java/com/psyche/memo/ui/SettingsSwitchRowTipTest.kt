package com.psyche.memo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sun
import org.junit.Assert.assertEquals
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

    /**
     * 用户 2026-09-12：「tip 图标应该在文字旁边」——ⓘ 必须紧贴标签文字右侧，
     * 不能像原版 `_iosSwitchRow` 那样浮到开关那侧（旧排布下这条会失败）。
     */
    @Test
    fun tipIconHugsTheLabelText() {
        compose.setContent {
            MaterialTheme {
                SettingsSwitchRow(
                    icon = Lucide.Sun,
                    label = "短标签",
                    tip = "tip text",
                    value = false,
                    onToggle = {},
                )
            }
        }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        // 行是 clickable（合并语义），取未合并树的节点边界：合并节点的边界是整行。
        val label = compose.onNodeWithText("短标签", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val icon = compose.onNodeWithContentDescription("tip text", useUnmergedTree = true).getUnclippedBoundsInRoot()

        // 在文字右侧、且间隙不超过 ⓘ 触控区（28dp）的一半。
        assertTrue("ⓘ 应在文字右侧", icon.left >= label.right)
        assertTrue("ⓘ 应紧贴文字，实际间隙 ${icon.left - label.right}", icon.left - label.right < 14.dp)
        // 并且整体靠左：旧排布（标签 Expanded → ⓘ → 开关）会把它推到半屏之外。
        assertTrue("ⓘ 不该被推到行尾", icon.left < (root.right - root.left) * 0.5f)
    }

    /**
     * 用户 2026-09-12（供应商详情页）：「人家这个是否启用和多Key管理 没有图标呀」
     * —— 原版 `_iosRow`（provider_detail_page L1350-1390）只有「标签 + 开关」，
     * 没有前置图标。传 `icon = null` 时标签必须顶到行左内边距（12dp），不留 36dp
     * 图标槽；带图标时则要留出那一段。
     */
    @Test
    fun nullIconDropsTheLeadingIconGutter() {
        compose.setContent {
            MaterialTheme {
                Column {
                    SettingsSwitchRow(label = "无图标", value = false, onToggle = {})
                    SettingsSwitchRow(icon = Lucide.Sun, label = "有图标", value = false, onToggle = {})
                }
            }
        }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val without = compose.onNodeWithText("无图标", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val with = compose.onNodeWithText("有图标", useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertEquals(12.dp, without.left - root.left)
        // 带图标那行的标签被 36dp 图标位 + 12dp 间隔推到后面。
        assertEquals(60.dp, with.left - root.left)
    }
}
