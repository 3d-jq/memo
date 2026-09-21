package com.psyche.memo.common

/**
 * Pure helpers for conversation title generation — port of
 * home_view_model.dart _maybeGenerateTitleFor (titleModelProvider /
 * titlePrompt / {content} + {locale}).
 */
object TitleText {

    const val MAX_CONTENT_MESSAGES = 12
    const val MAX_CONTENT_CHARS = 3000

    /**
     * buildContent — the trailing user/assistant turns, last [maxMessages]
     * entries, tail [maxChars] characters. Mirrors SuggestionText.buildContent.
     */
    fun buildContent(
        messages: List<Pair<String, String>>,
        maxMessages: Int = MAX_CONTENT_MESSAGES,
        maxChars: Int = MAX_CONTENT_CHARS,
    ): String {
        val recent = messages.filter {
            (it.first == "user" || it.first == "assistant") && it.second.trim().isNotEmpty()
        }
        val selected = if (recent.size > maxMessages) {
            recent.subList(recent.size - maxMessages, recent.size)
        } else {
            recent
        }
        val joined = selected.joinToString("\n\n") { (role, content) ->
            (if (role == "user") "User: " else "Assistant: ") + content.trim()
        }
        if (joined.length <= maxChars) return joined
        return joined.substring(joined.length - maxChars)
    }

    /**
     * shouldRefreshCurrent — home_view_model.dart L1531 gates the UI refresh on
     * `currentConversation?.id == convo.id`: a title write for some *other*
     * conversation must NOT touch the currently displayed top bar. Both ids are
     * nullable (no conversation selected yet / temporary chat).
     */
    fun shouldRefreshCurrent(changedId: String?, currentId: String?): Boolean =
        changedId != null && changedId == currentId

    /**
     * parseTitle — strip markdown code fences and wrapping quotes (single /
     * double / curly / corner brackets), then collapse internal whitespace.
     */
    fun parseTitle(raw: String): String {
        var t = raw.trim().removePrefix("```").removeSuffix("```").trim()
        while (t.length >= 2) {
            val next = when {
                t.startsWith("\"") && t.endsWith("\"") -> t.substring(1, t.length - 1).trim()
                t.startsWith("'") && t.endsWith("'") -> t.substring(1, t.length - 1).trim()
                t.startsWith("“") && t.endsWith("”") -> t.substring(1, t.length - 1).trim()
                t.startsWith("「") && t.endsWith("」") -> t.substring(1, t.length - 1).trim()
                else -> t
            }
            if (next == t) break
            t = next
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
