package com.psyche.memo.data.assistant

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.AssistantCache
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
    fun getAll(): List<Assistant> = dao.getAll().mapNotNull { row ->
        decode(row.payload)?.also { AssistantCache.put(row.id, it) }
    }

    /**
     * 单条读取走 [AssistantCache]：组合期（抽屉当前助手、消息头归属助手、各种选择
     * sheet）调用密集，每次「查库 + 解 JSON」都落在主线程那一帧上。缓存由
     * [PayloadEntityDao] 在 `assistant_rows` 写入时整体失效。
     */
    fun get(id: String): Assistant? {
        AssistantCache.get(id)?.let { return it }
        val assistant = dao.get(id)?.let { row -> decode(row.payload) } ?: return null
        AssistantCache.put(id, assistant)
        return assistant
    }

    /** ensureDefaults guard — seed only when the table has no rows. */
    fun isEmpty(): Boolean = dao.getAll().isEmpty()

    /** Persists the seeded assistants in list order (sort_order = index). */
    fun seedAll(items: List<Assistant>) {
        items.forEachIndexed { index, a -> dao.upsert(a.id, encode(a), index) }
    }

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
     * after the source. [copyLocalFile] mirrors the Dart `_duplicateLocalFile`
     * calls (L333-342): the app layer copies a picked avatar/background file to
     * a new name and returns its path, or null to keep the original value
     * (remote URLs and emoji need no copying).
     */
    fun duplicate(
        id: String,
        copyName: String,
        copyLocalFile: ((path: String, newId: String, isAvatar: Boolean) -> String?)? = null,
    ): String? {
        val source = get(id) ?: return null
        val newId = UUID.randomUUID().toString()
        val copy = source.copy(
            id = newId,
            name = copyName,
            avatar = source.avatar?.let { copyLocalFile?.invoke(it, newId, true) ?: it },
            background = source.background?.let { copyLocalFile?.invoke(it, newId, false) ?: it },
        )
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
