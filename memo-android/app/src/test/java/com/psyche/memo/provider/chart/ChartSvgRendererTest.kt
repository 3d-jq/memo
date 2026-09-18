package com.psyche.memo.provider.chart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 渲染（纯函数）：按坐标/元素断言，跑起来不需要 Robolectric。
 *
 * 坐标是**确定**的（900×560 画布、固定内边距），所以能钉住具体数值 —— 改坏了会立刻红。
 */
class ChartSvgRendererTest {

    private val palette = ChartPalette.LIGHT

    private fun spec(json: String) =
        ChartSpec.parse(Json.parseToJsonElement(json).jsonObject)

    private fun bar() = spec(
        """{"kind":"bar","title":"7 月营收对比","unit":"单位：万元",
           "categories":["第1周","第2周"],
           "series":[{"name":"春熙路店","data":[62,78]},{"name":"天府三街店","data":[40,52]}]}""",
    )

    @Test
    fun `renders a canvas with the theme background and the title`() {
        val svg = ChartSvgRenderer.render(bar(), palette)
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.endsWith("</svg>"))
        assertTrue(svg, svg.contains("""width="900""""))
        assertTrue(svg, svg.contains("""height="560""""))
        // 卡片底色来自调色板（SVG 是位图通道，自带底才能保证明暗主题下都清楚）
        assertTrue(svg, svg.contains("""fill="#FFFFFF""""))
        assertTrue(svg, svg.contains(">7 月营收对比<"))
        assertTrue(svg, svg.contains(">单位：万元<"))
    }

    @Test
    fun `draws one bar per category and series in the categorical colors`() {
        val svg = ChartSvgRenderer.render(bar(), palette)
        // 2 分类 × 2 系列 = 4 根柱（[^>]* 才不会跨标签贪婪匹配）
        assertEquals(4, Regex("""<rect [^>]*rx="4"""").findAll(svg).count())
        assertTrue(svg, svg.contains("""fill="#185FA5"""")) // 系列 1
        assertTrue(svg, svg.contains("""fill="#0F6E56"""")) // 系列 2
        assertTrue(svg, svg.contains(">第1周<"))
        assertTrue(svg, svg.contains(">第2周<"))
    }

    @Test
    fun `bar heights are proportional to the values`() {
        val svg = ChartSvgRenderer.render(
            spec("""{"kind":"bar","categories":["a","b"],"series":[{"name":"s","data":[0,100]}]}"""),
            palette,
        )
        // 0..100 → 刻度步长 25，绘图区高度 = PLOT_BOTTOM - plotTop = 428 - 96 = 332
        val rects = Regex("""<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" rx="4"""")
            .findAll(svg).map { it.groupValues }.toList()
        assertEquals(2, rects.size)
        assertEquals("0", rects[0][4])          // 值为 0 → 没有高度
        assertEquals("332", rects[1][4])         // 值为 100 → 顶到绘图区上沿
        assertEquals("96", rects[1][2])          // y = plotTop（无标题时）
    }

    @Test
    fun `line and area and scatter use their own geometry`() {
        val line = ChartSvgRenderer.render(
            spec("""{"kind":"line","categories":["a","b","c"],"series":[{"name":"s","data":[1,2,3]}]}"""),
            palette,
        )
        assertTrue(line, line.contains("<polyline"))
        assertTrue(line, line.contains("""fill="none""""))
        assertFalse(line, line.contains("<polygon"))

        val area = ChartSvgRenderer.render(
            spec("""{"kind":"area","categories":["a","b"],"series":[{"name":"s","data":[1,2]}]}"""),
            palette,
        )
        assertTrue(area, area.contains("<polygon"))
        assertTrue(area, area.contains("""fill-opacity="0.16""""))

        val scatter = ChartSvgRenderer.render(
            spec("""{"kind":"scatter","categories":["a","b"],"series":[{"name":"s","data":[1,2]}]}"""),
            palette,
        )
        assertTrue(scatter, scatter.contains("<circle"))
        assertFalse(scatter, scatter.contains("<polyline"))
    }

    @Test
    fun `pie draws slices and percentage labels`() {
        val svg = ChartSvgRenderer.render(
            spec("""{"kind":"pie","categories":["直营","加盟"],"series":[{"name":"s","data":[75,25]}]}"""),
            palette,
        )
        assertEquals(2, Regex("""<path d="M [\d.]+ [\d.]+ L""").findAll(svg).count())
        assertTrue(svg, svg.contains(">75%<"))
        assertTrue(svg, svg.contains(">25%<"))
    }

    @Test
    fun `labels are xml escaped and the legend follows the palette`() {
        val svg = ChartSvgRenderer.render(
            spec("""{"kind":"bar","categories":["A&B"],"series":[{"name":"<x>","data":[1]}]}"""),
            palette,
        )
        assertTrue(svg, svg.contains(">A&amp;B<"))
        assertTrue(svg, svg.contains("&lt;x&gt;"))
        assertFalse(svg, svg.contains("<x>"))
    }

    @Test
    fun `all-zero data renders without NaN`() {
        val svg = ChartSvgRenderer.render(
            spec("""{"kind":"line","categories":["a","b"],"series":[{"name":"s","data":[0,0]}]}"""),
            palette,
        )
        assertFalse(svg, svg.contains("NaN"))
        assertFalse(svg, svg.contains("Infinity"))
    }

    @Test
    fun `dark palette is applied when asked`() {
        val svg = ChartSvgRenderer.render(bar(), ChartPalette.DARK)
        assertTrue(svg, svg.contains("""fill="#1C1C1B""""))
        assertTrue(svg, svg.contains("""fill="#F1EFE8""""))
    }

    /** 后加的 5 种图各画各的几何（用户 2026-09-18「尽量全满」）。 */
    @Test
    fun `the extra kinds draw their own geometry`() {
        val hbar = ChartSvgRenderer.render(
            spec("""{"kind":"hbar","categories":["一","二"],"series":[{"name":"s","data":[10,20]}]}"""),
            palette,
        )
        // 横向：一个分类一根条，且分类名在左
        assertEquals(2, Regex("""<rect [^>]*rx="4"""").findAll(hbar).count())
        assertTrue(hbar, hbar.contains(">一<"))

        val donut = ChartSvgRenderer.render(
            spec("""{"kind":"donut","categories":["a","b"],"series":[{"name":"s","data":[3,1]}]}"""),
            palette,
        )
        assertEquals(2, Regex("""<path d="M [\d.]+ [\d.]+ L""").findAll(donut).count())
        assertTrue(donut, donut.contains(">合计<"))
        assertTrue(donut, donut.contains(">4<")) // 中心 = 合计

        val funnel = ChartSvgRenderer.render(
            spec("""{"kind":"funnel","categories":["访问","注册","付费"],"series":[{"name":"s","data":[100,40,10]}]}"""),
            palette,
        )
        assertEquals(3, Regex("""<path d="M [\d.]+ [\d.]+ L""").findAll(funnel).count())
        assertTrue(funnel, funnel.contains(">访问<"))
        // 每级右侧显示「值 + 占比」
        assertTrue(funnel, funnel.contains(">100  67%<"))

        val gauge = ChartSvgRenderer.render(
            spec("""{"kind":"gauge","categories":["完成度"],"series":[{"name":"任务","data":[63,100]}]}"""),
            palette,
        )
        assertTrue(gauge, gauge.contains(">63<"))   // 当前值
        assertTrue(gauge, gauge.contains(">63%<"))  // 达成率
        assertTrue(gauge, gauge.contains(">任务<"))  // 系列名当说明

        val heatmap = ChartSvgRenderer.render(
            spec(
                """{"kind":"heatmap","categories":["上午","下午","晚上"],
                   "series":[{"name":"微信","data":[10,40,80]},{"name":"抖音","data":[60,20,30]}]}""",
            ),
            palette,
        )
        // 2 行 × 3 列
        assertEquals(6, Regex("""<rect [^>]*rx="4"""").findAll(heatmap).count())
        assertTrue(heatmap, heatmap.contains(">微信<"))
        assertTrue(heatmap, heatmap.contains(">上午<"))
        assertTrue(heatmap, heatmap.contains(">低<"))
        assertTrue(heatmap, heatmap.contains(">高<"))
        assertFalse(heatmap, heatmap.contains("NaN"))
    }

    @Test
    fun `nice scale picks readable steps`() {
        fun assertScale(min: Double, max: Double, lo: Double, hi: Double, step: Double) {
            val scale = ChartSvgRenderer.niceScale(min, max)
            assertEquals(lo, scale.first, 1e-9)
            assertEquals(hi, scale.second, 1e-9)
            assertEquals(step, scale.third, 1e-9)
        }
        assertScale(0.0, 100.0, 0.0, 100.0, 25.0)
        assertScale(0.0, 2.5, 0.0, 3.0, 1.0)
        // 0.44 → 步长 0.2、上限 0.6（raw 0.11 落到 0.2 档；浮点用容差比）
        assertScale(0.0, 0.44, 0.0, 0.6, 0.2)
        assertScale(-1.5, 1.5, -2.0, 2.0, 1.0)
        // 全零也不会算出非法刻度
        assertScale(0.0, 0.0, 0.0, 1.0, 0.25)
    }
}
