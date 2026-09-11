package com.psyche.memo.logging

import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.common.logging.ContextTag
import com.psyche.memo.common.logging.TokenEstimator
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assembly side of the context log: the tagging helpers must slice back to
 * exactly the text that went into the request, because that is the whole point
 * of the log (see the reader in the log viewer).
 */
class ContextLogAssemblerTest {

    private fun partsOf(parts: List<Pair<ContextSource, String>>, content: String) =
        ContextLogAssembler.segmentsFromTaggedMessage(
            LlmMessage(role = "system", content = content, contextTags = ContextLogAssembler.systemMessageTags(parts))
        )

    /**
     * The original tags an appended block with `2 + length` because the handler
     * already joined it with "\n\n" (L2110-2129), so a block that is appended to
     * an existing message keeps that leading blank line inside its own segment.
     * Compare with it stripped and lock the behaviour in the test below.
     */
    private fun stripped(texts: List<String>) = texts.mapIndexed { index, t ->
        if (index == 0) t else t.trimStart('\n')
    }

    @Test
    fun systemPromptPartsSliceBackExactly() {
        val parts = listOf(
            ContextSource.systemPrompt to "you are helpful",
            ContextSource.memoryRules to "remember things",
            ContextSource.searchPrompt to "cite sources",
            ContextSource.instructionInjection to "answer in Chinese",
        )
        val content = ContextLogAssembler.joinSystemParts(parts)
        val segments = partsOf(parts, content)

        assertEquals(parts.map { it.second }, stripped(segments.map { it.text }))
        assertEquals(
            listOf(
                ContextSource.systemPrompt,
                ContextSource.memoryRules,
                ContextSource.searchPrompt,
                ContextSource.instructionInjection,
            ),
            segments.map { it.source },
        )
        // Every character of the request content is accounted for, exactly once.
        assertEquals(content.length, segments.sumOf { it.text.length })
    }

    @Test
    fun appendedSegmentsOwnTheirLeadingBlankLine() {
        val parts = listOf(
            ContextSource.systemPrompt to "a",
            ContextSource.searchPrompt to "b",
        )
        val segments = partsOf(parts, ContextLogAssembler.joinSystemParts(parts))
        assertEquals(listOf("a", "\n\nb"), segments.map { it.text })
    }

    @Test
    fun singlePartSystemMessageSliceIsExact() {
        val parts = listOf(ContextSource.systemPrompt to "hi")
        val segments = partsOf(parts, "hi")
        assertEquals(listOf("hi"), segments.map { it.text })
    }

    @Test
    fun appendedSystemMessageKeepsPreviousTagsAndSplitsNewParts() {
        val existingParts = listOf(ContextSource.systemPrompt to "old prompt")
        val previousTags = ContextLogAssembler.systemMessageTags(existingParts)
        val appended = listOf(ContextSource.searchPrompt to "search rules")
        val content = ContextLogAssembler.joinedAppending("old prompt", appended)
        val tags = ContextLogAssembler.appendedSystemMessageTags(previousTags, "old prompt", appended)

        val segments = ContextLogAssembler.segmentsFromTaggedMessage(
            LlmMessage(role = "system", content = content, contextTags = tags)
        )
        assertEquals(listOf("old prompt", "search rules"), stripped(segments.map { it.text }))
    }

    @Test
    fun untaggedSystemContentIsAttributedToTheFirstAppendedPart() {
        // An existing system message with no tags can only be attributed
        // wholesale; the appended part must still come out whole.
        val appended = listOf(ContextSource.searchPrompt to "search rules")
        val content = ContextLogAssembler.joinedAppending("legacy system text", appended)
        val tags = ContextLogAssembler.appendedSystemMessageTags(emptyList(), "legacy system text", appended)

        val segments = ContextLogAssembler.segmentsFromTaggedMessage(
            LlmMessage(role = "system", content = content, contextTags = tags)
        )
        assertEquals(ContextSource.systemPrompt, segments.first().source)
        assertEquals("legacy system text", segments.first().text)
        assertEquals("search rules", segments.last().text.trimStart('\n'))
    }

    @Test
    fun memorySnapshotPrefixSplitsFromTheUserTurn() {
        val prefix = "<user_profile/>\n<user_memory type=\"identity\"/>\n"
        val userText = "what should I cook tonight?"
        val segments = ContextLogAssembler.segmentsFromTaggedMessage(
            LlmMessage(
                role = "user",
                content = prefix + userText,
                contextTags = listOf(
                    ContextTag(ContextSource.memorySnapshot, prefix.length, mapOf("kind" to "full")),
                    ContextTag(ContextSource.chatHistory, userText.length),
                ),
            )
        )
        assertEquals(listOf(ContextSource.memorySnapshot, ContextSource.chatHistory), segments.map { it.source })
        assertEquals(listOf(prefix, userText), segments.map { it.text })
        assertEquals("full", segments.first().meta?.get("kind")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun lastTagAbsorbsTheRemainder() {
        val segments = ContextLogAssembler.segmentsFromTaggedMessage(
            LlmMessage(
                role = "user",
                content = "abc" + "defgh",
                contextTags = listOf(
                    ContextTag(ContextSource.memorySnapshot, 3),
                    ContextTag(ContextSource.chatHistory, 1),
                ),
            )
        )
        assertEquals(listOf("abc", "defgh"), segments.map { it.text })
    }

    @Test
    fun untaggedMessagesFallBackToRoleInference() {
        fun sourceOf(message: LlmMessage) =
            ContextLogAssembler.segmentsFromTaggedMessage(message).single().source

        assertEquals(ContextSource.systemPrompt, sourceOf(LlmMessage(role = "system", content = "s")))
        assertEquals(ContextSource.toolResult, sourceOf(LlmMessage(role = "tool", content = "r")))
        assertEquals(ContextSource.chatHistory, sourceOf(LlmMessage(role = "user", content = "u")))
        assertEquals(ContextSource.chatHistory, sourceOf(LlmMessage(role = "assistant", content = "a")))
        assertEquals(
            ContextSource.toolCall,
            sourceOf(
                LlmMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(LlmToolCall("id1", "get_time_info", "{}")),
                )
            ),
        )
    }

    @Test
    fun toolCallsAreAppendedToTheContent() {
        val segments = ContextLogAssembler.segmentsFromTaggedMessage(
            LlmMessage(
                role = "assistant",
                content = "let me check",
                toolCalls = listOf(LlmToolCall("call_1", "get_time_info", "{\"tz\":\"UTC\"}")),
            )
        )
        val text = segments.single().text
        assertTrue(text.startsWith("let me check\n"))
        assertTrue(text.contains("\"id\":\"call_1\""))
        assertTrue(text.contains("\"name\":\"get_time_info\""))
        assertTrue(text.contains("\"arguments\":\"{\\\"tz\\\":\\\"UTC\\\"}\""))
    }

    @Test
    fun snapshotTotalsAndMetadataFollowTheMessages() {
        val messages = listOf(
            LlmMessage(role = "system", content = "abcd", contextTags = listOf(ContextTag(ContextSource.systemPrompt, 4))),
            LlmMessage(role = "user", content = "你好"),
        )
        val snapshot = ContextLogAssembler.buildSnapshot(
            messages = messages,
            conversationId = "c1",
            assistantName = "A",
            provider = "Zhipu AI",
            model = "glm-4",
            timestamp = 1_700_000_000_000L,
        )
        assertEquals("c1", snapshot.conversationId)
        assertEquals("Zhipu AI", snapshot.provider)
        assertEquals(2, snapshot.messages.size)
        assertEquals(
            snapshot.messages.sumOf { m -> m.segments.sumOf { it.tokens } },
            snapshot.totalTokens,
        )
        // 1 token for "abcd" + 2 for the two CJK characters.
        assertEquals(1 + 2, snapshot.totalTokens)
        assertEquals(TokenEstimator.estimate("abcd"), snapshot.messages.first().segments.first().tokens)
    }
}
