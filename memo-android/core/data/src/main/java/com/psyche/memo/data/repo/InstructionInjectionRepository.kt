package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.InstructionInjection
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Instruction injection storage — instruction_injection_rows plus the two
 * preference maps the upstream provider keeps:
 * `instruction_injections_active_ids_by_assistant_v1` (assistant key → ids)
 * and `instruction_injection_group_collapsed_v1` (group → collapsed).
 */
class InstructionInjectionRepository(
    db: SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dao = PayloadEntityDao(db, "instruction_injection_rows")

    fun items(): List<InstructionInjection> =
        dao.getAll().mapNotNull { row ->
            runCatching { json.decodeFromString(InstructionInjection.serializer(), row.payload) }.getOrNull()
        }

    fun add(item: InstructionInjection) {
        dao.upsert(item.id, json.encodeToString(InstructionInjection.serializer(), item), dao.nextSortOrder())
    }

    fun update(item: InstructionInjection) {
        val existing = dao.get(item.id) ?: return add(item)
        dao.upsert(item.id, json.encodeToString(InstructionInjection.serializer(), item), existing.sortOrder)
    }

    fun delete(id: String) = dao.delete(id)

    /** reorderWithinGroup — only the matching group's rows move. */
    fun reorderWithinGroup(group: String, oldIndex: Int, newIndex: Int) {
        val list = items()
        val next = reorderGroupSubset(list, group, oldIndex, newIndex) ?: return
        next.forEachIndexed { index, item ->
            dao.upsert(item.id, json.encodeToString(InstructionInjection.serializer(), item), index)
        }
    }

    // ---- active ids per assistant ----

    /** null assistantId → the global key (''). */
    fun activeIds(assistantId: String?): List<String> {
        val all = activeMap()
        val key = assistantKey(assistantId)
        return all[key] ?: all[assistantKey(null)] ?: emptyList()
    }

    fun isActive(id: String, assistantId: String?): Boolean = id in activeIds(assistantId)

    fun toggleActive(id: String, assistantId: String?) {
        val key = assistantKey(assistantId)
        val all = activeMap().toMutableMap()
        val current = (all[key] ?: emptyList()).toMutableList()
        if (!current.remove(id)) current.add(id)
        all[key] = current
        prefs.writeJson(
            ACTIVE_KEY,
            JsonObject(all.mapValues { (_, ids) -> JsonArray(ids.map { JsonPrimitive(it) }) }).toString(),
        )
    }

    private fun activeMap(): Map<String, List<String>> {
        val raw = prefs.readJson(ACTIVE_KEY) ?: return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)?.entries?.associate { (key, value) ->
                key to ((value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList())
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    // ---- collapsed groups ----

    fun isCollapsed(group: String): Boolean {
        val raw = prefs.readJson(COLLAPSED_KEY) ?: return false
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)
                ?.get(group.trim())?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: false
        }.getOrDefault(false)
    }

    fun toggleCollapsed(group: String) {
        val raw = prefs.readJson(COLLAPSED_KEY) ?: "{}"
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val next = obj.toMutableMap()
        val key = group.trim()
        val current = (next[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        next[key] = JsonPrimitive(!current)
        prefs.writeJson(COLLAPSED_KEY, JsonObject(next).toString())
    }

    companion object {
        const val ACTIVE_KEY = "instruction_injections_active_ids_by_assistant_v1"
        const val COLLAPSED_KEY = "instruction_injection_group_collapsed_v1"

        /** InstructionInjectionStore.assistantKey — null/empty → ''. */
        fun assistantKey(assistantId: String?): String = assistantId?.trim().orEmpty()

        /** Pure port of InstructionInjectionProvider.reorderWithinGroup. */
        fun reorderGroupSubset(
            items: List<InstructionInjection>,
            group: String,
            oldIndex: Int,
            newIndex: Int,
        ): List<InstructionInjection>? {
            val target = group.trim()
            val indices = items.indices.filter { items[it].group.trim() == target }
            if (indices.isEmpty()) return null
            if (oldIndex < 0 || oldIndex >= indices.size) return null
            if (newIndex < 0 || newIndex >= indices.size) return null

            val subset = indices.map { items[it] }.toMutableList()
            val moved = subset.removeAt(oldIndex)
            subset.add(newIndex, moved)

            val merged = ArrayList<InstructionInjection>(items.size)
            var take = 0
            for (item in items) {
                if (item.group.trim() == target) merged.add(subset[take++]) else merged.add(item)
            }
            return merged
        }
    }
}
