package com.psyche.memo.common

/**
 * Pure helpers for chat suggestion bubbles — port of
 * chat_suggestion_service.dart parseSuggestions / buildContent.
 */
object SuggestionText {

    const val MAX_SUGGESTION_COUNT = 3
    const val MAX_SUGGESTION_CHARS = 300
    const val MAX_CONTENT_MESSAGES = 8
    const val MAX_CONTENT_CHARS = 4000

    /**
     * parseSuggestions — splits on line breaks and sentence ends, strips
     * bullet/numbering markers and wrapping quotes, dedupes and caps.
     */
    fun parseSuggestions(
        raw: String,
        maxCount: Int = MAX_SUGGESTION_COUNT,
        maxChars: Int = MAX_SUGGESTION_CHARS,
    ): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val lines = raw.split(Regex("[\\r\\n]+"))
            .flatMap { line -> line.split(Regex("(?<=[。！？!?])\\s+")) }
        for (line in lines) {
            var text = line.trim()
            if (text.isEmpty()) continue
            text = text.replaceFirst(Regex("^\\s*[-*•]\\s*"), "")
                .replaceFirst(Regex("^\\s*\\d+[.)、]\\s*"), "")
                .trim()
            if ((text.startsWith("\"") && text.endsWith("\"")) ||
                (text.startsWith("'") && text.endsWith("'")) ||
                (text.startsWith("“") && text.endsWith("”"))
            ) {
                text = text.substring(1, text.length - 1).trim()
            }
            if (text.isEmpty() || text.length > maxChars) continue
            if (!seen.add(text)) continue
            out.add(text)
            if (out.size >= maxCount) break
        }
        return out
    }

    /**
     * buildContent — the trailing user/assistant turns (already truncated by
     * the caller), last [maxMessages] entries, tail [maxChars] characters.
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
}
