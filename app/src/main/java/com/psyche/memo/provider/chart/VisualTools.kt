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
        if (kind == KIND_SVG) return executeRawSvg(context, args, palette)
        return executeChart(context, args, palette, kind)
    }

    // ------------------------------------------------------------------ 结构化图

    /** 结构化 kind：规格校验 → 渲染 SVG → 落盘（走已有图片通道）。 */
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
            put("status", JsonPrimitive("ok"))
            put("rendered", JsonPrimitive(true))
            put("note", JsonPrimitive(RESULT_NOTE))
            put("kind", JsonPrimitive(spec.kind.wireName))
            put("points", JsonPrimitive(spec.categories.size))
            put("series", JsonPrimitive(spec.activeSeries.size))
            put("paths", buildJsonArray { add(JsonPrimitive(media.path)) })
        }.toString()
    }

    // ------------------------------------------------------------------ 手写 SVG

    private fun executeRawSvg(context: Context, args: JsonObject, palette: ChartPalette): String {
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
                    // 底色跟当前主题（暗色模式下就是深色卡片，而不是白纸）。
                    bytes = withBackground(sanitized.svg, palette.background)
                        .toByteArray(Charsets.UTF_8),
                    mimeType = ChartSvgRenderer.MIME,
                )
                buildJsonObject {
                    put("type", JsonPrimitive("svg_result"))
                    put("status", JsonPrimitive("ok"))
                    put("rendered", JsonPrimitive(true))
                    put("note", JsonPrimitive(RESULT_NOTE))
                    put("aspect", JsonPrimitive(sanitized.aspectRatio))
                    put("paths", buildJsonArray { add(JsonPrimitive(media.path)) })
                }.toString()
            }
        }
    }

    /**
     * 结果里给模型的一句话。
     *
     * 为什么要有：原来只回 `{"type":"…","paths":[…]}`，**没有明确的成功标识** ——
     * 模型看半天没找到 status/success，就直接跟用户说「绘制失败」，可图其实已经渲染出来了
     *（用户 2026-09-18「他明明绘制出来怎么说没有绘制成功呀」；设备上同一秒落了两个文件，
     * 证明工具两次都成功了）。
     */
    private const val RESULT_NOTE =
        "Drawn successfully. The image has already been added to the conversation right below " +
            "this tool call — describe what it shows, and do not tell the user it failed."

    /**
     * 给手写 SVG 铺一层底色 —— **用当前主题的卡色**，不是死白
     *（用户 2026-09-18「暗色模式这个 svg 怎么不跟着暗色呀」）。
     */
    internal fun withBackground(svg: String, background: Long): String {
        val rootStart = svg.indexOf("<svg", ignoreCase = true)
        if (rootStart < 0) return svg
        val tagEnd = svg.indexOf('>', rootStart)
        if (tagEnd < 0) return svg
        val fill = "#%06X".format(background and 0xFFFFFF)
        val rect = """<rect x="0" y="0" width="100%" height="100%" fill="$fill"/>"""
        val insertAt = tagEnd + 1
        return svg.substring(0, insertAt) + rect + svg.substring(insertAt)
    }

    // ------------------------------------------------------------------ 主题配色

    /**
     * 按当前主题解析图表配色 —— **与 App 主题同一套解析**（照 MainActivity 那段）：
     * 自定义主题、Material You 动态取色、纯色背景、分层表面全都认。
     *
     * 之前只读「预设 id + 明暗」，于是自定义主题 / 动态取色下图表会退回默认配色
     *（用户 2026-09-18「还有没有办法跟主题吗」）。
     */
    fun paletteFor(container: AppContainerImpl): ChartPalette {
        val context = container.appContext
        val systemDark = (
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
        val dark = MemoTheme.resolve("default", com.psyche.memo.ui.ThemeState.mode, systemDark).second
        val scheme = chartSchemeFor(
            context = context,
            palette = com.psyche.memo.ui.ThemeState.resolvePalette(),
            dark = dark,
            pureBackground = com.psyche.memo.ui.ThemeState.usePureBackground,
            layeredSurfaces = com.psyche.memo.ui.ThemeState.useLayeredSurfaces,
            dynamicColor = com.psyche.memo.ui.ThemeState.useDynamicColor,
        )
        return chartPaletteOf(scheme, dark)
    }

    /** App 主题的三条通道（与 MainActivity 的 `colorScheme` 分支一致）。 */
    internal fun chartSchemeFor(
        context: Context,
        palette: com.psyche.memo.ui.theme.Palette,
        dark: Boolean,
        pureBackground: Boolean,
        layeredSurfaces: Boolean,
        dynamicColor: Boolean,
    ): androidx.compose.material3.ColorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val dynamic = if (dark) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
            MemoTheme.withDerivedSurfaceContainers(
                MemoTheme.applyPageSurface(dynamic, dark, pureBackground, layeredSurfaces),
                dark,
                layeredSurfaces,
            )
        }
        palette.id in com.psyche.memo.ui.theme.authoredSurfacePaletteIds ->
            MemoTheme.authoredColorScheme(palette, dark, pureBackground)
        else -> MemoTheme.colorScheme(palette, dark, pureBackground, layeredSurfaces)
    }

    private fun kindList(): String = ChartSpec.Kind.entries.joinToString(", ") { it.wireName } +
        ", $KIND_SVG"

    private fun errorJson(code: String, message: String): String = buildJsonObject {
        put("type", JsonPrimitive("tool_error"))
        put("error", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
    }.toString()

    /**
     * `kind` **字段**的说明 —— 模型同样会读它。
     *
     * 2026-09-18：这行原先写的是保守版（「use svg only for things a data chart cannot
     * express」「anything the other kinds cannot express」），与顶层 [DESCRIPTION] 的
     * 「Your drawing canvas…draw it, do not just describe it」自相矛盾 —— 模型读到字段
     * 说明里的「只能」就被劝退了，画板定位落不了地。现在改成与顶层一致的积极版：
     * svg 是**自由画板**，凡图表表达不了/不好表达的都可以画；结构图仍让位给
     * `render_mermaid`（差异化保留）。
     */
    private const val KIND_DESCRIPTION =
        "What to draw. Pick the mode that fits: a data-chart kind when the content is " +
            "actually data (we lay it out and colour it to match the app theme), or svg " +
            "when you want to draw the picture yourself. " +
            "bar = grouped/stacked columns; hbar = horizontal bars (better with long category " +
            "names); line = trend; area = trend with filled area; pie = parts of a whole; " +
            "donut = pie with the total in the middle; scatter = correlation; " +
            "funnel = conversion stages (categories are the stages); " +
            "gauge = one value against a maximum (first number is the value, optional second " +
            "number is the maximum, default 100); heatmap = one row per series, one column per " +
            "category, colour = magnitude; " +
            "svg = your free drawing canvas: you write the SVG yourself, so anything a chart " +
            "cannot express is fair game — annotated figures, dashboards, UI sketches, " +
            "comparisons, explainers, whatever makes the point clearer than prose. " +
            "For structural diagrams (flowcharts, " +
            "sequence/state/ER/class diagrams, gantt, mind maps, timelines) prefer the " +
            "render_mermaid tool instead."

    const val DESCRIPTION =
        "Your drawing canvas for this conversation. Whenever a picture would help the user " +
            "understand better than prose — a comparison, a trend, a distribution, parts of a " +
            "whole, a summary at a glance, an explainer — draw it, do not just describe it. " +
            "Two modes: (1) a data chart (bar, hbar, line, area, pie, donut, scatter, funnel, " +
            "gauge, heatmap) from numbers you pass; (2) kind=\"svg\" where you write the SVG " +
            "yourself for anything a chart cannot express. " +
            "For structure and flow — flowcharts, sequence/state/ER/class diagrams, gantt, " +
            "mind maps, timelines — use the render_mermaid tool instead. " +
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
