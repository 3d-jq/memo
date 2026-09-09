package com.psyche.memo.common

/**
 * Pure text helpers for context compression — port of
 * lib/core/models/compress_context_options.dart. Messages are represented as
 * role/content pairs so this module stays free of the data layer.
 */
object CompressText {

    enum class Mode { START, RECENT, UNLIMITED, KEEP_RECENT }

    const val DEFAULT_MAX_CHARS = 6000
    const val DEFAULT_KEEP_USER_MESSAGES = 3
    const val SAFE_REQUEST_CHARS = 100000
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32000
    const val CONTEXT_RESERVE_FRACTION = 0.30
    const val CHARS_PER_TOKEN = 1.6
    const val CONTEXT_RETRY_MAX_SPLITS = 5
    const val CONTEXT_RETRY_MIN_SPLIT_CHARS = 512

    /** conversationLineForCompression — null for blank content. */
    fun conversationLine(role: String, content: String): String? {
        if (content.trim().isEmpty()) return null
        return (if (role == "assistant") "Assistant: " else "User: ") + content
    }

    fun buildConversationText(messages: List<Pair<String, String>>): String =
        messages.mapNotNull { (role, content) -> conversationLine(role, content) }
            .joinToString("\n\n")

    /** buildBoundedConversationText — start or recent incremental window. */
    fun buildBoundedConversationText(
        messages: List<Pair<String, String>>,
        mode: Mode,
        maxChars: Int,
    ): String {
        if (maxChars <= 0) return ""
        return if (mode == Mode.RECENT) {
            buildRecentBounded(messages, maxChars)
        } else {
            buildStartBounded(messages, maxChars)
        }
    }

    private fun buildStartBounded(messages: List<Pair<String, String>>, maxChars: Int): String {
        val buf = StringBuilder()
        for ((role, content) in messages) {
            if (content.trim().isEmpty()) continue
            val prefix = if (role == "assistant") "Assistant: " else "User: "
            val sep = if (buf.isEmpty()) "" else "\n\n"
            val remaining = maxChars - buf.length
            if (remaining <= 0) break
            val head = sep + prefix
            if (head.length >= remaining) {
                buf.append(Utf16SafeCut.truncateHead(head, remaining))
                break
            }
            val contentBudget = remaining - head.length
            val body = if (content.length <= contentBudget) {
                content
            } else {
                Utf16SafeCut.truncateHead(content, contentBudget)
            }
            buf.append(head).append(body)
            if (content.length > contentBudget) break
        }
        return buf.toString()
    }

    private fun buildRecentBounded(messages: List<Pair<String, String>>, maxChars: Int): String {
        val parts = ArrayList<String>()
        var used = 0
        for (i in messages.indices.reversed()) {
            val (role, content) = messages[i]
            if (content.trim().isEmpty()) continue
            val prefix = if (role == "assistant") "Assistant: " else "User: "
            val sepLen = if (parts.isEmpty()) 0 else 2
            val remaining = maxChars - used - sepLen
            if (remaining <= 0) break
            val lineLen = prefix.length + content.length
            if (lineLen <= remaining) {
                parts.add(prefix + content)
                used += lineLen + sepLen
                continue
            }
            val tail = utf16SafeTailOfPrefixed(prefix, content, remaining)
            if (tail.isNotEmpty()) parts.add(tail)
            break
        }
        return parts.reversed().joinToString("\n\n")
    }

    private fun utf16SafeTailOfPrefixed(prefix: String, content: String, maxChars: Int): String {
        val total = prefix.length + content.length
        if (total <= maxChars) return prefix + content
        if (maxChars <= 0) return ""
        if (maxChars <= content.length) {
            val start = Utf16SafeCut.tailStart(content, content.length - maxChars)
            return content.substring(start)
        }
        val prefixKeep = maxChars - content.length
        val start = Utf16SafeCut.tailStart(prefix, prefix.length - prefixKeep)
        return prefix.substring(start) + content
    }

    /** chunkMessagesForCompression — message-boundary chunks. */
    fun chunkMessages(
        messages: List<Pair<String, String>>,
        maxChars: Int,
    ): List<String> {
        if (maxChars <= 0) return emptyList()
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            chunks.add(current.toString())
            current.clear()
        }
        for ((role, content) in messages) {
            val line = conversationLine(role, content) ?: continue
            if (line.length > maxChars) {
                flush()
                chunks.addAll(Utf16SafeCut.splitChunks(line, maxChars))
                continue
            }
            val extra = if (current.isEmpty()) line.length else line.length + 2
            if (current.isNotEmpty() && current.length + extra > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(line)
        }
        flush()
        return chunks
    }

    /** chunkPlainTexts — pack already-formatted summaries into budget groups. */
    fun chunkPlainTexts(texts: List<String>, maxChars: Int): List<String> {
        if (maxChars <= 0) return emptyList()
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            chunks.add(current.toString())
            current.clear()
        }
        for (text in texts) {
            if (text.isEmpty()) continue
            if (text.length > maxChars) {
                flush()
                chunks.addAll(Utf16SafeCut.splitChunks(text, maxChars))
                continue
            }
            val extra = if (current.isEmpty()) text.length else text.length + 2
            if (current.isNotEmpty() && current.length + extra > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(text)
        }
        flush()
        return chunks
    }

    /** buildCompressRequestContents — per-request payloads. */
    fun buildCompressRequestContents(
        messages: List<Pair<String, String>>,
        mode: Mode,
        maxChars: Int?,
        safeRequestChars: Int = SAFE_REQUEST_CHARS,
    ): List<String> {
        if (mode == Mode.START || mode == Mode.RECENT) {
            val bounded = buildBoundedConversationText(messages, mode, maxChars ?: DEFAULT_MAX_CHARS)
            if (bounded.trim().isEmpty()) return emptyList()
            if (bounded.length <= safeRequestChars) return listOf(bounded)
            return Utf16SafeCut.splitChunks(bounded, safeRequestChars)
        }
        return chunkMessages(messages, safeRequestChars)
    }

    /** selectKeepRecentMessages — trailing messages from the Nth-last user turn. */
    fun selectKeepRecentMessages(
        messages: List<Pair<String, String>>,
        keepUserMessages: Int,
    ): List<Pair<String, String>> {
        if (keepUserMessages <= 0) return emptyList()
        val userIndices = messages.indices.filter {
            messages[it].first == "user" && messages[it].second.trim().isNotEmpty()
        }
        if (userIndices.isEmpty()) return emptyList()
        if (userIndices.size <= keepUserMessages) return messages.toList()
        return messages.subList(userIndices[userIndices.size - keepUserMessages], messages.size).toList()
    }

    /** countUserMessages — non-empty user turns. */
    fun countUserMessages(messages: List<Pair<String, String>>): Int =
        messages.count { it.first == "user" && it.second.trim().isNotEmpty() }

    /** defaultKeepUserMessageCountFor — 1 / 2 / 3 by conversation size. */
    fun defaultKeepUserMessageCountFor(userMessageCount: Int): Int = when {
        userMessageCount < 5 -> 1
        userMessageCount < 10 -> 2
        else -> 3
    }

    /** compressRequestCharBudget — per-request UTF-16 budget. */
    fun compressRequestCharBudget(
        contextWindowTokens: Int?,
        safeRequestChars: Int = SAFE_REQUEST_CHARS,
        defaultContextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
        reserveFraction: Double = CONTEXT_RESERVE_FRACTION,
        charsPerToken: Double = CHARS_PER_TOKEN,
    ): Int {
        val window = if (contextWindowTokens == null || contextWindowTokens <= 0) {
            defaultContextWindowTokens
        } else {
            contextWindowTokens
        }
        val chars = (window * (1.0 - reserveFraction) * charsPerToken).toInt()
        if (chars < 1) return 1
        return if (chars < safeRequestChars) chars else safeRequestChars
    }

    data class TokenEstimate(
        val totalTokens: Int,
        val keptTokens: Int,
        val minResultTokens: Int,
        val maxResultTokens: Int,
    )

    /** estimateCompressionTokens — 10%-30% band on the summarized-away part. */
    fun estimateCompressionTokens(totalText: String, keptText: String): TokenEstimate {
        val totalTokens = estimateCharsToTokens(totalText)
        val totalChars = totalText.length
        if (totalChars == 0) return TokenEstimate(0, 0, 0, 0)
        val keptTokens = (totalTokens.toDouble() * keptText.length / totalChars).toInt()
        val oldTokens = totalTokens - keptTokens
        return TokenEstimate(
            totalTokens = totalTokens,
            keptTokens = keptTokens,
            minResultTokens = keptTokens + (oldTokens * 0.10).toInt(),
            maxResultTokens = keptTokens + (oldTokens * 0.30).toInt(),
        )
    }

    /** isContextLengthError — conservative prompt-too-long detector. */
    fun isContextLengthError(error: Throwable): Boolean {
        val lower = error.toString().lowercase()
        val phrases = listOf(
            "context_length", "context length", "context window", "maximum context",
            "max context", "too many tokens", "prompt is too long", "prompt too long",
            "input is too long", "input too long", "reduce the length of the messages",
            "reduce the length of the prompt", "exceeds the maximum number of tokens",
        )
        if (phrases.any { lower.contains(it) }) return true
        return lower.contains("max_tokens") && (
            lower.contains("exceed") || lower.contains("too long") ||
                lower.contains("too many") || lower.contains("over")
            )
    }

    /** resolveCompressContextModel — compress → summary → title → assistant → current. */
    fun resolveCompressModel(
        compress: Pair<String, String>?,
        summary: Pair<String, String>?,
        title: Pair<String, String>?,
        assistant: Pair<String, String>?,
        current: Pair<String, String>?,
    ): Pair<String, String>? {
        val provider = compress?.first ?: summary?.first ?: title?.first
            ?: assistant?.first ?: current?.first
        val model = compress?.second ?: summary?.second ?: title?.second
            ?: assistant?.second ?: current?.second
        return if (provider.isNullOrEmpty() || model.isNullOrEmpty()) null else provider to model
    }

    /** _estimateCharsToTokens — CJK / 1.6 + other / 4. */
    internal fun estimateCharsToTokens(text: String): Int {
        var cjk = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (isCjk(codePoint)) cjk++ else other++
            i += Character.charCount(codePoint)
        }
        return (cjk / 1.6 + other / 4.0).toInt()
    }

    private fun isCjk(codePoint: Int): Boolean =
        (codePoint in 0x2E80..0x9FFF) ||
            (codePoint in 0xF900..0xFAFF) ||
            (codePoint in 0xFF00..0xFFEF)
}
