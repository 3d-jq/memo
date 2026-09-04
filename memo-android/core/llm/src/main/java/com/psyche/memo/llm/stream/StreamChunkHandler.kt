package com.psyche.memo.llm.stream

import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart

/**
 * Folds [StreamChunk] events into an ordered [MessagePart] list (mirrors
 * RikkaHub StreamChunkHandler + Flutter's handler). One instance per stream.
 *
 * Text/reasoning parts are located by an implicit single series (P1 providers
 * stream one text + one reasoning series); tool calls are located by tool id.
 * A [Finish] marks the stream done; further chunks are ignored.
 */
class StreamChunkHandler {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val tools = LinkedHashMap<String, StringBuilder>() // toolId -> args json

    var finished = false
        private set
    var finishReason: String? = null
    var usage: com.psyche.memo.llm.client.LlmUsage? = null

    fun handle(chunk: StreamChunk) {
        if (finished) return
        when (chunk) {
            is StreamChunk.TextDelta -> text.append(chunk.text)
            is StreamChunk.ReasoningDelta -> reasoning.append(chunk.text)
            is StreamChunk.ToolCallDelta -> {
                val tool = tools.getOrPut(chunk.id ?: "tool") { StringBuilder() }
                tool.append(chunk.arguments)
            }
            is StreamChunk.Finish -> {
                finished = true
                finishReason = chunk.finishReason
            }
            is StreamChunk.Error -> Unit // surfaced by caller
        }
    }

    fun parts(): List<MessagePart> = buildList {
        if (reasoning.isNotEmpty()) add(ReasoningPart(reasoning.toString()))
        if (text.isNotEmpty()) add(TextPart(text.toString()))
        for ((id, args) in tools) {
            if (args.isNotEmpty()) {
                add(ToolCallPart("""{"id":"$id","arguments":${args.toString()}}"""))
            }
        }
    }

    fun textContent(): String = text.toString()

    companion object {
        /** Fold a completed stream: returns the final parts and marks finished. */
        fun collect(chunks: List<StreamChunk>): StreamChunkHandler =
            StreamChunkHandler().also { handler ->
                chunks.forEach(handler::handle)
            }
    }
}
