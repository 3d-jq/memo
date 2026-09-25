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

    // ---- 加载技能的工具卡标题（照 RikkaHub UseSkillToolUI.title） ----

    @Test
    fun skillTitleShowsTheSkillNameAndOptionalPath() {
        // 用户 2026-09-14：「加载 skill…我这个怎么是调用工具呀显示 应该是加载吧，
        // 你看看 rikkhub 就是显示加载 skill」—— 标题必须是「技能：<名>」而不是
        // 默认的「调用工具 use_skill」。
        assertEquals("技能：pdf", skillToolTitle("技能：pdf", null))
        assertEquals("技能：pdf", skillToolTitle("技能：pdf", ""))
        assertEquals("技能：pdf", skillToolTitle("技能：pdf", "   "))
        assertEquals("技能：pdf / reference.md", skillToolTitle("技能：pdf", "reference.md"))
    }

    @Test
    fun skillNameFallsBackToTheToolNameAndReadsTheArgument() {
        assertEquals(
            "pdf",
            skillNameFrom(JsonObject(mapOf("name" to JsonPrimitive("pdf")))),
        )
        // 参数里没有/是空白 → 退回 "skill"，别让标题变成空的「技能：」。
        assertEquals("skill", skillNameFrom(JsonObject(emptyMap())))
        assertEquals("skill", skillNameFrom(JsonObject(mapOf("name" to JsonPrimitive("  ")))))
        assertEquals("skill", skillNameFrom(null))
    }

    // ---- 工具结果里的图片（payload images 键，工作区读图片） ----

    @Test
    fun fromPayload_readsAttachedImagesAndMergesThemWithMarkdownPaths() {
        val payload = JsonObject(
            mapOf(
                "id" to JsonPrimitive("t1"),
                "name" to JsonPrimitive("workspace_read_file"),
                "content" to JsonPrimitive("shot\n\n![inline](/tmp/inline.png)"),
                "images" to JsonArray(
                    listOf(JsonObject(mapOf("uri" to JsonPrimitive("/files/tool_images/a.png")))),
                ),
            ),
        ).toString()
        val part = ToolUiPart.fromPayload(payload)!!

        // payload 附件在前，正文里的 markdown 图片标记在后；两者共用同一条图片横滚条。
        assertEquals(
            listOf("/files/tool_images/a.png", "/tmp/inline.png"),
            part.allImagePaths,
        )
    }

    @Test
    fun fromPayload_withoutImagesKeepsTheMarkdownOnlyList() {
        val payload = JsonObject(
            mapOf(
                "id" to JsonPrimitive("t1"),
                "name" to JsonPrimitive("workspace_read_file"),
                "content" to JsonPrimitive("![only](/tmp/only.png)"),
            ),
        ).toString()
        val part = ToolUiPart.fromPayload(payload)!!
        assertTrue(part.attachedImages.isEmpty())
        assertEquals(listOf("/tmp/only.png"), part.allImagePaths)
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

    // ---- prettyToolJson (chat_message_widget.dart _prettyToolJson) ----

    @Test
    fun prettyToolJson_validObjectIndentTwo() {
        // 等价 Dart：`jsonEncode(parse(raw))` 后用两空格缩进输出。原版没有
        // 断言缩进，仅验证「输入是合法 JSON 时输出也是合法 JSON 且无损 round-trip」。
        val raw = """{"a":1,"b":"x","c":[1,2]}"""
        val pretty = prettyToolJson(raw)
        val roundTrip = pretty.replace("\n", "").replace(" ", "")
        assertEquals(raw, roundTrip)
        // 美化后必须包含换行（与 [prettyPrint = true] 一致）。
        assertTrue(pretty.contains("\n"))
    }

    @Test
    fun prettyToolJson_invalidFallsBackToRaw() {
        val raw = "{not json"
        assertEquals(raw, prettyToolJson(raw))
        assertEquals("", prettyToolJson(""))
    }

    @Test
    fun prettyToolJson_nullElementPreserved() {
        val pretty = prettyToolJson("null")
        // kotlinx 的 compact 形式是 `null`；pretty 形式可以是多行；两者都能
        // 被 parseToJsonElement 重新识别为 JsonNull。
        assertTrue(pretty == "null" || pretty.lines().any { it.trim() == "null" })
    }
}
