package com.psyche.memo.provider.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * mcp_tool_service.dart `_flattenToolResult`（L248-324）：把 `tools/call` 返回的 content
 * 数组**按原顺序**累加成一整段 markdown 文本交给模型。
 *
 * 关键点（都是过去被丢掉的部分）：
 *  - image 块不是转成多模态部件，而是落盘换回路径后**就地**写一行 `![](...)`，
 *    所以图文顺序保住，对话里的图片横滚条（`parseToolResultImages`）也认这一行；
 *  - resource 块有 text 就当她文本，否则退化成一行 `resource: <uri>`（blob 忽略）；
 *  - audio / 未知类型依次试 text → uri → **美化 JSON 内联**；
 *  - 单个坏块静默跳过（上游每块一个 try），整段不失败；
 *  - `isError` 不看：错误文本也是工具结果（上游如此），所以这里不抛异常。
 *
 * [saveImage] 由容器注入（只有 app 侧拿得到 filesDir），返回落盘后的绝对路径或 null。
 */
internal fun flattenMcpToolResult(
    result: JsonObject,
    saveImage: (mime: String, base64Data: String) -> String?,
): String {
    val blocks = result["content"] as? JsonArray ?: return ""
    val buf = StringBuilder()
    for (element in blocks) {
        val block = element as? JsonObject ?: continue
        runCatching {
            when (block.stringValue("type")) {
                "text" -> writeEscapedToolText(buf, block.stringValue("text").orEmpty())

                "resource" -> {
                    // spec 2025 把载荷嵌在 `resource` 里，老版是平铺的（models.dart:217-235）。
                    val payload = block["resource"] as? JsonObject ?: block
                    val text = payload.stringValue("text").orEmpty()
                    if (text.isNotBlank()) {
                        writeEscapedToolText(buf, text)
                    } else {
                        val uri = payload.stringValue("uri").orEmpty()
                        if (uri.isNotEmpty()) writeEscapedToolText(buf, "resource: $uri")
                    }
                }

                "image" -> {
                    val data = block.stringValue("data").orEmpty()
                    val uri = if (data.isNotEmpty()) {
                        saveImage(block.stringValue("mimeType").orEmpty(), data)
                    } else {
                        block.stringValue("url").orEmpty().takeIf { it.isNotEmpty() }
                    }
                    if (!uri.isNullOrEmpty()) {
                        // 图片行必须独占一行：前面已有内容且不以换行结尾时先补一个换行。
                        if (buf.isNotEmpty() && buf.last() != '\n' && buf.last() != '\r') buf.append('\n')
                        writeLine(buf, "![](" + com.psyche.memo.ui.chat.encodeMarkdownImageDestination(uri) + ")")
                    }
                }

                else -> {
                    val text = block.stringValue("text")
                    val uri = block.stringValue("uri")
                    when {
                        text != null && text.isNotBlank() -> writeEscapedToolText(buf, text)
                        uri != null && uri.isNotEmpty() -> writeEscapedToolText(buf, "resource: $uri")
                        else -> writeEscapedToolText(buf, PRETTY_JSON.encodeToString(JsonObject.serializer(), block))
                    }
                }
            }
        }
    }
    return buf.toString().trim()
}

/** `_writeEscapedToolText` L326-329：剥掉私有区标记后整行写入（空白行不写）。 */
private fun writeEscapedToolText(buf: StringBuilder, text: String) {
    val escaped = escapeMcpStructuredImageText(text)
    if (escaped.isNotBlank()) writeLine(buf, escaped)
}

/** `escapeMcpStructuredImageText`（mcp_structured_image.dart:188-194）：去掉 U+E012/U+E013。 */
private fun escapeMcpStructuredImageText(text: String): String {
    if (text.isEmpty()) return text
    return if (text.indexOf('\uE012') >= 0 || text.indexOf('\uE013') >= 0) {
        text.replace("\uE012", "").replace("\uE013", "")
    } else {
        text
    }
}

private fun writeLine(buf: StringBuilder, text: String) {
    buf.append(text).append('\n')
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private val PRETTY_JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }
