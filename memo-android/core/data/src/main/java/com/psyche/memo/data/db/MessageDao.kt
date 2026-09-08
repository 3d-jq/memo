package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.MessagePart
import kotlinx.serialization.json.Json

/**
 * Hand-written DAO for message_rows + message_part_rows (drift v3 layout).
 *
 * UNIQUE(conversation_id, message_order) and UNIQUE(conversation_id, group_id,
 * version) are enforced by the schema; callers must use insertMessage that maps
 * conflicts to a regenerate path.
 */
class MessageDao(private val db: SQLiteDatabase) {

    private val json: Json = Json { ignoreUnknownKeys = true }

    /** Single message row (parts loaded). */
    fun get(id: String): ChatMessage? {
        db.query("message_rows", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.hydrate()
        }
        return null
    }

    fun getByIds(ids: List<String>): List<ChatMessage> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        db.query(
            "message_rows", null, "id IN ($placeholders)", ids.toTypedArray(), null, null, null,
        ).use { cursor ->
            val byId = LinkedHashMap<String, ChatMessage>()
            while (cursor.moveToNext()) {
                val m = cursor.hydrate()
                byId[m.id] = m
            }
            return ids.mapNotNull { byId[it] }
        }
    }

    /** All revision ids of a conversation in wall order (message_order ASC). */
    fun getMessageIds(conversationId: String): List<String> {
        db.query(
            "message_rows", arrayOf("id", "message_order"),
            "conversation_id = ?", arrayOf(conversationId), null, null, "message_order ASC, id ASC",
        ).use { cursor ->
            val out = ArrayList<String>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.getString(0))
            return out
        }
    }

    /**
     * Latest N messages of a conversation (wall order), most recent allowed.
     * Mirrors the tail page of loadTimelinePage (limit 40).
     */
    fun getTail(conversationId: String, limit: Int = 40): List<ChatMessage> {
        db.query(
            "message_rows", null, "conversation_id = ?", arrayOf(conversationId),
            null, null, "message_order DESC, id DESC", limit.toString(),
        ).use { cursor ->
            val out = ArrayList<ChatMessage>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.hydrate())
            return out.reversed()
        }
    }

    /** All messages of a conversation in wall order (parts loaded). */
    fun getAllForConversation(conversationId: String): List<ChatMessage> {
        db.query(
            "message_rows", null, "conversation_id = ?", arrayOf(conversationId),
            null, null, "message_order ASC, id ASC",
        ).use { cursor ->
            val out = ArrayList<ChatMessage>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.hydrate())
            return out
        }
    }

    /** Messages strictly before [beforeId]'s message_order (older). */
    fun getBefore(conversationId: String, beforeId: String, limit: Int = 40): List<ChatMessage> {
        val anchor = getOrder(conversationId, beforeId) ?: return emptyList()
        db.rawQuery(
            """
            SELECT * FROM message_rows
            WHERE conversation_id = ? AND message_order < ?
            ORDER BY message_order DESC, id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(conversationId, anchor.toString(), limit.toString()),
        ).use { cursor ->
            val out = ArrayList<ChatMessage>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.hydrate())
            return out.reversed()
        }
    }

    fun count(conversationId: String): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM message_rows WHERE conversation_id = ?", arrayOf(conversationId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Row of a global search hit. */
    data class GlobalHit(
        val conversationId: String,
        val conversationTitle: String,
        val firstMatchedMessageId: String,
        val snippet: String,
    )

    /**
     * Cross-conversation search over message text (side_drawer global
     * search): groups by conversation, keeps the first matched message.
     */
    fun searchGlobal(query: String, limit: Int = 40): List<GlobalHit> {
        val needle = "%" + query.trim().replace("'", "''") + "%"
        val lower = query.trim().lowercase()
        val hits = mutableListOf<GlobalHit>()
        db.rawQuery(
            "SELECT c.id, c.title, m.id, p.payload FROM conversation_rows c " +
            "JOIN message_rows m ON m.conversation_id = c.id " +
            "JOIN message_part_rows p ON p.revision_id = m.id AND p.kind = 'text' AND p.payload LIKE ? " +
            "WHERE c.title LIKE ? OR p.payload LIKE ? " +
            "GROUP BY c.id ORDER BY c.updated_at DESC LIMIT ?",
            arrayOf<String>(needle, needle, needle, limit.toString()),
).use { cursor ->
            while (cursor.moveToNext()) {
                val convId = cursor.getString(0)
                val title = cursor.getString(1) ?: ""
                val msgId = cursor.getString(2) ?: ""
                val rawPayload = cursor.getString(3) ?: ""
                val sample = rawPayload.replace("\\n", " ")
                val idx = sample.lowercase().indexOf(lower)
                val start = maxOf(0, idx - 20)
                val end = minOf(sample.length, idx + query.length + 40)
                val snippet = if (idx >= 0) sample.substring(start, end) else sample.take(80)
                hits.add(GlobalHit(convId, title, msgId, snippet))
            }
        }
        return hits
    }

    /** Inserts message and its parts transactionally; parts ordinal from index. */
    fun insert(message: ChatMessage) {
        db.beginTransaction()
        try {
            db.insertOrThrow("message_rows", null, message.toRow())
            insertParts(message)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Inserts messages and their parts in a single transaction. */
    fun insertAllInTransaction(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        db.beginTransaction()
        try {
            for (message in messages) {
                db.insertOrThrow("message_rows", null, message.toRow())
                insertParts(message)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Replaces the stored parts of [message] (parts ordinal from index). */
    private fun insertParts(message: ChatMessage) {
        db.delete("message_part_rows", "revision_id = ?", arrayOf(message.id))
        message.parts.forEachIndexed { index, part ->
            db.insertOrThrow(
                "message_part_rows", null,
                ContentValues().apply {
                    put("conversation_id", message.conversationId)
                    put("revision_id", message.id)
                    put("ordinal", index)
                    put("kind", part.kind)
                    put("payload", part.encodePayload())
                    put("created_at", message.timestamp)
                    put("updated_at", message.timestamp)
                },
            )
        }
    }

    /** Rewrites only the text content of the target revision (regenerate path). */
    fun replaceTextPart(message: ChatMessage, newContent: String) {
        val parts = ChatMessage.partsWithReplacedText(message.parts, newContent)
        db.beginTransaction()
        try {
            db.delete("message_part_rows", "revision_id = ?", arrayOf(message.id))
            parts.forEachIndexed { index, part ->
                db.insertOrThrow(
                    "message_part_rows", null,
                    ContentValues().apply {
                        put("conversation_id", message.conversationId)
                        put("revision_id", message.id)
                        put("ordinal", index)
                        put("kind", part.kind)
                        put("payload", part.encodePayload())
                        put("created_at", message.timestamp)
                        put("updated_at", System.currentTimeMillis())
                    },
                )
            }
            db.execSQL(
                "UPDATE message_rows SET updated_at = ?, is_streaming = 0 WHERE id = ?",
                arrayOf<Any>(System.currentTimeMillis(), message.id),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Replaces the stored parts of an existing revision and sets its streaming
     * flag (home_page_controller.submitRecoveredAskUserAnswer → upsertToolEvent
     * path: fold the tool answer into the persisted message, then stream the
     * follow-up). Mirrors [replaceTextPart]'s transaction shape.
     */
    fun replaceParts(message: ChatMessage, streaming: Boolean) {
        db.beginTransaction()
        try {
            db.delete("message_part_rows", "revision_id = ?", arrayOf(message.id))
            message.parts.forEachIndexed { index, part ->
                db.insertOrThrow(
                    "message_part_rows", null,
                    ContentValues().apply {
                        put("conversation_id", message.conversationId)
                        put("revision_id", message.id)
                        put("ordinal", index)
                        put("kind", part.kind)
                        put("payload", part.encodePayload())
                        put("created_at", message.timestamp)
                        put("updated_at", System.currentTimeMillis())
                    },
                )
            }
            db.execSQL(
                "UPDATE message_rows SET updated_at = ?, is_streaming = ? WHERE id = ?",
                arrayOf<Any>(System.currentTimeMillis(), if (streaming) 1 else 0, message.id),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setStreaming(id: String, streaming: Boolean) {
        db.execSQL("UPDATE message_rows SET is_streaming = ? WHERE id = ?", arrayOf<Any>(if (streaming) 1 else 0, id))
    }

    /**
     * Persists the translated body (chat_service.updateMessage(translation:)
     * parity). Empty string clears the translation; null keeps it untouched.
     */
    fun updateTranslation(id: String, translation: String) {
        db.execSQL("UPDATE message_rows SET translation = ? WHERE id = ?", arrayOf<Any>(translation, id))
    }

    /**
     * Persists reasoning segment state after an expand/collapse
     * (home_page_controller.toggleReasoningSegment → updateReasoningSegmentsInDb).
     */
    fun updateReasoningSegments(id: String, segmentsJson: String?) {
        db.execSQL(
            "UPDATE message_rows SET reasoning_segments_json = ? WHERE id = ?",
            arrayOf(segmentsJson ?: "", id),
        )
    }

    fun delete(id: String) {
        db.delete("message_rows", "id = ?", arrayOf(id))
        // message_part_rows rows cascade via FK, but parts of the deleted
        // revision are also cleaned explicitly in case foreign_keys is off.
        db.delete("message_part_rows", "revision_id = ?", arrayOf(id))
    }

    /** Deletes every message strictly after [order] in the conversation (regenerate trailing cut). */
    fun deleteAfterOrder(conversationId: String, order: Int) {
        db.delete("message_rows", "conversation_id = ? AND message_order > ?", arrayOf(conversationId, order.toString()))
    }

    /** Deletes every version of a message group (delete-all-versions action). */
    fun deleteByGroup(conversationId: String, groupId: String) {
        val ids = ArrayList<String>()
        db.query("message_rows", arrayOf("id"), "conversation_id = ? AND group_id = ?", arrayOf(conversationId, groupId), null, null, null).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        for (id in ids) delete(id)
    }

    /** Highest stored version of a group (-1 when the group has no rows). */
    fun maxVersionForGroup(conversationId: String, groupId: String): Int {
        db.rawQuery(
            "SELECT MAX(version) FROM message_rows WHERE conversation_id = ? AND group_id = ?",
            arrayOf(conversationId, groupId),
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else -1
        }
    }

    /**
     * Available versions per group (sorted ASC) for the branch selector.
     * Groups with a single version are included so the UI can compute counts.
     */
    fun groupVersions(conversationId: String): Map<String, List<Int>> {
        val out = LinkedHashMap<String, MutableSet<Int>>()
        db.query(
            "message_rows", arrayOf("group_id", "version"), "conversation_id = ?",
            arrayOf(conversationId), null, null, "message_order ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val gid = cursor.getString(0) ?: continue
                out.getOrPut(gid) { LinkedHashSet() }.add(cursor.getInt(1))
            }
        }
        return out.mapValues { (_, vs) -> vs.toSortedSet().toList() }
    }

    /**
     * Next message_order. MAX+1 (not count) so mid-timeline deletes
     * (delete-by-version) can never collide with the UNIQUE
     * (conversation_id, message_order) constraint.
     */
    fun nextOrder(conversationId: String): Int {
        db.rawQuery(
            "SELECT MAX(message_order) FROM message_rows WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) + 1 else 0
        }
    }

    private fun getOrder(conversationId: String, id: String): Int? {
        db.query(
            "message_rows", arrayOf("message_order"), "conversation_id = ? AND id = ?",
            arrayOf(conversationId, id), null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else null
        }
    }

    private fun ChatMessage.toRow(): ContentValues = ContentValues().apply {
        put("id", id)
        put("conversation_id", conversationId)
        put("role", role)
        put("timestamp", timestamp)
        put("model_id", modelId)
        put("provider_id", providerId)
        put("total_tokens", totalTokens)
        put("is_streaming", if (isStreaming) 1 else 0)
        put("reasoning_start_at", reasoningStartAt)
        put("reasoning_finished_at", reasoningFinishedAt)
        put("translation", translation)
        put("reasoning_segments_json", reasoningSegmentsJson)
        put("group_id", groupId)
        put("version", version)
        put("prompt_tokens", promptTokens)
        put("completion_tokens", completionTokens)
        put("cached_tokens", cachedTokens)
        put("duration_ms", durationMs)
        put("message_order", messageOrder)
        put("updated_at", updatedAt)
        putNull("sender_id")
        put("extras_json", "{}")
    }

    private fun Cursor.hydrate(): ChatMessage {
        val id = getString(getColumnIndexOrThrow("id"))
        val parts = loadParts(id)
        return ChatMessage(
            id = id,
            role = getString(getColumnIndexOrThrow("role")),
            parts = parts,
            timestamp = getLong(getColumnIndexOrThrow("timestamp")),
            modelId = getString(getColumnIndexOrThrow("model_id")),
            providerId = getString(getColumnIndexOrThrow("provider_id")),
            totalTokens = getIntOrNull("total_tokens"),
            conversationId = getString(getColumnIndexOrThrow("conversation_id")),
            isStreaming = getInt(getColumnIndexOrThrow("is_streaming")) == 1,
            reasoningStartAt = getLongOrNull("reasoning_start_at"),
            reasoningFinishedAt = getLongOrNull("reasoning_finished_at"),
            translation = getString(getColumnIndexOrThrow("translation")),
            reasoningSegmentsJson = getString(getColumnIndexOrThrow("reasoning_segments_json")),
            groupId = getString(getColumnIndexOrThrow("group_id")) ?: id,
            version = getInt(getColumnIndexOrThrow("version")),
            promptTokens = getIntOrNull("prompt_tokens"),
            completionTokens = getIntOrNull("completion_tokens"),
            cachedTokens = getIntOrNull("cached_tokens"),
            durationMs = getLongOrNull("duration_ms"),
            updatedAt = getLongOrNull("updated_at"),
            messageOrder = getInt(getColumnIndexOrThrow("message_order")),
        )
    }

    private fun loadParts(revisionId: String): List<MessagePart> {
        db.query(
            "message_part_rows", arrayOf("ordinal", "kind", "payload"),
            "revision_id = ?", arrayOf(revisionId), null, null, "ordinal ASC",
        ).use { cursor ->
            val out = ArrayList<MessagePart>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(MessagePart.fromRow(cursor.getString(1), cursor.getString(2)))
            }
            return out
        }
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getInt(idx)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getLong(idx)
    }
}
