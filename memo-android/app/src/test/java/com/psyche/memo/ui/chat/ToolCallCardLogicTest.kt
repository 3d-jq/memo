package com.psyche.memo.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logic tests for tool-call rendering helpers: ToolUiPart parsing, the lazy
 * text chunking thresholds, ask-user question normalisation, and the
 * screen-time / weather summary formatters.
 */
class ToolCallCardLogicTest {

    // ---- ToolUiPart.fromPayload / fromToolMessage ----

    @Test
    fun fromPayload_parsesFullObject() {
        val payload = JsonObject(
            mapOf(
                "id" to JsonPrimitive("t1"),
                "name" to JsonPrimitive("get_weather"),
                "arguments" to JsonObject(emptyMap()),
                "content" to JsonPrimitive("sunny"),
            ),
        ).toString()
        val part = ToolUiPart.fromPayload(payload)
        assertNotNull(part)
        assertEquals("t1", part!!.id)
        assertEquals("get_weather", part.toolName)
        assertEquals("sunny", part.content)
        assertFalse(part.loading)
    }

    @Test
    fun fromPayload_derivesIdWhenMissing() {
        val payload = JsonObject(
            mapOf("name" to JsonPrimitive("calc")),
        ).toString()
        assertEquals("calc-3", ToolUiPart.fromPayload(payload, fallbackOrdinal = 3)!!.id)
    }

    @Test
    fun fromPayload_invalidJsonIsNull() {
        assertNull(ToolUiPart.fromPayload("not json"))
        assertNull(ToolUiPart.fromPayload(""))
    }

    @Test
    fun fromPayload_loadingFromMissingOrEmptyContent() {
        fun payload(content: kotlinx.serialization.json.JsonElement?) = JsonObject(
            buildMap {
                put("id", JsonPrimitive("a"))
                put("name", JsonPrimitive("x"))
                put("arguments", JsonObject(emptyMap()))
                content?.let { put("content", it) }
            },
        ).toString()
        assertTrue(ToolUiPart.fromPayload(payload(null))!!.loading)
        assertTrue(ToolUiPart.fromPayload(payload(JsonPrimitive("")))!!.loading)
        assertFalse(ToolUiPart.fromPayload(payload(JsonPrimitive("result")))!!.loading)
    }

    @Test
    fun fromToolMessage_parsesToolMessageBody() {
        val body = JsonObject(
            mapOf(
                "tool" to JsonPrimitive("calc"),
                "arguments" to JsonObject(emptyMap()),
                "result" to JsonPrimitive("42"),
            ),
        ).toString()
        val part = ToolUiPart.fromToolMessage("msg-1", body)
        assertNotNull(part)
        assertEquals("msg-1", part!!.id)
        assertEquals("calc", part.toolName)
        assertEquals("42", part.content)
        assertFalse(part.loading)
    }

    @Test
    fun fromToolMessage_emptyResultIsStillNotLoading() {
        val body = JsonObject(mapOf("tool" to JsonPrimitive("x"))).toString()
        val part = ToolUiPart.fromToolMessage("id", body)!!
        assertEquals("", part.content)
        assertFalse(part.loading)
    }

    @Test
    fun fromToolMessage_invalidJsonIsNull() {
        assertNull(ToolUiPart.fromToolMessage("id", "not json"))
    }

    // ---- shouldChunkText / chunkText (tool_detail_text_section.dart) ----

    @Test
    fun shouldChunk_shortTextDoesNotChunk() {
        assertFalse(shouldChunkText("hello world"))
    }

    @Test
    fun shouldChunk_manyLinesChunks() {
        val text = (1..200).joinToString("\n") { "line $it" }
        assertTrue(shouldChunkText(text))
    }

    @Test
    fun shouldChunk_characterCountChunks() {
        assertTrue(shouldChunkText("x".repeat(8001)))
    }

    @Test
    fun chunkText_splitsIntoLineBoundedChunks() {
        val lines = (1..90).joinToString("\n") { "line $it" }
        val chunks = chunkText(lines)
        assertEquals(3, chunks.size)
        assertEquals(40, chunks[0].split('\n').size)
        assertEquals(40, chunks[1].split('\n').size)
        assertEquals(10, chunks[2].split('\n').size)
    }

    @Test
    fun chunkText_singleChunkWhenShort() {
        assertEquals(1, chunkText("one line").size)
    }

    @Test
    fun chunkText_emptyTextYieldsOriginal() {
        assertEquals(listOf(""), chunkText(""))
    }

    // ---- normalizeAskUserQuestions (ask_user_interaction_service.dart) ----

    private fun question(id: String?, q: String, type: String? = null) = JsonObject(
        buildMap {
            id?.let { put("id", JsonPrimitive(it)) }
            put("question", JsonPrimitive(q))
            type?.let { put("type", JsonPrimitive(it)) }
        },
    )

    @Test
    fun askUser_capsAtFourQuestions() {
        val args = JsonObject(
            mapOf(
                "questions" to JsonArray(
                    (1..6).map { question(null, "q$it") },
                ),
            ),
        )
        assertEquals(4, normalizeAskUserQuestions(args).size)
    }

    @Test
    fun askUser_dropsBlankOrNonObject() {
        val args = JsonObject(
            mapOf(
                "questions" to JsonArray(
                    listOf(
                        question("1", "  ", type = null),            // blank question
                        JsonPrimitive("not an object"),
                        question("2", "real"),
                    ),
                ),
            ),
        )
        val out = normalizeAskUserQuestions(args)
        assertEquals(1, out.size)
        assertEquals("real", out[0].question)
    }

    @Test
    fun askUser_dedupesIdsAndReassignsDefaults() {
        val args = JsonObject(
            mapOf(
                "questions" to JsonArray(
                    listOf(
                        question("a", "first"),
                        question("a", "second"),
                        question(null, "third"),
                    ),
                ),
            ),
        )
        val out = normalizeAskUserQuestions(args)
        assertEquals(3, out.size)
        // First keeps its id; the second keeps 'a'? No — dup is reassigned.
        assertEquals("a", out[0].id)
        val ids = out.map { it.id }
        assertEquals(ids.size, ids.toSet().size) // all unique
        assertEquals("third", out[2].question)
    }

    @Test
    fun askUser_multiKind() {
        val args = JsonObject(
            mapOf(
                "questions" to JsonArray(
                    listOf(
                        question("1", "pick", type = "multi"),
                        question("2", "single"),
                    ),
                ),
            ),
        )
        val out = normalizeAskUserQuestions(args)
        assertEquals(AskUserQuestionKind.Multi, out[0].kind)
        assertEquals(AskUserQuestionKind.Single, out[1].kind)
    }

    @Test
    fun askUser_optionsDedupAndCapAtFour() {
        val args = JsonObject(
            mapOf(
                "questions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "question" to JsonPrimitive("pick"),
                                "options" to JsonArray(
                                    listOf("a", "a", "b", "c", "d", "e").map { JsonPrimitive(it) },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf("a", "b", "c", "d"), normalizeAskUserQuestions(args)[0].options)
    }

    // ---- screen-time / weather formatters ----

    @Test
    fun screenTimeMinutes_format() {
        assertEquals("2h 5m", formatScreenTimeMinutes(125))
        assertEquals("2h", formatScreenTimeMinutes(120))
        assertEquals("5m", formatScreenTimeMinutes(5))
    }

    @Test
    fun screenTimeRange_bareLocalTimeParsedInLocalZone() {
        assertEquals("09-07 10:30", formatScreenTimeRange("2026-09-07T10:30:00"))
    }

    @Test
    fun screenTimeRange_invalidFallsBackToRaw() {
        assertEquals("not a date", formatScreenTimeRange("not a date"))
    }

    @Test
    fun weatherCurrentLine_composesParts() {
        val r = WeatherToolResult(
            condition = "Partly Cloudy",
            temperatureC = 24.0,
            apparentTemperatureC = 25.5,
            precipitationChance = 0.35,
            placeLabel = "40.71, -74.00",
            error = null,
        )
        val line = weatherCurrentLine(r)
        assertEquals("40.71, -74.00 · Partly Cloudy · 24°C · feels 25.5°C · 35% precip", line)
    }

    @Test
    fun weatherCurrentLine_omitsMissingParts() {
        val r = WeatherToolResult(null, null, null, null, null, null)
        assertEquals("", weatherCurrentLine(r))
    }

    // ---- matchingApprovalRequest (tool_approval_service.dart pendingFor 列表版) ----

    private fun req(id: String, conversationId: String?, args: JsonObject = JsonObject(emptyMap())) =
        ToolApprovalRequest(
            toolCallId = id,
            toolName = "tool",
            arguments = args,
            conversationId = conversationId,
            completer = CompletableDeferred(),
        )

    @Test
    fun matchingApproval_returnsNullForEmptyToolCallId() {
        assertNull(matchingApprovalRequest(emptyList(), "conv-1", null))
        assertNull(matchingApprovalRequest(emptyList(), "conv-1", ""))
    }

    @Test
    fun matchingApproval_prefersExactScopedMatch() {
        val requests = listOf(
            req("t1", "conv-9"),
            req("t1", "conv-1"),
        )
        val found = matchingApprovalRequest(requests, "conv-1", "t1")
        assertEquals("conv-1", found!!.conversationId)
    }

    @Test
    fun matchingApproval_fallsBackToUnscopedForScopedId() {
        val requests = listOf(req("t1", null))
        val found = matchingApprovalRequest(requests, "conv-1", "t1")
        assertNotNull(found)
        assertNull(found!!.conversationId)
    }

    @Test
    fun matchingApproval_ignoresOtherConversationScoped() {
        val requests = listOf(req("t1", "conv-2"))
        assertNull(matchingApprovalRequest(requests, "conv-1", "t1"))
    }

    @Test
    fun matchingApproval_emptyConversationIdSingleMatch() {
        val requests = listOf(req("t1", "conv-1"))
        assertNotNull(matchingApprovalRequest(requests, null, "t1"))
    }

    @Test
    fun matchingApproval_emptyConversationIdMultipleFallsBackToUnscoped() {
        val requests = listOf(req("t1", "conv-1"), req("t1", "conv-2"), req("t1", null))
        val found = matchingApprovalRequest(requests, null, "t1")
        assertNotNull(found)
        assertNull(found!!.conversationId)
    }

    @Test
    fun matchingApproval_emptyConversationIdMultipleNoUnscopedIsNull() {
        val requests = listOf(req("t1", "conv-1"), req("t1", "conv-2"))
        assertNull(matchingApprovalRequest(requests, null, "t1"))
    }

    @Test
    fun matchingApproval_ignoresOtherToolCallId() {
        val requests = listOf(req("t1", "conv-1"))
        assertNull(matchingApprovalRequest(requests, "conv-1", "t2"))
    }
}
