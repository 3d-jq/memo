package com.psyche.memo.ui.slider

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MemoSlider` 的几何与交互守卫（纯逻辑在 `core:ui` 的 `MemoSliderTest`，这里管 Compose 侧）。
 *
 * 用户 2026-09-16 拍板用自定义控件取代 M3 原生 `Slider`，前提是**只换皮肤、不改行为**。
 * 这里钉两件事：
 *  - 几何照原版 `SfSliderThemeData` 取值：控件高 50dp、圆钮 20dp、圆心落在轨道中心线
 *    （胶囊区 20dp + 轨道区一半 15dp = 距顶 35dp，故圆钮顶边 25dp）；
 *  - 行为与 M3 一致：拖动吸附到 `steps + 1` 等分点、抬手回调一次
 *    `onValueChangeFinished`、数值胶囊只在拖动中出现、禁用态不吃手势。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoSliderUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `thumb and track keep the original SfSlider geometry`() {
        renderSlider()
        val bounds = compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).getUnclippedBoundsInRoot()
        val width = bounds.right.value - bounds.left.value
        val height = bounds.bottom.value - bounds.top.value
        assertEquals("圆钮 20dp", 20f, width, 0.5f)
        assertEquals("圆钮 20dp", 20f, height, 0.5f)
        // 圆心 = 25 + 10 = 35dp（轨道中心线）；容器从 (0,0) 起，故圆钮顶边 25dp。
        assertEquals("圆钮圆心落在轨道中心线", 25f, bounds.top.value, 0.5f)
    }

    @Test
    fun `dragging snaps to steps and reports once finished`() {
        var value = -1f
        var finished = 0
        renderSlider(
            onValueChange = { value = it },
            onValueChangeFinished = { finished++ },
        )
        // 按住圆钮往右拖 40px：每一步都吸附到 0.1 的倍数（steps = 9 → 10 等分）。
        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
        }
        assertEquals("抬手前不该回调 onValueChangeFinished", 0, finished)
        assertTrue("拖动中必须已经上报过值", value in 0f..1f)
        val snapped = Math.round(value * 10f) / 10f
        assertEquals("值必须落在 steps 的等分点上", snapped, value, 0.0001f)

        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput { up() }
        assertEquals("抬手回调一次", 1, finished)
    }

    @Test
    fun `value capsule only shows while dragging`() {
        renderSlider(valueLabel = { "42%" })
        compose.onNode(hasText("42%")).assertDoesNotExist()

        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
        }
        compose.onNode(hasText("42%")).assertExists()

        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput { up() }
        compose.onNode(hasText("42%")).assertDoesNotExist()
    }

    @Test
    fun `disabled slider ignores gestures`() {
        var value = 0.25f
        renderSlider(enabled = false, onValueChange = { value = it })
        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
            up()
        }
        assertEquals("禁用态不该改值", 0.25f, value, 0.0001f)
    }

    private fun renderSlider(
        enabled: Boolean = true,
        valueLabel: ((Float) -> String)? = { "v" },
        onValueChange: (Float) -> Unit = {},
        onValueChangeFinished: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                // 固定宽度视口：拖动的像素位移才可预期（与 MarkdownTableLayoutTest 同一手法）。
                Box(modifier = Modifier.width(300.dp)) {
                    var value by remember { mutableFloatStateOf(0.5f) }
                    MemoSlider(
                        value = value,
                        onValueChange = { v ->
                            value = v
                            onValueChange(v)
                        },
                        enabled = enabled,
                        valueRange = 0f..1f,
                        steps = 9,
                        onValueChangeFinished = onValueChangeFinished,
                        valueLabel = valueLabel,
                    )
                }
            }
        }
    }
}
