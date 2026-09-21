package com.psyche.memo.provider.chart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `render_chart` 工具的**图表规格**（自研功能）。
 *
 * 模型只传 JSON（数据 + 类型），我们本地校验后画成 SVG —— 不联网、不依赖任何图表库。
 * 校验失败抛 [ChartSpecException]，message 是**给模型看的**一句话（它会自己改正重试）。
 *
 * 上限（[MAX_*]）是刻意的：模型很容易一口气塞 200 个点进来，画出来糊成一片、
 * 消息也变重。超出就**截断 + 在结果里说明**，不整条失败。
 */
data class ChartSpec(
    val kind: Kind,
    val title: String = "",
    val unit: String = "",
    val categories: List<String> = emptyList(),
    val series: List<Series> = emptyList(),
    val stacked: Boolean = false,
) {

    /**
     * `bar`（柱）/ `hbar`（横向条，分类名长时好读）/ `line`（折线）/ `area`（面积）/
     * `pie`（饼）/ `donut`（环形，中心显示合计）/ `scatter`（散点）/
     * `funnel`（漏斗，分类 = 环节）/ `gauge`（仪表盘，单指标达成率）/
     * `heatmap`（热力图，分类 × 系列）。
     *
     * 单系列类（[SINGLE_SERIES_KINDS]）只画第一组数据（工具描述里也写明）。
     */
    enum class Kind(val wireName: String) {
        BAR("bar"),
        HBAR("hbar"),
        LINE("line"),
        AREA("area"),
        PIE("pie"),
        DONUT("donut"),
        SCATTER("scatter"),
        FUNNEL("funnel"),
        GAUGE("gauge"),
        HEATMAP("heatmap");

        companion object {
            fun of(raw: String?): Kind? =
                entries.firstOrNull { it.wireName == raw?.trim()?.lowercase() }
        }
    }

    data class Series(val name: String, val data: List<Double>)

    val isEmpty: Boolean get() = series.isEmpty() || series.all { it.data.isEmpty() }

    /** 饼 / 环 / 漏斗 / 仪表盘这类「一份数据一个形状」的图，只管第一组。 */
    val activeSeries: List<Series>
        get() = if (kind in SINGLE_SERIES_KINDS) series.take(1) else series

    /** 数值范围（堆叠时按分类求和，坐标轴才不会顶穿）。 */
    fun valueRange(): ClosedFloatingPointRange<Double> {
        val values = if (stacked) {
            categories.indices.map { i -> series.sumOf { it.data.getOrElse(i) { 0.0 } } }
        } else {
            series.flatMap { it.data }
        }
        val min = minOf(0.0, values.minOrNull() ?: 0.0)
        val max = maxOf(0.0, values.maxOrNull() ?: 0.0)
        return if (max == min) 0.0..1.0 else min..max
    }

    companion object {
        /** 只取第一组数据的图形（工具描述里对模型也这么说）。 */
        val SINGLE_SERIES_KINDS = setOf(Kind.PIE, Kind.DONUT, Kind.FUNNEL, Kind.GAUGE)

        const val MAX_CATEGORIES = 24
        const val MAX_SERIES = 6
        const val MAX_POINTS = 200
        const val MAX_TITLE = 80
        const val MAX_LABEL = 18

        /**
         * 工具参数 → 规格。失败抛 [ChartSpecException]（文案是给模型看的，
         * 说清哪个字段不对、期望什么）。
         */
        fun parse(args: JsonObject): ChartSpec {
            val rawKind = (args["kind"] as? JsonPrimitive)?.contentOrNull
            val kind = Kind.of(rawKind)
                ?: throw ChartSpecException(
                    "kind must be one of: " + Kind.entries.joinToString(", ") { it.wireName },
                )
            val seriesJson = args["series"] as? JsonArray
                ?: throw ChartSpecException("series must be an array of {name, data}")
            if (seriesJson.isEmpty()) throw ChartSpecException("series must not be empty")

            val categories = (args["categories"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val series = seriesJson.mapIndexed { index, element ->
                val obj = element as? JsonObject
                    ?: throw ChartSpecException("series[$index] must be an object")
                val data = (obj["data"] as? JsonArray)
                    ?: throw ChartSpecException("series[$index].data must be an array of numbers")
                Series(
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        .ifEmpty { "Series ${index + 1}" },
                    data = data.mapIndexed { i, v ->
                        val text = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                        text.toDoubleOrNull()?.takeIf { it.isFinite() }
                            ?: throw ChartSpecException(
                                "series[$index].data[$i] is not a finite number: \"$text\"",
                            )
                    },
                )
            }

            val length = series.first().data.size
            series.forEachIndexed { index, s ->
                if (s.data.size != length) {
                    throw ChartSpecException(
                        "every series must have the same number of points " +
                            "(series[$index] has ${s.data.size}, series[0] has $length)",
                    )
                }
            }

            return ChartSpec(
                kind = kind,
                title = ((args["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
                    .take(MAX_TITLE),
                unit = ((args["unit"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
                    .take(MAX_LABEL),
                categories = categories.ifEmpty { List(length) { "${it + 1}" } },
                series = series,
                stacked = (args["stacked"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.lowercase() == "true",
            ).clamped()
        }

        /** 截断到上限（超出只截断，不失败）。 */
        private fun ChartSpec.clamped(): ChartSpec {
            val take = minOf(categories.size, MAX_CATEGORIES, MAX_POINTS)
            val keptSeries = series.take(MAX_SERIES).map { s -> s.copy(data = s.data.take(take)) }
            return copy(
                categories = categories.take(take).map { it.take(MAX_LABEL) },
                series = keptSeries,
            )
        }
    }
}

/** 规格不合法：message 是**给模型看的**（它会据此改正并重试）。 */
class ChartSpecException(message: String) : Exception(message)
