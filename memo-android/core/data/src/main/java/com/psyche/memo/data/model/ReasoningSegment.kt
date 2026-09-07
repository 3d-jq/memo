package com.psyche.memo.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One reasoning segment's span + UI state — mirrors `ReasoningSegmentData`
 * (stream_controller.dart). The text itself lives in the message's
 * [ReasoningPart]s, so segments and reasoning parts zip up positionally.
 *
 * Deviation: the Dart payload stores `startAt`/`finishedAt` as ISO-8601 strings
 * and duplicates the segment `text`; the native clock is epoch milliseconds
 * (same unit as `message.timestamp`) and the text is not stored twice.
 */
data class ReasoningSegment(
    var startAt: Long = 0,
    var finishedAt: Long? = null,
    var expanded: Boolean = true,
    var toolStartIndex: Int = 0,
)

/** Reads/writes the `reasoning_segments_json` column. */
object ReasoningSegmentCodec {
    /**
     * serializeReasoningSegments (stream_controller.dart 271-284) — a bare
     * list of segment objects.
     */
    fun encode(segments: List<ReasoningSegment>): String? {
        if (segments.isEmpty()) return null
        val array = JsonArray(
            segments.map { s ->
                JsonObject(
                    linkedMapOf<String, JsonElement>(
                        "startAt" to JsonPrimitive(s.startAt),
                        "finishedAt" to (s.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull),
                        "expanded" to JsonPrimitive(s.expanded),
                        "toolStartIndex" to JsonPrimitive(s.toolStartIndex),
                    ),
                )
            },
        )
        return array.toString()
    }

    /** `_DecodedReasoningPayload.decode` — accepts both the bare list and v2. */
    fun decode(json: String?): List<ReasoningSegment> {
        if (json.isNullOrEmpty()) return emptyList()
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["segments"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return list.mapNotNull { item ->
            val obj = (item as? JsonObject) ?: return@mapNotNull null
            ReasoningSegment(
                startAt = obj.long("startAt") ?: 0,
                finishedAt = obj.long("finishedAt"),
                expanded = (obj["expanded"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: true,
                toolStartIndex = (obj.long("toolStartIndex") ?: 0).toInt(),
            )
        }
    }

    private fun JsonObject.long(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.content?.toLongOrNull()
    }
}
