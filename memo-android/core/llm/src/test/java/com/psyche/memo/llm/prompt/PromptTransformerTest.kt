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
}
