package com.psyche.memo.ui.markdown

import androidx.compose.material3.Text
import com.psyche.memo.ui.R
import kotlinx.coroutines.flow.map
import org.commonmark.node.Link
import org.commonmark.node.Text

/**
 * Normalize Cherry-style `[cite:id]` (optionally comma-separated,
 * e.g. `[cite:a1b2c3, d4e5f6]`) and legacy `[citation:ref]` markers into
 * `[citation](id)` markdown links so they render as numbered capsules.
 * Ports markdown_with_highlight.dart `_normalizeCiteMarkers` +
 * `_normalizeRawCitationMetadata`.
 */
internal fun preprocessCitations(input: String): String =
    normalizeRawCitationMetadata(normalizeCiteMarkers(input))

private fun normalizeCiteMarkers(input: String): String {
    val citeMarker = Regex(
        """\[cite:\s*([A-Za-z0-9_-]+(?:\s*,\s*[A-Za-z0-9_-]+)*)\s*\]""",
        RegexOption.IGNORE_CASE,
    )
    return citeMarker.replace(input) { m ->
        m.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "[citation]($it)" }
    }
}

private fun normalizeRawCitationMetadata(input: String): String {
    val rawCitation = Regex("""\[citation:([^\]\r\n]+)\]""", RegexOption.IGNORE_CASE)
    return rawCitation.replace(input) { m ->
        val refs = parseCitationRefList(m.groupValues[1])
        if (refs.isEmpty()) m.value else refs.joinToString(" ") { "[citation](${it.markdownTarget})" }
    }
}

/** Parsed `[citation](index:id)` target. */
internal data class CitationRef(val indexText: String, val id: String) {
    val markdownTarget: String
        get() = if (indexText == id) indexText else "$indexText:$id"
}

private fun String.isCitationIndex(): Boolean = Regex("""^[A-Za-z0-9_-]+$""").matches(this)

internal fun parseCitationRef(raw: String): CitationRef? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val sep = trimmed.indexOf(':')
    val hasSep = sep != -1
    val indexText = if (sep == -1) trimmed else trimmed.substring(0, sep).trim()
    val id = if (sep == -1) indexText else trimmed.substring(sep + 1).trim()
    if (!indexText.isCitationIndex() ||
        (hasSep && !Regex("""\d""").containsMatchIn(indexText)) ||
        id.isEmpty()
    ) {
        return null
    }
    if (id.contains(')') || id.contains(']') || Regex("""\s""").containsMatchIn(id)) return null
    return CitationRef(indexText = indexText, id = id)
}

/** A resolved inline citation capsule: lookup key + display text. */
internal data class CitationCapsule(val key: String, val text: String)

/**
 * 普通 Markdown 链接 `[标签](https://…)` → 序号胶囊，前提是这个 URL 能解析成
 * 本条消息的来源（[resolver] 返回了序号）。
 *
 * 模型实测（DeepSeek + MCP 搜索工具）不写 `[cite:id]`，而是把来源写成
 * `[链接](https://aihot.news/items/…)`；那时正文里只剩"链接"两个字，用户要的是
 * 「胶囊 + 数字」。命中来源才转换，未命中的链接照旧按普通链接渲染。
 */
internal fun resolveSourceUrlCapsule(
    url: String,
    resolver: ((String) -> CitationInfo?)?,
): CitationCapsule? {
    val dest = url.trim()
    if (!dest.startsWith("http://", ignoreCase = true) &&
        !dest.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val index = resolver?.invoke(dest)?.index ?: return null
    return CitationCapsule(dest, index.toString())
}

/**
 * Resolve a `[citation,X](Y)` / `[cite,X](Y)` link into a capsule.
 *
 * Models drift from the prompted `[cite:id]` to the comma-metadata spelling
 * (`[citation,domain](id)` / `[cite,domain](id)`); both are accepted here. The
 * capsule key prefers a 6-char id token (Memo search ids are `UUID.take(6)`)
 * taken from the destination first, then the label; a URL destination renders
 * a capsule that opens the link directly. The label metadata is ignored for
 * display — the capsule always shows the numeric index, like the original
 * project.
 *
 * Returns `null` when the label is not a citation at all (the caller then
 * renders an ordinary link), and a capsule with an **empty [CitationCapsule.text]**
 * when the marker *is* a citation but its index cannot be resolved — the caller
 * drops those rather than drawing a "?" (2026-09-12, user decision; the
 * original falls back to "?" here). A numeric label metadata still wins over
 * dropping, since it is a usable index on its own.
 */
internal fun resolveCitationCapsule(
    label: String,
    destination: String,
    resolver: ((String) -> CitationInfo?)?,
): CitationCapsule? {
    val lower = label.lowercase()
    val meta = when {
        lower.startsWith("citation,") -> label.substring("citation,".length).trim()
        lower.startsWith("cite,") -> label.substring("cite,".length).trim()
        else -> return null
    }
    if (meta.isEmpty()) return null
    val dest = destination.trim()
    val key = listOf(dest, meta).firstOrNull { it.length == 6 && it.isCitationIndex() }
        ?: dest.takeIf { it.contains('.') || it.contains('/') }
        ?: return null
    val info = resolver?.invoke(key)
    val text = info?.index?.toString()
        ?: meta.toIntOrNull()?.toString()
        ?: ""
    return CitationCapsule(key, text)
}

private fun parseCitationRefList(raw: String): List<CitationRef> {
    val refs = mutableListOf<CitationRef>()
    for (part0 in raw.split(',')) {
        var part = part0.trim()
        if (part.isEmpty()) return emptyList()
        if (part.startsWith("citation:", ignoreCase = true)) part = part.substring("citation:".length).trim()
        val ref = parseCitationRef(part) ?: return emptyList()
        refs.add(ref)
    }
    return refs
}