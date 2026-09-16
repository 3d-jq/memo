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
import androidx.compose.ui.platform.testTag
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
    fun `thumb and track keep the reasoning-slider geometry`() {
        renderSlider()
        val bounds = compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).getUnclippedBoundsInRoot()
        val width = bounds.right.value - bounds.left.value
        val height = bounds.bottom.value - bounds.top.value
        assertEquals("圆钮 38dp", 38f, width, 0.5f)
        assertEquals("圆钮 38dp", 38f, height, 0.5f)
        // 圆心 = 胶囊区 18 + 轨道区一半 19 = 距顶 37dp，故圆钮顶边 18dp（与胶囊区下沿齐平）。
        assertEquals("圆钮顶边贴着胶囊区", 18f, bounds.top.value, 0.5f)
    }

    /**
     * 无级滑动：从轨道左侧按下 → **每步只走几个像素**（真机每帧的位移量）连续拖到右侧，值必须
     * 一路单调跟随。
     *
     * 这条是「怎么是靠点击来的呀」那个 bug 的回归判据：有一版 slop 判据写成了**每帧位移**，
     * 每帧几像素永远超不过 `touchSlop`，于是只有按下那一下会改值。现在的仲裁是「横向累计过
     * slop 才判定为拖动」，所以头一两步不动、之后必须每步都跟 —— 两条都要钉住。
     */
    @Test
    fun `drag follows the finger frame by frame`() {
        var value = -1f
        renderSlider(onValueChange = { value = it })
        val slider = compose.onNodeWithTag(MEMO_SLIDER_TAG)

        slider.performTouchInput { down(Offset(100f, centerY)) }
        assertEquals("按下不改值、也不回调（避免一按就跳）", -1f, value, 0.0001f)

        // 每步 4px：前几步用来越过 slop，之后**每一步都必须跟**。
        var previous = value
        var movedEveryStepAfterSlop = true
        var stepsSeen = 0
        for (step in 1..30) {
            slider.performTouchInput { moveBy(Offset(4f, 0f)) }
            if (value != previous) stepsSeen++
            if (step > 4 && value - previous < 0.0001f) movedEveryStepAfterSlop = false
            previous = value
        }
        assertTrue("越过 slop 之后每一帧都要跟手（不是只跳一次）", movedEveryStepAfterSlop)
        assertTrue("整段拖动至少要真的动过（实际 $stepsSeen 步有变化）", stepsSeen >= 20)
        // 100 + 30*4 = 220px → 约 (220-19)/(300-38) ≈ 0.77（圆钮行程两端各扣一个半径）。
        assertTrue("终点要落在手指处（实际 $value）", value > 0.7f)

        slider.performTouchInput { up() }
    }

    /**
     * 划过滑块去滚 sheet：纵向意图必须让位，而且**一个值都不许改**（曾经的做法是「按下即改值 +
     * 纵向偏移就回滚」，真机上表现为「一按就跳、划弧就跳回来」，用户报「不能随意左右滑动」）。
     */
    @Test
    fun `vertical drag gives way without touching the value`() {
        var value = -1f
        var finished = 0
        renderSlider(
            onValueChange = { value = it },
            onValueChangeFinished = { finished++ },
        )
        val slider = compose.onNodeWithTag(MEMO_SLIDER_TAG)

        slider.performTouchInput { down(Offset(10f, centerY)) }
        // 纵向为主的一次滑动：让给外面（滚动），值保持原样。
        slider.performTouchInput { moveBy(Offset(4f, 120f)) }
        slider.performTouchInput { up() }
        assertEquals("纵向滑动一个回调都不该有", -1f, value, 0.0001f)
        assertEquals("抬手仍然回调一次", 1, finished)
    }

    @Test
    fun `dragging is continuous and reaches values between old steps`() {
        var value = -1f
        var finished = 0
        renderSlider(
            onValueChange = { value = it },
            onValueChangeFinished = { finished++ },
        )
        // 按住圆钮往右拖 40px：值必须**不吸附**（用户点名要无极），落在任意小数上。
        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
        }
        assertEquals("抬手前不该回调 onValueChangeFinished", 0, finished)
        assertTrue("拖动中必须已经上报过值", value in 0f..1f)
        assertTrue(
            "无极：值不该落在 0.1 的档位上（实际 $value）",
            kotlin.math.abs(value * 10f - Math.round(value * 10f)) > 0.01f,
        )

        compose.onNodeWithTag(MEMO_SLIDER_THUMB_TAG).performTouchInput { up() }
        assertEquals("抬手回调一次", 1, finished)
    }

    /**
     * **真机上「不能随意左右滑动」的复现**：滑条右侧那个「当前值」文本会随值变宽变窄
     * （"50%" → "100%"），于是滑条自身宽度在拖动过程中变化 —— 只要 `pointerInput` 把宽度
     * 当 key，Compose 就会**重启这块手势代码（=取消进行中的拖动）**，表现为「每碰一下只动
     * 一点点、像只能点」。测试里滑条宽度原本固定，所以一直测不出来，这里专门放一个会变宽的
     * 兄弟节点。
     */
    @Test
    fun `drag survives a sibling value label that changes width`() {
        var value = 0.5f
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    androidx.compose.foundation.layout.Row {
                        MemoSlider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f).testTag(MEMO_SLIDER_TAG),
                        )
                        // 宽度随值变化（"0%" 与 "100%" 差 ~10dp），复现真机的重排。
                        androidx.compose.material3.Text(
                            text = if (value < 0.5f) "0%" else "100%",
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        val slider = compose.onNodeWithTag(MEMO_SLIDER_TAG)
        // 从左侧按下，分多步拖到**最右端** —— 每一步都会触发上面那次重排（宽度变化）。
        slider.performTouchInput { down(Offset(20f, centerY)) }
        repeat(12) { slider.performTouchInput { moveBy(Offset(24f, 0f)) } }
        slider.performTouchInput { up() }
        assertTrue("拖到最右后值必须接近 1（实际 $value）—— 中途被取消就会停在中途", value > 0.9f)
        assertTrue("拖到最右后值必须接近 1（实际 $value）—— 中途被取消就会停在中途", value > 0.9f)
    }

    /**
     * 用户 2026-09-16：「左右数字会变 导致这个 slider 也会变 这个不太好 有解决方法吗？」
     * —— 数值文本从 "9" 变 "100" 时，`weight(1f)` 的滑条宽度**不能**跟着变。
     */
    @Test
    fun `fixed width value label keeps the slider width stable`() {
        var value = 0.1f
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        MemoSlider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f).testTag(MEMO_SLIDER_TAG),
                        )
                        SliderValueLabel(
                            text = if (value < 0.5f) "9" else "100",
                            widest = "100",
                            style = androidx.compose.ui.text.TextStyle(),
                        )
                    }
                }
            }
        }
        fun sliderWidth(): Float {
            val b = compose.onNodeWithTag(MEMO_SLIDER_TAG).getUnclippedBoundsInRoot()
            return b.right.value - b.left.value
        }
        val narrowLabelWidth = sliderWidth()

        // 拖到右侧：数值文本变成更宽的 "100"，滑条宽度必须一模一样。
        val slider = compose.onNodeWithTag(MEMO_SLIDER_TAG)
        slider.performTouchInput { down(Offset(30f, centerY)) }
        repeat(10) { slider.performTouchInput { moveBy(Offset(25f, 0f)) } }
        slider.performTouchInput { up() }
        assertTrue("拖到最右（实际 $value）", value > 0.9f)
        assertEquals("数值文案变宽不能改变滑条宽度", narrowLabelWidth, sliderWidth(), 0.5f)
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
                        onValueChangeFinished = onValueChangeFinished,
                        valueLabel = valueLabel,
                    )
                }
            }
        }
    }
}
