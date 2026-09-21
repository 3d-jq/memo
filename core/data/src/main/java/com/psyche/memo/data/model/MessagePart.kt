package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured message part — mirrors Flutter `MessagePart`.
 *
 * Payload contract (same as Dart):
 * - text / reasoning: raw string
 * - tool_call: JSON string preserved as-is
 * - image: {"uri","mime"?,"assetId"?,"unavailable"?}
 * - file: {"uri","name","mime"?,"assetId"?,"unavailable"?}
 * - unknown kinds are preserved losslessly via [UnknownPart]
 */
sealed class MessagePart {
    abstract val kind: String
    abstract fun encodePayload(): String

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_REASONING = "reasoning"
        const val KIND_TOOL_CALL = "tool_call"
        const val KIND_IMAGE = "image"
        const val KIND_FILE = "file"

        /**
         * 上下文压缩检查点（本工程新增，opencode compaction 消息的等价物）：
         * payload 是 {"summary","recent","boundary"}。它**不是**发给模型的普通
         * 消息 —— 组装请求时整条替换成 `<conversation-checkpoint>` user 轮次。
         */
        const val KIND_COMPACTION = "compaction"

        fun fromRow(kind: String, payload: String): MessagePart = when (kind) {
            KIND_TEXT -> TextPart(payload)
            KIND_REASONING -> ReasoningPart(payload)
            KIND_TOOL_CALL -> ToolCallPart(payload)
            KIND_IMAGE -> ImagePart.fromPayload(payload)
            KIND_FILE -> FilePart.fromPayload(payload)
            KIND_COMPACTION -> CompactionPart.fromPayload(payload)
            else -> UnknownPart(rawKind = kind, payload = payload)
        }
    }
}

class TextPart(val text: String) : MessagePart() {
    override val kind: String get() = KIND_TEXT
    override fun encodePayload(): String = text
}

class ReasoningPart(val text: String) : MessagePart() {
    override val kind: String get() = KIND_REASONING
    override fun encodePayload(): String = text
}

class ToolCallPart(val payloadJson: String) : MessagePart() {
    override val kind: String get() = KIND_TOOL_CALL
    override fun encodePayload(): String = payloadJson

    companion object {
        /**
         * stream_chunk_handler.dart `_upsertTool` 339-347 — the payload is
         * always `{id, name, arguments, content, server}` plus `metadata`
         * when non-empty.
         */
        fun encode(
            id: String,
            name: String,
            arguments: JsonElement,
            content: JsonElement?,
            server: Boolean,
            metadata: JsonObject? = null,
            images: List<ToolImage> = emptyList(),
        ): ToolCallPart {
            val root = LinkedHashMap<String, JsonElement>()
            root["id"] = JsonPrimitive(id)
            root["name"] = JsonPrimitive(name)
            root["arguments"] = arguments
            root["content"] = content ?: JsonNull
            root["server"] = JsonPrimitive(server)
            metadata?.let { root["metadata"] = it }
            // 工具结果附带的图片（本工程新增，见 ToolImage 的注释）；为空时不写这个键，
            // 老 payload 的形状原样保持。
            if (images.isNotEmpty()) {
                root["images"] = JsonArray(
                    images.map {
                        JsonObject(
                            buildMap {
                                put("uri", JsonPrimitive(it.uri))
                                it.mime?.let { mime -> put("mime", JsonPrimitive(mime)) }
                            },
                        )
                    },
                )
            }
            return ToolCallPart(JsonObject(root).toString())
        }

        /** Reverse of [encode]; null for payloads that predate the contract. */
        fun decode(payloadJson: String): ToolCallPayload? = try {
            val obj = Json.parseToJsonElement(payloadJson).jsonObject
            ToolCallPayload(
                id = obj.string("id") ?: "",
                name = obj.string("name") ?: "",
                arguments = obj.value("arguments") ?: "",
                content = obj.value("content"),
                server = obj.string("server")?.toBooleanStrictOrNull() ?: false,
                metadata = obj["metadata"] as? JsonObject,
                images = (obj["images"] as? JsonArray)?.mapNotNull { element ->
                    val image = element as? JsonObject ?: return@mapNotNull null
                    val uri = image.string("uri") ?: return@mapNotNull null
                    ToolImage(uri = uri, mime = image.string("mime"))
                }.orEmpty(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 工具结果附带的图片 —— 上游 RikkaHub 的工具结果本身就是 `List<UIMessagePart>`
 * （Text / Image 混排），Memo 的工具结果只有 `content` 字符串，所以图片挂在 payload
 * 的 `images` 键上。这是**本工程新增的 payload 键**（Flutter 原版没有工作区，也就没有
 * 带图的工具结果），读取端全部忽略未知键、缺失即空列表。
 */
data class ToolImage(val uri: String, val mime: String? = null)

/** Decoded tool_call payload. [arguments] keeps the raw JSON fragment. */
data class ToolCallPayload(
    val id: String,
    val name: String,
    val arguments: String,
    val content: String?,
    val server: Boolean,
    val metadata: JsonObject?,
    val images: List<ToolImage> = emptyList(),
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.value(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return if (element is JsonPrimitive) element.content else element.toString()
}

data class ImagePart(
    val uri: String,
    val mime: String? = null,
    val assetId: String? = null,
    val unavailable: Boolean? = null,
) : MessagePart() {
    override val kind: String get() = KIND_IMAGE

    override fun encodePayload(): String {
        val json = JsonObject(
            buildMap {
                put("uri", JsonPrimitive(uri))
                mime?.let { put("mime", JsonPrimitive(it)) }
                assetId?.let { put("assetId", JsonPrimitive(it)) }
                unavailable?.let { put("unavailable", JsonPrimitive(it)) }
            },
        )
        return json.toString()
    }

    companion object {
        fun fromPayload(payload: String): ImagePart {
            val json = Json.parseToJsonElement(payload).jsonObject
            return ImagePart(
                uri = getOrNull(json, "uri") ?: "",
                mime = getOrNull(json, "mime"),
                assetId = getOrNull(json, "assetId"),
                unavailable = json["unavailable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            )
        }
    }
}

data class FilePart(
    val uri: String,
    val name: String,
    val mime: String? = null,
    val assetId: String? = null,
    val unavailable: Boolean? = null,
) : MessagePart() {
    override val kind: String get() = KIND_FILE

    override fun encodePayload(): String {
        val json = JsonObject(
            buildMap {
                put("uri", JsonPrimitive(uri))
                put("name", JsonPrimitive(name))
                mime?.let { put("mime", JsonPrimitive(it)) }
                assetId?.let { put("assetId", JsonPrimitive(it)) }
                unavailable?.let { put("unavailable", JsonPrimitive(it)) }
            },
        )
        return json.toString()
    }

    companion object {
        fun fromPayload(payload: String): FilePart {
            val json = Json.parseToJsonElement(payload).jsonObject
            return FilePart(
                uri = getOrNull(json, "uri") ?: "",
                name = getOrNull(json, "name") ?: "",
                mime = getOrNull(json, "mime"),
                assetId = getOrNull(json, "assetId"),
                unavailable = json["unavailable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            )
        }
    }
}

class UnknownPart(val rawKind: String, val payload: String) : MessagePart() {
    override val kind: String get() = rawKind
    override fun encodePayload(): String = payload
}

/**
 * 上下文压缩检查点（见 [MessagePart.KIND_COMPACTION]）。
 *
 * [summary] 是模型按锚定模板归纳出的摘要；[recent] 是压缩时**原样保留**的最近
 * 上下文（opencode `select` 的 recent）；[boundaryOrder] 是已被折叠的最后一条消息
 * 的 `message_order` —— 组装请求时 order ≤ boundary 的消息都不再发送。
 */
data class CompactionPart(
    val summary: String,
    val recent: String,
    val boundaryOrder: Int,
) : MessagePart() {
    override val kind: String get() = KIND_COMPACTION

    override fun encodePayload(): String = JsonObject(
        linkedMapOf(
            "summary" to JsonPrimitive(summary),
            "recent" to JsonPrimitive(recent),
            "boundary" to JsonPrimitive(boundaryOrder),
        ),
    ).toString()

    companion object {
        fun fromPayload(payload: String): CompactionPart {
            val json = try {
                Json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            return CompactionPart(
                summary = getOrNull(json, "summary") ?: "",
                recent = getOrNull(json, "recent") ?: "",
                boundaryOrder = getOrNull(json, "boundary")?.toIntOrNull() ?: -1,
            )
        }
    }
}

private fun getOrNull(json: JsonObject, key: String): String? =
    json[key]?.jsonPrimitive?.content
