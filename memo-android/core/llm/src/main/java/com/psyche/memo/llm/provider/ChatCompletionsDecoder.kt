package com.psyche.memo.llm.provider

import com.psyche.memo.llm.stream.DecodeResult
import com.psyche.memo.llm.stream.SseEvent
import com.psyche.memo.llm.stream.StreamChunk
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
) {
    private val json = Json { ignoreUnknownKeys = true }

    var finishReason: String? = null
    var reasoningEcho: StringBuilder? = if (needsReasoningEcho) StringBuilder() else null
    var assistantContent: String = ""
        private set

    /** index -> {id, name, args, extra_content} */
    private val toolCalls = LinkedHashMap<Int, JsonObject>()
    private var usageJson: JsonObject? = null
    private var closed = false
    private var completed = false

    fun usage(): JsonObject? = usageJson

    fun accept(event: SseEvent): DecodeResult {
        if (closed || completed) return DecodeResult(chunks = emptyList(), completed = true)
        val data = event.data
        if (data.isEmpty()) return DecodeResult(chunks = emptyList())
        if (data == "[DONE]") {
            completed = true
            val out = ArrayList<StreamChunk>()
            out.add(StreamChunk.Finish(finishReason, usageJson))
            return DecodeResult(chunks = out, completed = true)
        }
        val obj = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            return DecodeResult(chunks = emptyList())
        }
        val chunks = ArrayList<StreamChunk>()
        try {
            parseEvent(obj, chunks)
        } catch (e: Exception) {
            // Malformed payloads never kill the stream; log and continue.
        }
        return DecodeResult(chunks = chunks, completed = completed)
    }

    fun onClosed(): List<StreamChunk> {
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
                finishReason = (c0["finish_reason"] as? JsonPrimitive)?.content
                val message = c0["message"]?.jsonObject
                val delta = c0["delta"]?.jsonObject
                if (delta != null) {
                    val deltaContent = extractDeltaText(delta)
                    if (deltaContent.isNotEmpty()) {
                        content += deltaContent
                        assistantContent += deltaContent
                    }
                    (delta["reasoning_content"] ?: delta["reasoning"])?.let { rc ->
                        val rcText = (rc as? JsonPrimitive)?.content
                        if (!rcText.isNullOrEmpty()) {
                            reasoning = rcText
                            reasoningEcho?.append(rcText)
                        }
                    }
                    accumulateToolCalls(delta["tool_calls"], chunks)
                }
                if (message != null) {
                    message["content"]?.let { m ->
                        if (m is JsonPrimitive && m.content.isNotEmpty()) {
                            content += m.content
                            assistantContent += m.content
                        }
                    }
                    val rcMsg = message["reasoning_content"] ?: message["reasoning"]
                    if (rcMsg is JsonPrimitive && rcMsg.content.isNotEmpty()) {
                        reasoningEcho?.append(rcMsg.content)
                        reasoning = reasoning ?: rcMsg.content
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
                val id = (tObj["id"] as? JsonPrimitive)?.content ?: ""
                val type = (tObj["type"] as? JsonPrimitive)?.content ?: "function"
                if (type != "function") continue
                val func = tObj["function"]?.jsonObject ?: continue
                val name = (func["name"] as? JsonPrimitive)?.content ?: ""
                val argsStr = (func["arguments"] as? JsonPrimitive)?.content ?: ""
                if (name.isEmpty()) continue
                toolCalls[toolCalls.size] = JsonObject(
                    mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "args" to JsonPrimitive(argsStr)),
                )
                chunks.add(StreamChunk.ToolCallDelta(id.ifEmpty { null }, name, argsStr))
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

    private fun accumulateToolCalls(delta: Any?, chunks: MutableList<StreamChunk>) {
        val items = delta as? JsonArray ?: return
        for (item in items) {
            val obj = item.jsonObject ?: continue
            val index = (obj["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.content ?: ""
            val type = (obj["type"] as? JsonPrimitive)?.content ?: "function"
            if (type != "function") continue
            val func = obj["function"]?.jsonObject ?: continue
            val nameDelta = (func["name"] as? JsonPrimitive)?.content ?: ""
            val argsDelta = (func["arguments"] as? JsonPrimitive)?.content ?: ""
            val entry = toolCalls.getOrPut(index) {
                JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(""), "args" to JsonPrimitive("")))
            }
            toolCalls[index] = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(if (entry["id"]?.jsonPrimitive?.content.isNullOrEmpty()) id else entry["id"]!!.jsonPrimitive.content),
                    "name" to JsonPrimitive(entry["name"]!!.jsonPrimitive.content + nameDelta),
                    "args" to JsonPrimitive(entry["args"]!!.jsonPrimitive.content + argsDelta),
                ),
            )
            chunks.add(StreamChunk.ToolCallDelta(id.ifEmpty { null }, nameDelta, argsDelta))
        }
    }

    private fun ingestCompleteToolCalls(value: Any?, chunks: MutableList<StreamChunk>) {
        val items = value as? JsonArray ?: return
        for (t in items) {
            val obj = t.jsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.content ?: ""
            val type = (obj["type"] as? JsonPrimitive)?.content ?: "function"
            if (type != "function") continue
            val func = obj["function"]?.jsonObject ?: continue
            val name = (func["name"] as? JsonPrimitive)?.content ?: ""
            val argsStr = (func["arguments"] as? JsonPrimitive)?.content ?: ""
            if (name.isEmpty()) continue
            val idx = toolCalls.size
            toolCalls[idx] = JsonObject(
                mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "args" to JsonPrimitive(argsStr)),
            )
            chunks.add(StreamChunk.ToolCallDelta(id.ifEmpty { null }, name, argsStr))
        }
    }

    private fun endOpenTools(): List<StreamChunk> = emptyList()

    private fun extractDeltaText(delta: JsonObject): String {
        val content = delta["content"] ?: return ""
        if (content is JsonPrimitive) return content.content
        // Array-form content with type = text / image_url — P1 keeps index 0 text.
        val array = (content as? JsonArray) ?: return ""
        return array.firstOrNull()?.jsonObject?.get("text")?.let { (it as JsonPrimitive).content } ?: ""
    }
}
