package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.WorldBook
import com.psyche.memo.data.model.WorldBookEntry
import com.psyche.memo.data.settings.PreferenceRepository
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * World book storage — world_book_rows (payload = WorldBook JSON, entries
 * inline) plus the two preference maps WorldBookStore keeps:
 * `world_books_active_ids_by_assistant_v1` (assistant key → ids, missing key
 * falls back to `__global__`) and `world_books_collapsed_v1` (book id → bool).
 */
class WorldBookRepository(
    db: SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val dao = PayloadEntityDao(db, "world_book_rows")

    fun books(): List<WorldBook> =
        dao.getAll().mapNotNull { row ->
            runCatching { json.decodeFromString(WorldBook.serializer(), row.payload) }.getOrNull()
        }

    fun add(book: WorldBook) {
        dao.upsert(book.id, json.encodeToString(WorldBook.serializer(), book), dao.nextSortOrder())
    }

    /** WorldBookProvider.updateBook — a disabled book also drops out of every active set. */
    fun update(book: WorldBook) {
        if (!book.enabled) removeFromActiveMaps(book.id)
        val existing = dao.get(book.id) ?: return add(book)
        dao.upsert(book.id, json.encodeToString(WorldBook.serializer(), book), existing.sortOrder)
    }

    fun delete(id: String) {
        dao.delete(id)
        removeFromActiveMaps(id)
        val collapsed = collapsedMap().toMutableMap()
        if (collapsed.remove(id) != null) writeCollapsedMap(collapsed)
    }

    fun clear() {
        dao.getAll().forEach { dao.delete(it.id) }
        prefs.remove(ACTIVE_KEY)
        prefs.remove(COLLAPSED_KEY)
    }

    /** WorldBookStore.reorder — remove-then-insert (no index adjustment). */
    fun reorder(oldIndex: Int, newIndex: Int) {
        val list = books().toMutableList()
        if (oldIndex !in list.indices || newIndex !in list.indices) return
        val item = list.removeAt(oldIndex)
        list.add(newIndex, item)
        list.forEachIndexed { index, book ->
            dao.upsert(book.id, json.encodeToString(WorldBook.serializer(), book), index)
        }
    }

    fun reorderEntries(bookId: String, oldIndex: Int, newIndex: Int) {
        val list = books()
        val book = list.firstOrNull { it.id == bookId } ?: return
        val entries = book.entries.toMutableList()
        if (oldIndex !in entries.indices || newIndex !in entries.indices) return
        val item = entries.removeAt(oldIndex)
        entries.add(newIndex, item)
        update(book.copy(entries = entries))
    }

    // ---- active ids per assistant ----

    /** null/blank assistantId → `__global__`; a missing key falls back to it. */
    fun activeIds(assistantId: String?): List<String> {
        val all = activeMap()
        return all[assistantKey(assistantId)] ?: all[assistantKey(null)] ?: emptyList()
    }

    fun isActive(id: String, assistantId: String?): Boolean = id in activeIds(assistantId)

    fun toggleActive(id: String, assistantId: String?) {
        val key = assistantKey(assistantId)
        val all = activeMap().toMutableMap()
        val current = (all[key] ?: emptyList()).toMutableList()
        if (!current.remove(id)) {
            val book = books().firstOrNull { it.id == id } ?: return
            if (!book.enabled) return
            current.add(id)
        }
        all[key] = current
        writeActiveMap(all)
    }

    private fun removeFromActiveMaps(bookId: String) {
        val all = activeMap()
        var changed = false
        val next = all.mapValues { (_, ids) ->
            if (bookId in ids) {
                changed = true
                ids.filterNot { it == bookId }
            } else {
                ids
            }
        }
        if (changed) writeActiveMap(next)
    }

    private fun activeMap(): Map<String, List<String>> {
        val raw = prefs.readJson(ACTIVE_KEY) ?: return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)?.entries?.associate { (key, value) ->
                key to ((value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList())
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun writeActiveMap(map: Map<String, List<String>>) {
        prefs.writeJson(
            ACTIVE_KEY,
            JsonObject(map.mapValues { (_, ids) -> JsonArray(ids.map { JsonPrimitive(it) }) }).toString(),
        )
    }

    // ---- collapsed books ----

    fun isCollapsed(id: String): Boolean = collapsedMap()[id] == true

    fun toggleCollapsed(id: String) {
        val key = id.trim()
        if (key.isEmpty()) return
        val next = collapsedMap().toMutableMap()
        next[key] = next[key] != true
        writeCollapsedMap(next)
    }

    /** WorldBookProvider.loadAll — drop entries whose book no longer exists. */
    fun pruneCollapsed(knownIds: Set<String>) {
        val map = collapsedMap()
        val next = map.filterKeys { it in knownIds }
        if (next.size != map.size) writeCollapsedMap(next)
    }

    private fun collapsedMap(): Map<String, Boolean> {
        val raw = prefs.readJson(COLLAPSED_KEY) ?: return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)?.entries?.mapNotNull { (key, value) ->
                val id = key.trim()
                if (id.isEmpty()) null
                else id to ((value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false)
            }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun writeCollapsedMap(map: Map<String, Boolean>) {
        prefs.writeJson(COLLAPSED_KEY, JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }).toString())
    }

    companion object {
        const val ACTIVE_KEY = "world_books_active_ids_by_assistant_v1"
        const val COLLAPSED_KEY = "world_books_collapsed_v1"
        const val DEFAULT_ASSISTANT_KEY = "__global__"

        /** WorldBookStore.assistantKey — null/blank → `__global__`. */
        fun assistantKey(assistantId: String?): String =
            assistantId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ASSISTANT_KEY

        /**
         * world_book_page.dart _parseWorldBookImport: `{"data": {...}}` (the
         * RikkaHub export wrapper) or a flat book object carrying `entries`.
         */
        fun parseImportedBook(json: Json, text: String): WorldBook? {
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
            val obj = element as? JsonObject ?: return null
            val data = obj["data"]
            if (data is JsonObject) {
                return runCatching { json.decodeFromString(WorldBook.serializer(), data.toString()) }.getOrNull()
            }
            if (!obj.containsKey("entries")) return null
            return runCatching { json.decodeFromString(WorldBook.serializer(), obj.toString()) }.getOrNull()
        }

        /**
         * _normalizeImportedBook — blank or colliding book/entry ids get fresh
         * ones so an import never overwrites an existing row.
         */
        fun normalizeImportedBook(
            book: WorldBook,
            existingBookIds: Set<String>,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): WorldBook {
            var bookId = book.id.trim()
            if (bookId.isEmpty() || bookId in existingBookIds) bookId = newId()

            val seen = mutableSetOf<String>()
            val entries = book.entries.map { entry ->
                var entryId = entry.id.trim()
                if (entryId.isEmpty() || !seen.add(entryId)) entryId = newId()
                entry.copy(id = entryId)
            }
            return book.copy(id = bookId, entries = entries)
        }

        /** _toRikkaHubExportJson — the shared lorebook interchange wrapper. */
        fun toExportJson(book: WorldBook): String =
            EXPORT_JSON.encodeToString(WorldBookExport.serializer(), WorldBookExport(data = book))

        /** _safeFileName — strip path-hostile characters, cap at 80 chars. */
        fun safeFileName(name: String): String {
            val cleaned = name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isEmpty()) return "lorebook"
            return if (cleaned.length > 80) cleaned.substring(0, 80) else cleaned
        }

        private val EXPORT_JSON = Json { encodeDefaults = true; prettyPrint = true }

        @Serializable
        private data class WorldBookExport(
            val version: Int = 1,
            val type: String = "lorebook",
            val data: WorldBook,
        )
    }
}
