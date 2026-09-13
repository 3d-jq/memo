package com.psyche.memo.llm.prompt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PromptTransformerTest {

    private val now = LocalDateTime.of(2026, 9, 7, 4, 5, 6)

    @Test
    fun substitutesAllFourVariables() {
        val out = PromptTransformer.applyMessageTemplate(
            "[{{ date }} {{ time }}] {{ role }}: {{ message }}",
            role = "user",
            message = "你好",
            now = now,
        )
        assertEquals("[2026-09-07 04:05] user: 你好", out)
    }

    @Test
    fun padsDateAndTimeWithZeroes() {
        val out = PromptTransformer.applyMessageTemplate(
            "{{ date }} {{ time }}",
            role = "user",
            message = "x",
            now = LocalDateTime.of(2026, 1, 2, 3, 4),
        )
        assertEquals("2026-01-02 03:04", out)
    }

    @Test
    fun ignoresWhitespaceAroundTheVariableName() {
        val tight = PromptTransformer.applyMessageTemplate("{{message}}", "user", "hi", now)
        val loose = PromptTransformer.applyMessageTemplate("{{   message\t}}", "user", "hi", now)
        assertEquals("hi", tight)
        assertEquals("hi", loose)
    }

    @Test
    fun leavesUnknownVariablesVerbatim() {
        val out = PromptTransformer.applyMessageTemplate(
            "{{ message }}/{{ unknown }}/{{role}}",
            role = "assistant",
            message = "ok",
            now = now,
        )
        assertEquals("ok/{{ unknown }}/assistant", out)
    }

    @Test
    fun repeatsEveryOccurrence() {
        assertEquals(
            "hi hi hi",
            PromptTransformer.applyMessageTemplate("{{ message }} {{ message }} {{ message }}", "user", "hi", now),
        )
    }

    @Test
    fun plainTemplatePassesThrough() {
        assertEquals(
            "no placeholders here",
            PromptTransformer.applyMessageTemplate("no placeholders here", "user", "hi", now),
        )
    }

    // ---- 系统提示词的 12 个 `{...}` 变量（PromptTransformer.buildPlaceholders）----

    private fun placeholders(now: LocalDateTime = this.now) = PromptTransformer.buildPlaceholders(
        assistantName = "小满",
        userNickname = "老大",
        modelId = "glm-5.3-flash",
        modelName = null, // 原版调用方也传 modelId → {model_name} 回退
        locale = "zh-CN",
        timezone = "GMT+08:00",
        systemVersion = "android 15",
        deviceInfo = "android Xiaomi 23127PN0CC",
        batteryLevel = "82%",
        now = now,
    )

    @Test
    fun systemPromptVariablesCoverEveryAdvertisedKey() {
        val vars = placeholders()
        // 助手编辑页「可用变量」列的 12 个，一个都不能少。
        assertEquals(
            setOf(
                "{cur_date}", "{cur_time}", "{cur_datetime}", "{model_id}", "{model_name}",
                "{locale}", "{timezone}", "{system_version}", "{device_info}",
                "{battery_level}", "{nickname}", "{assistant_name}",
            ),
            vars.keys,
        )
        assertEquals("2026-09-07", vars["{cur_date}"])
        assertEquals("04:05", vars["{cur_time}"])
        assertEquals("2026-09-07 04:05", vars["{cur_datetime}"])
        assertEquals("glm-5.3-flash", vars["{model_id}"])
        assertEquals("glm-5.3-flash", vars["{model_name}"])
        assertEquals("小满", vars["{assistant_name}"])
        assertEquals("老大", vars["{nickname}"])
    }

    @Test
    fun systemPromptReplacementKeepsUnknownTextAndIsLiteralNotRegex() {
        val out = PromptTransformer.replacePlaceholders(
            "现在是 {cur_datetime}，主人是 {nickname}；{unknown} 保持原样，{cur_date} 重复也替换 {cur_date}",
            placeholders(),
        )
        assertEquals(
            "现在是 2026-09-07 04:05，主人是 老大；{unknown} 保持原样，2026-09-07 重复也替换 2026-09-07",
            out,
        )
    }

    @Test
    fun messageTemplateTokensAreNotTouchedByTheSystemPromptPass() {
        // 两套括号别混：`{{ message }}` 是消息模板语法，系统提示词替换不该动它。
        val out = PromptTransformer.replacePlaceholders("{{ message }} {cur_date}", placeholders())
        assertEquals("{{ message }} 2026-09-07", out)
        // 反过来，消息模板替换也不该吃掉单括号变量。
        assertEquals(
            "{cur_date} hi",
            PromptTransformer.applyMessageTemplate("{cur_date} {{ message }}", "user", "hi", now),
        )
    }

    @Test
    fun missingModelIdBecomesEmptyNotLiteralNull() {
        val vars = PromptTransformer.buildPlaceholders(
            assistantName = "A",
            userNickname = "",
            modelId = null,
            modelName = null,
            locale = "en-US",
            timezone = "UTC",
            systemVersion = "android 14",
            deviceInfo = "android",
            now = now,
        )
        assertEquals("", vars["{model_id}"])
        assertEquals("", vars["{model_name}"])
    }
}
