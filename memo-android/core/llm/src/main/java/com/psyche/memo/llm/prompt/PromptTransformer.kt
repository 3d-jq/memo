package com.psyche.memo.llm.prompt

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Port of PromptTransformer in lib/core/services/chat/prompt_transformer.dart.
 * Only the mustache message-template pass lives here so far: it is what the
 * assistant prompt-tab preview renders, and the chat request pipeline will
 * reuse it once {cur_date}-style placeholders are wired up.
 */
object PromptTransformer {

    private val VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun applyMessageTemplate(
        template: String,
        role: String,
        message: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String {
        val vars = mapOf(
            "role" to role,
            "message" to message,
            "time" to now.format(TIME_FORMAT),
            "date" to now.format(DATE_FORMAT),
        )
        return VARIABLE.replace(template) { match ->
            vars[match.groupValues[1]] ?: match.value
        }
    }
}
