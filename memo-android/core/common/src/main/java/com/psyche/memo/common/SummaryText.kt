package com.psyche.memo.common

/**
 * Pure helpers for conversation summary generation — port of
 * home_view_model.dart _maybeGenerateSummaryFor (summaryModelProvider /
 * summaryPrompt / {previous_summary} + {user_messages}).
 */
object SummaryText {

    const val MAX_CONTENT_CHARS = 2000

    /**
     * buildContent — join only the NEW user messages (since the last
     * summarization) into a single block, head-truncated to [maxChars].
     */
    fun buildContent(newUserMessages: List<String>, maxChars: Int = MAX_CONTENT_CHARS): String {
        val joined = newUserMessages.joinToString("\n\n") { it.trim() }
        return if (joined.length > maxChars) joined.substring(0, maxChars) else joined
    }

    /** parseSummary — strip code fences, trim. */
    fun parseSummary(raw: String): String =
        raw.trim().removePrefix("```").removeSuffix("```").trim()
}
