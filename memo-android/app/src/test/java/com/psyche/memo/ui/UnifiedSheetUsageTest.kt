package com.psyche.memo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用户 2026-09-14：「添加 skill 这个 sheet 你没有用我们统一那个样式呀 不用添加技能这个
 * 标题…记忆列表界面里的全部范围、全部类型这个 sheet 也没用我们那个统一的 sheet 样式」。
 *
 * 这条锁住「这些面板必须走统一件」——即渲染出来的是 [MemoSheetOptionRow] 的卡片行
 * （`SHEET_OPTION_TAG`、精确 48dp、`spacedBy(8.dp)`），而不是各自手撸的行。
 * 统一样式的几何本身由 `SheetStyleTest` 锁着，这里只管「用了没有」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedSheetUsageTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun actionSheetRendersUnifiedOptionRowsWithEightDpSpacing() {
        compose.setContent {
            MaterialTheme {
                ActionSheet(
                    onDismiss = {},
                    actions = listOf(
                        SheetAction(Lucide.Plus, "Add manually") {},
                        SheetAction(Lucide.Trash2, "Delete", destructive = true) {},
                    ),
                )
            }
        }

        val rows = compose.onAllNodesWithTag(SHEET_OPTION_TAG, useUnmergedTree = true)
        rows.assertCountEquals(2)
        val first = rows[0].getUnclippedBoundsInRoot()
        val second = rows[1].getUnclippedBoundsInRoot()
        assertEquals("统一件是 48dp 卡行", 48.dp, first.height)
        assertEquals("统一件用 spacedBy(8.dp)", 8.dp, second.top - first.bottom)
    }

    /** 危险项也只是同一颗组件的着色差异，不该另起一行样式。 */
    @Test
    fun destructiveActionStillUsesTheUnifiedRow() {
        compose.setContent {
            MaterialTheme {
                ActionSheet(
                    onDismiss = {},
                    actions = listOf(SheetAction(Lucide.Trash2, "Delete skill", destructive = true) {}),
                )
            }
        }
        compose.onAllNodesWithTag(SHEET_OPTION_TAG, useUnmergedTree = true).assertCountEquals(1)
    }
}
