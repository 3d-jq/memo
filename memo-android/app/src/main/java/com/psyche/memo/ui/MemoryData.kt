package com.psyche.memo.ui

import com.psyche.memo.data.settings.PreferenceRepository
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
 * The Dart app stores these as business-entity rows via the chat database
 * (BusinessEntityKind.memoryEntry → `memory_entries_v1`, assistantMemory →
 * `assistant_memories_v1`). The Kotlin side has no business-entity table yet,
 * so per team direction this layer persists through PreferenceRepository JSON
 * using the **original storage key names**, with payloads byte-compatible with
 * `MemoryEntry.toPayload()` / `AssistantMemory.toJson()` from the Dart source.
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
            content.trim().replace(Regex("\\s+"), " ").lowercase()

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
 * restore/hardDelete/search, backed by the JSON store. Notifies Compose via
 * a version counter — screens read `entries` inside composition.
 */
class MemoryProviderV2(private val prefs: PreferenceRepository) {

    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    val entries: List<MemoryEntry> get() = store

    private val store = mutableListOf<MemoryEntry>()

    fun initialize(loadAll: Boolean = false) {
        if (store.isNotEmpty()) return
        loadAll()
    }

    fun loadAll() {
        val raw = prefs.readJson(MEMORY_ENTRIES_KEY)
        val list = if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            runCatching {
                Json.parseToJsonElement(raw).jsonArray.map { MemoryEntry.fromPayload(it.jsonObject) }
            }.getOrDefault(emptyList())
        }
        store.clear()
        store.addAll(list.sortedByDescending { it.updatedAt })
        version++
    }

    private fun persist() {
        val arr = JsonArray(store.map { it.toPayload() })
        prefs.writeJson(MEMORY_ENTRIES_KEY, arr.toString())
        version++
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
        store.add(0, entry)
        persist()
        return entry
    }

    fun updateContent(id: String, content: String) {
        val idx = store.indexOfFirst { it.id == id }
        if (idx < 0) return
        store[idx] = store[idx].copy(content = content, updatedAt = nowMicros())
        persist()
    }

    fun updateType(id: String, type: MemoryType) {
        val idx = store.indexOfFirst { it.id == id }
        if (idx < 0) return
        store[idx] = store[idx].copy(type = type, updatedAt = nowMicros())
        persist()
    }

    fun updateScope(id: String, scope: MemoryScope, assistantId: String? = null) {
        val idx = store.indexOfFirst { it.id == id }
        if (idx < 0) return
        store[idx] = store[idx].copy(
            scope = scope,
            assistantId = if (scope == MemoryScope.assistant) assistantId else null,
            updatedAt = nowMicros(),
        )
        persist()
    }

    fun archive(id: String): Boolean = setStatus(id, MemoryStatus.archived)

    fun restore(id: String): Boolean = setStatus(id, MemoryStatus.active)

    private fun setStatus(id: String, status: MemoryStatus): Boolean {
        val idx = store.indexOfFirst { it.id == id }
        if (idx < 0) return false
        store[idx] = store[idx].copy(status = status, updatedAt = nowMicros())
        persist()
        return true
    }

    fun hardDelete(id: String): Boolean {
        val removed = store.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    /** memory_provider_v2.dart hardDeleteMany (batch delete). */
    fun hardDeleteMany(ids: List<String>): Int {
        val idSet = ids.toSet()
        val before = store.size
        store.removeAll { it.id in idSet }
        if (before != store.size) persist()
        return before - store.size
    }

    /** Entries whose assistantId no longer resolves (memory_provider_v2.dart orphanCount). */
    fun orphanCount(validAssistantIds: Set<String>): Int =
        store.count { it.scope == MemoryScope.assistant && it.assistantId != null && it.assistantId !in validAssistantIds }

    fun deleteOrphanAssistantMemories(validAssistantIds: Set<String>): Int {
        val before = store.size
        store.removeAll { it.scope == MemoryScope.assistant && it.assistantId != null && it.assistantId !in validAssistantIds }
        if (before != store.size) persist()
        return before - store.size
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

    companion object {
        /** BusinessEntityKind.memoryEntry.sourceKey (business_data.dart L31). */
        const val MEMORY_ENTRIES_KEY = "memory_entries_v1"

        private fun nowMicros(): Long = System.currentTimeMillis() * 1000
    }
}

/** assistant_memory.dart 1:1 — legacy read-only memories (§14.5 / D-29). */
data class LegacyMemory(
    val id: Long,
    val assistantId: String,
    val content: String,
)

/**
 * Legacy memory store (memory_store.dart + MemoryProvider minimal port),
 * persisted under the original business-entity key.
 */
class LegacyMemoryStore(private val prefs: PreferenceRepository) {

    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private val store = mutableListOf<LegacyMemory>()

    val memories: List<LegacyMemory> get() = store

    fun initialize() {
        if (store.isNotEmpty()) return
        loadAll()
    }

    fun loadAll() {
        val raw = prefs.readJson(ASSISTANT_MEMORIES_KEY)
        val list = if (raw.isNullOrEmpty()) {
            emptyList()
        } else {
            runCatching {
                Json.parseToJsonElement(raw).jsonArray.map { o ->
                    val obj = o.jsonObject
                    LegacyMemory(
                        id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                        assistantId = obj["assistantId"]?.jsonPrimitive?.content ?: "",
                        content = obj["content"]?.jsonPrimitive?.content ?: "",
                    )
                }
            }.getOrDefault(emptyList())
        }
        store.clear()
        store.addAll(list)
        version++
    }

    private fun persist() {
        val arr = JsonArray(store.map { m ->
            buildJsonObject {
                put("id", m.id)
                put("assistantId", m.assistantId)
                put("content", m.content)
            }
        })
        prefs.writeJson(ASSISTANT_MEMORIES_KEY, arr.toString())
        version++
    }

    fun add(assistantId: String, content: String): LegacyMemory {
        val nextId = (store.maxOfOrNull { it.id } ?: 0) + 1
        val mem = LegacyMemory(id = nextId, assistantId = assistantId, content = content)
        store.add(mem)
        persist()
        return mem
    }

    fun update(id: Long, content: String): LegacyMemory? {
        val idx = store.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = store[idx].copy(content = content)
        store[idx] = updated
        persist()
        return updated
    }

    fun delete(id: Long): Boolean {
        val removed = store.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    companion object {
        /** BusinessEntityKind.assistantMemory.sourceKey (business_data.dart L18). */
        const val ASSISTANT_MEMORIES_KEY = "assistant_memories_v1"
    }
}

/**
 * memory_trace.dart L278+ — MemoryTraceRecorder is a ChangeNotifier that is
 * **never persisted** (traces live only for the session; "nothing is ever
 * persisted"). The Kotlin port is an in-memory singleton; the background
 * memory pipeline does not run on this side yet, so the list starts empty.
 */
object MemoryTraceRecorder {

    data class TraceMutation(
        val kind: String,
        val targetId: String? = null,
        val detail: String? = null,
    )

    data class TraceStep(
        val kind: String,
        val label: String? = null,
        val detail: String? = null,
        val atMs: Long = System.currentTimeMillis(),
        val mutations: List<TraceMutation> = emptyList(),
    )

    data class Trace(
        val id: Long,
        val conversationId: String?,
        val startedAtMs: Long,
        val endedAtMs: Long? = null,
        val outcome: String? = null,
        val steps: List<TraceStep> = emptyList(),
    )

    private val _traces = mutableStateListOf<Trace>()

    val traces: List<Trace> get() = _traces

    fun record(trace: Trace) {
        _traces.add(0, trace)
    }

    fun clear() {
        _traces.clear()
    }
}
