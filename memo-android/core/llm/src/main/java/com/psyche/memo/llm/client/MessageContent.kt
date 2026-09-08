package com.psyche.memo.llm.client

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

    data class ImageRef(val uri: String, val mime: String?)

    /** Image payloads in order, deduplicated by source. */
    fun imagesOf(message: LlmMessage): List<ImageRef> {
        val out = ArrayList<ImageRef>()
        val seen = HashSet<String>()
        for (payload in message.parts) {
            val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: continue
            val uri = (obj["uri"] as? JsonPrimitive)?.content ?: continue
            if (uri.isEmpty()) continue
            // File parts carry a name; their text extraction
            // (document_text_extractor.dart) is not ported yet, so they are
            // omitted from the request instead of being sent as images.
            val name = (obj["name"] as? JsonPrimitive)?.content
            if (!name.isNullOrEmpty()) continue
            if (!seen.add(uri)) continue
            out.add(ImageRef(uri, (obj["mime"] as? JsonPrimitive)?.content))
        }
        return out
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
                val url = when {
                    image.uri.startsWith("http://") || image.uri.startsWith("https://") -> image.uri
                    image.uri.startsWith("data:") -> image.uri
                    else -> {
                        val b64 = readBase64(image.uri) ?: continue
                        "data:${mimeFor(image.uri, image.mime)};base64,$b64"
                    }
                }
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
                        put("media_type", mimeFor(image.uri, image.mime))
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
                            put("mime_type", mimeFor(image.uri, image.mime))
                        }
                    })
                    continue
                }
                val b64 = readBase64(image.uri) ?: continue
                add(buildJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", mimeFor(image.uri, image.mime))
                        put("data", b64)
                    }
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

    /** Explicit mime > data-URI mime > extension > image/png (Claude default). */
    fun mimeFor(uri: String, explicit: String?): String {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (uri.startsWith("data:")) {
            val comma = uri.indexOf(',')
            val header = uri.substring(5, if (comma > 5) comma else uri.length)
            val mime = header.split(';').first().trim()
            if (mime.isNotEmpty() && mime != "base64") return mime
        }
        val path = uri.split('?').first()
        val dot = path.lastIndexOf('.')
        if (dot >= 0 && dot < path.length - 1) {
            when (path.substring(dot + 1).lowercase()) {
                "jpg", "jpeg" -> return "image/jpeg"
                "png" -> return "image/png"
                "gif" -> return "image/gif"
                "webp" -> return "image/webp"
                "heic" -> return "image/heic"
                "bmp" -> return "image/bmp"
                "pdf" -> return "application/pdf"
                "txt", "md", "json", "csv" -> return "text/plain"
            }
        }
        return "image/png"
    }
}
