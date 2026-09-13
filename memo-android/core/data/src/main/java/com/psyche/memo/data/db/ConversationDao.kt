package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.model.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Hand-written DAO for conversation_rows (drift v3 layout).
 * Column names/types/order follow memo_schema_v3.sql exactly so a memo.db
 * made by the Flutter app opens in place.
 */
class ConversationDao(private val db: SQLiteDatabase) {

    private val json: Json = Json { ignoreUnknownKeys = true }

    fun insert(conversation: Conversation) {
        db.insertOrThrow("conversation_rows", null, conversation.toRow())
    }

    fun update(conversation: Conversation) {
        db.update("conversation_rows", conversation.toRow(), "id = ?", arrayOf(conversation.id))
    }

    fun delete(id: String) {
        db.delete("conversation_rows", "id = ?", arrayOf(id))
    }

    fun get(id: String): Conversation? {
        db.query(
            "conversation_rows", null, "id = ?", arrayOf(id), null, null, null,
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toConversation()
        }
        return null
    }

    /**
     * All conversations newest-first (updated_at DESC, id ASC — matches
     * idx_conversations_updated_at).
     */
    fun getAll(): List<Conversation> {
        db.query(
            "conversation_rows", null, null, null, null, null,
            "updated_at DESC, id ASC",
        ).use { cursor ->
            val out = ArrayList<Conversation>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.toConversation())
            return out
        }
    }

    fun updateTitle(id: String, title: String, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE conversation_rows SET title = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(title, now, id),
        )
    }

    /**
     * Persist a generated conversation summary plus the message count at which
     * it was produced (home_view_model.dart updateConversationSummary). The
     * count drives the next-summary threshold.
     */
    fun updateSummary(
        id: String,
        summary: String,
        lastSummarizedMessageCount: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        db.execSQL(
            "UPDATE conversation_rows SET summary = ?, last_summarized_message_count = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(summary, lastSummarizedMessageCount, now, id),
        )
    }

    /**
     * 有总结的会话（chat_service.getConversationsWithSummaryForAssistant）——
     * 助手记忆 tab 的「管理总结」列表按 updated_at DESC 列出。
     */
    fun withSummaryForAssistant(assistantId: String): List<Conversation> {
        db.query(
            "conversation_rows", null,
            "assistant_id = ? AND summary IS NOT NULL AND TRIM(summary) != ''",
            arrayOf(assistantId), null, null, "updated_at DESC, id ASC",
        ).use { cursor ->
            val out = ArrayList<Conversation>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.toConversation())
            return out
        }
    }

    /** 清掉一条会话总结（chat_service.clearConversationSummary）。 */
    fun clearSummary(id: String, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE conversation_rows SET summary = NULL, last_summarized_message_count = 0, " +
                "updated_at = ? WHERE id = ?",
            arrayOf<Any>(now, id),
        )
    }

    fun updatePinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE conversation_rows SET is_pinned = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(if (pinned) 1 else 0, now, id),
        )
    }

    /**
     * 会话级模型选择持久化（model_select_sheet.dart:283-303
     * `controller.setConversationModel`）：选中的 (provider, model) 写到
     * conversation_rows，null 表示"跟随默认"。
     */
    fun setChatModel(
        id: String,
        providerId: String?,
        modelId: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        db.execSQL(
            "UPDATE conversation_rows SET chat_model_provider = ?, chat_model_id = ?, " +
                "updated_at = ? WHERE id = ?",
            arrayOf<Any?>(providerId, modelId, now, id),
        )
    }

    fun setAssistant(id: String, assistantId: String?, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE conversation_rows SET assistant_id = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(assistantId, now, id),
        )
    }

    /** chat_service.toggleTruncateAtTail —— -1 = send everything. */
    fun setTruncateIndex(id: String, value: Int, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("truncate_index", value)
            put("updated_at", now)
        }
        db.update("conversation_rows", values, "id = ?", arrayOf(id))
    }

    /** memory pipeline watermark — the highest message order already extracted. */
    fun setLastMemoryExtractedOrder(id: String, order: Int, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("last_memory_extracted_order", order)
            put("updated_at", now)
        }
        db.update("conversation_rows", values, "id = ?", arrayOf(id))
    }

    fun touch(id: String, now: Long = System.currentTimeMillis()) {
        db.execSQL("UPDATE conversation_rows SET updated_at = ? WHERE id = ?", arrayOf<Any>(now, id))
    }

    /** Upsert a member updated_at bump without touching other columns. */
    fun updateJsonColumn(id: String, column: String, jsonText: String, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE conversation_rows SET \"$column\" = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(jsonText, now, id),
        )
    }

    private fun Conversation.toRow(): ContentValues = ContentValues().apply {
        put("id", id)
        put("title", title)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("is_pinned", if (isPinned) 1 else 0)
        put("assistant_id", assistantId)
        put("truncate_index", truncateIndex)
        put(
            "version_selections_json",
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(versionSelections.mapValues { JsonPrimitive(it.value) }),
            ),
        )
        put("summary", summary)
        put("last_summarized_message_count", lastSummarizedMessageCount)
        put(
            "chat_suggestions_json",
            json.encodeToString(JsonArray.serializer(), JsonArray(chatSuggestions.map { JsonPrimitive(it) })),
        )
        put("injected_memory_hash", injectedMemoryHash)
        put("last_memory_extracted_order", lastMemoryExtractedOrder)
        put("chat_model_provider", chatModelProvider)
        put("chat_model_id", chatModelId)
        put("extras_json", extras?.toString() ?: "{}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun android.database.Cursor.toConversation(): Conversation {
        val id = getString(getColumnIndexOrThrow("id"))
        val versionSelections = getString(getColumnIndexOrThrow("version_selections_json"))
            .takeIf { it != "{}" }
            ?.let { text ->
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    obj.entries.associate { it.key to ((it.value as JsonPrimitive).content.toIntOrNull() ?: 0) }
                }.getOrDefault(emptyMap())
            } ?: emptyMap()
        val chatSuggestions = getString(getColumnIndexOrThrow("chat_suggestions_json"))
            .takeIf { it != "[]" }
            ?.let { text ->
                runCatching {
                    val arr = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray
                    arr.map { (it as JsonPrimitive).content }
                }.getOrDefault(emptyList())
            } ?: emptyList()
        val extras = getString(getColumnIndexOrThrow("extras_json"))
            .takeIf { it != "{}" }
            ?.let { text -> runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() }
        return Conversation(
            id = id,
            title = getString(getColumnIndexOrThrow("title")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            isPinned = getInt(getColumnIndexOrThrow("is_pinned")) == 1,
            assistantId = getString(getColumnIndexOrThrow("assistant_id")),
            truncateIndex = getInt(getColumnIndexOrThrow("truncate_index")),
            versionSelections = versionSelections,
            summary = getString(getColumnIndexOrThrow("summary")),
            lastSummarizedMessageCount = getInt(getColumnIndexOrThrow("last_summarized_message_count")),
            chatSuggestions = chatSuggestions,
            injectedMemoryHash = getString(getColumnIndexOrThrow("injected_memory_hash")),
            lastMemoryExtractedOrder = getInt(getColumnIndexOrThrow("last_memory_extracted_order")),
            chatModelProvider = getString(getColumnIndexOrThrow("chat_model_provider")),
            chatModelId = getString(getColumnIndexOrThrow("chat_model_id")),
            extras = extras,
        )
    }
}
