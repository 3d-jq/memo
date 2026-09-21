package com.psyche.memo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 标签行的**版面**守卫：上下文消息那组档位（1/64/128/256/512/1024/2048/4096）渲染出来之后，
 * 任意两个可见标签的横条不能相交（用户 2026-09-16「下面那个数字显示有重叠」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantParamLabelRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val contextStops = listOf(1.0, 64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0, 4096.0)

    private fun boundsOf(text: String): DpRect =
        compose.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test
    fun `context message labels never overlap each other`() {
        compose.setContent {
            MaterialTheme {
                // 宽度取 sheet 里标签行的真实量级（360dp 屏 − 左右 16 − 数值胶囊与间距）。
                Box(modifier = Modifier.width(240.dp)) {
                    SliderLabelRow(stops = contextStops, range = 1f..4096f)
                }
            }
        }
        // 被去掉的那几个（与 1 挤在一起）不该存在。
        listOf("64", "128", "256").forEach { gone ->
            assertTrue(
                "挤在一起的标签 $gone 应当被去掉",
                runCatching { compose.onNodeWithText(gone).assertExists() }.isFailure,
            )
        }
        val kept = listOf("1", "512", "1024", "2048", "4096").map { it to boundsOf(it) }
        kept.zipWithNext().forEach { (left, right) ->
            val (leftText, leftBounds) = left
            val (rightText, rightBounds) = right
            assertTrue(
                "「$leftText」与「$rightText」重叠了（${leftBounds.right.value} > ${rightBounds.left.value}）",
                leftBounds.right.value <= rightBounds.left.value + 0.5f,
            )
        }
    }
}
