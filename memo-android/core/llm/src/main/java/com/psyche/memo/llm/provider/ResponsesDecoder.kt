package com.psyche.memo.llm.provider

import com.psyche.memo.common.logging.FlutterLogger
import com.psyche.memo.llm.stream.DecodeResult
import com.psyche.memo.llm.stream.SseEvent
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * OpenAI **Responses API** SSE 解码器（移植 Dart `ResponsesStreamDecoder`，
 * responses_decoder.dart）。
 *
 * 与 chat-completions 的关键差异：
 * - 正文是 `response.output_text.delta`（不是 `choices[].delta.content`）；
 * - 思考是 `response.reasoning_summary_text.delta` / `response.reasoning_text.delta`；
 * - 工具调用拆成 `output_item.added` / `function_call_arguments.delta` /
 *   `output_item.done` 三段，用 `output_index` 归并；
 * - 终止事件是 `response.completed` / `response.incomplete` / `response.failed`，
 *   用量在 `response.usage`（`input_tokens` / `output_tokens`）。
 *
 * 工具调用 id 取厂商的 `call_id`（不是 Dart 里的 series id）—— 我们上行
 * follow-up 时 `function_call.call_id` 必须与 `function_call_output.call_id`
 * 成对，而 `ToolCallPart.id` 就是回传的那个 id。
 */
class ResponsesDecoder(
    /** Provider key, only used to label decoder failures in the app log. */
    private val providerLabel: String = "",
) : com.psyche.memo.llm.stream.StreamDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    private var closed = false
    private var finishReason: String? = null

    var completed = false
        private set

    /** `response.usage` of the terminal event, raw (`parseUsage` reads it). */
    var usageJson: JsonObject? = null
        private set

    /** Emitted a tool call → callers treat the round as a tool round. */
    var hasFunctionCalls = false
        private set

    /** output_index → id, so argument deltas land on the same call. */
    private val idByIndex = HashMap<Int, String>()

    /** id → 已累加的参数（`output_item.done` 兜底时使用）。 */
    private val argsById = HashMap<String, StringBuilder>()

    /** id → 已经用 name/args 起过头（避免 done 事件把参数再拼一遍）。 */
    private val started = HashSet<String>()

    override fun accept(event: SseEvent): DecodeResult {
        if (closed || completed) return DecodeResult(chunks = emptyList(), completed = true)
        val data = event.data
        if (data.isEmpty()) return DecodeResult(chunks = emptyList())
        // 带内错误帧（200 OK 里塞 {"error":…}）必须在解析正文前抛出，
        // 否则半成品会被当成功收尾。chat_api_helpers.dart:857-919。
        throwIfInBandStreamError(data)
        if (data == "[DONE]") {
            completed = true
            return DecodeResult(chunks = listOf(finishChunk()), completed = true)
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

    override fun onClosed(): List<StreamChunk> {
        if (closed) return emptyList()
        closed = true
        if (completed) return emptyList()
        // 流被掐断（没有 terminal 事件）时也要给出结束标记，否则调用方拿不到用量。
        return listOf(finishChunk())
    }

    private fun finishChunk(): StreamChunk.Finish =
        StreamChunk.Finish(if (hasFunctionCalls) "tool_calls" else finishReason, usageJson)

    private fun parseEvent(obj: JsonObject, chunks: MutableList<StreamChunk>) {
        when (val type = obj.str("type")) {
            "response.output_text.delta" -> {
                val delta = obj.str("delta") ?: return
                if (delta.isNotEmpty()) chunks.add(StreamChunk.TextDelta(delta))
            }

            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                val delta = obj.str("delta") ?: return
                if (delta.isNotEmpty()) chunks.add(StreamChunk.ReasoningDelta(delta))
            }

            "response.output_item.added" -> {
                val item = obj["item"]?.jsonObject ?: return
                if (item.str("type") != "function_call") return
                val id = callIdOf(item, outputIndex(obj))
                startCall(id, item.str("name") ?: "", item.str("arguments") ?: "", chunks)
            }

            "response.function_call_arguments.delta" -> {
                val delta = obj.str("delta") ?: return
                if (delta.isEmpty()) return
                val id = idByIndex[outputIndex(obj)] ?: return
                argsById.getOrPut(id) { StringBuilder() }.append(delta)
                chunks.add(StreamChunk.ToolCallDelta(id, "", delta))
            }

            "response.output_item.done" -> {
                val item = obj["item"]?.jsonObject ?: return
                if (item.str("type") != "function_call") return
                // 已经靠 added/delta 起过头 → 参数在累加，别再拼一遍。
                startCall(
                    callIdOf(item, outputIndex(obj)),
                    item.str("name") ?: "",
                    item.str("arguments") ?: "",
                    chunks,
                )
            }

            "response.completed", "response.incomplete", "response.failed" -> {
                finishReason = when (type) {
                    "response.completed" -> "stop"
                    "response.incomplete" -> "incomplete"
                    else -> "failed"
                }
                val response = obj["response"]?.jsonObject
                (response?.get("usage") as? JsonObject)?.let { usageJson = it }
                // 兜底：terminal 事件里带着完整的 function_call（有些兼容端不发
                // output_item.done），补上没起过头的调用，避免工具轮次丢失。
                val output = response?.get("output") as? JsonArray
                output?.forEachIndexed { index, element ->
                    val item = element as? JsonObject ?: return@forEachIndexed
                    if (item.str("type") != "function_call") return@forEachIndexed
                    startCall(
                        callIdOf(item, index),
                        item.str("name") ?: "",
                        item.str("arguments") ?: "",
                        chunks,
                    )
                }
                completed = true
                chunks.add(finishChunk())
            }
        }
    }

    /** 一条工具调用只起一次头（name + 初始参数），之后靠 delta 续写。 */
    private fun startCall(id: String, name: String, args: String, chunks: MutableList<StreamChunk>) {
        if (!started.add(id)) return
        hasFunctionCalls = true
        chunks.add(StreamChunk.ToolCallDelta(id, name, args))
        argsById.getOrPut(id) { StringBuilder() }.append(args)
    }

    private fun outputIndex(obj: JsonObject): Int = obj.str("output_index")?.toIntOrNull() ?: 0

    /** `call_id` 优先（上行 follow-up 要用它），缺了退回 item id / index。 */
    private fun callIdOf(item: JsonObject, index: Int): String {
        val callId = item.str("call_id")
        if (!callId.isNullOrEmpty()) {
            idByIndex[index] = callId
            return callId
        }
        idByIndex[index]?.let { return it }
        val itemId = item.str("id")
        val id = if (!itemId.isNullOrEmpty()) itemId else "tool-${index + 1}"
        idByIndex[index] = id
        return id
    }

    /** chat_api_helpers.dart:839-845 —— DecoderParseError 一行一条。 */
    private fun reportParseError(event: SseEvent, error: Exception) {
        FlutterLogger.log(
            "provider=$providerLabel eventType=${event.event ?: "message"} error=$error",
            tag = "DecoderParseError",
        )
    }
}

/**
 * **JSON null 陷阱**：kotlinx 的 `JsonNull` 也是 `JsonPrimitive`，`.content`
 * 会给出字面量 "null"；统一用 `contentOrNull`（非 primitive 返回 null）。
 */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** `response.output_text` 的**非流式**取值（`complete()` 用）。 */
internal fun responsesOutputText(obj: JsonObject): String {
    val output = obj["output"] as? JsonArray ?: return ""
    val buffer = StringBuilder()
    for (element in output) {
        val item = element as? JsonObject ?: continue
        if (item.str("type") != "message") continue
        val content = item["content"] as? JsonArray ?: continue
        for (part in content) {
            val block = part as? JsonObject ?: continue
            val blockType = block.str("type")
            if (blockType != "output_text" && blockType != "text") continue
            buffer.append(block.str("text") ?: "")
        }
    }
    return buffer.toString()
}

/** `reasoning` 汇总（非流式；responses_api.dart `responsesReasoningText`）。 */
internal fun responsesReasoningText(obj: JsonObject): String {
    val output = obj["output"] as? JsonArray ?: return ""
    val buffer = StringBuilder()
    for (element in output) {
        val item = element as? JsonObject ?: continue
        if (item.str("type") != "reasoning") continue
        when (val content = item["content"]) {
            is JsonPrimitive -> content.contentOrNull?.let(buffer::append)
            is JsonArray -> for (part in content) {
                when (val block = part) {
                    is JsonPrimitive -> block.contentOrNull?.let(buffer::append)
                    is JsonObject -> {
                        val type = block.str("type")
                        if (type == "reasoning_text" || type == "text") {
                            buffer.append(block.str("text") ?: block.str("content") ?: "")
                        }
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
    }
    return buffer.toString()
}
