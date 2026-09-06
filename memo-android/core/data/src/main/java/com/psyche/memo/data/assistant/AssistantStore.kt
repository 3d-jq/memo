package com.psyche.memo.data.assistant

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.Assistant
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * assistant_rows CRUD — the Android counterpart of Flutter's
 * AssistantProvider (lib/core/providers/assistant_provider.dart).
 *
 * Rows live in the drift-v3 payload table `assistant_rows` (PK `id`,
 * verified against memo_schema_v3.sql); the payload JSON uses the exact
 * keys of assistant.dart toJson, so a parse -> edit -> write round trip
 * is lossless (every toJson key is covered by the Android DTO).
 */
class AssistantStore(private val db: SQLiteDatabase) {

    private val dao = PayloadEntityDao(db, "assistant_rows", primaryKey = "id")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun decode(payload: String): Assistant? =
        runCatching { Assistant.fromJsonString(json, payload) }.getOrNull()

    private fun encode(a: Assistant): String = json.encodeToString(Assistant.serializer(), a)

    /** assistants getter — rows already come back sorted by sort_order. */
    fun getAll(): List<Assistant> = dao.getAll().mapNotNull { row -> decode(row.payload) }

    fun get(id: String): Assistant? = dao.get(id)?.let { row -> decode(row.payload) }

    /** addAssistant L306-322 — appended with a fresh uuid. */
    fun add(name: String): String {
        val id = UUID.randomUUID().toString()
        val assistant = Assistant(
            id = id,
            name = name,
            temperature = null,
            topP = null,
            limitContextMessages = false,
        )
        dao.upsert(id, encode(assistant), dao.nextSortOrder())
        return id
    }

    /**
     * duplicateAssistant L324-381 — fresh id + [copyName], inserted right
     * after the source. Local avatar/background file copying is not
     * applicable on Android (avatars are emoji or remote URLs in
     * practice); list references are copied verbatim like the Dart copyWith.
     */
    fun duplicate(id: String, copyName: String): String? {
        val source = get(id) ?: return null
        val newId = UUID.randomUUID().toString()
        val copy = source.copy(id = newId, name = copyName)
        dao.upsert(newId, encode(copy), 0)
        // Position the copy right after its source (L377 insert(idx + 1)).
        setOrder(insertAfter(dao.getAll().map { it.id }, id, newId))
        return newId
    }

    /** updateAssistant — persists the edited model in place. */
    fun update(a: Assistant) {
        val existing = dao.get(a.id)
        dao.upsert(a.id, encode(a), existing?.sortOrder ?: dao.nextSortOrder())
    }

    /** deleteAssistant L483-506 — guard + conversations cascade. */
    fun delete(id: String): Boolean {
        if (!canDeleteAssistant(getAll().size)) return false
        // chatService.deleteConversationsForAssistant (L489).
        db.execSQL(
            "DELETE FROM message_rows WHERE conversation_id IN " +
                "(SELECT id FROM conversation_rows WHERE assistant_id = ?)",
            arrayOf(id),
        )
        db.execSQL("DELETE FROM conversation_rows WHERE assistant_id = ?", arrayOf(id))
        dao.delete(id)
        return true
    }

    /** reorderAssistants — persists the full id order (sort_order = index). */
    fun setOrder(ids: List<String>) {
        ids.forEachIndexed { index, id ->
            dao.get(id)?.let { row -> dao.upsert(id, row.payload, index) }
        }
    }
}
