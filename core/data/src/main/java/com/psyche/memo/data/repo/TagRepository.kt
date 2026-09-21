package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.AssistantTag
import com.psyche.memo.data.settings.PreferenceRepository
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Assistant group tags — TagProvider 1:1. Tags live in `assistant_tag_rows`
 * (payload = AssistantTag JSON), the per-assistant assignment in
 * `assistant_tag_map_v1` (assistantId → tagId) and collapse state in
 * `assistant_tag_collapsed_v1`.
 */
class TagRepository(
    db: SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dao = PayloadEntityDao(db, "assistant_tag_rows")

    fun tags(): List<AssistantTag> =
        dao.getAll().mapNotNull { row ->
            runCatching { json.decodeFromString(AssistantTag.serializer(), row.payload) }.getOrNull()
        }

    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(id, json.encodeToString(AssistantTag.serializer(), AssistantTag(id = id, name = name.trim())), dao.nextSortOrder())
        return id
    }

    fun rename(tagId: String, name: String) {
        val existing = dao.get(tagId) ?: return
        dao.upsert(tagId, json.encodeToString(AssistantTag.serializer(), AssistantTag(id = tagId, name = name.trim())), existing.sortOrder)
    }

    /** TagProvider.deleteTag — also drops the tag from assignments and collapse. */
    fun delete(tagId: String) {
        dao.delete(tagId)
        val map = assignmentMap().toMutableMap()
        if (map.values.remove(tagId)) writeAssignmentMap(map)
        val collapsed = collapsedMap().toMutableMap()
        if (collapsed.remove(tagId) != null) writeCollapsedMap(collapsed)
    }

    /** TagProvider.reorderTags — remove-then-insert (direct indices). */
    fun reorder(oldIndex: Int, newIndex: Int) {
        val list = tags().toMutableList()
        if (oldIndex !in list.indices || newIndex !in list.indices) return
        val item = list.removeAt(oldIndex)
        list.add(newIndex, item)
        list.forEachIndexed { index, tag ->
            dao.upsert(tag.id, json.encodeToString(AssistantTag.serializer(), tag), index)
        }
    }

    // ---- assignment: assistantId -> tagId ----

    fun tagOfAssistant(assistantId: String?): String? = assignmentMap()[assistantId]

    fun assignAssistant(assistantId: String, tagId: String?) {
        val map = assignmentMap().toMutableMap()
        if (tagId == null || tagId.isEmpty()) {
            map.remove(assistantId)
        } else {
            map[assistantId] = tagId
        }
        writeAssignmentMap(map)
    }

    private fun assignmentMap(): Map<String, String> {
        val raw = prefs.readJson(ASSIGN_KEY) ?: return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)?.entries?.associate { (k, v) ->
                k to ((v as? JsonPrimitive)?.content ?: "")
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun writeAssignmentMap(map: Map<String, String>) {
        prefs.writeJson(
            ASSIGN_KEY,
            JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }).toString(),
        )
    }

    // ---- collapse: tagId -> bool ----

    fun isCollapsed(tagId: String): Boolean = collapsedMap()[tagId] == true

    fun toggleCollapsed(tagId: String) {
        val map = collapsedMap().toMutableMap()
        map[tagId] = map[tagId] != true
        writeCollapsedMap(map)
    }

    private fun collapsedMap(): Map<String, Boolean> {
        val raw = prefs.readJson(COLLAPSED_KEY) ?: return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                k to ((v as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false)
            }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun writeCollapsedMap(map: Map<String, Boolean>) {
        prefs.writeJson(COLLAPSED_KEY, JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }).toString())
    }

    companion object {
        const val ASSIGN_KEY = "assistant_tag_map_v1"
        const val COLLAPSED_KEY = "assistant_tag_collapsed_v1"
    }
}
