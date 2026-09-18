package com.psyche.memo.provider.chart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `render_chart` 的规格解析与截断（用户 2026-09-17「加个本地工具 就是可以可视化的工具」）。
 *
 * 失败文案是**给模型看的** —— 它据此改正重试，所以错误信息必须点名字段与期望。
 */
class ChartSpecTest {

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `parses a grouped bar chart`() {
        val spec = ChartSpec.parse(
            args(
                """{"kind":"bar","title":"7 月营收对比","unit":"单位：万元",
                   "categories":["第1周","第2周"],
                   "series":[{"name":"春熙路店","data":[62,78]},{"name":"天府三街店","data":[40,52]}]}""",
            ),
        )
        assertEquals(ChartSpec.Kind.BAR, spec.kind)
        assertEquals("7 月营收对比", spec.title)
        assertEquals("单位：万元", spec.unit)
        assertEquals(listOf("第1周", "第2周"), spec.categories)
        assertEquals(2, spec.series.size)
        assertEquals("春熙路店", spec.series[0].name)
        assertEquals(listOf(62.0, 78.0), spec.series[0].data)
        assertFalse(spec.stacked)
    }

    @Test
    fun `unknown kind lists the supported ones`() {
        val error = runCatching {
            ChartSpec.parse(args("""{"kind":"radar","series":[{"name":"a","data":[1]}]}"""))
        }.exceptionOrNull()
        assertTrue(error is ChartSpecException)
        val message = error!!.message.orEmpty()
        assertTrue(message, message.contains("bar"))
        assertTrue(message, message.contains("scatter"))
    }

    @Test
    fun `series lengths must match and the message names the offenders`() {
        val error = runCatching {
            ChartSpec.parse(
                args("""{"kind":"line","series":[{"name":"a","data":[1,2,3]},{"name":"b","data":[1]}]}"""),
            )
        }.exceptionOrNull()
        assertTrue(error is ChartSpecException)
        assertTrue(error!!.message.orEmpty().contains("series[1]"))
        assertTrue(error.message.orEmpty().contains("3"))
    }

    @Test
    fun `non numeric points are rejected with the value quoted`() {
        val error = runCatching {
            ChartSpec.parse(args("""{"kind":"bar","series":[{"name":"a","data":[1,"十二月"]}]}"""))
        }.exceptionOrNull()
        assertTrue(error is ChartSpecException)
        assertTrue(error!!.message.orEmpty().contains("十二月"))
    }

    @Test
    fun `empty categories fall back to ordinal labels`() {
        val spec = ChartSpec.parse(
            args("""{"kind":"bar","series":[{"name":"a","data":[3,4,5]}]}"""),
        )
        assertEquals(listOf("1", "2", "3"), spec.categories)
    }

    @Test
    fun `over-long input is truncated instead of failing`() {
        val categories = (1..40).joinToString(",") { "\"c$it\"" }
        val data = (1..40).joinToString(",") { "$it" }
        val spec = ChartSpec.parse(
            args("""{"kind":"bar","categories":[$categories],"series":[{"name":"a","data":[$data]}]}"""),
        )
        assertEquals(ChartSpec.MAX_CATEGORIES, spec.categories.size)
        assertEquals(ChartSpec.MAX_CATEGORIES, spec.series.first().data.size)
    }

    @Test
    fun `series count is capped`() {
        val series = (1..9).joinToString(",") { """{"name":"s$it","data":[1]}""" }
        val spec = ChartSpec.parse(args("""{"kind":"bar","series":[$series]}"""))
        assertEquals(ChartSpec.MAX_SERIES, spec.series.size)
    }

    /**
     * 全部图形都能解析（kind 的 wireName 就是模型看到的那份枚举）。
     * 单系列类只取第一组 —— 工具描述里对模型也是这么写的。
     */
    @Test
    fun `every kind parses and single series kinds keep only the first series`() {
        ChartSpec.Kind.entries.forEach { kind ->
            val spec = ChartSpec.parse(
                args("""{"kind":"${kind.wireName}","categories":["a","b"],"series":[{"name":"x","data":[1,2]},{"name":"y","data":[3,4]}]}"""),
            )
            assertEquals(kind, spec.kind)
            if (kind in ChartSpec.SINGLE_SERIES_KINDS) {
                assertEquals("${kind.wireName} 只该取第一组", 1, spec.activeSeries.size)
            } else {
                assertEquals("${kind.wireName} 该保留两组", 2, spec.activeSeries.size)
            }
        }
        // 拼错的 kind 会把合法取值列出来
        val error = runCatching { ChartSpec.parse(args("""{"kind":"donuts","series":[{"name":"a","data":[1]}]}""")) }
            .exceptionOrNull()
        assertTrue(error is ChartSpecException)
        assertTrue(error!!.message.orEmpty().contains("donut"))
    }

    @Test
    fun `stacked flag and pie single-series rule`() {
        val stacked = ChartSpec.parse(
            args("""{"kind":"bar","stacked":"true","series":[{"name":"a","data":[1]}]}"""),
        )
        assertTrue(stacked.stacked)

        val pie = ChartSpec.parse(
            args("""{"kind":"pie","categories":["a","b"],"series":[{"name":"x","data":[1,2]},{"name":"y","data":[3,4]}]}"""),
        )
        assertEquals(1, pie.activeSeries.size)
        assertEquals("x", pie.activeSeries.first().name)
    }

    @Test
    fun `value range sums stacked series and keeps zero in view`() {
        val plain = ChartSpec.parse(
            args("""{"kind":"bar","series":[{"name":"a","data":[5,20]}]}"""),
        )
        assertEquals(0.0, plain.valueRange().start, 0.0001)
        assertEquals(20.0, plain.valueRange().endInclusive, 0.0001)

        val stacked = ChartSpec.parse(
            args("""{"kind":"bar","stacked":"true","series":[{"name":"a","data":[5,10]},{"name":"b","data":[5,10]}]}"""),
        )
        assertEquals(20.0, stacked.valueRange().endInclusive, 0.0001)
    }
}
