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

    /**
     * Flip the [ReasoningSegment.expanded] flag at [index].
     *
     * `projectAssistantBlocks` defaults a reasoning block to *expanded* when no
     * stored segment exists (`segment?.expanded ?: true`). For older messages
     * whose `reasoning_segments_json` is null/empty or shorter than the part
     * count, that made blocks show expanded yet uncollapsible (the toggle
     * found no segment and no-op'd). Synthesize the missing entries so the
     * toggle always takes effect, flipping the displayed state.
     */
    /**
     * stream_controller.dart 771/776 —— 决定一组 segment 在**编码前**的展开态：
     * 下标不在 [seenIndices] 里的视为**新段**，赋予 [initialExpanded]；已存在的段
     * 保留其当前 `expanded`（用户在流式过程中手动展开/折叠的点击）。[seenIndices]
     * 会被就地更新（新出现的下标加入其中），返回处理后的列表。
     */
    fun applyInitialExpanded(
        segments: List<ReasoningSegment>,
        seenIndices: MutableSet<Int>,
        initialExpanded: Boolean,
    ): List<ReasoningSegment> = segments.mapIndexed { index, segment ->
        if (seenIndices.add(index)) segment.copy(expanded = initialExpanded) else segment
    }

    /**
     * stream_controller.dart 1231-1255 —— 流结束时若开启「自动折叠思考」，把
     * **已结束**（`finishedAt != null`）的 segment 折起；开关关闭时原样返回，
     * 从而保留用户的手动展开。调用前应先把仍未结束的最后一段补上 finishedAt。
     */
    fun collapseFinishedSegments(
        segments: List<ReasoningSegment>,
        autoCollapse: Boolean,
    ): List<ReasoningSegment> {
        if (!autoCollapse) return segments
        return segments.map { if (it.finishedAt != null) it.copy(expanded = false) else it }
    }

    /**
     * 结束并（按需）折起**最后一段仍未结束**的思考段。
     *
     * 原项目里这是"思考阶段结束"的唯一动作，出现在 6 个时机
     * （`stream_controller.dart` L853 工具调用开始 / L1232 正文开始到达 /
     * L1280 流正常结束 / L1339 用户取消 / L1355 出错 / `finishReasoningIfNeeded`
     * 兜底），每处都是同一套三步：
     * 1. 若最后一段 `finishedAt == null` → 打上 [now]（计时器停住）；
     * 2. 若 [autoCollapse] 开启 → 该段 `expanded = false`（**立刻**折起）；
     * 3. 若未开启 → 只打时间戳，保留用户手动展开。
     *
     * 已经结束的最后一段不会被重复处理（幂等）。返回 (新列表, 是否变化)。
     */
    fun finishLastOpenSegment(
        segments: List<ReasoningSegment>,
        now: Long,
        autoCollapse: Boolean,
    ): Pair<List<ReasoningSegment>, Boolean> {
        val last = segments.lastOrNull() ?: return segments to false
        if (last.finishedAt != null) return segments to false
        val finished = last.copy(
            finishedAt = now,
            expanded = if (autoCollapse) false else last.expanded,
        )
        return segments.toMutableList().also { it[it.lastIndex] = finished } to true
    }

    fun toggleExpandedAt(json: String?, index: Int): String? {
        if (index < 0) return json
        val list = decode(json).toMutableList()
        val displayedExpanded = list.getOrNull(index)?.expanded ?: true
        while (list.size <= index) list.add(ReasoningSegment())
        list[index] = list[index].copy(expanded = !displayedExpanded)
        return encode(list)
    }
}
