package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.CompactionPart
import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Message export text builder — port of message_export_sheet.dart
 * `_writeExportBlocks` / `exportChatMessagesMarkdown` / `exportChatMessagesTxt`
 * (image export via widget capture is not ported).
 */
object MessageExport {

    /** Minimal message view the exporter needs (ChatMessage / UiMessage). */
    data class ExportMessage(
        val role: String,
        val parts: List<MessagePart>,
        val timestamp: Long,
        val modelName: String? = null,
    )

    /**
     * Markdown / plain-text export of [messages].
     *
     * [includeThinking] mirrors `showThinkingAndToolCards && expandThinkingContent`,
     * [includeTools] mirrors `showThinkingAndToolCards`. [imageLine] returns the
     * line(s) written for an image uri (markdown inlines local files as base64).
     */
    fun export(
        title: String,
        messages: List<ExportMessage>,
        roleNameOf: (ExportMessage) -> String,
        timeOf: (Long) -> String,
        thinkingLabel: String,
        includeThinking: Boolean,
        includeTools: Boolean,
        markdown: Boolean,
        imageLine: (String) -> String,
    ): String {
        val buf = StringBuilder()
        buf.append(if (markdown) "# $title" else title).append('\n')
        buf.append('\n')
        // 压缩检查点不是真实发言（用户点名摘要不进对话/导出）：整条跳过。
        for (msg in messages.filterNot { m -> m.parts.any { it is CompactionPart } }) {
            buf.append("${timeOf(msg.timestamp)} · ${roleNameOf(msg)}").append('\n')
            buf.append('\n')
            writeBlocks(
                buf = buf,
                message = msg,
                includeThinking = includeThinking,
                includeTools = includeTools,
                thinkingLabel = thinkingLabel,
                markdown = markdown,
                imageLine = imageLine,
            )
            buf.append("\n---\n")
        }
        return buf.toString()
    }

    /** _writeExportBlocks — walk the structured parts in order. */
    fun writeBlocks(
        buf: StringBuilder,
        message: ExportMessage,
        includeThinking: Boolean,
        includeTools: Boolean,
        thinkingLabel: String,
        markdown: Boolean,
        imageLine: (String) -> String,
    ) {
        for (part in message.parts) {
            when (part) {
                is TextPart -> {
                    if (part.text.isEmpty()) continue
                    buf.append(part.text).append('\n')
                    buf.append('\n')
                }
                is ImagePart -> {
                    val uri = part.uri.trim()
                    if (uri.isEmpty()) continue
                    buf.append(imageLine(uri)).append('\n')
                }
                is FilePart -> {
                    val mime = part.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                    if (markdown) {
                        buf.append("- ${part.name}  `($mime)`").append('\n')
                    } else {
                        buf.append("- ${part.name} ($mime)").append('\n')
                    }
                }
                is ReasoningPart -> {
                    if (!includeThinking || part.text.trim().isEmpty()) continue
                    buf.append('\n')
                    buf.append(if (markdown) "**$thinkingLabel**" else "[$thinkingLabel]").append('\n')
                    buf.append('\n')
                    if (markdown) {
                        buf.append("```text").append('\n')
                        buf.append(part.text.trim()).append('\n')
                        buf.append("```").append('\n')
                    } else {
                        buf.append(part.text.trim()).append('\n')
                    }
                    buf.append('\n')
                }
                is ToolCallPart -> {
                    if (!includeTools) continue
                    val obj = runCatching { EXPORT_JSON.parseToJsonElement(part.payloadJson).jsonObject }
                        .getOrNull() ?: continue
                    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                    // toolResultContentForModel 的 legacy MCP envelope 转换未移植，
                    // 直接写 content 原文（普通工具结果即纯文本）。
                    val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
                    buf.append(if (name.isEmpty()) "[tool]" else "[$name]").append('\n')
                    if (content.trim().isNotEmpty()) buf.append(content).append('\n')
                    buf.append('\n')
                }
                else -> Unit
            }
        }
    }

    private val EXPORT_JSON = Json { ignoreUnknownKeys = true }
}
