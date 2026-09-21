package com.psyche.memo.provider.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 图表配色：**外壳跟主题、数据色固定**。
 *
 * 背景/轴线/文字/次要文字跟着当前主题（明暗都好看），系列色用一套固定的分类色板 ——
 * 图表必须能分辨不同系列，而 Memo 默认主题是黑白灰，套主题色会画成一片灰。
 */
data class ChartPalette(
    val background: Long,
    val axis: Long,
    val text: Long,
    val muted: Long,
    val series: List<Long> = CATEGORICAL,
) {
    fun seriesColor(index: Int): Long = series[index % series.size]

    companion object {
        /** 分类色板（蓝/青/琥珀/紫/玫红/红），与自研图表的视觉规范一致。 */
        val CATEGORICAL = listOf(
            0xFF185FA5, 0xFF0F6E56, 0xFFBA7517, 0xFF534AB7, 0xFF993556, 0xFFA32D2D,
        )

        /** 深色主题用的提亮版（压在深底上不发闷），顺序与 [CATEGORICAL] 一一对应。 */
        val DARK_CATEGORICAL = listOf(
            0xFF85B7EB, 0xFF5DCAA5, 0xFFEF9F27, 0xFFAFA9EC, 0xFFED93B1, 0xFFF09595,
        )

        /** 浅色主题默认（工具在拿不到主题时兜底）。 */
        val LIGHT = ChartPalette(
            background = 0xFFFFFFFF,
            axis = 0xFFD3D1C7,
            text = 0xFF2C2C2A,
            muted = 0xFF5F5E5A,
        )

        /** 深色主题默认（工具在拿不到主题时兜底）。 */
        val DARK = ChartPalette(
            background = 0xFF1C1C1B,
            axis = 0xFF444441,
            text = 0xFFF1EFE8,
            muted = 0xFFB4B2A9,
            series = DARK_CATEGORICAL,
        )
    }
}

/**
 * [ChartSpec] → **SVG 字符串**（纯函数，无 Compose / 无 Android 依赖）。
 *
 * 为什么是 SVG 而不是 Canvas 位图：
 *  1. 矢量，放大到全屏查看也不糊；
 *  2. 现有图片通道（coil + `SvgDecoder`）直接就能渲染、能点开全屏、能导出、能随
 *     备份走 —— 不需要新增 part 类型；
 *  3. 纯字符串拼装 ⇒ 可以**按坐标断言**单测（不需要 Robolectric / 布局测量）。
 *
 * 视觉规范照自研可视化那套：扁平、卡片圆角、1.5px 网格线、次要文字用 muted、
 * 数值/文字都不低于 22px（在小屏上等价于可读字号）。
 */
object ChartSvgRenderer {

    const val WIDTH = 900
    const val HEIGHT = 560

    /** 产物 MIME —— 聊天侧的图片附件据此辨认「这是图表」并换用等比卡片。 */
    const val MIME = "image/svg+xml"

    /** 宽高比（900×560）：等比满宽显示用，避免被 112dp 缩略块裁掉坐标轴。 */
    val ASPECT_RATIO = WIDTH.toFloat() / HEIGHT.toFloat()

    private const val PAD = 56.0
    private const val PLOT_LEFT = 96.0
    private const val PLOT_RIGHT = WIDTH - PAD
    private const val PLOT_BOTTOM = HEIGHT - 132.0
    private const val LEGEND_Y = HEIGHT - 44.0
    private const val TICKS = 4

    fun render(spec: ChartSpec, palette: ChartPalette = ChartPalette.LIGHT): String {
        val plotTop = if (spec.title.isEmpty() && spec.unit.isEmpty()) 96.0 else 150.0
        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="$WIDTH" height="$HEIGHT" """ +
                """viewBox="0 0 $WIDTH $HEIGHT" font-family="sans-serif">""",
        )
        sb.append(
            """<rect x="0" y="0" width="$WIDTH" height="$HEIGHT" rx="28" ry="28" """ +
                """fill="${palette.background.toHex()}"/>""",
        )
        if (spec.title.isNotEmpty()) {
            sb.append(text(PAD, 88.0, 36.0, 500, palette.text, spec.title))
        }
        if (spec.unit.isNotEmpty()) {
            sb.append(text(PAD, 126.0, 24.0, 400, palette.muted, spec.unit))
        }
        sb.append(legend(spec, palette))

        when (spec.kind) {
            ChartSpec.Kind.PIE, ChartSpec.Kind.DONUT ->
                sb.append(pie(spec, palette, plotTop, donut = spec.kind == ChartSpec.Kind.DONUT))
            ChartSpec.Kind.FUNNEL -> sb.append(funnel(spec, palette, plotTop))
            ChartSpec.Kind.GAUGE -> sb.append(gauge(spec, palette))
            ChartSpec.Kind.HEATMAP -> sb.append(heatmap(spec, palette, plotTop))
            ChartSpec.Kind.HBAR -> sb.append(horizontal(spec, palette, plotTop))
            else -> sb.append(cartesian(spec, palette, plotTop))
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 直角坐标图

    private fun cartesian(spec: ChartSpec, palette: ChartPalette, plotTop: Double): String {
        val sb = StringBuilder()
        val range = spec.valueRange()
        val (niceMin, niceMax, step) = niceScale(range.start, range.endInclusive)
        val span = (niceMax - niceMin).takeIf { it > 0 } ?: 1.0
        val plotW = PLOT_RIGHT - PLOT_LEFT
        val plotH = PLOT_BOTTOM - plotTop
        fun x(value: Double) = plotW * value
        fun y(value: Double) = PLOT_BOTTOM - plotH * ((value - niceMin) / span)

        // 网格线 + Y 轴刻度
        for (i in 0..TICKS) {
            val value = niceMin + span * i / TICKS
            val gy = y(value)
            sb.append(
                """<line x1="$PLOT_LEFT" y1="${gy.fmt()}" x2="$PLOT_RIGHT" y2="${gy.fmt()}" """ +
                    """stroke="${palette.axis.toHex()}" stroke-width="${if (i == 0) 2.0 else 1.5}"/>""",
            )
            sb.append(
                text(PLOT_LEFT - 16, gy + 8, 22.0, 400, palette.muted, value.tickLabel(), "end"),
            )
        }

        val count = spec.categories.size
        val groupW = plotW / count
        val seriesCount = spec.activeSeries.size

        when (spec.kind) {
            ChartSpec.Kind.BAR -> {
                spec.activeSeries.forEachIndexed { si, series ->
                    val color = palette.seriesColor(si).toHex()
                    series.data.forEachIndexed { ci, value ->
                        val groupX = PLOT_LEFT + groupW * ci
                        val barArea = groupW * 0.72
                        val barW = if (spec.stacked) barArea else barArea / seriesCount
                        val bx = if (spec.stacked) {
                            groupX + (groupW - barArea) / 2
                        } else {
                            groupX + (groupW - barArea) / 2 + barW * si
                        }
                        val baseline = if (spec.stacked) {
                            // 堆叠：下面几段之和作为底
                            spec.activeSeries.take(si).sumOf { it.data.getOrElse(ci) { 0.0 } }
                        } else {
                            niceMin
                        }
                        val top = baseline + value
                        val yTop = y(maxOf(top, baseline))
                        val yBottom = y(minOf(top, baseline))
                        sb.append(
                            """<rect x="${bx.fmt()}" y="${yTop.fmt()}" width="${barW.fmt()}" """ +
                                """height="${(yBottom - yTop).fmt()}" rx="4" ry="4" fill="$color"/>""",
                        )
                    }
                }
            }
            ChartSpec.Kind.LINE, ChartSpec.Kind.AREA, ChartSpec.Kind.SCATTER -> {
                spec.activeSeries.forEachIndexed { si, series ->
                    val color = palette.seriesColor(si).toHex()
                    val points = series.data.mapIndexed { ci, value ->
                        val cx = PLOT_LEFT + groupW * (ci + 0.5)
                        cx to y(value)
                    }
                    if (spec.kind == ChartSpec.Kind.AREA) {
                        val areaPath = points.joinToString(" ") { (px, py) -> "${px.fmt()},${py.fmt()}" }
                        sb.append(
                            """<polygon points="${PLOT_LEFT.fmt()},${PLOT_BOTTOM.fmt()} $areaPath """ +
                                """${PLOT_RIGHT.fmt()},${PLOT_BOTTOM.fmt()}" fill="$color" fill-opacity="0.16"/>""",
                        )
                    }
                    if (spec.kind != ChartSpec.Kind.SCATTER) {
                        val line = points.joinToString(" ") { (px, py) -> "${px.fmt()},${py.fmt()}" }
                        sb.append(
                            """<polyline points="$line" fill="none" stroke="$color" stroke-width="4" """ +
                                """stroke-linecap="round" stroke-linejoin="round"/>""",
                        )
                    }
                    if (spec.kind == ChartSpec.Kind.SCATTER || series.data.size <= 14) {
                        points.forEach { (px, py) ->
                            sb.append(
                                """<circle cx="${px.fmt()}" cy="${py.fmt()}" r="7" fill="$color" """ +
                                    """fill-opacity="${if (spec.kind == ChartSpec.Kind.SCATTER) 0.8 else 1.0}"/>""",
                            )
                        }
                    }
                }
            }
            ChartSpec.Kind.PIE, ChartSpec.Kind.DONUT, ChartSpec.Kind.FUNNEL,
            ChartSpec.Kind.GAUGE, ChartSpec.Kind.HEATMAP, ChartSpec.Kind.HBAR,
            -> Unit // 这些走自己的绘制分支（见 render 的分发）
        }

        // X 轴分类标签（太挤就隔一个显示）
        val everyOther = count > 12
        spec.categories.forEachIndexed { ci, label ->
            if (everyOther && ci % 2 == 1) return@forEachIndexed
            val cx = PLOT_LEFT + groupW * (ci + 0.5)
            sb.append(text(cx, PLOT_BOTTOM + 38, 22.0, 400, palette.muted, label, "middle"))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 饼图

    private fun pie(
        spec: ChartSpec,
        palette: ChartPalette,
        plotTop: Double,
        donut: Boolean = false,
    ): String {
        val series = spec.activeSeries.firstOrNull() ?: return ""
        val total = series.data.sum()
        if (total <= 0.0) return ""
        val cx = (PLOT_LEFT + PLOT_RIGHT) / 2
        val cy = (plotTop + PLOT_BOTTOM) / 2
        val radius = min(PLOT_RIGHT - PLOT_LEFT, PLOT_BOTTOM - plotTop) / 2 * 0.86
        val labelRadius = radius * (if (donut) 0.78 else 0.62)
        val sb = StringBuilder()
        var angle = -90.0
        series.data.forEachIndexed { index, value ->
            val sweep = 360.0 * value / total
            val color = palette.seriesColor(index).toHex()
            if (sweep >= 359.999) {
                sb.append("""<circle cx="${cx.fmt()}" cy="${cy.fmt()}" r="${radius.fmt()}" fill="$color"/>""")
            } else {
                val x1 = cx + radius * cos(Math.toRadians(angle))
                val y1 = cy + radius * sin(Math.toRadians(angle))
                val end = angle + sweep
                val x2 = cx + radius * cos(Math.toRadians(end))
                val y2 = cy + radius * sin(Math.toRadians(end))
                val large = if (sweep > 180) 1 else 0
                sb.append(
                    """<path d="M ${cx.fmt()} ${cy.fmt()} L ${x1.fmt()} ${y1.fmt()} """ +
                        """A ${radius.fmt()} ${radius.fmt()} 0 $large 1 ${x2.fmt()} ${y2.fmt()} Z" """ +
                        """fill="$color" stroke="${palette.background.toHex()}" stroke-width="3"/>""",
                )
                if (sweep >= 28) {
                    val mid = angle + sweep / 2
                    val lx = cx + labelRadius * cos(Math.toRadians(mid))
                    val ly = cy + labelRadius * sin(Math.toRadians(mid))
                    sb.append(
                        text(lx, ly + 8, 24.0, 500, 0xFFFFFFFF, "${(value / total * 100).roundToInt()}%", "middle"),
                    )
                }
            }
            angle += sweep
        }
        if (donut) {
            // 环形：中心掏空 + 显示合计（百分比标签已经挪到外圈，见 labelRadius）
            val hole = radius * 0.54
            sb.append(
                """<circle cx="${cx.fmt()}" cy="${cy.fmt()}" r="${hole.fmt()}" """ +
                    """fill="${palette.background.toHex()}"/>""",
            )
            sb.append(text(cx, cy + 10, 48.0, 500, palette.text, total.tickLabel(), "middle"))
            sb.append(text(cx, cy + 44, 22.0, 400, palette.muted, "合计", "middle"))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 横向条形

    /** `hbar`：分类在左、条向右 —— 分类名一长，竖柱会挤成一团，横向就顺了。 */
    private fun horizontal(spec: ChartSpec, palette: ChartPalette, plotTop: Double): String {
        val sb = StringBuilder()
        val range = spec.valueRange()
        val (niceMin, niceMax, _) = niceScale(range.start, range.endInclusive)
        val span = (niceMax - niceMin).takeIf { it > 0 } ?: 1.0
        val labelW = 200.0
        val left = PAD + labelW
        val plotW = PLOT_RIGHT - left
        val plotH = PLOT_BOTTOM - plotTop
        fun x(value: Double) = left + plotW * ((value - niceMin) / span)

        for (i in 0..TICKS) {
            val value = niceMin + span * i / TICKS
            val gx = x(value)
            sb.append(
                """<line x1="${gx.fmt()}" y1="${plotTop.fmt()}" x2="${gx.fmt()}" """ +
                    """y2="${PLOT_BOTTOM.fmt()}" stroke="${palette.axis.toHex()}" """ +
                    """stroke-width="${if (i == 0) 2.0 else 1.5}"/>""",
            )
            sb.append(text(gx, PLOT_BOTTOM + 38, 22.0, 400, palette.muted, value.tickLabel(), "middle"))
        }

        val rows = spec.categories.size
        val rowH = plotH / rows
        val seriesCount = spec.activeSeries.size
        spec.activeSeries.forEachIndexed { si, series ->
            val color = palette.seriesColor(si).toHex()
            series.data.forEachIndexed { ci, value ->
                val rowTop = plotTop + rowH * ci
                val area = rowH * 0.72
                val barH = if (spec.stacked) area else area / seriesCount
                val by = if (spec.stacked) rowTop + (rowH - area) / 2 else rowTop + (rowH - area) / 2 + barH * si
                val start = if (spec.stacked) {
                    spec.activeSeries.take(si).sumOf { it.data.getOrElse(ci) { 0.0 } }
                } else {
                    niceMin
                }
                val from = x(minOf(start, start + value))
                val to = x(maxOf(start, start + value))
                sb.append(
                    """<rect x="${from.fmt()}" y="${by.fmt()}" width="${(to - from).fmt()}" """ +
                        """height="${barH.fmt()}" rx="4" ry="4" fill="$color"/>""",
                )
            }
        }
        spec.categories.forEachIndexed { ci, label ->
            val cy = plotTop + rowH * (ci + 0.5)
            sb.append(text(left - 14, cy + 8, 22.0, 400, palette.muted, label.take(14), "end"))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 漏斗

    /** `funnel`：分类当成环节，宽度按数值收窄（转化率/流程步骤场景）。 */
    private fun funnel(spec: ChartSpec, palette: ChartPalette, plotTop: Double): String {
        val series = spec.activeSeries.firstOrNull() ?: return ""
        val values = series.data
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: return ""
        val total = values.sum().takeIf { it > 0 } ?: return ""
        val sb = StringBuilder()
        val cx = (PLOT_LEFT + PLOT_RIGHT) / 2
        val maxW = (PLOT_RIGHT - PLOT_LEFT) * 0.62
        val rowH = (PLOT_BOTTOM - plotTop) / values.size
        val stageH = rowH * 0.78
        fun width(value: Double) = (maxW * (value / max)).coerceAtLeast(maxW * 0.08)

        values.forEachIndexed { index, value ->
            val top = plotTop + rowH * index + (rowH - stageH) / 2
            val bottom = top + stageH
            val wTop = width(value)
            val wBottom = width(values.getOrElse(index + 1) { value })
            val color = palette.seriesColor(0).toHex()
            sb.append(
                """<path d="M ${(cx - wTop / 2).fmt()} ${top.fmt()} L ${(cx + wTop / 2).fmt()} ${top.fmt()} """ +
                    """L ${(cx + wBottom / 2).fmt()} ${bottom.fmt()} L ${(cx - wBottom / 2).fmt()} ${bottom.fmt()} Z" """ +
                    """fill="$color" fill-opacity="${(0.95f - index * 0.12f).coerceAtLeast(0.4f)}"/>""",
            )
            val label = spec.categories.getOrNull(index).orEmpty()
            val labelFits = wTop > label.length * 24.0
            if (label.isNotEmpty()) {
                if (labelFits) {
                    sb.append(text(cx, (top + bottom) / 2 + 8, 22.0, 500, 0xFFFFFFFF, label, "middle"))
                } else {
                    sb.append(text(cx - maxW / 2 - 14, (top + bottom) / 2 + 8, 22.0, 400, palette.muted, label, "end"))
                }
            }
            val pct = (value / total * 100).roundToInt()
            sb.append(
                text(cx + maxW / 2 + 16, (top + bottom) / 2 + 8, 22.0, 400, palette.muted, "${value.tickLabel()}  $pct%"),
            )
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 仪表盘

    /**
     * `gauge`：单指标达成率。第一个数是**当前值**，第二个数（可选）是**满值**
     *（缺省 100）—— 工具描述里对模型也是这么写的。
     */
    private fun gauge(spec: ChartSpec, palette: ChartPalette): String {
        val data = spec.activeSeries.firstOrNull()?.data ?: return ""
        val value = data.getOrNull(0) ?: return ""
        val max = data.getOrNull(1)?.takeIf { it > 0 } ?: 100.0
        val ratio = (value / max).coerceIn(0.0, 1.0)
        val sb = StringBuilder()
        val cx = (PLOT_LEFT + PLOT_RIGHT) / 2
        val cy = 300.0
        val radius = 150.0
        val start = 150.0
        val sweep = 240.0
        fun point(angle: Double, r: Double): Pair<Double, Double> =
            cx + r * cos(Math.toRadians(angle)) to cy + r * sin(Math.toRadians(angle))

        fun arc(from: Double, to: Double, color: String): String {
            val (x1, y1) = point(from, radius)
            val (x2, y2) = point(to, radius)
            val large = if (to - from > 180.0) 1 else 0
            return """<path d="M ${x1.fmt()} ${y1.fmt()} A ${radius.fmt()} ${radius.fmt()} 0 $large 1 """ +
                """${x2.fmt()} ${y2.fmt()}" fill="none" stroke="$color" stroke-width="30" stroke-linecap="round"/>"""
        }
        sb.append(arc(start, start + sweep, palette.axis.toHex()))
        if (ratio > 0.001) sb.append(arc(start, start + sweep * ratio, palette.seriesColor(0).toHex()))
        sb.append(text(cx, cy + 16, 64.0, 500, palette.text, value.tickLabel(), "middle"))
        val caption = buildString {
            append(spec.activeSeries.firstOrNull()?.name.orEmpty())
            if (spec.unit.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(spec.unit)
            }
        }
        if (caption.isNotEmpty()) sb.append(text(cx, cy + 56, 22.0, 400, palette.muted, caption, "middle"))
        val (lx, ly) = point(start + sweep, radius)
        sb.append(text(lx + 6, ly + 26, 22.0, 400, palette.muted, "0", "middle"))
        val (rx, ry) = point(start, radius)
        sb.append(text(rx - 6, ry + 26, 22.0, 400, palette.muted, max.tickLabel(), "middle"))
        // 达成率单独一行，别和「值」混在一起
        sb.append(
            text(cx, cy + 92, 24.0, 500, palette.seriesColor(0), "${(ratio * 100).roundToInt()}%", "middle"),
        )
        return sb.toString()
    }

    // ------------------------------------------------------------------ 热力图

    /** `heatmap`：行 = 系列、列 = 分类，色深表示大小（时段 × 渠道这类矩阵）。 */
    private fun heatmap(spec: ChartSpec, palette: ChartPalette, plotTop: Double): String {
        val seriesList = spec.activeSeries.filter { it.data.isNotEmpty() }
        if (seriesList.isEmpty()) return ""
        val columns = spec.categories.size
        if (columns == 0) return ""
        val values = seriesList.flatMap { it.data }
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val sb = StringBuilder()
        val labelW = 150.0
        val left = PAD + labelW
        val cellW = (PLOT_RIGHT - left) / columns
        val cellH = (PLOT_BOTTOM - plotTop) / seriesList.size
        val gap = 3.0
        val base = palette.seriesColor(0)
        val low = lerpColor(base, 0xFFFFFFFF, 0.88f)
        val showValues = cellW >= 54.0 && cellH >= 44.0
        val everyOther = columns > 12

        seriesList.forEachIndexed { row, series ->
            val y = plotTop + cellH * row
            series.data.take(columns).forEachIndexed { col, value ->
                val t = ((value - min) / span).toFloat().coerceIn(0f, 1f)
                val cellX = left + cellW * col
                sb.append(
                    """<rect x="${(cellX + gap / 2).fmt()}" y="${(y + gap / 2).fmt()}" """ +
                        """width="${(cellW - gap).fmt()}" height="${(cellH - gap).fmt()}" rx="4" ry="4" """ +
                        """fill="${lerpColor(low, base, t).toHex()}"/>""",
                )
                if (showValues) {
                    val ink = if (t > 0.55f) 0xFFFFFFFF else palette.text
                    sb.append(
                        text(
                            cellX + cellW / 2,
                            y + cellH / 2 + 8,
                            22.0,
                            400,
                            ink,
                            value.tickLabel(),
                            "middle",
                        ),
                    )
                }
            }
            sb.append(text(left - 14, y + cellH / 2 + 8, 22.0, 400, palette.muted, series.name.take(10), "end"))
        }
        spec.categories.forEachIndexed { col, label ->
            if (everyOther && col % 2 == 1) return@forEachIndexed
            sb.append(
                text(left + cellW * (col + 0.5), PLOT_BOTTOM + 38, 22.0, 400, palette.muted, label.take(8), "middle"),
            )
        }
        // 低 → 高 色阶提示
        val scaleY = PLOT_BOTTOM + 74
        sb.append(text(PLOT_RIGHT - 150, scaleY, 20.0, 400, palette.muted, "低"))
        for (i in 0..4) {
            sb.append(
                """<rect x="${(PLOT_RIGHT - 108 + i * 20).fmt()}" y="${(scaleY - 8).fmt()}" width="18" height="16" """ +
                    """rx="3" ry="3" fill="${lerpColor(low, base, i / 4f).toHex()}"/>""",
            )
        }
        sb.append(text(PLOT_RIGHT, scaleY, 20.0, 400, palette.muted, "高"))
        return sb.toString()
    }

    // ------------------------------------------------------------------ 图例

    private fun legend(spec: ChartSpec, palette: ChartPalette): String {
        val entries = when (spec.kind) {
            ChartSpec.Kind.PIE -> spec.categories.mapIndexed { i, label ->
                val value = spec.activeSeries.firstOrNull()?.data?.getOrNull(i) ?: 0.0
                label to "$label  ${value.tickLabel()}"
            }
            else -> spec.activeSeries.mapIndexed { i, s -> (i to s.name).second to s.name }
        }
        val total = spec.activeSeries.firstOrNull()?.data?.sum() ?: 0.0
        val labels = if (spec.kind == ChartSpec.Kind.PIE && total > 0) {
            entries.mapIndexed { i, (_, text) ->
                val value = spec.activeSeries.first().data.getOrNull(i) ?: 0.0
                "$text  ${(value / total * 100).roundToInt()}%"
            }
        } else {
            entries.map { it.second }
        }
        if (labels.isEmpty()) return ""
        val swatch = 22.0
        val gap = 14.0
        val itemGap = 34.0
        val widths = labels.map { swatch + gap + it.length * 15.0 }
        val totalW = widths.sum() + itemGap * (labels.size - 1)
        var x = PAD + ((PLOT_RIGHT - PAD) - totalW).coerceAtLeast(0.0) / 2
        val sb = StringBuilder()
        labels.forEachIndexed { index, label ->
            val color = when (spec.kind) {
                ChartSpec.Kind.PIE -> palette.seriesColor(index)
                else -> palette.seriesColor(index)
            }.toHex()
            sb.append(
                """<rect x="${x.fmt()}" y="${(LEGEND_Y - 17).fmt()}" width="$swatch" height="$swatch" """ +
                    """rx="5" ry="5" fill="$color"/>""",
            )
            sb.append(text(x + swatch + gap, LEGEND_Y, 24.0, 400, palette.muted, label))
            x += widths[index] + itemGap
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 工具

    private fun text(
        x: Double,
        y: Double,
        size: Double,
        weight: Int,
        color: Long,
        value: String,
        anchor: String = "start",
    ): String = """<text x="${x.fmt()}" y="${y.fmt()}" font-size="$size" font-weight="$weight" """ +
        """fill="${color.toHex()}" text-anchor="$anchor">${escape(value)}</text>"""

    /** 轴刻度：0 / 1 / 2 / 2.5 / 5 × 10ⁿ 取整，保证是「好看的数」。 */
    internal fun niceScale(min: Double, max: Double): Triple<Double, Double, Double> {
        val lo = minOf(0.0, min)
        val hi = maxOf(0.0, max)
        if (hi - lo <= 0.0) return Triple(0.0, 1.0, 0.25)
        val rawStep = (hi - lo) / TICKS
        val magnitude = 10.0.pow(ceil(log10(rawStep)))
        val norm = rawStep / magnitude
        val niceNorm = when {
            norm <= 0.1 -> 0.1
            norm <= 0.2 -> 0.2
            norm <= 0.25 -> 0.25
            norm <= 0.5 -> 0.5
            else -> 1.0
        }
        val step = niceNorm * magnitude
        val niceMax = ceil(hi / step) * step
        val niceMin = if (lo < 0) -ceil(-lo / step) * step else 0.0
        return Triple(niceMin, niceMax, step)
    }

    private fun Double.tickLabel(): String {
        val rounded = (this * 1000).roundToInt() / 1000.0
        return if (abs(rounded - rounded.roundToInt()) < 0.001) {
            rounded.roundToInt().toString()
        } else {
            rounded.toString()
        }
    }

    private fun Double.fmt(): String {
        val rounded = (this * 100).roundToInt() / 100.0
        return if (abs(rounded - rounded.roundToInt()) < 0.01) {
            rounded.roundToInt().toString()
        } else {
            rounded.toString()
        }
    }

    private fun Long.toHex(): String = "#%06X".format(this and 0xFFFFFF)

    /** 两个实色之间线性插值（热力图色深 / 淡色底）。 */
    private fun lerpColor(from: Long, to: Long, fraction: Float): Long {
        fun channel(color: Long, shift: Int) = ((color shr shift) and 0xFF).toFloat()
        fun mix(shift: Int) = (
            channel(from, shift) + (channel(to, shift) - channel(from, shift)) * fraction
            ).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
