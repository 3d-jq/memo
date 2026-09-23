package com.psyche.memo.logging

import com.psyche.memo.common.logging.ContextLogMessage
import com.psyche.memo.common.logging.ContextLogSnapshot
import com.psyche.memo.common.logging.ContextSegment
import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.common.logging.ContextTag
import com.psyche.memo.common.logging.TokenEstimator
import com.psyche.memo.llm.client.LlmMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Assembly side of the context log (1:1 with
 * `context_log_models.dart:243 segmentsFromTaggedMessage` +
 * `context_logger.dart:95 buildSnapshot`).
 *
 * The chat pipeline tags each request message while it composes it
 * ([LlmMessage.contextTags]); this turns those tags back into per-source
 * segments by slicing the final content, so what the log shows is exactly
 * what the request carries. Messages without tags fall back to the
 * role-based guess of `inferContextSource`.
 */
object ContextLogAssembler {

    /**
     * `context_logger.dart:151 logPrepared` — builds the snapshot and hands it
     * to [ContextLogger]. No-op when the writer is disabled.
     */
    fun logPrepared(
        messages: List<LlmMessage>,
        conversationId: String,
        assistantName: String,
        provider: String,
        model: String,
    ) {
        if (!ContextLogger.isEnabled) return
        ContextLogger.logSnapshot(
            buildSnapshot(messages, conversationId, assistantName, provider, model)
        )
    }

    fun buildSnapshot(
        messages: List<LlmMessage>,
        conversationId: String,
        assistantName: String,
        provider: String,
        model: String,
        timestamp: Long = System.currentTimeMillis(),
    ): ContextLogSnapshot {
        val logged = messages.map { message ->
            ContextLogMessage(
                role = message.role,
                segments = segmentsFromTaggedMessage(message),
            )
        }
        return ContextLogSnapshot(
            timestamp = timestamp,
            conversationId = conversationId,
            assistantName = assistantName,
            provider = provider,
            model = model,
            messages = logged,
            totalTokens = logged.sumOf { m -> m.segments.sumOf { it.tokens } },
        )
    }

    /**
     * `segmentsFromTaggedMessage`: tool calls are appended to the content the
     * way the providers replay them, then the tag lengths slice the result.
     * The last tag absorbs the remainder, so small length drift inside the
     * message never truncates it.
     */
    fun segmentsFromTaggedMessage(message: LlmMessage): List<ContextSegment> {
        var content = message.content.orEmpty()
        if (message.toolCalls.isNotEmpty()) {
            val encoded = toolCallsJson(message)
            content = if (content.isEmpty()) encoded else "$content\n$encoded"
        }
        val tags = message.contextTags
        if (tags.isEmpty()) {
            return listOf(segment(inferContextSource(message), content))
        }
        val out = mutableListOf<ContextSegment>()
        var offset = 0
        for ((index, tag) in tags.withIndex()) {
            val slice = if (index == tags.lastIndex) {
                if (offset <= content.length) content.substring(offset) else ""
            } else {
                val end = (offset + tag.length).coerceIn(0, content.length)
                val text = content.substring(offset, end)
                offset = end
                text
            }
            out += segment(tag.source, slice, tag.meta)
        }
        return out
    }

    /** `inferContextSource` — the untagged-message fallback. */
    fun inferContextSource(message: LlmMessage): ContextSource = when {
        message.role == "system" -> ContextSource.systemPrompt
        message.role == "tool" -> ContextSource.toolResult
        message.role == "assistant" && message.toolCalls.isNotEmpty() -> ContextSource.toolCall
        else -> ContextSource.chatHistory
    }

    /** The content a fresh system message gets: every part joined with a blank line. */
    fun joinSystemParts(parts: List<Pair<ContextSource, String>>): String =
        // 与真实请求**同一条渲染路径**（provider/prompt/PromptAssembly）—— 两条路径必然漂移。
        com.psyche.memo.provider.prompt.assembleSystemPrompt(parts)

    /**
     * Tags for [joinSystemParts]: the first part starts at offset 0, every later
     * part owns the "\n\n" that precedes it (mirror of `_appendToSystemMessage`
     * L2110-2129). Slicing the joined content by these lengths reproduces the
     * parts exactly.
     */
    fun systemMessageTags(parts: List<Pair<ContextSource, String>>): List<ContextTag> =
        // 标签长度必须按**渲染后的**顺序与文本算，否则按标签切出来的段落对不上真实提示词。
        com.psyche.memo.provider.prompt.orderedPromptParts(parts)
            .mapIndexed { index, (source, text) ->
                ContextTag(source, if (index == 0) text.length else 2 + text.length)
            }

    /**
     * Tags when [parts] are appended to a system message that already exists:
     * the previous content keeps its own tags (or gets one [ContextSource.systemPrompt]
     * tag when it had none), then each appended part is prefixed by "\n\n".
     */
    fun appendedSystemMessageTags(
        existing: List<ContextTag>,
        existingContent: String?,
        parts: List<Pair<ContextSource, String>>,
    ): List<ContextTag> = buildList {
        if (existing.isEmpty() && !existingContent.isNullOrEmpty()) {
            add(ContextTag(ContextSource.systemPrompt, existingContent.length))
        }
        addAll(existing)
        addAll(systemMessageTags(parts))
    }

    /** The content of a system message with [parts] appended to [existing]. */
    fun joinedAppending(existing: String?, parts: List<Pair<ContextSource, String>>): String =
        (
            listOf(existing) +
                com.psyche.memo.provider.prompt.orderedPromptParts(parts).map { it.second }
            ).filterNotNull().filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun segment(
        source: ContextSource,
        text: String,
        meta: Map<String, String> = emptyMap(),
    ): ContextSegment {
        val jsonMeta = meta.takeIf { it.isNotEmpty() }
            ?.let { JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) }) }
        return ContextSegment(
            source = source,
            text = text,
            tokens = TokenEstimator.estimate(text),
            meta = jsonMeta,
        )
    }

    /** openai tool_calls 形态（`openaiToolCallMaps`）—— 与 provider 上行一致。 */
    private fun toolCallsJson(message: LlmMessage): String =
        JsonArray(
            message.toolCalls.map { call ->
                JsonObject(
                    linkedMapOf(
                        "id" to JsonPrimitive(call.id),
                        "type" to JsonPrimitive("function"),
                        "function" to JsonObject(
                            linkedMapOf(
                                "name" to JsonPrimitive(call.name),
                                "arguments" to JsonPrimitive(call.argumentsJson),
                            )
                        ),
                    )
                )
            }
        ).toString()
}
