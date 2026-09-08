package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ToolHandlerTest {

    private fun assistant(localToolIds: List<String>): Assistant =
        Assistant(id = "a1", name = "A", localToolIds = localToolIds)

    private fun handler(
        approval: ToolApprovalService? = null,
        askUser: AskUserInteractionService? = null,
        assistant: Assistant? = assistant(listOf(LocalToolNames.TIME_INFO)),
        conversationId: String? = "conv-1",
    ) = ToolHandler(approval, askUser, conversationId, assistant)

    private fun decode(content: String): JsonObject =
        Json.parseToJsonElement(content).jsonObject

    // ------------------------------------------------------------------
    // get_time_info executor (local_tools_service.dart tryHandleToolCall)
    // ------------------------------------------------------------------

    @Test
    fun timeInfoReturnsPayloadJson() = runBlocking {
        val content = handler().handle(LocalToolNames.TIME_INFO, JsonObject(emptyMap()), "call-1")
        val obj = decode(content)
        assertNull("a result, not a tool_error", obj["type"])
        val weekdays = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        )
        assertTrue(obj["weekday_en"]!!.jsonPrimitive.content in weekdays)
        assertTrue(obj["weekday"]!!.jsonPrimitive.content in weekdays)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(obj["date"]!!.jsonPrimitive.content))
        assertTrue(Regex("""\d{2}:\d{2}:\d{2}""").matches(obj["time"]!!.jsonPrimitive.content))
        assertTrue(Regex("""[+-]\d{2}:\d{2}""").matches(obj["utc_offset"]!!.jsonPrimitive.content))
        assertTrue(obj["timestamp_ms"]!!.jsonPrimitive.content.toLong() > 0)
    }

    /** _buildTimeInfoPayload 1048-1077 — fixed instant, byte-shape checks. */
    @Test
    fun buildTimeInfoPayloadMatchesDartShape() {
        val now = ZonedDateTime.of(2026, 9, 8, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(8))
        val obj = ToolHandler(null, null, "conv-1", null).buildTimeInfoPayload(now)
        // Raw ints like the Dart map ('month': now.month); only date/time pad.
        assertEquals("2026", obj["year"]!!.jsonPrimitive.content)
        assertEquals("9", obj["month"]!!.jsonPrimitive.content)
        assertEquals("8", obj["day"]!!.jsonPrimitive.content)
        assertEquals("Tuesday", obj["weekday_en"]!!.jsonPrimitive.content)
        assertEquals("Tuesday", obj["weekday"]!!.jsonPrimitive.content)
        assertEquals("2", obj["weekday_index"]!!.jsonPrimitive.content)
        assertEquals("2026-09-08", obj["date"]!!.jsonPrimitive.content)
        assertEquals("10:30:45", obj["time"]!!.jsonPrimitive.content)
        // DateTime.toIso8601String() — local, always 6-digit microseconds.
        assertEquals("2026-09-08T10:30:45.123000", obj["datetime"]!!.jsonPrimitive.content)
        assertEquals("+08:00", obj["utc_offset"]!!.jsonPrimitive.content)
        assertEquals(now.toInstant().toEpochMilli(), obj["timestamp_ms"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun buildTimeInfoPayloadNegativeOffset() {
        val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5))
        val obj = ToolHandler(null, null, "conv-1", null).buildTimeInfoPayload(now)
        assertEquals("-05:00", obj["utc_offset"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Approval gate (tool_handler_service.dart 454-471)
    // ------------------------------------------------------------------

    @Test
    fun approvalDeniedReturnsToolError() = runBlocking {
        val service = ToolApprovalService()
        val h = handler(
            approval = service,
            assistant = assistant(listOf(LocalToolNames.CALENDAR_CREATE)),
        )
        val content = async {
            h.handle(LocalToolNames.CALENDAR_CREATE, JsonObject(emptyMap()), "call-1")
        }
        // The async block starts only when this coroutine suspends; wait until
        // the request is registered before completing it.
        while (!service.isPending("call-1", conversationId = "conv-1")) { yield() }
        service.deny("call-1", reason = "not now", conversationId = "conv-1")
        val obj = decode(content.await())
        assertEquals("tool_error", obj["type"]!!.jsonPrimitive.content)
        assertEquals("approval_denied", obj["error"]!!.jsonPrimitive.content)
        assertEquals("not now", obj["message"]!!.jsonPrimitive.content)
        assertEquals(LocalToolNames.CALENDAR_CREATE, obj["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun approvalApprovedFallsThroughToExecutor() = runBlocking {
        val service = ToolApprovalService()
        val h = handler(
            approval = service,
            assistant = assistant(listOf(LocalToolNames.CALENDAR_CREATE)),
        )
        val content = async {
            h.handle(LocalToolNames.CALENDAR_CREATE, JsonObject(emptyMap()), "call-1")
        }
        while (!service.isPending("call-1", conversationId = "conv-1")) { yield() }
        service.approve("call-1", conversationId = "conv-1")
        val obj = decode(content.await())
        // Executor unported this batch → honest execution_error after approval.
        assertEquals("tool_error", obj["type"]!!.jsonPrimitive.content)
        assertEquals("execution_error", obj["error"]!!.jsonPrimitive.content)
        assertEquals(LocalToolNames.CALENDAR_CREATE, obj["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun approvalIdForFallsBackToNameEpoch() {
        val h = handler()
        assertEquals("call-1", h.approvalIdFor(LocalToolNames.CALENDAR_CREATE, "call-1"))
        assertEquals("call-1", h.approvalIdFor(LocalToolNames.CALENDAR_CREATE, "  call-1  "))
        val fallback = h.approvalIdFor(LocalToolNames.CALENDAR_CREATE, null)
        assertTrue(fallback.startsWith("${LocalToolNames.CALENDAR_CREATE}_"))
        assertTrue(fallback.length > LocalToolNames.CALENDAR_CREATE.length + 1)
    }

    // ------------------------------------------------------------------
    // ask_user_input_v0 (tool_handler_service.dart 501-527)
    // ------------------------------------------------------------------

    private fun askUserArgs(): JsonObject = JsonObject(
        mapOf(
            "questions" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("q1"),
                            "question" to JsonPrimitive("Continue?"),
                            "type" to JsonPrimitive("single"),
                            "options" to JsonArray(listOf(JsonPrimitive("Yes"), JsonPrimitive("No"))),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun askUserRoutesToInteractionService() = runBlocking {
        val askUser = AskUserInteractionService()
        val h = handler(
            askUser = askUser,
            assistant = assistant(listOf(AskUserToolNames.ASK_USER)),
        )
        val content = async { h.handle(AskUserToolNames.ASK_USER, askUserArgs(), "ask-1") }
        while (!askUser.isPending("ask-1")) { yield() }
        askUser.answer("ask-1", mapOf("q1" to AskUserAnswerValue.single("Yes", custom = false)))
        val obj = decode(content.await())
        assertEquals("ask_user_answer", obj["type"]!!.jsonPrimitive.content)
        val answer = obj["answers"]!!.jsonObject["q1"]!!.jsonObject
        assertEquals("single", answer["type"]!!.jsonPrimitive.content)
        assertEquals("Yes", answer["value"]!!.jsonPrimitive.content)
        assertEquals("false", answer["custom"]!!.jsonPrimitive.content)
    }

    @Test
    fun askUserEmptyQuestionsReturnsInvalidRequestError() = runBlocking {
        val askUser = AskUserInteractionService()
        val h = handler(
            askUser = askUser,
            assistant = assistant(listOf(AskUserToolNames.ASK_USER)),
        )
        val obj = decode(h.handle(AskUserToolNames.ASK_USER, JsonObject(emptyMap()), "ask-1"))
        assertEquals("tool_error", obj["type"]!!.jsonPrimitive.content)
        assertEquals("invalid_ask_user_request", obj["error"]!!.jsonPrimitive.content)
        assertEquals(AskUserToolNames.ASK_USER, obj["tool"]!!.jsonPrimitive.content)
        assertFalse(askUser.isPending("ask-1"))
    }

    // ------------------------------------------------------------------
    // Fallthrough / catch-all (MCP fallthrough + catch 530-540)
    // ------------------------------------------------------------------

    @Test
    fun unportedToolReturnsExecutionError() = runBlocking {
        val h = handler(assistant = assistant(listOf(LocalToolNames.CLIPBOARD)))
        val obj = decode(h.handle(LocalToolNames.CLIPBOARD, JsonObject(emptyMap()), "call-1"))
        assertEquals("tool_error", obj["type"]!!.jsonPrimitive.content)
        assertEquals("execution_error", obj["error"]!!.jsonPrimitive.content)
        assertEquals(LocalToolNames.CLIPBOARD, obj["tool"]!!.jsonPrimitive.content)
        assertTrue(obj.containsKey("instruction"))
    }

    @Test
    fun nullAssistantReturnsExecutionError() = runBlocking {
        val h = handler(assistant = null)
        val obj = decode(h.handle(LocalToolNames.TIME_INFO, JsonObject(emptyMap()), "call-1"))
        assertEquals("execution_error", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolNotInLocalToolIdsReturnsExecutionError() = runBlocking {
        // time_info offered but the assistant never enabled it.
        val h = handler(assistant = assistant(listOf(LocalToolNames.ASK_USER)))
        val obj = decode(h.handle(LocalToolNames.TIME_INFO, JsonObject(emptyMap()), "call-1"))
        assertEquals("execution_error", obj["error"]!!.jsonPrimitive.content)
    }
}
