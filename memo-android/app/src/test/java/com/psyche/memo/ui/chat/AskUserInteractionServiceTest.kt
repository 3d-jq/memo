package com.psyche.memo.ui.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ask_user_interaction_service.dart 的 1:1 行为测试：请求注册/键生成、空问题
 * 抛错、answer/cancel 完成语义、cancelForConversation 隔离。
 */
class AskUserInteractionServiceTest {

    private fun questionsJson(vararg questions: JsonObject): JsonObject = JsonObject(
        mapOf("questions" to JsonArray(questions.toList())),
    )

    private fun question(id: String, text: String, type: String = "single"): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "question" to JsonPrimitive(text),
                "type" to JsonPrimitive(type),
            ),
        )

    @Test
    fun requestAnswer_registersPending() {
        val service = AskUserInteractionService()
        val args = questionsJson(question("q1", "Pick one"))
        val deferred = service.requestAnswer("call-1", args, conversationId = "conv-1")
        assertFalse(deferred.isCompleted)
        assertTrue(service.isPending("call-1"))
        assertEquals(1, service.pendingRequests.value.size)
        val req = service.pendingRequests.value.single()
        assertEquals("call-1", req.toolCallId)
        assertEquals("conv-1", req.conversationId)
        assertEquals(listOf("q1"), req.questions.map { it.id })
    }

    @Test
    fun requestAnswer_emptyQuestions_throws() {
        val service = AskUserInteractionService()
        val e = assertThrows(AskUserInvalidRequestException::class.java) {
            service.requestAnswer("call-1", JsonObject(emptyMap()))
        }
        assertEquals("questions must contain at least one question", e.message)
        // 只含空 question 文本同样抛错。
        assertThrows(AskUserInvalidRequestException::class.java) {
            service.requestAnswer(
                "call-2",
                questionsJson(question("q1", "   ")),
            )
        }
        assertFalse(hasPending(service))
    }

    private fun hasPending(service: AskUserInteractionService): Boolean =
        service.pendingRequests.value.isNotEmpty()

    @Test
    fun requestAnswer_blankToolCallId_usesGeneratedKey() {
        val service = AskUserInteractionService()
        val deferred = service.requestAnswer("  ", questionsJson(question("q1", "Q")))
        assertFalse(deferred.isCompleted)
        val key = service.pendingRequests.value.single().toolCallId
        assertTrue(key.startsWith("ask_user_input_v0_"))
        assertTrue(service.isPending(key))
    }

    /**
     * 两条会话共用同一个（占位）toolCallId 时，各自要持有**自己**的 deferred。
     *
     * 厂商不给 tool_call id 时解码器会造 `tool-1` 这种占位 id（见
     * `ChatCompletionsDecoder.toolSeriesId`），每轮响应都从 1 开始 ⇒ 裸 id 做键必然串台：
     * 后一条会话的请求会**顶掉**前一条的表项，前一条的等待方从此挂在没人应答的对象上
     * （而面板只按会话过滤，用户在哪儿都看不到它）。键的形状与审批服务一致。
     */
    @Test
    fun sharedPlaceholderId_isScopedByConversation() = runBlocking {
        val service = AskUserInteractionService()
        val args = questionsJson(question("q1", "Pick one"))
        val a = service.requestAnswer("tool-1", args, "conv-1")
        val b = service.requestAnswer("tool-1", args, "conv-2")
        assertEquals(2, service.pendingRequests.value.size)

        service.answer("tool-1", mapOf("q1" to AskUserAnswerValue.single("a", custom = false)), "conv-1")
        assertTrue(a.isCompleted)
        assertFalse(b.isCompleted)
        assertTrue(service.isPending("tool-1", "conv-2"))
    }

    @Test
    fun answer_completesWithAnswerPayload() = runBlocking {
        val service = AskUserInteractionService()
        val args = questionsJson(question("q1", "Pick one"))
        val deferred = service.requestAnswer("call-1", args, "conv-1")
        service.answer("call-1", mapOf("q1" to AskUserAnswerValue.single("a", custom = false)))
        assertTrue(deferred.isCompleted)
        val result = deferred.await()
        assertEquals(
            """{"type":"ask_user_answer","answers":{"q1":{"type":"single","value":"a","custom":false,"skipped":false}}}""",
            result.jsonString,
        )
        assertFalse(hasPending(service))
    }

    @Test
    fun answer_unknownToolCallId_isNoOp() {
        val service = AskUserInteractionService()
        service.answer("nope", emptyMap())
        assertFalse(hasPending(service))
    }

    @Test
    fun cancelAll_completesWithToolError() = runBlocking {
        val service = AskUserInteractionService()
        val a = service.requestAnswer("call-1", questionsJson(question("q1", "Q")), "conv-1")
        val b = service.requestAnswer("call-2", questionsJson(question("q1", "Q")))
        service.cancelAll()
        assertTrue(a.isCompleted)
        assertTrue(b.isCompleted)
        val payload = Json.parseToJsonElement(a.await().jsonString).jsonObject
        assertEquals("tool_error", payload["type"]?.toString()?.trim('"'))
        assertEquals("cancelled", payload["error"]?.toString()?.trim('"'))
        assertEquals("ask_user_input_v0", payload["tool"]?.toString()?.trim('"'))
        assertFalse(hasPending(service))
    }

    @Test
    fun cancelForConversation_keepsOtherConversations() = runBlocking {
        val service = AskUserInteractionService()
        val a = service.requestAnswer("call-1", questionsJson(question("q1", "Q")), "conv-1")
        val b = service.requestAnswer("call-2", questionsJson(question("q1", "Q")), "conv-2")
        val c = service.requestAnswer("call-3", questionsJson(question("q1", "Q")))
        service.cancelForConversation("conv-1")
        assertTrue(a.isCompleted)
        assertEquals("cancelled", Json.parseToJsonElement(a.await().jsonString).jsonObject["error"]?.toString()?.trim('"'))
        // 同会话 + 未记录会话被取消；其他会话的请求继续等待。
        assertTrue(c.isCompleted)
        assertFalse(b.isCompleted)
        assertTrue(service.isPending("call-2"))
    }
}
