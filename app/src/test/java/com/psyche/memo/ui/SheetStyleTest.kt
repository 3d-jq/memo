package com.psyche.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.psyche.memo.common.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * sheet 样式统一（用户 2026-09-12）：
 *  1. 拖柄一律自绘（`MemoSheetHandle`：40×4、上方 8dp），不再用 Material 原生把手；
 *  2. 选项面板一律「一张卡一个选项」（`MemoSheetOptionRow`：48dp 高、r14、项间距 8、
 *     选中带 ✓），不再用横线隔开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetStyleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun optionRowsAre48dpCardsWith8dpGapsAndACheckWhenSelected() {
        compose.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MemoSheetHandle(trailingGap = 0.dp)
                    MemoSheetOptionRow(label = "选中的", selected = true, onClick = {})
                    MemoSheetOptionRow(label = "没选中", selected = false, onClick = {})
                }
            }
        }
        // 选项行是 clickable（合并语义），tag 要按未合并树取。
        compose.onNodeWithTag(SHEET_HANDLE_TAG, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag(SHEET_OPTION_TAG, useUnmergedTree = true).assertCountEquals(2)
        // 选中项才带勾。
        compose.onAllNodesWithTag(SHEET_OPTION_CHECK_TAG, useUnmergedTree = true).assertCountEquals(1)

        val first = compose.onAllNodesWithTag(SHEET_OPTION_TAG, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertEquals(48.dp, first.bottom - first.top)
        val second = compose.onAllNodesWithTag(SHEET_OPTION_TAG, useUnmergedTree = true)[1].getUnclippedBoundsInRoot()
        // 卡片间距 8dp（横线列表样式下行距会明显更大）。
        assertEquals(8.dp, second.top - first.bottom)
    }

    /** 转换后的选择面板：语言 sheet 应有一根手绘拖柄 + 四个卡片选项。 */
    @Test
    fun languageSheetUsesTheUnifiedSheetChrome() {
        compose.setContent {
            MaterialTheme {
                LanguageSheet(current = AppLocale.SYSTEM, onSelect = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag(SHEET_HANDLE_TAG, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag(SHEET_OPTION_TAG, useUnmergedTree = true).assertCountEquals(4)
        compose.onAllNodesWithTag(SHEET_OPTION_CHECK_TAG, useUnmergedTree = true).assertCountEquals(1)
    }
}
