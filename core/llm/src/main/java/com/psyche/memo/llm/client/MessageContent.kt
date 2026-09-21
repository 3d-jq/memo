package com.psyche.memo.llm.client

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64

/**
 * Multimodal message content — turns the `parts` payloads carried on
 * [LlmMessage] into each provider's wire shape.
 *
 * Ported rules (chat_completions_api.dart / claude_history.dart /
 * google_common.dart):
 * - `data:` URIs and local files are inlined as base64,
 * - remote http(s) images keep their URL for OpenAI and degrade to a text
 *   block for Claude (upstream preserves the URL as text there),
 * - duplicate sources collapse to one attachment,
 * - `file` parts are skipped: upstream extracts their text through
 *   document_text_extractor.dart, which is not ported yet.
 */
object MessageContent {

    private val json = Json { ignoreUnknownKeys = true }

    /** Image payloads in order, deduplicated by source. */
    fun imagesOf(message: LlmMessage): List<LlmImage> {
        val out = ArrayList<LlmImage>()
        val seen = HashSet<String>()
        for (payload in message.parts) {
            val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: continue
            val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (uri.isEmpty()) continue
            // File parts carry a name; their text extraction
            // (document_text_extractor.dart) is not ported yet, so they are
            // omitted from the request instead of being sent as images.
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
            if (!name.isNullOrEmpty()) continue
            if (!seen.add(uri)) continue
            out.add(LlmImage(uri, (obj["mime"] as? JsonPrimitive)?.contentOrNull))
        }
        return out
    }

    /**
     * 可直接内嵌的图片地址：远端 http(s)/data URI 原样返回，本地文件读成
     * base64 data URI（读不到返回 null）。OpenAI 系（含 Responses）共用。
     */
    fun dataUrlFor(image: LlmImage): String? = when {
        image.uri.startsWith("http://") || image.uri.startsWith("https://") -> image.uri
        image.uri.startsWith("data:") -> image.uri
        else -> readBase64(image.uri)?.let { "data:${mimeFor(image.uri, explicit = null)};base64,$it" }
    }

    /** OpenAI chat-completions content: plain string when text-only. */
    fun openAiContent(message: LlmMessage): JsonElement {
        val images = imagesOf(message)
        val text = message.content ?: ""
        if (images.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            for (image in images) {
                val url = dataUrlFor(image) ?: continue
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", url) }
                })
            }
        }
    }

    /**
     * Claude content blocks: text first, then images. Remote URLs become text
     * blocks exactly like claude_history.dart's addClaudeImage.
     */
    fun claudeContent(message: LlmMessage): JsonElement {
        val images = imagesOf(message)
        val text = message.content ?: ""
        if (images.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            for (image in images) {
                if (image.uri.startsWith("http://") || image.uri.startsWith("https://")) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", image.uri)
                    })
                    continue
                }
                val b64 = readBase64(image.uri) ?: continue
                add(buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", mimeFor(image.uri, explicit = null))
                        put("data", b64)
                    }
                })
            }
        }
    }

    /** Gemini parts: text part plus inline_data parts. */
    fun geminiParts(message: LlmMessage): JsonArray {
        val images = imagesOf(message)
        val text = message.content ?: ""
        return buildJsonArray {
            if (text.isNotEmpty() || images.isEmpty()) {
                add(buildJsonObject { put("text", text) })
            }
            for (image in images) {
                if (image.uri.startsWith("http://") || image.uri.startsWith("https://")) {
                    add(buildJsonObject {
                        putJsonObject("file_data") {
                            put("file_uri", image.uri)
                            put("mime_type", mimeFor(image.uri, explicit = null))
                        }
                    })
                    continue
                }
                val b64 = readBase64(image.uri) ?: continue
                add(buildJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", mimeFor(image.uri, explicit = null))
                        put("data", b64)
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具结果里的图片（照 RikkaHub `UIMessagePart.Tool.toToolResultContent`
    // 与各 provider 的 tool_result 编码）
    //
    // 只有模型支持图片输入时才把图片作为多模态内容回传，否则换成文本占位 ——
    // 上游注释原话：「避免发给不支持的模型报错」。
    // ------------------------------------------------------------------

    /** 图片编码失败的占位（逐字照上游）。 */
    const val ENCODE_FAILED = "Error: Failed to encode image to base64"

    /** 不支持图片输入时的占位（逐字照上游）。 */
    const val IMAGE_OMITTED =
        "[Image output omitted: current model does not support image input]"

    private fun toolImagesOf(message: LlmMessage, supportsImageInput: Boolean): List<LlmImage> =
        if (supportsImageInput) message.toolImages else emptyList()

    /** 工具结果正文；图片被丢掉时补上占位文案，让模型知道这里本来有图。 */
    private fun toolResultText(message: LlmMessage, supportsImageInput: Boolean): String {
        val text = message.content ?: ""
        val omitted = if (supportsImageInput || message.toolImages.isEmpty()) "" else IMAGE_OMITTED
        return listOf(text, omitted).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** OpenAI chat-completions 的 tool 消息 content（只有带图时才换成数组形态）。 */
    fun openAiToolResultContent(message: LlmMessage, supportsImageInput: Boolean): JsonElement {
        val images = toolImagesOf(message, supportsImageInput)
        val text = toolResultText(message, supportsImageInput)
        if (images.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            for (image in images) {
                val url = dataUrlFor(image)
                if (url == null) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", ENCODE_FAILED)
                    })
                    continue
                }
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", url) }
                })
            }
        }
    }

    /** Responses API 的 `function_call_output.output`：input_text + input_image。 */
    fun responsesToolResultOutput(message: LlmMessage, supportsImageInput: Boolean): JsonElement {
        val images = toolImagesOf(message, supportsImageInput)
        val text = toolResultText(message, supportsImageInput)
        if (images.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
            for (image in images) {
                val url = dataUrlFor(image)
                if (url == null) {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", ENCODE_FAILED)
                    })
                    continue
                }
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", url)
                })
            }
        }
    }

    /** Base64 payload for a data URI or a local file path; null when unreadable. */
    fun readBase64(uri: String): String? {
        if (uri.startsWith("data:")) {
            val idx = uri.indexOf("base64,")
            return if (idx > 0) uri.substring(idx + 7) else null
        }
        val path = when {
            uri.startsWith("file://") -> uri.removePrefix("file://")
            uri.startsWith("file:") -> uri.removePrefix("file:")
            else -> uri
        }
        return runCatching {
            val file = File(path)
            if (!file.isFile) return null
            Base64.getEncoder().encodeToString(file.readBytes())
        }.getOrNull()
    }

    /** Explicit mime > data-URI mime > **真字节嗅探** > extension > image/png (Claude default). */
    fun mimeFor(uri: String, explicit: String?): String {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (uri.startsWith("data:")) {
            val comma = uri.indexOf(',')
            val header = uri.substring(5, if (comma > 5) comma else uri.length)
            val mime = header.split(';').first().trim()
            if (mime.isNotEmpty() && mime != "base64") return mime
        }
        // multimodal_input_utils.dart:287-361 `inferAttachmentMime`：本地文件先读文件头
        // 16 字节嗅探，**声明的 mime 一律不信**（上游 message_generation_service.dart:423
        // 调它时压根不传 explicitMime）。相册 HEIC 被按 image/heic 声明直发就是这里缺这一步，
        // 厂商直接 400「unsupported image」。
        sniffLocalMime(uri)?.let { return it }
        val path = uri.split('?').first()
        val dot = path.lastIndexOf('.')
        if (dot >= 0 && dot < path.length - 1) {
            when (path.substring(dot + 1).lowercase()) {
                "jpg", "jpeg" -> return "image/jpeg"
                "png" -> return "image/png"
                "gif" -> return "image/gif"
                "webp" -> return "image/webp"
                "bmp" -> return "image/bmp"
                "pdf" -> return "application/pdf"
                "txt", "md", "json", "csv" -> return "text/plain"
            }
        }
        return "image/png"
    }

    /**
     * `sniffMimeFromBytes`（multimodal_input_utils.dart:322-361）1:1 —— 只可能返回
     * jpeg/png/gif/webp/pdf，认不出就 null。**刻意没有 heic 分支**：上游全仓
     * `image/heic` 零匹配，它从不向请求声明 HEIC（选图那侧由 image_picker 转成 JPEG，
     * Android 侧这一步在 `AttachmentStore` 里补）。
     */
    internal fun sniffMimeFromBytes(bytes: ByteArray): String? {
        fun at(index: Int, vararg wanted: Int): Boolean =
            bytes.size > index + wanted.size - 1 && wanted.withIndex().all { (offset, value) ->
                bytes[index + offset].toInt() and 0xFF == value
            }
        if (at(0, 0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png"
        if (at(0, 0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) return "image/webp"
        if (at(0, 0x25, 0x50, 0x44, 0x46)) return "application/pdf"
        return null
    }

    /** 读本地文件头 16 字节嗅探；远端/data/读不到都返回 null。 */
    private fun sniffLocalMime(uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("data:")) return null
        val path = uri.substringBefore('?').removePrefix("file:")
        return runCatching {
            java.io.RandomAccessFile(path, "r").use { file ->
                val toRead = minOf(16L, file.length()).toInt()
                if (toRead <= 0) return null
                val head = ByteArray(toRead)
                file.readFully(head)
                sniffMimeFromBytes(head)
            }
        }.getOrNull()
    }
}
