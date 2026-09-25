package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
        askUser: AskUserInteractionService? = null,
        assistant: Assistant? = assistant(listOf(LocalToolNames.TIME_INFO)),
        conversationId: String? = "conv-1",
    ) = ToolHandler(askUser, conversationId, assistant)

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
        val obj = ToolHandler(null, "conv-1", null).buildTimeInfoPayload(now)
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
        val obj = ToolHandler(null, "conv-1", null).buildTimeInfoPayload(now)
        assertEquals("-05:00", obj["utc_offset"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 审批闸门已整块拆除（用户 2026-09-25「工具的权限审批全部去掉」）：改用户数据的
    // 本地工具直接执行，ToolHandler 构造里不再有 approvalService。这条测试钉住
    // 「不再有闸」——日历创建一路走到执行器（执行器本批未移植 ⇒ execution_error），
    // 中途不会挂起等任何人点批准。
    // ------------------------------------------------------------------

    @Test
    fun dataModifyingLocalToolRunsStraightThrough() = runBlocking {
        val obj = decode(
            handler(assistant = assistant(listOf(LocalToolNames.CALENDAR_CREATE)))
                .handle(LocalToolNames.CALENDAR_CREATE, JsonObject(emptyMap()), "call-1"),
        )
        assertEquals("execution_error", obj["error"]!!.jsonPrimitive.content)
        assertEquals(LocalToolNames.CALENDAR_CREATE, obj["tool"]!!.jsonPrimitive.content)
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

    // ------------------------------------------------------------------
    // 取消 ≠ 工具失败（handle() 末尾那个通用 catch）
    // ------------------------------------------------------------------

    /**
     * 用户点「停止」= 协程被取消。取消**不是**一次工具结果：把它归成
     * `execution_error` + 「You may try again with different parameters」有三个后果 ——
     *  1. 对 `click`/`type`/写文件这类**有副作用**的动作，模型在「可能已经做了」的情况下
     *     被告知重试（`ToolRunner` 的 tool_timeout 分支刻意躲的就是这一类）；
     *  2. 那句注释里「只有先 rethrow，用户点停止才真停得下来」的保证被同一函数作废；
     *  3. 与本仓「打断请求必须随生成终止释放」的纪律（AGENTS / PORTING §5.59）对不上。
     *
     * 所以这里断言的是**没有结果**：`handle` 被取消时不许返回任何写给模型的字符串。
     */
    @Test
    fun cancellationIsNotReportedAsAToolFailure() = runBlocking {
        val askUser = AskUserInteractionService()
        val h = handler(askUser = askUser, assistant = assistant(listOf(AskUserToolNames.ASK_USER)))
        val returned = CompletableDeferred<String>()
        val job = launch {
            returned.complete(h.handle(AskUserToolNames.ASK_USER, askUserArgs(), "ask-cancel"))
        }
        while (!askUser.isPending("ask-cancel")) { yield() }
        job.cancelAndJoin()
        assertFalse(
            "取消被吞成了一条工具结果（等于叫模型去重试可能有副作用的动作）：" +
                if (returned.isCompleted) returned.getCompleted() else "",
            returned.isCompleted,
        )
    }
}
