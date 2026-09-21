package com.psyche.memo.worldbook

import com.psyche.memo.data.model.WorldBook
import com.psyche.memo.data.model.WorldBookEntry
import com.psyche.memo.data.model.WorldBookInjectionPosition
import com.psyche.memo.data.model.WorldBookInjectionRole
import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.common.logging.ContextTag
import com.psyche.memo.llm.client.LlmMessage

/**
 * 1:1 port of the lorebook injector in
 * `lib/features/home/services/message_builder_service.dart` L1776-2106
 * (`MessageBuilderService.injectWorldBookPrompts`).
 *
 * Triggers [WorldBookEntry]s by keyword / regex / `constantActive`, then
 * injects their content into the API message list at one of five
 * [WorldBookInjectionPosition]s. Triggers are sorted by `priority` desc, then
 * by file order; content is grouped by [WorldBookInjectionRole]:
 *   - `USER`      → wraps in `<system>...</system>` so providers that treat
 *                   a `user` message containing a `<system>` block as
 *                   authoritative still see the lorebook text.
 *   - `ASSISTANT` → emitted as a plain assistant message (used by some
 *                   providers to seed assistant behavior).
 *
 * The injector is a pure function: callers pass the active book id list and
 * the API messages, and get back a new list with the world-book content
 * spliced in. 5 MiB upper cap per the Dart `clamp(1, 200)` + sane
 * `scanDepth` defaults mirror the source.
 */
object WorldBookInjector {

    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_TOOL = "tool"
    private const val ROLE_SYSTEM = "system"
    private const val SCAN_DEPTH_CAP = 200
    private const val MAX_SCAN_DEPTH = 1

    /**
     * Returns a new list of [LlmMessage] with triggered world-book entries
     * spliced in. `books` and `activeIdsForAssistant` are read from
     * [com.psyche.memo.data.repo.WorldBookRepository]; entries whose book is
     * disabled or whose id is not in `activeIdsForAssistant` are ignored.
     *
     * [tagContextLog] mirrors the original's `if (ContextLogger.enabled)`
     * guards (L1902-2020): when set, every message this injector composes
     * carries a [ContextSource.worldBook] tag (with its injection position) so
     * the context log can attribute it. Tags already on the system message are
     * preserved and the world-book blocks wrap around them.
     */
    fun inject(
        apiMessages: List<LlmMessage>,
        books: List<WorldBook>,
        activeIdsForAssistant: List<String>,
        tagContextLog: Boolean = false,
    ): List<LlmMessage> {
        if (apiMessages.isEmpty() || books.isEmpty() || activeIdsForAssistant.isEmpty()) {
            return apiMessages
        }
        val activeSet = activeIdsForAssistant.toSet()
        val activeBooks = books.filter { it.enabled && it.id in activeSet }
        if (activeBooks.isEmpty()) return apiMessages

        val contextCache = HashMap<Int, String>()
        val triggered = mutableListOf<Pair<WorldBookEntry, Int>>()
        var seq = 0
        for (book in activeBooks) {
            for (entry in book.entries) {
                val depth = entry.scanDepth.coerceIn(1, SCAN_DEPTH_CAP)
                val ctx = contextCache.getOrPut(depth) { extractContextForDepth(apiMessages, depth) }
                if (isTriggered(entry, ctx)) {
                    triggered += entry to seq
                }
                seq++
            }
        }
        if (triggered.isEmpty()) return apiMessages

        // priority desc, then file order asc
        val ordered = triggered.sortedWith(
            compareByDescending<Pair<WorldBookEntry, Int>> { it.first.priority }
                .thenBy { it.second }
        )

        val result = apiMessages.toMutableList()
        val byPosition: Map<WorldBookInjectionPosition, List<WorldBookEntry>> =
            ordered.groupBy { it.first.position }
                .mapValues { (_, entries) -> entries.map { it.first } }

        mergeSystemPrompt(result, byPosition, tagContextLog)
        insertTop(result, byPosition[WorldBookInjectionPosition.TOP_OF_CHAT].orEmpty(), tagContextLog)
        insertBottom(result, byPosition[WorldBookInjectionPosition.BOTTOM_OF_CHAT].orEmpty(), tagContextLog)
        insertAtDepth(result, byPosition[WorldBookInjectionPosition.AT_DEPTH].orEmpty(), tagContextLog)
        return result
    }

    // — internals —

    /**
     * Mirrors `extractContextForDepth` L1799-1814: walk the API messages from
     * the end, collecting up to `scanDepth` non-empty user / assistant
     * contents (newest first), then re-join in chronological order.
     */
    private fun extractContextForDepth(
        apiMessages: List<LlmMessage>,
        scanDepth: Int,
    ): String {
        val depth = if (scanDepth <= 0) MAX_SCAN_DEPTH else scanDepth
        val parts = ArrayList<String>(depth)
        for (i in apiMessages.indices.reversed()) {
            if (parts.size >= depth) break
            val m = apiMessages[i]
            if (m.role != ROLE_USER && m.role != ROLE_ASSISTANT) continue
            val content = m.content?.trim().orEmpty()
            if (content.isEmpty()) continue
            parts += content
        }
        return parts.reversed().joinToString("\n")
    }

    /**
     * Mirrors `isTriggered` L1816-1841:
     *   - `enabled == false` → never
     *   - `constantActive` → always (even with empty keywords)
     *   - empty keywords → no
     *   - else: any keyword (or regex) matches the scan context
     */
    private fun isTriggered(entry: WorldBookEntry, context: String): Boolean {
        if (!entry.enabled) return false
        if (entry.constantActive) return true
        if (entry.keywords.isEmpty()) return false
        for (raw in entry.keywords) {
            val keyword = raw.trim()
            if (keyword.isEmpty()) continue
            val matched = if (entry.useRegex) {
                runCatching {
                    val regex = if (entry.caseSensitive) {
                        Regex(keyword)
                    } else {
                        Regex(keyword, RegexOption.IGNORE_CASE)
                    }
                    regex.containsMatchIn(context)
                }.getOrDefault(false)
            } else {
                if (entry.caseSensitive) context.contains(keyword)
                else context.contains(keyword, ignoreCase = true)
            }
            if (matched) return true
        }
        return false
    }

    /**
     * Group the given entries by [WorldBookInjectionRole], then build one
     * message per role: USER → `<system>...</system>` wrapped, ASSISTANT →
     * plain text. Mirrors `createMergedInjectionMessages` L1881-1913.
     */
    private fun buildInjectionMessages(
        entries: List<WorldBookEntry>,
        position: WorldBookInjectionPosition,
        tagContextLog: Boolean,
    ): List<LlmMessage> {
        if (entries.isEmpty()) return emptyList()
        val byRole = entries
            .filter { it.content.isNotBlank() }
            .groupBy { it.role }
        val out = mutableListOf<LlmMessage>()
        for ((role, group) in byRole) {
            val merged = group.joinToString("\n") { it.content.trim() }
            if (merged.isEmpty()) continue
            val content = when (role) {
                WorldBookInjectionRole.ASSISTANT -> merged
                WorldBookInjectionRole.USER -> wrapSystemTag(merged)
            }
            out += LlmMessage(
                role = if (role == WorldBookInjectionRole.ASSISTANT) ROLE_ASSISTANT else ROLE_USER,
                content = content,
                contextTags = if (tagContextLog) {
                    listOf(ContextTag(ContextSource.worldBook, content.length, position.meta()))
                } else {
                    emptyList()
                },
            )
        }
        return out
    }

    /** `WorldBookInjectionPosition.toJson()` — the enum's wire spelling. */
    private fun WorldBookInjectionPosition.meta(): Map<String, String> =
        mapOf("position" to name)

    private fun wrapSystemTag(content: String): String = "<system>\n$content\n</system>"

    /**
     * Find the first system message and merge `before` content before its
     * content and `after` content after. If no system message exists, create
     * one at the top with the merged content. Mirrors L1932-2041.
     */
    private fun mergeSystemPrompt(
        result: MutableList<LlmMessage>,
        byPosition: Map<WorldBookInjectionPosition, List<WorldBookEntry>>,
        tagContextLog: Boolean,
    ) {
        val before = joinContent(byPosition[WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT])
        val after = joinContent(byPosition[WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT])
        if (before.isEmpty() && after.isEmpty()) return
        val beforeMeta = WorldBookInjectionPosition.BEFORE_SYSTEM_PROMPT.meta()
        val afterMeta = WorldBookInjectionPosition.AFTER_SYSTEM_PROMPT.meta()

        val systemIndex = result.indexOfFirst { it.role == ROLE_SYSTEM }
        if (systemIndex >= 0) {
            val original = result[systemIndex].content.orEmpty()
            val sb = StringBuilder()
            if (before.isNotEmpty()) {
                sb.append(before)
                sb.append('\n')
            }
            sb.append(original)
            if (after.isNotEmpty()) {
                sb.append('\n')
                sb.append(after)
            }
            val existing = result[systemIndex]
            // The before-block owns the "\n" that follows it, the after-block
            // the one that precedes it (L1960-1980). An untagged system
            // message (no assistant prompt / memory rules / search prompt)
            // gets one systemPrompt tag so the original text is not attributed
            // to the world-book block.
            val tags = if (!tagContextLog) {
                existing.contextTags
            } else {
                buildList {
                    if (before.isNotEmpty()) {
                        add(ContextTag(ContextSource.worldBook, before.length + 1, beforeMeta))
                    }
                    if (existing.contextTags.isEmpty() && original.isNotEmpty()) {
                        add(ContextTag(ContextSource.systemPrompt, original.length))
                    }
                    addAll(existing.contextTags)
                    if (after.isNotEmpty()) {
                        add(ContextTag(ContextSource.worldBook, 1 + after.length, afterMeta))
                    }
                }
            }
            result[systemIndex] = existing.copy(content = sb.toString(), contextTags = tags)
        } else {
            val sb = StringBuilder()
            if (before.isNotEmpty()) sb.append(before)
            if (after.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(after)
            }
            if (sb.isEmpty()) return
            val tags = when {
                !tagContextLog -> emptyList()
                before.isNotEmpty() && after.isNotEmpty() -> listOf(
                    ContextTag(ContextSource.worldBook, before.length + 1, beforeMeta),
                    ContextTag(ContextSource.worldBook, after.length, afterMeta),
                )
                before.isNotEmpty() -> listOf(
                    ContextTag(ContextSource.worldBook, before.length, beforeMeta),
                )
                else -> listOf(ContextTag(ContextSource.worldBook, after.length, afterMeta))
            }
            result.add(0, LlmMessage(role = ROLE_SYSTEM, content = sb.toString(), contextTags = tags))
        }
    }

    /** TOP_OF_CHAT: insert before the first user message (or at end). L2044-2059. */
    private fun insertTop(
        result: MutableList<LlmMessage>,
        entries: List<WorldBookEntry>,
        tagContextLog: Boolean,
    ) {
        if (entries.isEmpty()) return
        val messages = buildInjectionMessages(entries, WorldBookInjectionPosition.TOP_OF_CHAT, tagContextLog)
        if (messages.isEmpty()) return
        var idx = result.indexOfFirst { it.role == ROLE_USER }
        if (idx < 0) idx = result.size
        idx = safeInsertIndex(result, idx)
        result.addAll(idx, messages)
    }

    /** BOTTOM_OF_CHAT: insert before the last message. L2061-2074. */
    private fun insertBottom(
        result: MutableList<LlmMessage>,
        entries: List<WorldBookEntry>,
        tagContextLog: Boolean,
    ) {
        if (entries.isEmpty()) return
        val messages = buildInjectionMessages(entries, WorldBookInjectionPosition.BOTTOM_OF_CHAT, tagContextLog)
        if (messages.isEmpty()) return
        var idx = if (result.isEmpty()) 0 else result.size - 1
        idx = safeInsertIndex(result, idx)
        result.addAll(idx, messages)
    }

    /**
     * AT_DEPTH: each entry's `injectDepth` is clamped to [1, 200]; entries
     * with the same depth are merged into one message group. Insertions
     * happen at `length - depth`, deepest first, so each subsequent (shallower)
     * insertion lands at the right index. L2076-2105.
     */
    private fun insertAtDepth(
        result: MutableList<LlmMessage>,
        entries: List<WorldBookEntry>,
        tagContextLog: Boolean,
    ) {
        if (entries.isEmpty()) return
        val byDepth = entries
            .groupBy { it.injectDepth.coerceIn(1, SCAN_DEPTH_CAP) }
        for (depth in byDepth.keys.sortedDescending()) {
            val messages = buildInjectionMessages(
                byDepth[depth].orEmpty(),
                WorldBookInjectionPosition.AT_DEPTH,
                tagContextLog,
            )
            if (messages.isEmpty()) continue
            val target = (result.size - depth).coerceIn(0, result.size)
            val idx = safeInsertIndex(result, target)
            result.addAll(idx, messages)
        }
    }

    /**
     * Walk backwards from `target` until we land on a non-tool message
     * (or hit index 0), so injected content doesn't get sandwiched between
     * tool calls. Mirrors `findSafeInsertIndex` L1915-1923.
     */
    private fun safeInsertIndex(result: List<LlmMessage>, target: Int): Int {
        var idx = target.coerceIn(0, result.size)
        while (idx > 0 && idx < result.size) {
            if (result[idx].role != ROLE_TOOL) break
            idx--
        }
        return idx
    }

    private fun joinContent(entries: List<WorldBookEntry>?): String =
        entries?.joinToString("\n") { it.content.trim() }?.takeIf { it.isNotEmpty() }.orEmpty()
}
