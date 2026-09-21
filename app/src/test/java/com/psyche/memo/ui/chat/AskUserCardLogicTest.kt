package com.psyche.memo.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ask_user_interaction_service.dart AskUserAnswerValue / AskUserResult.toJsonString
 * 与 chat_message_widget.dart _buildAnswers / _answeredValues / _answerLabel
 * 的纯逻辑测试。
 */
class AskUserCardLogicTest {

    private val singleQ = AskUserQuestion("q1", "Pick one", AskUserQuestionKind.Single, listOf("a", "b"))
    private val multiQ = AskUserQuestion("q2", "Pick many", AskUserQuestionKind.Multi, listOf("a", "b"))

    // ---- buildAskUserAnswers (_buildAnswers) ----

    @Test
    fun single_selectedOption() {
        val out = buildAskUserAnswers(
            listOf(singleQ),
            singleAnswers = mapOf("q1" to "a"),
            multiAnswers = emptyMap<String, Set<String>>(),
            textValues = emptyMap<String, String>(),
            skipped = emptySet<String>(),
        )
        val v = out["q1"]!!
        assertEquals("single", v.type)
        assertEquals(JsonPrimitive("a"), v.value)
        assertEquals(false, v.custom)
        assertEquals(false, v.skipped)
    }

    @Test
    fun single_customOtherWins() {
        val out = buildAskUserAnswers(
            listOf(singleQ),
            singleAnswers = mapOf("q1" to "a"),
            multiAnswers = emptyMap<String, Set<String>>(),
            textValues = mapOf("q1" to "my answer"),
            skipped = emptySet<String>(),
        )
        val v = out["q1"]!!
        assertEquals(JsonPrimitive("my answer"), v.value)
        assertEquals(true, v.custom)
    }

    @Test
    fun single_textMatchesSelectedIsNotCustom() {
        val out = buildAskUserAnswers(
            listOf(singleQ),
            singleAnswers = mapOf("q1" to "a"),
            multiAnswers = emptyMap<String, Set<String>>(),
            textValues = mapOf("q1" to "a"),
            skipped = emptySet<String>(),
        )
        assertEquals(JsonPrimitive("a"), out["q1"]!!.value)
        assertEquals(false, out["q1"]!!.custom)
    }

    @Test
    fun multi_selectedValues() {
        val out = buildAskUserAnswers(
            listOf(multiQ),
            singleAnswers = emptyMap<String, String>(),
            multiAnswers = mapOf("q2" to setOf("a", "b")),
            textValues = emptyMap<String, String>(),
            skipped = emptySet<String>(),
        )
        val v = out["q2"]!!
        assertEquals(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), v.value)
        assertEquals(false, v.custom)
    }

    @Test
    fun multi_customAppendsWhenNotSelected() {
        val out = buildAskUserAnswers(
            listOf(multiQ),
            singleAnswers = emptyMap<String, String>(),
            multiAnswers = mapOf("q2" to setOf("a")),
            textValues = mapOf("q2" to "c"),
            skipped = emptySet<String>(),
        )
        val v = out["q2"]!!
        assertEquals(
            kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("c"))),
            v.value,
        )
        assertEquals(true, v.custom)
    }

    @Test
    fun multi_customAlreadySelectedIsNotAppended() {
        val out = buildAskUserAnswers(
            listOf(multiQ),
            singleAnswers = emptyMap<String, String>(),
            multiAnswers = mapOf("q2" to setOf("a")),
            textValues = mapOf("q2" to "a"),
            skipped = emptySet<String>(),
        )
        val v = out["q2"]!!
        assertEquals(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"))), v.value)
        assertEquals(false, v.custom)
    }

    @Test
    fun skipped_overridesEverything() {
        val out = buildAskUserAnswers(
            listOf(singleQ),
            singleAnswers = mapOf("q1" to "a"),
            multiAnswers = emptyMap<String, Set<String>>(),
            textValues = mapOf("q1" to "text"),
            skipped = setOf("q1"),
        )
        val v = out["q1"]!!
        assertEquals(JsonPrimitive(""), v.value)
        assertEquals(true, v.skipped)
    }

    // ---- parseAnsweredValues / answerLabel (_answeredValues / _answerLabel) ----

    @Test
    fun answeredValues_parsesAnswerPayload() {
        val content = """{"type":"ask_user_answer","answers":{"q1":{"type":"single","value":"a","custom":false,"skipped":false}}}"""
        val values = parseAnsweredValues(content)
        assertEquals(1, values.size)
        assertEquals(JsonPrimitive("a"), values["q1"]!!["value"])
    }

    @Test
    fun answeredValues_invalidJsonIsEmpty() {
        assertEquals(emptyMap<String, JsonObject>(), parseAnsweredValues("not json"))
        assertEquals(emptyMap<String, JsonObject>(), parseAnsweredValues(""))
        assertEquals(emptyMap<String, JsonObject>(), parseAnsweredValues("""{"answers": 3}"""))
    }

    @Test
    fun answerLabel_scalarValue() {
        val content = """{"answers":{"q1":{"type":"single","value":"a","custom":false,"skipped":false}}}"""
        val values = parseAnsweredValues(content)
        assertEquals("a", answerLabel(singleQ, values, "Skipped"))
    }

    @Test
    fun answerLabel_listValueJoinsWithComma() {
        val content = """{"answers":{"q2":{"type":"multi","value":["a","b"],"custom":false,"skipped":false}}}"""
        val values = parseAnsweredValues(content)
        assertEquals("a, b", answerLabel(multiQ, values, "Skipped"))
    }

    @Test
    fun answerLabel_skippedReturnsSkippedLabel() {
        val content = """{"answers":{"q1":{"type":"single","value":"","custom":false,"skipped":true}}}"""
        val values = parseAnsweredValues(content)
        assertEquals("Skipped", answerLabel(singleQ, values, "Skipped"))
    }

    @Test
    fun answerLabel_missingQuestionIsBlank() {
        assertEquals("", answerLabel(singleQ, emptyMap<String, JsonObject>(), "Skipped"))
    }

    // ---- AskUserResult.toJsonString ----

    @Test
    fun answerJson_matchesDartShape() {
        val answers = buildAskUserAnswers(
            listOf(singleQ),
            singleAnswers = mapOf("q1" to "a"),
            multiAnswers = emptyMap<String, Set<String>>(),
            textValues = emptyMap<String, String>(),
            skipped = emptySet<String>(),
        )
        assertEquals(
            """{"type":"ask_user_answer","answers":{"q1":{"type":"single","value":"a","custom":false,"skipped":false}}}""",
            AskUserResult.answer(answers).jsonString,
        )
    }

    @Test
    fun errorJson_matchesDartShape() {
        assertEquals(
            """{"type":"tool_error","error":"cancelled","message":"msg","tool":"ask_user_input_v0"}""",
            AskUserResult.error("cancelled", "msg").jsonString,
        )
    }
}
