package com.psyche.memo.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成中指示器的形状守卫（2026-09-28 改成应用图标那 8 向放射线 + 绽放动画）。
 *
 * 为什么是「读源码」而不是跑 Robolectric：这个指示器是纯绘制态的 8 条
 * `drawLine`，没有可断言的状态输出；真正会悄悄退化的是**几何比例**和
 * **相位读取位置**——后者一挪出绘制 lambda，逐帧就会重组所在的消息行，
 * 那条 skippable 是有守卫测试的（`ChatRowRecompositionTest`），不能让动画破掉。
 * 所以这两件事用源码断言钉住，形状本身靠上面的肉眼比对。
 */
class GenerationActivityBurstTest {

    private val source = File(
        "src/main/java/com/psyche/memo/ui/chat/GenerationActivityBurst.kt",
    ).readText()

    @Test
    fun eightRaysWithHollowCentre() {
        assertTrue(
            "必须是 8 向放射线（应用图标本体就是 8 条）",
            source.contains("private const val RAY_COUNT = 8"),
        )
        assertTrue(
            "中心必须留白 —— 图标中间那个洞是识别特征，填满就不像了",
            source.contains("private const val RAY_INNER_RADIUS = 2.1f"),
        )
        assertTrue(
            "射线外半径要留边距，不能顶到视口边缘",
            source.contains("private const val RAY_OUTER_RADIUS = 11f"),
        )
    }

    @Test
    fun bloomAnimatesLengthNotWholeGlyphScale() {
        assertTrue(
            "绽放靠射线长度变化（LENGTH_MIN），不是整体 scale —— 整体缩放会让中心洞一起缩",
            source.contains("private const val LENGTH_MIN = 0.52f"),
        )
        assertTrue(
            "线宽也要跟着相位变化，否则只变长不变粗，像根针",
            source.contains("private const val THICKNESS_MIN = 0.80f"),
        )
        assertTrue(
            "外扩与回收时长必须不同：等时长正反运动会读成弹簧抖动而不是绽放",
            source.contains("private const val EXPAND_MILLIS = 700") &&
                source.contains("private const val RETRACT_MILLIS = 1_100"),
        )
    }

    @Test
    fun phaseIsReadInDrawScopeSoTheMessageRowStaysSkippable() {
        val canvasIndex = source.indexOf("Canvas(")
        val readIndex = source.indexOf("phase.value")
        assertTrue("找不到 Canvas 绘制块", canvasIndex > 0)
        assertTrue(
            "相位必须在绘制 lambda 里读；挪到组合期读取会让消息行每帧重组",
            readIndex > canvasIndex,
        )
    }

    @Test
    fun colourStillFollowsThemePrimary() {
        assertTrue(
            "颜色必须跟随主题 primary，不能贴位图（贴图就丢主题了）",
            source.contains("color: Color = MaterialTheme.colorScheme.primary"),
        )
    }

    @Test
    fun oldStarComponentIsGone() {
        assertTrue(
            "旧四角星组件应当已删除，不留兼容层",
            !File("src/main/java/com/psyche/memo/ui/chat/GenerationActivityStar.kt").exists(),
        )
    }
}
