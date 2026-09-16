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
import androidx.compose.ui.test.moveTo
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
 * 这里钉三件事：
 *  - 几何照原版 `SfSliderThemeData` 取值：控件高 50dp、圆钮 20dp、圆心落在轨道中心线
 *    （胶囊区 20dp + 轨道区一半 15dp = 距顶 35dp，故圆钮顶边 25dp）；
 *  - **能自由滑动**（用户 2026-09-16 第二句：「好看是好看 但是怎么不能自由滑动呀」）：
 *    按下即跟手、全程逐步跟随、抬手回调一次；纵向意图（划过滑块去滚 sheet）要**回滚**；
 *  - 数值胶囊只在拖动中出现、禁用态不吃手势。
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

    /**
     * 无级滑动：从轨道左端按下（按下即跟手）→ **每步只走几个像素**（真机每帧的位移量）
     * 连续拖到中段，值必须一路单调跟随。
     *
     * 这条是「怎么是靠点击来的呀」那个 bug 的回归判据：上一版 slop 判据写成了**每帧位移**，
     * 每帧几像素永远超不过 `touchSlop`，于是只有按下那一下会改值 —— 而之前的测试每步跳 23px，
     * 正好越过 slop，所以没抓到。
     */
    @Test
    fun `drag follows the finger frame by frame`() {
        var value = -1f
        renderSlider(steps = 0, onValueChange = { value = it })
        val slider = compose.onNodeWithTag(MEMO_SLIDER_TAG)

        slider.performTouchInput { down(Offset(100f, centerY)) }
        val afterDown = value
        assertEquals("按下即跟手（x=100 → ≈0.32）", 0.32f, afterDown, 0.03f)

        // 每步 4px（远小于 slop）：必须每步都跟。
        var previous = afterDown
        var monotonic = true
        var movedEveryStep = true
        for (step in 1..30) {
            slider.performTouchInput { moveBy(Offset(4f, 0f)) }
            if (value < previous - 0.0001f) monotonic = false
            if (value - previous < 0.0001f) movedEveryStep = false
            previous = value
        }
        assertTrue("每一帧都要跟手（不是只有越过 slop 才跳一次）", movedEveryStep)
        assertTrue("拖动过程必须单调", monotonic)
        // 100 + 30*4 = 220 → (220-10)/280 ≈ 0.75
        assertEquals("终点要落在手指处", 0.75f, value, 0.04f)

        slider.performTouchInput { up() }
    }

    /** 划过滑块去滚 sheet：纵向意图必须让位，且把按下时改掉的值**回滚**。 */
    @Test
    fun `vertical drag gives way and reverts the value`() {
        var value = -1f
        var finished = 0
        renderSlider(
            steps = 0,
            onValueChange = { value = it },
            onValueChangeFinished = { finished++ },
        )
        val slider = compose.onNodeWithTag(MEMO_SLIDER_TAG)

        slider.performTouchInput { down(Offset(10f, centerY)) }
        assertEquals("按下把值挪到手指处", 0f, value, 0.03f)

        // 纵向为主的一次滑动：让给外面（滚动），值回滚到按下之前。
        slider.performTouchInput { moveBy(Offset(4f, 120f)) }
        slider.performTouchInput { up() }
        assertEquals("纵向滑动要回滚到按下前的值", 0.5f, value, 0.001f)
        assertEquals("抬手仍然回调一次", 1, finished)
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
        compose.onNodeWithTag(MEMO_SLIDER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
            up()
        }
        assertEquals("禁用态不该改值", 0.25f, value, 0.0001f)
    }

    private fun renderSlider(
        enabled: Boolean = true,
        steps: Int = 9,
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
                        steps = steps,
                        onValueChangeFinished = onValueChangeFinished,
                        valueLabel = valueLabel,
                    )
                }
            }
        }
    }
}
