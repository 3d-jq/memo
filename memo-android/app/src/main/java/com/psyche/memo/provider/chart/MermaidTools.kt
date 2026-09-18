package com.psyche.memo.provider.chart

import android.content.Context
import com.psyche.memo.provider.generation.GeneratedMediaStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * `render_mermaid` —— **Mermaid 图**（自研，用户 2026-09-18「加个 Mermaid…分成两个工具吧，
 * Mermaid 和自由画板」）。
 *
 * 与 `render_visual`（自由画板：10 种结构化图 + 手写 SVG）的分工：
 *
 * | 工具 | 输入 | 擅长 |
 * |---|---|---|
 * | **`render_mermaid`** | Mermaid 文本（模型本来就会写） | **专业的结构图**：流程图 / 时序图 / 状态图 / ER / 类图 / 甘特 / 思维导图 / 时间线 / 旅程 / 饼图 / 象限 / git 图 |
 * | `render_visual` | 结构化 spec 或 SVG 标记 | **数据图**（10 种，自动布局 + 跟主题）与任意手绘 |
 *
 * 渲染交给 [MermaidRenderer]（内置 mermaid.js + 离屏 WebView → PNG），产物走**已有图片通道**。
 */
object MermaidTools {

    const val TOOL_NAME = "render_mermaid"

    val ALL_TOOL_NAMES = setOf(TOOL_NAME)

    const val DESCRIPTION =
        "Draw a Mermaid diagram in the conversation — the most reliable way to give the user a " +
            "professional diagram. Whenever structure, sequence, states, relationships, " +
            "schedules or hierarchy would help them understand, draw it instead of describing " +
            "it in prose. Supported types: flowcharts (flowchart TD/LR), sequence diagrams " +
            "(sequenceDiagram), state machines (stateDiagram-v2), class diagrams (classDiagram), " +
            "ER models (erDiagram), Gantt charts (gantt), mind maps (mindmap), timelines " +
            "(timeline), user journeys (journey), pie charts (pie), quadrant charts " +
            "(quadrantChart) and commit graphs (gitGraph). " +
            "Pass the diagram as Mermaid text in the code field — start with a type header " +
            "(e.g. \"flowchart TD\"), one statement per line, short labels. Do not wrap it in " +
            "code fences and do not add a title line unless the diagram type supports it. " +
            "It is rendered locally (no network) into the conversation, following the app theme. " +
            "For plain data charts (bar/line/pie of numbers) use render_visual instead, and for " +
            "hand-drawn figures that Mermaid cannot express use render_visual with kind=\"svg\"."

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
                                    "code",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put(
                                            "description",
                                            JsonPrimitive(
                                                "The Mermaid diagram text, starting with the diagram type " +
                                                    "header, e.g. \"flowchart TD\\n  A[读取数据] --> " +
                                                    "B{校验通过?}\\n  B -->|否| C[返回错误]\\n  " +
                                                    "B -->|是| D[写入库]\".",
                                            ),
                                        )
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("code")) })
                    },
                )
            },
        )
    }

    /** 执行：渲染 → PNG 落盘 → 结果 JSON（与其它产物同一条通道）。 */
    suspend fun execute(context: Context, args: JsonObject, palette: ChartPalette): String {
        val code = (args["code"] as? JsonPrimitive)?.contentOrNull
            ?: return errorJson(
                "mermaid_invalid",
                "code is required: pass the Mermaid diagram text (start with a type header " +
                    "such as \"flowchart TD\").",
            )
        return when (val result = MermaidRenderer.render(context, code, palette)) {
            is MermaidRenderer.Result.Error -> errorJson(
                "mermaid_failed",
                "mermaid could not render this diagram: ${result.message}. " +
                    "Fix the Mermaid syntax and try again.",
            )
            is MermaidRenderer.Result.Ok -> {
                val media = GeneratedMediaStore(context).saveImage(result.png, "image/png")
                buildJsonObject {
                    put("type", JsonPrimitive("mermaid_result"))
                    put("status", JsonPrimitive("ok"))
                    put("rendered", JsonPrimitive(true))
                    put(
                        "note",
                        JsonPrimitive(
                            "Drawn successfully. The image has already been added to the " +
                                "conversation right below this tool call — describe what it " +
                                "shows, and do not tell the user it failed.",
                        ),
                    )
                    put("paths", buildJsonArray { add(JsonPrimitive(media.path)) })
                }.toString()
            }
        }
    }

    private fun errorJson(code: String, message: String): String = buildJsonObject {
        put("type", JsonPrimitive("tool_error"))
        put("error", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
    }.toString()
}
