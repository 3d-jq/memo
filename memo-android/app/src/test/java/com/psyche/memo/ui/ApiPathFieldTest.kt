package com.psyche.memo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * API 路径 combobox（**用户 2026-09-12 二次点名**：「把这个 api 路径还原样式吧，
 * 把这个 sheet 改成点击出来那个 combobox 来选择」）：
 *
 * - 字段里显示当前路径（还原原版 `_inputRow` 的观感），且与同屏输入框同宽同高；
 * - 菜单默认收起，点字段才弹出三个选项（选项名 + 路径，选中项变色不打勾）；
 * - 选中即回调该选项（详情页/添加页据此写 chatPath + useResponseApi）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiPathFieldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapOpensTheComboboxAndSelectionReportsThePath() {
        var selected: ApiPathOption? = null
        compose.setContent {
            MaterialTheme {
                ApiPathField(
                    label = "API 路径",
                    chatPath = "/chat/completions",
                    useResponseApi = null,
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText("API 路径").assertIsDisplayed()
        compose.onNodeWithText("/chat/completions").assertIsDisplayed()
        // 收起状态：选项名不可见。
        compose.onNodeWithText("Anthropic Messages").assertDoesNotExist()

        compose.onNodeWithText("/chat/completions").performClick()

        compose.onNodeWithText("Anthropic Messages").assertIsDisplayed()
        compose.onNodeWithText("Responses").assertIsDisplayed()
        compose.onNodeWithText("/responses").assertIsDisplayed()

        compose.onNodeWithText("Responses").performClick()

        assertEquals("/responses", selected?.path)
        assertEquals(true, useResponseApiFor(selected!!.path))
    }

    @Test
    fun fieldKeepsTheSameGeometryAsTheSiblingInputs() {
        compose.setContent {
            MaterialTheme {
                Column {
                    InputRow("API Key", "sk-test") {}
                    ApiPathField(
                        label = "API 路径",
                        chatPath = "/chat/completions",
                        useResponseApi = null,
                        onSelect = {},
                    )
                }
            }
        }
        // 用户 2026-09-12：「这个 api 这个输入框大小样式和名称和 baseUrl 这个样式
        // 不一样大小了 统一下嘛」—— 与同屏输入框同宽同高（同一个 M3 外壳）。
        val input = compose.onNodeWithTag(PROVIDER_INPUT_FIELD_TAG).getUnclippedBoundsInRoot()
        val path = compose.onNodeWithTag(API_PATH_FIELD_TAG).getUnclippedBoundsInRoot()
        assertEquals(input.bottom - input.top, path.bottom - path.top)
        assertEquals(input.left, path.left)
        assertEquals(input.right, path.right)
    }

    @Test
    fun unlistedStoredPathIsShownVerbatim() {
        var selected: ApiPathOption? = null
        compose.setContent {
            MaterialTheme {
                ApiPathField(
                    label = "API 路径",
                    chatPath = "/v1/chat/completions",
                    useResponseApi = null,
                    onSelect = { selected = it },
                )
            }
        }
        // 自定义路径（老数据/手填）原样显示，且不会把任一项标成选中。
        compose.onNodeWithText("/v1/chat/completions").assertIsDisplayed()
        assertNull(selected)
    }
}
