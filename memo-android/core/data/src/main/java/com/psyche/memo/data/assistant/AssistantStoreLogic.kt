package com.psyche.memo.data.assistant

import com.psyche.memo.data.model.Assistant

/**
 * Pure business rules for the assistant list — 1:1 port of
 * lib/core/providers/assistant_provider.dart (kept free of Android types
 * so the unit tests can exercise them directly).
 */

/** assistant_provider.dart L155-170 _buildCopyName. */
fun buildCopyName(
    sourceName: String,
    existingNames: Set<String>,
    copySuffix: String,
    fallbackName: String,
): String {
    val suffix = copySuffix.trim()
    val baseName = sourceName.trim().ifEmpty { fallbackName }
    var candidate = if (suffix.isEmpty()) baseName else "$baseName $suffix"
    var counter = 2
    while (existingNames.contains(candidate)) {
        val counterSuffix = if (suffix.isEmpty()) "$counter" else "$suffix $counter"
        candidate = "$baseName $counterSuffix"
        counter++
    }
    return candidate
}

/** assistant_provider.dart L377: a duplicate is inserted right after its source. */
fun insertAfter(ids: List<String>, afterId: String, newId: String): List<String> {
    val idx = ids.indexOf(afterId)
    if (idx == -1) return ids + newId
    return ids.toMutableList().apply { add(idx + 1, newId) }
}

/** assistant_provider.dart L508-521 reorderAssistants with its bounds guards. */
fun reorderMove(ids: List<String>, from: Int, to: Int): List<String>? {
    if (from == to) return null
    if (from < 0 || from >= ids.size) return null
    if (to < 0 || to >= ids.size) return null
    val next = ids.toMutableList()
    val moved = next.removeAt(from)
    next.add(to, moved)
    return next
}

/** assistant_provider.dart L487: never delete the last remaining assistant. */
fun canDeleteAssistant(count: Int): Boolean = count > 1

/**
 * assistant_provider.dart L116-142 ensureDefaults — the localized default
 * assistant plus the sample assistant (its system prompt template keeps the
 * literal {model_name} placeholder, formatted by the caller). Only the two
 * constructors; emptiness is the caller's decision.
 */
fun buildSeedAssistants(
    defaultName: String,
    sampleName: String,
    samplePrompt: String,
    newId: () -> String,
): List<Assistant> = listOf(
    Assistant(id = newId(), name = defaultName),
    Assistant(id = newId(), name = sampleName, systemPrompt = samplePrompt),
)
