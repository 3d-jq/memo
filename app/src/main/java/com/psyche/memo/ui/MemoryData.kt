package com.psyche.memo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Minimal memory data layer backing the memory settings family
 * (memory_settings/entries/trace/legacy pages).
 *
 * The Dart app stores these as business-entity rows `memory_entry_rows` /
 * `assistant_memory_rows`; this layer writes those tables through
 * [MemoryEntryRowDao] / [AssistantMemoryRowDao], keeping `MemoryEntry.toPayload()`
 * byte-compatible with the Dart source (the payload is authoritative on read,
 * the typed columns are the projection used for queries and backups).
 */

enum class MemoryScope(val wire: String) { global("global"), assistant("assistant");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.wire == s } ?: global
    }
}

enum class MemoryType(val wire: String) { identity("identity"), workflow("workflow"), voice("voice"), instruction("instruction");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.wire == s } ?: identity
    }
}

enum class MemoryStatus(val wire: String) { active("active"), archived("archived");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.wire == s } ?: active
    }
}

enum class MemorySource(val wire: String) { manual("manual"), tool("tool"), extracted("extracted"), distilled("distilled");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.wire == s } ?: manual
    }
}

/** memory_entry.dart L19-50 — payload keys and microsecond timestamps match. */
data class MemoryEntry(
    val id: String,
    val scope: MemoryScope,
    val assistantId: String? = null,
    val type: MemoryType,
    val status: MemoryStatus = MemoryStatus.active,
    val content: String,
    val source: MemorySource = MemorySource.manual,
    val relatedIds: List<String> = emptyList(),
    val migrationIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toPayload(): JsonObject = buildJsonObject {
        put("id", id)
        put("scope", scope.wire)
        if (assistantId != null) put("assistantId", assistantId)
        put("type", type.wire)
        put("status", status.wire)
        put("content", content)
        put("source", source.wire)
        put("relatedIds", JsonArray(relatedIds.map { JsonPrimitive(it) }))
        if (migrationIds.isNotEmpty()) put("migrationIds", JsonArray(migrationIds.map { JsonPrimitive(it) }))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromPayload(obj: JsonObject): MemoryEntry {
            fun str(key: String): String? = obj[key].strOrNull()
            fun long(key: String): Long = obj[key]?.jsonPrimitive?.longOrNull ?: 0L
            fun strList(key: String): List<String> =
                (obj[key] as? JsonArray)?.mapNotNull { it.strOrNull() } ?: emptyList()
            return MemoryEntry(
                id = str("id") ?: "",
                scope = MemoryScope.from(str("scope")),
                assistantId = str("assistantId"),
                type = MemoryType.from(str("type")),
                status = MemoryStatus.from(str("status") ?: "active"),
                content = str("content") ?: "",
                source = MemorySource.from(str("source") ?: "manual"),
                relatedIds = strList("relatedIds"),
                migrationIds = strList("migrationIds"),
                createdAt = long("createdAt"),
                updatedAt = long("updatedAt"),
            )
        }

        /** memory_entry.dart L107-115 — trim, collapse whitespace, lowercase. */
        fun normalizeContent(content: String): String =
            com.psyche.memo.data.db.normalizeMemoryContent(content)

        /** memory_entry.dart L118-126 — `mem_` + 8 hex chars, Random.secure. */
        fun newId(): String {
            val alphabet = "0123456789abcdef"
            val rng = java.security.SecureRandom()
            val buf = StringBuilder("mem_")
            repeat(8) { buf.append(alphabet[rng.nextInt(alphabet.length)]) }
            return buf.toString()
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.strOrNull(): String? =
    (this as? JsonPrimitive)?.content

/**
 * memory_provider_v2.dart 1:1 surface (minimal): load/create/update/archive/
 * restore/hardDelete/search, backed by `memory_entry_rows`. Notifies Compose
 * via a version counter — screens read `entries` inside composition.
 *
 * Every mutation re-reads the table before writing (the Dart `JsonBlobStore`
 * does the same read-modify-write on each call). More than one provider
 * instance is alive at a time — the container's, plus one per open screen — so
 * persisting a cached snapshot would silently drop whatever another instance
 * wrote in the meantime.
 */
class MemoryProviderV2(db: android.database.sqlite.SQLiteDatabase) {

    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    val entries: List<MemoryEntry> get() = store

    private val store = mutableListOf<MemoryEntry>()

    private val dao = com.psyche.memo.data.db.MemoryEntryRowDao(db)

    private var loaded = false

    /**
     * Loads once per instance. Readers that do not run inside a screen — the
     * system-prompt memory block, the memory tool — must call this first,
     * otherwise they see an empty store in a process where no memory screen was
     * opened yet.
     */
    fun ensureLoaded() {
        if (!loaded) loadAll()
    }

    fun initialize() = ensureLoaded()

    fun loadAll() {
        refresh(readEntries())
        loaded = true
    }

    private fun readEntries(): MutableList<MemoryEntry> =
        dao.getAll().mapNotNull { row ->
            runCatching { MemoryEntry.fromPayload(Json.parseToJsonElement(row.payload).jsonObject) }
                .getOrNull()
        }.toMutableList()

    private fun refresh(entries: List<MemoryEntry>) {
        store.clear()
        store.addAll(entries.sortedWith(compareByDescending<MemoryEntry> { it.updatedAt }.thenBy { it.id }))
        version++
    }

    /** Read-modify-write the whole table for one mutation. */
    private fun mutate(block: (MutableList<MemoryEntry>) -> Unit) {
        val all = readEntries()
        block(all)
        val now = System.currentTimeMillis()
        dao.replaceAll(
            all.mapIndexed { index, entry ->
                com.psyche.memo.data.db.MemoryEntryRowDao.Row.fromPayload(
                    id = entry.id,
                    payload = entry.toPayload().toString(),
                    sortOrder = index,
                    updatedAt = now,
                )
            },
        )
        refresh(all)
    }

    /** memory_provider_v2.dart L107-119. */
    fun create(scope: MemoryScope, assistantId: String?, type: MemoryType, content: String, source: MemorySource): MemoryEntry {
        val now = System.currentTimeMillis() * 1000 // microseconds, matching Dart
        val entry = MemoryEntry(
            id = MemoryEntry.newId(),
            scope = scope,
            assistantId = if (scope == MemoryScope.assistant) assistantId else null,
            type = type,
            content = content,
            source = source,
            createdAt = now,
            updatedAt = now,
        )
        mutate { it.add(0, entry) }
        return entry
    }

    fun updateContent(id: String, content: String): MemoryEntry? {
        var updated: MemoryEntry? = null
        mutate { all ->
            all.updateById(id) {
                updated = it.copy(content = content, updatedAt = nowMicros())
                updated!!
            }
        }
        return updated
    }

    fun updateType(id: String, type: MemoryType) {
        mutate { all -> all.updateById(id) { it.copy(type = type, updatedAt = nowMicros()) } }
    }

    fun updateScope(id: String, scope: MemoryScope, assistantId: String? = null) {
        mutate { all ->
            all.updateById(id) {
                it.copy(
                    scope = scope,
                    assistantId = if (scope == MemoryScope.assistant) assistantId else null,
                    updatedAt = nowMicros(),
                )
            }
        }
    }

    fun archive(id: String): Boolean = setStatus(id, MemoryStatus.archived)

    fun restore(id: String): Boolean = setStatus(id, MemoryStatus.active)

    private fun setStatus(id: String, status: MemoryStatus): Boolean {
        var changed = false
        mutate { all ->
            all.updateById(id) {
                changed = true
                it.copy(status = status, updatedAt = nowMicros())
            }
        }
        return changed
    }

    fun hardDelete(id: String): Boolean {
        var removed = false
        mutate { all -> removed = all.removeAll { it.id == id } }
        return removed
    }

    /** memory_provider_v2.dart hardDeleteMany (batch delete). */
    fun hardDeleteMany(ids: List<String>): Int {
        val idSet = ids.toSet()
        var removed = 0
        mutate { all ->
            val before = all.size
            all.removeAll { it.id in idSet }
            removed = before - all.size
        }
        return removed
    }

    /**
     * Idempotent both-ways `relatedIds` link (memory_provider_v2.dart
     * linkBidirectional). Deliberately leaves the entry's own `updatedAt`
     * alone: `relatedIds` never reaches the injected block, so bumping it would
     * change the snapshot hash and force a pointless re-injection.
     */
    fun linkBidirectional(a: String, b: String) {
        if (a == b) return
        mutate { all ->
            all.updateById(a) { if (b in it.relatedIds) it else it.copy(relatedIds = it.relatedIds + b) }
            all.updateById(b) { if (a in it.relatedIds) it else it.copy(relatedIds = it.relatedIds + a) }
        }
    }

    /** Entries whose assistantId no longer resolves (memory_provider_v2.dart orphanCount). */
    fun orphanCount(validAssistantIds: Set<String>): Int =        store.count { it.scope == MemoryScope.assistant && it.assistantId != null && it.assistantId !in validAssistantIds }

    fun deleteOrphanAssistantMemories(validAssistantIds: Set<String>): Int {
        var removed = 0
        mutate { all ->
            removed = all.count {
                it.scope == MemoryScope.assistant && it.assistantId != null && it.assistantId !in validAssistantIds
            }
            all.removeAll {
                it.scope == MemoryScope.assistant && it.assistantId != null && it.assistantId !in validAssistantIds
            }
        }
        return removed
    }

    /** memory_provider_v2.dart L120-135 — token search across all entries. */
    fun search(tokens: List<String>, includeArchived: Boolean = false, type: MemoryType? = null): List<MemoryEntry> {
        if (tokens.isEmpty()) return emptyList()
        return store.filter { e ->
            (includeArchived || e.status == MemoryStatus.active) &&
                (type == null || e.type == type) &&
                tokens.all { MemoryEntry.normalizeContent(e.content).contains(MemoryEntry.normalizeContent(it)) }
        }
    }

    /** Visible for [assistantId] = global ∪ assistant (memory_provider_v2.dart L40-49). */
    fun visibleFor(assistantId: String?, includeArchived: Boolean = false): List<MemoryEntry> =
        store.filter {
            (includeArchived || it.status == MemoryStatus.active) &&
                (it.scope == MemoryScope.global || (it.scope == MemoryScope.assistant && it.assistantId == assistantId))
        }

    private companion object {
        private fun nowMicros(): Long = System.currentTimeMillis() * 1000

        /** Applies [transform] in place; returns false without touching anything
         * when [id] is absent. */
        private inline fun MutableList<MemoryEntry>.updateById(
            id: String,
            transform: (MemoryEntry) -> MemoryEntry,
        ): Boolean {
            val index = indexOfFirst { it.id == id }
            if (index < 0) return false
            this[index] = transform(this[index])
            return true
        }
    }
}
