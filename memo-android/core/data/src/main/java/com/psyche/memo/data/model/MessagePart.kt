package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
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

        fun fromRow(kind: String, payload: String): MessagePart = when (kind) {
            KIND_TEXT -> TextPart(payload)
            KIND_REASONING -> ReasoningPart(payload)
            KIND_TOOL_CALL -> ToolCallPart(payload)
            KIND_IMAGE -> ImagePart.fromPayload(payload)
            KIND_FILE -> FilePart.fromPayload(payload)
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
        ): ToolCallPart {
            val root = LinkedHashMap<String, JsonElement>()
            root["id"] = JsonPrimitive(id)
            root["name"] = JsonPrimitive(name)
            root["arguments"] = arguments
            root["content"] = content ?: JsonNull
            root["server"] = JsonPrimitive(server)
            metadata?.let { root["metadata"] = it }
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
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Decoded tool_call payload. [arguments] keeps the raw JSON fragment. */
data class ToolCallPayload(
    val id: String,
    val name: String,
    val arguments: String,
    val content: String?,
    val server: Boolean,
    val metadata: JsonObject?,
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

private fun getOrNull(json: JsonObject, key: String): String? =
    json[key]?.jsonPrimitive?.content
