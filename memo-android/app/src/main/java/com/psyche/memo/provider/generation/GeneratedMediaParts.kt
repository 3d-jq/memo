package com.psyche.memo.provider.generation

import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.MessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 生成类工具（`generate_image` / `generate_video`）的结果 JSON → **助手消息里的产物
 * part**（图片 / 视频）。
 *
 * 结果形如 `{"type":"image_generation_result","status":"succeeded","paths":[…]}` 或
 * `{"type":"video_generation_result","status":"succeeded","path":"…"}`。
 *
 * 两条口径（2026-09-17 定）：
 *  1. **并进当前这一轮**，不单开一条消息 —— 产物紧跟工具卡出现在**同一条助手消息**
 *     里（用户：「生成结果再开一个输出…好割裂呀」）；图片走助手图片气泡 + 查看器、
 *     视频走文件卡（点开交给系统播放器）。视频与图片是同一条路径，行为一致。
 *  2. 提示词**不**重复成文本 part（工具卡里已经有参数）；解析失败 / 没有产物 →
 *     空表（工具 JSON 本身已把错误讲清楚，模型据此回话）。
 */
internal fun generatedMediaParts(resultJson: String): List<MessagePart> {
    val result = runCatching { Json.parseToJsonElement(resultJson) }.getOrNull() as? JsonObject
        ?: return emptyList()
    val imagePaths = (result["paths"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    val videoPath = (result["path"] as? JsonPrimitive)?.contentOrNull
    return buildList {
        imagePaths.forEach { path -> add(ImagePart(uri = path, mime = mimeForGeneratedPath(path))) }
        videoPath?.let { path ->
            add(FilePart(uri = path, name = java.io.File(path).name, mime = "video/mp4"))
        }
    }
}

/** 产物路径 → MIME（生成结果只可能是这几种；认不出按 png）。 */
internal fun mimeForGeneratedPath(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        // 图表工具（render_chart）产出的是 SVG：coil 的 SvgDecoder 直接能渲染。
        "svg" -> "image/svg+xml"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

/**
 * 会把产物挂进消息的工具（生成图片 / 生成视频 / 绘制图表 / 自由绘制）—— `ChatViewModel`
 * 用它决定「这个工具的结果要不要转成消息里的媒体 part」。
 */
internal val MEDIA_TOOL_NAMES: Set<String> =
    com.psyche.memo.provider.generation.GenerationTools.ALL_TOOL_NAMES +
        com.psyche.memo.provider.chart.VisualTools.ALL_TOOL_NAMES +
        com.psyche.memo.provider.chart.MermaidTools.ALL_TOOL_NAMES
