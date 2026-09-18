package com.psyche.memo.provider.chart

import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.provider.LocalToolExecutors
import com.psyche.memo.provider.generation.MEDIA_TOOL_NAMES
import com.psyche.memo.ui.BuiltInToolCatalog
import com.psyche.memo.ui.BuiltInToolGroup
import com.psyche.memo.ui.MemoryPromptLang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `render_visual` —— **一个工具**（用户 2026-09-18「为什么不能一个工具渲染各种呀」）：
 * 结构化 kind 由我们画，`kind = "svg"` 时模型手写并走消毒管线；两条路都落 SVG、都进消息。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class VisualToolsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun call(json: String): JsonObject = parse(VisualTools.execute(context, args(json), ChartPalette.LIGHT))

    private fun svgCall(svg: String): JsonObject = call(
        buildJsonObject {
            put("kind", JsonPrimitive(VisualTools.KIND_SVG))
            put("svg", JsonPrimitive(svg))
        }.toString(),
    )

    private val bar = """
        {"kind":"bar","title":"7 月营收对比","unit":"单位：万元",
         "categories":["第1周","第2周"],
         "series":[{"name":"春熙路店","data":[62,78]},{"name":"天府三街店","data":[40,52]}]}
    """

    private val flowchart = """<svg viewBox="0 0 800 400" font-family="sans-serif">""" +
        """<rect x="60" y="60" width="160" height="56" rx="10" fill="#E6F1FB" stroke="#185FA5"/>""" +
        """<text x="140" y="94" font-size="20" text-anchor="middle" fill="#0C447C">读取数据</text>""" +
        """<path d="M 220 88 L 300 88" stroke="#185FA5" stroke-width="2" fill="none"/></svg>"""

    // ------------------------------------------------------------------ 结构化图

    @Test
    fun `a data kind renders a chart and reports the path`() {
        val result = call(bar)
        assertEquals("chart_result", result["type"]?.jsonPrimitive?.content)
        assertEquals("bar", result["kind"]?.jsonPrimitive?.content)
        val path = result["paths"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(path, path.endsWith(".svg"))
        assertTrue(File(path).readText().startsWith("<svg "))
    }

    @Test
    fun `a broken spec comes back as a tool error the model can act on`() {
        val badKind = call("""{"kind":"radar","series":[{"name":"a","data":[1]}]}""")
        assertEquals("tool_error", badKind["type"]?.jsonPrimitive?.content)
        assertEquals("chart_invalid_spec", badKind["error"]?.jsonPrimitive?.content)
        assertTrue(badKind["message"]!!.jsonPrimitive.content.contains("scatter"))

        val noSeries = call("""{"kind":"bar","categories":["a"]}""")
        assertEquals("chart_invalid_spec", noSeries["error"]?.jsonPrimitive?.content)

        val noKind = call("""{"series":[{"name":"a","data":[1]}]}""")
        assertEquals("visual_invalid", noKind["error"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------ 手写 SVG

    @Test
    fun `kind svg writes a sanitized drawing with an opaque white background`() {
        val result = svgCall(flowchart)
        assertEquals("svg_result", result["type"]?.jsonPrimitive?.content)
        val path = result["paths"]!!.jsonArray.single().jsonPrimitive.content
        val text = File(path).readText()
        assertTrue(text, text.startsWith("<svg "))
        assertTrue(text, text.contains("""fill="#FFFFFF""""))
        assertTrue(text.indexOf("""fill="#FFFFFF"""") < text.indexOf("读取数据"))
        assertEquals(2f, result["aspect"]!!.jsonPrimitive.content.toFloat(), 0.001f)
        // 自闭合标签不能多出结束标签（2026-09-18「渲染不出来」的根因）
        assertFalse(text, text.contains("/></rect>"))
        assertFalse(text, text.contains("/></path>"))
    }

    @Test
    fun `script injected by the model never reaches the file`() {
        val result = svgCall(
            """<svg viewBox="0 0 10 10"><script>fetch('//evil')</script><rect width="5" height="5"/></svg>""",
        )
        val text = File(result["paths"]!!.jsonArray.single().jsonPrimitive.content).readText()
        assertFalse(text, text.contains("script"))
        assertFalse(text, text.contains("evil"))
    }

    @Test
    fun `svg mode without a drawing or with a broken one is a tool error`() {
        val missing = call("""{"kind":"svg"}""")
        assertEquals("svg_invalid", missing["error"]?.jsonPrimitive?.content)

        val noViewBox = svgCall("""<svg><rect width="5" height="5"/></svg>""")
        assertEquals("svg_invalid", noViewBox["error"]?.jsonPrimitive?.content)
        assertTrue(
            noViewBox["message"]!!.jsonPrimitive.content,
            noViewBox["message"]!!.jsonPrimitive.content.contains("viewBox"),
        )
    }

    @Test
    fun `background is injected right after the root tag and follows the theme`() {
        val light = VisualTools.withBackground(
            """<svg viewBox="0 0 10 10"><rect/></svg>""",
            ChartPalette.LIGHT.background,
        )
        assertTrue(light.startsWith("""<svg viewBox="0 0 10 10"><rect x="0" y="0""""))
        assertTrue(light.contains("""fill="#FFFFFF""""))
        assertTrue(light.endsWith("<rect/></svg>"))

        // 暗色主题 → 深色底（不再是死白，用户 2026-09-18「暗色模式这个 svg 怎么不跟着暗色呀」）
        val dark = VisualTools.withBackground(
            """<svg viewBox="0 0 10 10"><rect/></svg>""",
            ChartPalette.DARK.background,
        )
        assertFalse(dark.contains("""fill="#FFFFFF""""))
        assertTrue(dark.contains("""fill="#1C1C1B""""))
    }

    // ------------------------------------------------------------------ 接线与配色

    @Test
    fun `one tool is wired through every gate`() {
        val entry = BuiltInToolCatalog.entries(MemoryPromptLang.zh)
            .firstOrNull { it.name == VisualTools.TOOL_NAME }
        assertTrue("render_visual 应该出现在内置工具目录里", entry != null)
        assertEquals(BuiltInToolGroup.LOCAL, entry!!.group)
        assertTrue(VisualTools.TOOL_NAME in BuiltInToolCatalog.LocalToolNames.all)
        assertTrue(VisualTools.TOOL_NAME in LocalToolExecutors.EXECUTABLE)
        assertTrue(VisualTools.TOOL_NAME in MEDIA_TOOL_NAMES)
        // 合并之后不该再有第二个入口
        assertFalse(VisualTools.TOOL_NAME == "render_chart")
        assertFalse(VisualTools.TOOL_NAME == "render_svg")
    }

    @Test
    fun `the definition exposes one kind enum that also carries the svg fallback`() {
        val fn = VisualTools.DEFINITION["function"]!!.jsonObject
        assertEquals(VisualTools.TOOL_NAME, fn["name"]?.jsonPrimitive?.content)
        val parameters = fn["parameters"]!!.jsonObject
        val properties = parameters["properties"]!!.jsonObject
        val kinds = properties["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(ChartSpec.Kind.entries.map { it.wireName } + VisualTools.KIND_SVG, kinds)
        assertTrue(properties.containsKey("svg"))
        assertEquals(
            listOf("kind"),
            parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // 描述里要点明「数据图别用手写」
        assertTrue(
            VisualTools.DESCRIPTION,
            VisualTools.DESCRIPTION.contains("do not hand-draw a data chart"),
        )
    }

    /**
     * 结果必须**明说成功**：原来只回 paths，模型找不到成功标识就跟用户说「绘制失败」，
     * 而图其实已经渲染出来了（用户 2026-09-18 报的就是这个）。
     */
    @Test
    fun `results state success explicitly`() {
        val chart = call(bar)
        assertEquals("ok", chart["status"]?.jsonPrimitive?.content)
        assertEquals(true, chart["rendered"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(
            chart["note"]!!.jsonPrimitive.content,
            chart["note"]!!.jsonPrimitive.content.contains("added to the conversation"),
        )

        val svg = svgCall(flowchart)
        assertEquals("ok", svg["status"]?.jsonPrimitive?.content)
        assertTrue(
            svg["note"]!!.jsonPrimitive.content,
            svg["note"]!!.jsonPrimitive.content.contains("do not tell the user it failed"),
        )
    }

    /** 图表配色走 App 主题的完整解析（含「原样表面」预设通道），不再只读预设 id + 明暗。 */
    @Test
    fun `the chart palette goes through the app theme pipeline`() {
        val scheme = VisualTools.chartSchemeFor(
            context = context,
            palette = com.psyche.memo.ui.ThemeState.resolvePalette(),
            dark = true,
            pureBackground = false,
            layeredSurfaces = false,
            dynamicColor = false,
        )
        val chart = chartPaletteOf(scheme, dark = true)
        assertEquals(
            scheme.surfaceBright.toArgb().toLong() and 0xFFFFFFFFL,
            chart.background,
        )
        assertEquals(ChartPalette.DARK_CATEGORICAL, chart.series)
    }

    @Test
    fun `palette follows the theme surfaces and keeps categorical series colours`() {
        val lightScheme = androidx.compose.material3.lightColorScheme()
        val light = chartPaletteOf(lightScheme, dark = false)
        assertEquals(
            lightScheme.surfaceBright.toArgb().toLong() and 0xFFFFFFFFL,
            light.background,
        )
        assertEquals(ChartPalette.CATEGORICAL, light.series)

        val dark = chartPaletteOf(androidx.compose.material3.darkColorScheme(), dark = true)
        assertEquals(ChartPalette.DARK_CATEGORICAL, dark.series)
    }

    @Test
    fun `svg products are shown as a card not a thumbnail`() {
        assertTrue(
            com.psyche.memo.ui.chat.isChartAttachment(
                com.psyche.memo.data.model.ImagePart(uri = "/x/gen_1.svg", mime = ChartSvgRenderer.MIME),
            ),
        )
        assertFalse(
            com.psyche.memo.ui.chat.isChartAttachment(
                com.psyche.memo.data.model.ImagePart(uri = "/x/a.png", mime = "image/png"),
            ),
        )
    }
}
