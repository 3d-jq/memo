package com.psyche.memo.provider.chart

import android.content.Context
import android.content.res.Configuration
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.provider.generation.GeneratedMediaStore
import com.psyche.memo.ui.theme.MemoTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * `render_visual` —— **唯一的可视化工具**（自研，用户 2026-09-17 起分两批长出来的）。
 *
 * 一个工具、一个 `kind` 枚举、一条产物通道：
 *
 * | kind | 谁画 | 输入 |
 * |---|---|---|
 * | `bar` `hbar` `line` `area` `pie` `donut` `scatter` `funnel` `gauge` `heatmap` | **我们**（[ChartSvgRenderer]，自动布局 + 跟主题） | 结构化 `categories` + `series` |
 * | `svg` | **模型** | 手写 SVG 标记（[SvgSanitizer] 消毒后落盘） |
 *
 * 为什么合成一个（用户 2026-09-18「为什么不能一个工具渲染各种呀」）：两个工具意味着两个开关、
 * 两份描述、还要靠文案互相约束「谁该用谁」；合成一个之后模型只需选 `kind`，而**数据图优先
 * 用结构化 kind**这条规矩写在 `kind` 字段的说明里就够了（`svg` 只留给结构化表达不了的东西）。
 *
 * 产物一律是 `<filesDir>/images/gen_*.svg`（`image/svg+xml`）→ 走已有图片通道：
 * 等比卡片显示、点开全屏、导出、备份、存储页归类，**不新增 part 类型**。
 */
object VisualTools {

    const val TOOL_NAME = "render_visual"

    val ALL_TOOL_NAMES = setOf(TOOL_NAME)

    /** `svg` 是一个「kind」，不是另一个工具 —— 手写模式只有这一个入口。 */
    const val KIND_SVG = "svg"

    val DEFINITION: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(TOOL_NAME))
                put("description", JsonPrimitive(DESCRIPTION))
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "kind",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put("description", JsonPrimitive(KIND_DESCRIPTION))
                                        put(
                                            "enum",
                                            buildJsonArray {
                                                ChartSpec.Kind.entries.forEach { add(JsonPrimitive(it.wireName)) }
                                                add(JsonPrimitive(KIND_SVG))
                                            },
                                        )
                                    },
                                )
                                put(
                                    "categories",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("array"))
                                        put(
                                            "description",
                                            JsonPrimitive(
                                                "Labels for the x-axis, the pie/donut slices, the funnel " +
                                                    "stages or the heatmap columns — in the same order as the values.",
                                            ),
                                        )
                                        put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                                    },
                                )
                                put(
                                    "series",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("array"))
                                        put("description", JsonPrimitive("One entry per data series."))
                                        put(
                                            "items",
                                            buildJsonObject {
                                                put("type", JsonPrimitive("object"))
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put(
                                                            "name",
                                                            buildJsonObject {
                                                                put("type", JsonPrimitive("string"))
                                                                put("description", JsonPrimitive("Legend label for this series."))
                                                            },
                                                        )
                                                        put(
                                                            "data",
                                                            buildJsonObject {
                                                                put("type", JsonPrimitive("array"))
                                                                put(
                                                                    "description",
                                                                    JsonPrimitive("Numeric values, one per category, in the same order."),
                                                                )
                                                                put("items", buildJsonObject { put("type", JsonPrimitive("number")) })
                                                            },
                                                        )
                                                    },
                                                )
                                                put(
                                                    "required",
                                                    buildJsonArray {
                                                        add(JsonPrimitive("name"))
                                                        add(JsonPrimitive("data"))
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                put(
                                    "title",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put("description", JsonPrimitive("Chart title, in the user's language. Optional."))
                                    },
                                )
                                put(
                                    "unit",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put(
                                            "description",
                                            JsonPrimitive("Unit line under the title, e.g. \"单位：万元\". Optional."),
                                        )
                                    },
                                )
                                put(
                                    "stacked",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("boolean"))
                                        put("description", JsonPrimitive("Stack the series instead of grouping them (bar/hbar only). Optional."))
                                    },
                                )
                                put(
                                    "svg",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put(
                                            "description",
                                            JsonPrimitive(
                                                "Only for kind=\"svg\": the complete SVG document, starting with " +
                                                    "<svg ...> and ending with </svg>.",
                                            ),
                                        )
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            buildJsonArray { add(JsonPrimitive("kind")) },
                        )
                    },
                )
            },
        )
    }

    /**
     * 执行：结构化 kind 走 [ChartSpec] + [ChartSvgRenderer]，`svg` 走 [SvgSanitizer] 消毒。
     * 规格不合法**不抛异常**，回 `{"type":"tool_error",…}` 让模型自己改。
     */
    fun execute(context: Context, args: JsonObject, palette: ChartPalette): String {
        val kind = (args["kind"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return errorJson("visual_invalid", "kind is required (one of: ${kindList()})")
        if (kind == KIND_SVG) return executeRawSvg(context, args)
        return executeChart(context, args, palette, kind)
    }

    // ------------------------------------------------------------------ 结构化图

    private fun executeChart(
        context: Context,
        args: JsonObject,
        palette: ChartPalette,
        kind: String,
    ): String {
        val spec = try {
            ChartSpec.parse(args)
        } catch (e: ChartSpecException) {
            return errorJson("chart_invalid_spec", e.message ?: "invalid chart spec")
        }
        if (spec.isEmpty) {
            return errorJson(
                "chart_invalid_spec",
                "series is required for kind=\"$kind\": pass one or more {name, data} entries",
            )
        }
        val svg = ChartSvgRenderer.render(spec, palette)
        val media = GeneratedMediaStore(context).saveImage(
            bytes = svg.toByteArray(Charsets.UTF_8),
            mimeType = ChartSvgRenderer.MIME,
        )
        return buildJsonObject {
            put("type", JsonPrimitive("chart_result"))
            put("kind", JsonPrimitive(spec.kind.wireName))
            put("points", JsonPrimitive(spec.categories.size))
            put("series", JsonPrimitive(spec.activeSeries.size))
            put("paths", buildJsonArray { add(JsonPrimitive(media.path)) })
        }.toString()
    }

    // ------------------------------------------------------------------ 手写 SVG

    private fun executeRawSvg(context: Context, args: JsonObject): String {
        val raw = (args["svg"] as? JsonPrimitive)?.contentOrNull
            ?: return errorJson(
                "svg_invalid",
                "kind=\"svg\" needs the svg field: pass the whole SVG document as a string",
            )
        return when (val sanitized = SvgSanitizer.sanitize(raw)) {
            is SvgSanitizer.Result.Error -> errorJson("svg_invalid", sanitized.message)
            is SvgSanitizer.Result.Ok -> {
                // 消毒只保证「良构 + 无危险内容」，还得确认**渲染器解得开**：
                // AndroidSVG 对不认识的写法会抛异常，那就会变成一张空白卡片
                //（2026-09-18 的 orient="auto-start-reverse" 事故）。解不开就回给模型改。
                val parseError = runCatching {
                    com.caverock.androidsvg.SVG.getFromString(sanitized.svg)
                }.exceptionOrNull()
                if (parseError != null) {
                    return errorJson(
                        "svg_invalid",
                        "the renderer cannot parse this SVG: ${parseError.message ?: parseError::class.simpleName}. " +
                            "Stick to plain shapes, paths, lines, <text> and simple groups.",
                    )
                }
                val media = GeneratedMediaStore(context).saveImage(
                    bytes = withBackground(sanitized.svg).toByteArray(Charsets.UTF_8),
                    mimeType = ChartSvgRenderer.MIME,
                )
                buildJsonObject {
                    put("type", JsonPrimitive("svg_result"))
                    put("aspect", JsonPrimitive(sanitized.aspectRatio))
                    put("paths", buildJsonArray { add(JsonPrimitive(media.path)) })
                }.toString()
            }
        }
    }

    /** 白底：任何主题下都保证对比度（模型不知道当前主题，别让它赌配色）。 */
    private const val BACKGROUND = """<rect x="0" y="0" width="100%" height="100%" fill="#FFFFFF"/>"""

    /** 把白底插成根节点的第一个子元素（保证在所有内容之下）。 */
    internal fun withBackground(svg: String): String {
        val rootStart = svg.indexOf("<svg", ignoreCase = true)
        if (rootStart < 0) return svg
        val tagEnd = svg.indexOf('>', rootStart)
        if (tagEnd < 0) return svg
        val insertAt = tagEnd + 1
        return svg.substring(0, insertAt) + BACKGROUND + svg.substring(insertAt)
    }

    // ------------------------------------------------------------------ 主题配色

    /** 按当前主题解析图表配色（外壳跟主题、系列色固定，见 [ChartPalette]）。 */
    fun paletteFor(container: AppContainerImpl): ChartPalette {
        val prefs = container.preferenceRepository
        val systemDark = (
            container.appContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
        val (palette, dark) = MemoTheme.resolve(
            paletteId = prefs.readJson(MemoTheme.PALETTE_KEY),
            mode = prefs.readJson(MemoTheme.MODE_KEY),
            systemDark = systemDark,
        )
        return chartPaletteOf(MemoTheme.colorScheme(palette, dark), dark)
    }

    private fun kindList(): String = ChartSpec.Kind.entries.joinToString(", ") { it.wireName } +
        ", $KIND_SVG"

    private fun errorJson(code: String, message: String): String = buildJsonObject {
        put("type", JsonPrimitive("tool_error"))
        put("error", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
    }.toString()

    private const val KIND_DESCRIPTION =
        "What to draw. Prefer a data-chart kind whenever the content is actually data; " +
            "use svg only for things a data chart cannot express. " +
            "bar = grouped/stacked columns; hbar = horizontal bars (better with long category " +
            "names); line = trend; area = trend with filled area; pie = parts of a whole; " +
            "donut = pie with the total in the middle; scatter = correlation; " +
            "funnel = conversion stages (categories are the stages); " +
            "gauge = one value against a maximum (first number is the value, optional second " +
            "number is the maximum, default 100); heatmap = one row per series, one column per " +
            "category, colour = magnitude; " +
            "svg = you draw it yourself: flowcharts, sequence/org diagrams, timelines, " +
            "dashboards, annotated figures, UI sketches."

    const val DESCRIPTION =
        "Draw a picture in the conversation: either a data chart (bar, hbar, line, area, pie, " +
            "donut, scatter, funnel, gauge, heatmap) from numbers you pass, or a free-form " +
            "drawing (kind=\"svg\") for diagrams and sketches. " +
            "Everything is rendered locally into the chat as an image (no network, no cost); " +
            "data charts follow the app theme automatically, so do not hand-draw a data chart " +
            "with svg. " +
            "For data charts: give categories plus one or more {name, data} series; single-series " +
            "kinds (pie, donut, funnel, gauge) use only the first series; a stacked bar sums the " +
            "series per category; keep it small (at most 6 series and 24 categories) and round " +
            "the numbers you pass (e.g. 12.3, not 12.3456789). " +
            "For kind=\"svg\": write a single <svg> root with a viewBox (e.g. " +
            "viewBox=\"0 0 800 500\"); the app paints an opaque WHITE background behind your " +
            "drawing, so design for a light background — dark ink (#2C2C2A) for text and " +
            "saturated fills (#185FA5, #0F6E56, #BA7517, #534AB7, #993556) for shapes. Use " +
            "plain shapes, paths, lines and <text> (no scripts, no external images, no " +
            "foreignObject), keep it under 256KB, and keep labels short so they stay readable " +
            "at phone width."

}

/** 主题 ColorScheme → 图表配色（卡片底 = `surfaceBright`，与项目「卡片色」口径一致）。 */
fun chartPaletteOf(
    scheme: androidx.compose.material3.ColorScheme,
    dark: Boolean,
): ChartPalette = ChartPalette(
    background = scheme.surfaceBright.toArgbLong(),
    axis = scheme.outlineVariant.toArgbLong(),
    text = scheme.onSurface.toArgbLong(),
    muted = blend(scheme.onSurface, scheme.surfaceBright, 0.66f).toArgbLong(),
    series = if (dark) ChartPalette.DARK_CATEGORICAL else ChartPalette.CATEGORICAL,
)

private fun androidx.compose.ui.graphics.Color.toArgbLong(): Long = android.graphics.Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
).toLong() and 0xFFFFFFFFL

/** 按 [fraction] 把 [foreground] 混到 [background] 上（等价于前景 alpha 合成后的实色）。 */
internal fun blend(
    foreground: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    fraction: Float,
): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(
    red = foreground.red * fraction + background.red * (1 - fraction),
    green = foreground.green * fraction + background.green * (1 - fraction),
    blue = foreground.blue * fraction + background.blue * (1 - fraction),
)
