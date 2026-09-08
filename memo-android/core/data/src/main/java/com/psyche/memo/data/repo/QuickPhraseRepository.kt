package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.QuickPhrase
import kotlinx.serialization.json.Json

/**
 * Quick phrase storage — quick_phrase_rows (payload = QuickPhrase JSON),
 * mirroring QuickPhraseProvider: one flat list with global and
 * assistant-scoped entries, subset reorder that leaves other rows in place.
 */
class QuickPhraseRepository(db: SQLiteDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dao = PayloadEntityDao(db, "quick_phrase_rows")

    fun all(): List<QuickPhrase> =
        dao.getAll().mapNotNull { row ->
            runCatching { json.decodeFromString(QuickPhrase.serializer(), row.payload) }.getOrNull()
        }

    fun globalPhrases(): List<QuickPhrase> = all().filter { it.isGlobal }

    fun forAssistant(assistantId: String): List<QuickPhrase> =
        all().filter { !it.isGlobal && it.assistantId == assistantId }

    fun add(phrase: QuickPhrase) {
        dao.upsert(phrase.id, json.encodeToString(QuickPhrase.serializer(), phrase), dao.nextSortOrder())
    }

    fun update(phrase: QuickPhrase) {
        val existing = dao.get(phrase.id) ?: return add(phrase)
        dao.upsert(phrase.id, json.encodeToString(QuickPhrase.serializer(), phrase), existing.sortOrder)
    }

    fun delete(id: String) = dao.delete(id)

    /** Reorders the [assistantId] subset (null = global) in place. */
    fun reorder(oldIndex: Int, newIndex: Int, assistantId: String?) {
        val list = all()
        val next = reorderSubset(list, oldIndex, newIndex, assistantId) ?: return
        next.forEachIndexed { index, phrase ->
            dao.upsert(phrase.id, json.encodeToString(QuickPhrase.serializer(), phrase), index)
        }
    }

    companion object {
        /**
         * Pure port of QuickPhraseProvider._reorderInMemory: reorder the
         * matching subset and merge it back without moving non-matching rows.
         * Returns null when the move is a no-op/out of range.
         */
        fun reorderSubset(
            phrases: List<QuickPhrase>,
            oldIndex: Int,
            newIndex: Int,
            assistantId: String?,
        ): List<QuickPhrase>? {
            val isGlobal = assistantId == null
            fun matches(p: QuickPhrase) =
                if (isGlobal) p.isGlobal else (!p.isGlobal && p.assistantId == assistantId)

            val subsetIndices = phrases.indices.filter { matches(phrases[it]) }
            if (subsetIndices.isEmpty()) return null
            if (oldIndex < 0 || oldIndex >= subsetIndices.size) return null
            if (newIndex < 0 || newIndex >= subsetIndices.size) return null

            val subset = subsetIndices.map { phrases[it] }.toMutableList()
            val item = subset.removeAt(oldIndex)
            subset.add(newIndex, item)

            val merged = ArrayList<QuickPhrase>(phrases.size)
            var take = 0
            for (phrase in phrases) {
                if (matches(phrase)) merged.add(subset[take++]) else merged.add(phrase)
            }
            return merged
        }
    }
}
