package com.psyche.memo.provider.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 模型手写 SVG 的消毒/校验（`render_svg`）。这是**安全边界**：脚本、事件属性、
 * `<foreignObject>`、外部引用一个都不能漏，否则「静态图」就成了任意 HTML/JS 容器。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class SvgSanitizerTest {

    private fun ok(svg: String): SvgSanitizer.Result.Ok =
        SvgSanitizer.sanitize(svg) as SvgSanitizer.Result.Ok

    private fun error(svg: String): String =
        (SvgSanitizer.sanitize(svg) as SvgSanitizer.Result.Error).message

    /**
     * 自闭合标签**不能**再补一个 `</rect>`：XmlPullParser 会给空元素补一个 END_TAG，
     * 两边都写就输出非法 XML → AndroidSVG 解析失败 → 图一张都渲染不出来
     *（2026-09-18 用户「让大模型调用自由渲染 怎么渲染不出来」的根因）。
     */
    @Test
    fun `self closing tags do not get a stray end tag`() {
        val result = ok(
            """<svg viewBox="0 0 10 10"><rect width="5" height="5"/>""" +
                """<circle cx="1" cy="1" r="1"/><g><path d="M0 0 L1 1"/></g>""" +
                """<text x="1" y="1">hi</text></svg>""",
        )
        assertFalse(result.svg, result.svg.contains("/></rect>"))
        assertFalse(result.svg, result.svg.contains("/></circle>"))
        assertFalse(result.svg, result.svg.contains("/></path>"))
        assertTrue(result.svg, result.svg.contains("""<rect width="5" height="5"/>"""))
        assertTrue(result.svg, result.svg.contains("</text>"))
        assertTrue(result.svg, result.svg.contains("</g>"))
        // 回环校验：产物必须是良构 XML（能再被消毒器解析一次并通过）
        assertTrue(
            SvgSanitizer.sanitize(result.svg) is SvgSanitizer.Result.Ok,
        )
    }

    /**
     * AndroidSVG 只认 `orient="auto"` 或数字；模型爱写 SVG2 的 `auto-start-reverse`，
     * 那会让解析抛异常、整张图空白（2026-09-18 真凶）。消毒时降级成 `auto`。
     */
    @Test
    fun `unsupported marker orient is normalised instead of breaking the render`() {
        val result = ok(
            """<svg viewBox="0 0 10 10"><defs><marker id="a" orient="auto-start-reverse">""" +
                """<path d="M0 0 L1 1"/></marker><marker id="b" orient="45"><path d="M0 0 L1 1"/></marker>""" +
                """<marker id="c" orient="auto"><path d="M0 0 L1 1"/></marker></defs></svg>""",
        )
        assertFalse(result.svg, result.svg.contains("auto-start-reverse"))
        assertTrue(result.svg, result.svg.contains("""orient="auto">"""))
        assertTrue(result.svg, result.svg.contains("""orient="45">"""))
    }

    @Test
    fun `keeps a plain drawing and reads the aspect from the viewBox`() {
        val result = ok(
            """<svg viewBox="0 0 800 400"><rect x="10" y="10" width="80" height="40" rx="6" fill="#185FA5"/>""" +
                """<text x="20" y="90" font-size="16">读数</text></svg>""",
        )
        assertEquals(2f, result.aspectRatio, 0.001f)
        assertTrue(result.svg, result.svg.contains("<rect"))
        assertTrue(result.svg, result.svg.contains("fill=\"#185FA5\""))
        assertTrue(result.svg, result.svg.contains(">读数<"))
    }

    @Test
    fun `drops scripts and their content`() {
        val result = ok(
            """<svg viewBox="0 0 10 10"><script>alert('x')</script><rect width="5" height="5"/></svg>""",
        )
        assertFalse(result.svg, result.svg.contains("script"))
        assertFalse(result.svg, result.svg.contains("alert"))
        assertTrue(result.svg, result.svg.contains("<rect"))
    }

    @Test
    fun `drops event attributes and foreignObject subtrees`() {
        val result = ok(
            """<svg viewBox="0 0 10 10"><rect width="5" height="5" onclick="evil()" onload="evil()"/>""" +
                """<foreignObject width="10" height="10"><div xmlns="http://www.w3.org/1999/xhtml">hi</div></foreignObject></svg>""",
        )
        assertFalse(result.svg, result.svg.contains("onclick"))
        assertFalse(result.svg, result.svg.contains("onload"))
        assertFalse(result.svg, result.svg.contains("foreignObject"))
        assertFalse(result.svg, result.svg.contains("<div"))
    }

    @Test
    fun `drops external references but keeps inline shapes`() {
        val result = ok(
            """<svg viewBox="0 0 10 10"><image href="https://evil.example/x.png" width="10" height="10"/>""" +
                """<use xlink:href="http://evil.example/y#a"/>""" +
                """<rect width="4" height="4" fill="url(#g)"/></svg>""",
        )
        assertFalse(result.svg, result.svg.contains("evil.example"))
        assertFalse(result.svg, result.svg.contains("https:"))
    }

    @Test
    fun `requires an svg root with a layout box`() {
        assertTrue(error("""<div>nope</div>"""), error("""<div>nope</div>""").contains("root"))
        val missing = error("""<svg><rect width="5" height="5"/></svg>""")
        assertTrue(missing, missing.contains("viewBox"))
    }

    @Test
    fun `rejects malformed xml too large and extreme aspects`() {
        assertTrue(error("""<svg viewBox="0 0 10 10"><rect></svg>""").contains("well-formed"))
        assertTrue(error("").contains("empty"))

        val huge = "<svg viewBox=\"0 0 10 10\">" +
            "<desc>${"x".repeat(SvgSanitizer.MAX_BYTES)}</desc></svg>"
        assertTrue(error(huge), error(huge).contains("too large"))

        val thin = error("""<svg viewBox="0 0 10 900"><rect width="1" height="1"/></svg>""")
        assertTrue(thin, thin.contains("aspect ratio"))
    }

    @Test
    fun `falls back to width and height when there is no viewBox`() {
        val result = ok("""<svg width="600" height="300"><rect width="1" height="1"/></svg>""")
        assertEquals(2f, result.aspectRatio, 0.001f)

        // 百分比宽度读不出比例 → 要求补 viewBox
        val percent = error("""<svg width="100%" height="100%"><rect width="1" height="1"/></svg>""")
        assertTrue(percent, percent.contains("viewBox"))
    }

    @Test
    fun `aspect helper reads a saved file head`() {
        assertEquals(2f, SvgAspect.of("""<svg viewBox="0 0 800 400">""")!!, 0.001f)
        assertEquals(1.5f, SvgAspect.of("""<svg width="300" height="200">""")!!, 0.001f)
        assertEquals(null, SvgAspect.of("not an svg at all"))

        val file = kotlin.io.path.createTempFile(suffix = ".svg").toFile()
        file.writeText("""<svg viewBox="0 0 300 600"><rect width="1" height="1"/></svg>""")
        assertEquals(0.5f, SvgAspect.ofFile(file.absolutePath)!!, 0.001f)
        assertEquals(null, SvgAspect.ofFile("/definitely/missing.svg"))
        file.delete()
    }
}
