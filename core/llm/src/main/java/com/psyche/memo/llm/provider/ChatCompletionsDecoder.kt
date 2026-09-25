package com.psyche.memo.llm.provider

import com.psyche.memo.common.logging.FlutterLogger
import com.psyche.memo.llm.stream.DecodeResult
import com.psyche.memo.llm.stream.SseEvent
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stateful OpenAI Chat Completions SSE decoder (mirrors Dart
 * ChatCompletionsStreamDecoder). One instance per HTTP response.
 */
class ChatCompletionsDecoder(
    private val needsReasoningEcho: Boolean = false,
    /** Provider key, only used to label decoder failures in the app log. */
    private val providerLabel: String = "",
) : com.psyche.memo.llm.stream.StreamDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    var finishReason: String? = null
    var reasoningEcho: StringBuilder? = if (needsReasoningEcho) StringBuilder() else null
    var assistantContent: String = ""
        private set

    /** index -> {id, name, args, extra_content} */
    private val toolCalls = LinkedHashMap<Int, JsonObject>()
    /** index -> series id, so continuation fragments (which omit `id`) still land on the same call. */
    private val toolIdsByIndex = HashMap<Int, String>()
    private var usageJson: JsonObject? = null
    private var closed = false
    private var completed = false

    fun usage(): JsonObject? = usageJson

    override fun accept(event: SseEvent): DecodeResult {
        if (closed || completed) return DecodeResult(chunks = emptyList(), completed = true)
        val data = event.data
        if (data.isEmpty()) return DecodeResult(chunks = emptyList())
        // 带内错误帧（200 OK 里塞 {"error":…}）必须在解析正文前抛出，
        // 否则半成品会被当成功收尾。chat_api_helpers.dart:857-919。
        throwIfInBandStreamError(data)
        if (data == "[DONE]") {
            completed = true
            val out = ArrayList<StreamChunk>()
            out.add(StreamChunk.Finish(finishReason, usageJson))
            return DecodeResult(chunks = out, completed = true)
        }
        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            reportParseError(event, e)
            return DecodeResult(chunks = emptyList())
        }
        val chunks = ArrayList<StreamChunk>()
        try {
            parseEvent(obj, chunks)
        } catch (e: Exception) {
            // Malformed payloads never kill the stream; log and continue.
            reportParseError(event, e)
        }
        return DecodeResult(chunks = chunks, completed = completed)
    }

    /** chat_api_helpers.dart:839-845 —— DecoderParseError 一行一条。 */
    private fun reportParseError(event: SseEvent, error: Exception) {
        FlutterLogger.log(
            "provider=$providerLabel eventType=${event.event ?: "message"} error=$error",
            tag = "DecoderParseError",
        )
    }

    override fun onClosed(): List<StreamChunk> {
        if (closed) return emptyList()
        closed = true
        if (completed) return emptyList()
        return endOpenTools()
    }
    private fun parseEvent(obj: JsonObject, chunks: MutableList<StreamChunk>) {
        var content = ""
        var reasoning: String? = null

        val choices = obj["choices"]?.let { it as? JsonArray }
        if (choices != null && choices.isNotEmpty()) {
            val c0 = choices.firstOrNull()?.jsonObject
            if (c0 != null) {
                finishReason = (c0["finish_reason"] as? JsonPrimitive)?.contentOrNull
                val message = c0["message"]?.jsonObject
                val delta = c0["delta"]?.jsonObject
                if (delta != null) {
                    val deltaContent = extractDeltaText(delta)
                    if (deltaContent.isNotEmpty()) {
                        content += deltaContent
                        assistantContent += deltaContent
                    }
                    (delta["reasoning_content"] ?: delta["reasoning"])?.let { rc ->
                        val rcText = (rc as? JsonPrimitive)?.contentOrNull
                        if (!rcText.isNullOrEmpty()) {
                            reasoning = rcText
                            reasoningEcho?.append(rcText)
                        }
                    }
                    accumulateToolCalls(delta["tool_calls"], chunks)
                }
                if (message != null) {
                    // **同一个 JSON null 陷阱，非流式分支也踩得着**：DeepSeek / GLM 在没开
                    // 思考时照样回 `"reasoning_content": null`，而 `JsonNull` 也是
                    // `JsonPrimitive`、`.content` 是字面量 "null" ⇒ 凭空开一张写着 null 的
                    // 思考卡、正文里也混进 null。流式分支用 contentOrNull 挡过（见
                    // JsonNullTrapTest），这里同源补齐（NonStreamChatTest 钉住）。
                    (message["content"] as? JsonPrimitive)?.contentOrNull?.let { m ->
                        if (m.isNotEmpty()) {
                            content += m
                            assistantContent += m
                        }
                    }
                    val rcMsg = (message["reasoning_content"] ?: message["reasoning"])
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (!rcMsg.isNullOrEmpty()) {
                        reasoningEcho?.append(rcMsg)
                        reasoning = reasoning ?: rcMsg
                    }
                    if (delta == null || delta["tool_calls"] == null) {
                        ingestCompleteToolCalls(message["tool_calls"], chunks)
                    }
                }
            }
        }

        val rootToolCalls = obj["tool_calls"]?.let { it as? JsonArray }
        if (rootToolCalls != null && rootToolCalls.isNotEmpty()) {
            for (t in rootToolCalls) {
                val tObj = t.jsonObject ?: continue
                val id = (tObj["id"] as? JsonPrimitive)?.contentOrNull ?: ""
                val type = (tObj["type"] as? JsonPrimitive)?.contentOrNull ?: "function"
                if (type != "function") continue
                val func = tObj["function"]?.jsonObject ?: continue
                val name = (func["name"] as? JsonPrimitive)?.contentOrNull ?: ""
                val argsStr = (func["arguments"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (name.isEmpty()) continue
                val idx = toolCalls.size
                toolCalls[idx] = JsonObject(
                    mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "args" to JsonPrimitive(argsStr)),
                )
                chunks.add(StreamChunk.ToolCallDelta(toolSeriesId(idx, id), name, argsStr))
            }
            finishReason = "tool_calls"
        }

        obj["usage"]?.let { usageJson = it.jsonObject }

        if (reasoning != null && reasoning.isNotEmpty()) {
            chunks.add(StreamChunk.ReasoningDelta(reasoning))
        }
        if (content.isNotEmpty()) {
            chunks.add(StreamChunk.TextDelta(content))
        }
    }

    /**
     * chat_completions_decoder.dart `_toolSeriesId` 346-352 — the first id seen
     * for a stream index wins, so OpenAI fragments that omit `id` fold onto the
     * same call instead of starting a new one.
     */
    private fun toolSeriesId(index: Int, vendorId: String): String {
        toolIdsByIndex[index]?.let { return it }
        val id = vendorId.ifEmpty { "tool-${index + 1}" }
        toolIdsByIndex[index] = id
        return id
    }

    private fun accumulateToolCalls(delta: Any?, chunks: MutableList<StreamChunk>) {
        val items = delta as? JsonArray ?: return
        for (item in items) {
            val obj = item.jsonObject ?: continue
            val index = (obj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: ""
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "function"
            if (type != "function") continue
            val func = obj["function"]?.jsonObject ?: continue
            val nameDelta = (func["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            val argsDelta = (func["arguments"] as? JsonPrimitive)?.contentOrNull ?: ""
            val entry = toolCalls.getOrPut(index) {
                JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(""), "args" to JsonPrimitive("")))
            }
            val eventId = toolSeriesId(index, id)
            toolCalls[index] = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(if (entry["id"]?.jsonPrimitive?.content.isNullOrEmpty()) id else entry["id"]!!.jsonPrimitive.content),
                    "name" to JsonPrimitive(entry["name"]!!.jsonPrimitive.content + nameDelta),
                    "args" to JsonPrimitive(entry["args"]!!.jsonPrimitive.content + argsDelta),
                ),
            )
            chunks.add(StreamChunk.ToolCallDelta(eventId, nameDelta, argsDelta))
        }
    }

    private fun ingestCompleteToolCalls(value: Any?, chunks: MutableList<StreamChunk>) {
        val items = value as? JsonArray ?: return
        for (t in items) {
            val obj = t.jsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: ""
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "function"
            if (type != "function") continue
            val func = obj["function"]?.jsonObject ?: continue
            val name = (func["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            val argsStr = (func["arguments"] as? JsonPrimitive)?.contentOrNull ?: ""
            if (name.isEmpty()) continue
            val idx = toolCalls.size
            toolCalls[idx] = JsonObject(
                mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "args" to JsonPrimitive(argsStr)),
            )
            chunks.add(StreamChunk.ToolCallDelta(toolSeriesId(idx, id), name, argsStr))
        }
    }

    private fun endOpenTools(): List<StreamChunk> = emptyList()

    private fun extractDeltaText(delta: JsonObject): String {
        val content = delta["content"] ?: return ""
        // **JSON null 陷阱**：kotlinx 的 `JsonNull` 也是 `JsonPrimitive`，`.content`
        // 会给出字面量 "null" —— DeepSeek 思考时每个 chunk 都是
        // `{"content":null,"reasoning_content":"…"}`，用 `.content` 会把 "null"
        // 当正文追加进消息（用户实测「换 DeepSeek 输出全是乱的、会输出 null」）。
        // `contentOrNull` 对 JsonNull 返回 null。
        if (content is JsonPrimitive) return content.contentOrNull ?: ""
        // Array-form content with type = text / image_url — P1 keeps index 0 text.
        val array = (content as? JsonArray) ?: return ""
        return array.firstOrNull()?.jsonObject?.get("text")
            ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
    }
}
