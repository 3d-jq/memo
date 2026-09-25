package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.MessagePart

/**
 * Hand-written DAO for message_rows + message_part_rows (drift v3 layout).
 *
 * UNIQUE(conversation_id, message_order) and UNIQUE(conversation_id, group_id,
 * version) are enforced by the schema; callers must use insertMessage that maps
 * conflicts to a regenerate path.
 */
class MessageDao(private val db: SQLiteDatabase) {

    /** Single message row (parts loaded). */
    fun get(id: String): ChatMessage? {
        db.query("message_rows", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            return cursor.readMessages().firstOrNull()
        }
    }

    fun getByIds(ids: List<String>): List<ChatMessage> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        db.query(
            "message_rows", null, "id IN ($placeholders)", ids.toTypedArray(), null, null, null,
        ).use { cursor ->
            val byId = HashMap<String, ChatMessage>()
            for (message in cursor.readMessages()) byId[message.id] = message
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
            return cursor.readMessages().reversed()
        }
    }

    /** All messages of a conversation in wall order (parts loaded). */
    fun getAllForConversation(conversationId: String): List<ChatMessage> {
        db.query(
            "message_rows", null, "conversation_id = ?", arrayOf(conversationId),
            null, null, "message_order ASC, id ASC",
        ).use { cursor ->
            return cursor.readMessages()
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
            return cursor.readMessages().reversed()
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

    /** One message match for the chat_search tool (memory_tools._handleChatSearch). */
    data class MessageHit(
        val conversationId: String,
        val conversationTitle: String,
        val summary: String?,
        val role: String,
        val content: String,
        val timestamp: Long,
    )

    /**
     * Per-message search scoped to the assistant's conversations (plus unowned
     * older chats). [tokens] are ANDed; pass [onlyConversationId] to search one
     * conversation, or [excludeConversationId] to skip the current one.
     */
    fun searchMessagesForAssistant(
        tokens: List<String>,
        assistantId: String,
        onlyConversationId: String? = null,
        excludeConversationId: String? = null,
        limit: Int = 40,
    ): List<MessageHit> {
        if (tokens.isEmpty()) return emptyList()
        val clauses = StringBuilder("m.role IN ('user','assistant') AND (c.assistant_id = ? OR c.assistant_id IS NULL)")
        val args = mutableListOf(assistantId)
        if (!onlyConversationId.isNullOrEmpty()) {
            clauses.append(" AND m.conversation_id = ?")
            args.add(onlyConversationId)
        } else if (!excludeConversationId.isNullOrEmpty()) {
            clauses.append(" AND m.conversation_id != ?")
            args.add(excludeConversationId)
        }
        for (token in tokens) {
            clauses.append(" AND p.payload LIKE ?")
            args.add("%" + token.replace("'", "''") + "%")
        }
        args.add(limit.toString())
        val out = ArrayList<MessageHit>()
        db.rawQuery(
            "SELECT m.conversation_id, c.title, c.summary, m.role, m.timestamp, p.payload " +
                "FROM message_rows m " +
                "JOIN conversation_rows c ON c.id = m.conversation_id " +
                "JOIN message_part_rows p ON p.revision_id = m.id AND p.kind = 'text' " +
                "WHERE $clauses ORDER BY m.timestamp DESC LIMIT ?",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    MessageHit(
                        conversationId = cursor.getString(0) ?: "",
                        conversationTitle = cursor.getString(1) ?: "",
                        summary = cursor.getString(2),
                        role = cursor.getString(3) ?: "",
                        timestamp = cursor.getLong(4),
                        content = cursor.getString(5) ?: "",
                    ),
                )
            }
        }
        return out
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

    /**
     * 重新生成「删除后续消息」打开时的裁剪（chat_database_repository.dart:4624-4662
     * `_truncateLinearMessageGroupsAfter`）：按**分组首条**的 message_order 判断，
     * 删掉锚点分组之后出现的**所有分组**（连同它们的全部版本），锚点分组本身的其它
     * 版本保留。`deleteAfterOrder` 是按单行 order 切的，会误删保留分组里的高版本行。
     */
    fun deleteTrailingGroups(conversationId: String, anchorGroupId: String) {
        val anchorFirst = db.rawQuery(
            "SELECT MIN(message_order) FROM message_rows WHERE conversation_id = ? AND group_id = ?",
            arrayOf(conversationId, anchorGroupId),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null }
            ?: return
        val groups = ArrayList<String>()
        db.rawQuery(
            "SELECT group_id FROM message_rows WHERE conversation_id = ? " +
                "GROUP BY group_id HAVING MIN(message_order) > ?",
            arrayOf(conversationId, anchorFirst.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { groups.add(it) }
        }
        for (groupId in groups) deleteByGroup(conversationId, groupId)
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
     *
     * [groups] narrows the scan to the groups the caller actually needs (a single
     * `IN (?,?,…)` query); the original only preloads groups whose window row has
     * `version > 0` (`chat_controller.dart:180-198`), so callers should pass that
     * set instead of scanning the whole conversation on every open.
     */
    fun groupVersions(
        conversationId: String,
        groups: Collection<String>? = null,
    ): Map<String, List<Int>> {
        if (groups != null && groups.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableSet<Int>>()
        val selection = if (groups == null) {
            "conversation_id = ?"
        } else {
            "conversation_id = ? AND group_id IN (${groups.joinToString(",") { "?" }})"
        }
        val args = buildList {
            add(conversationId)
            if (groups != null) addAll(groups)
        }.toTypedArray()
        db.query(
            "message_rows", arrayOf("group_id", "version"), selection,
            args, null, null, "message_order ASC, id ASC",
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
    }

    /**
     * Builds one message from the current row. [parts] comes from [loadPartsFor]:
     * a page's parts are read in **one** batched query instead of one query per
     * row (the old per-row `loadParts` cost 41 queries for a 40-message tail page,
     * which is what made opening a conversation feel slow).
     */
    private fun Cursor.hydrate(parts: List<MessagePart>): ChatMessage {
        val id = getString(getColumnIndexOrThrow("id"))
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

    /**
     * Parts of several revisions in **one** query per chunk, grouped by revision.
     *
     * `IN (...)`, so the chunk size is capped well under SQLite's variable limit
     * (999 on older builds). `ORDER BY revision_id, ordinal` guarantees the parts
     * of each revision stay in `ordinal ASC` order once grouped.
     */
    private fun loadPartsFor(revisionIds: Collection<String>): Map<String, List<MessagePart>> {
        if (revisionIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<MessagePart>>(revisionIds.size)
        for (chunk in revisionIds.distinct().chunked(SQLITE_IN_CLAUSE_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT revision_id, kind, payload FROM message_part_rows " +
                    "WHERE revision_id IN ($placeholders) " +
                    "ORDER BY revision_id, ordinal ASC",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out.getOrPut(cursor.getString(0)) { ArrayList() }
                        .add(MessagePart.fromRow(cursor.getString(1), cursor.getString(2)))
                }
            }
        }
        return out
    }

    /**
     * Reads every row of [cursor] into messages with their parts attached.
     *
     * Two passes over the same cursor: the first only collects ids (cheap), then
     * one batched parts query, then the rows are assembled. `SQLiteCursor` is
     * random-access, so rewinding with `moveToPosition(-1)` is fine — that is what
     * keeps this a single `message_rows` query.
     */
    private fun Cursor.readMessages(): List<ChatMessage> {
        val ids = ArrayList<String>(count)
        while (moveToNext()) ids.add(getString(getColumnIndexOrThrow("id")))
        if (ids.isEmpty()) return emptyList()
        val partsByRevision = loadPartsFor(ids)
        moveToPosition(-1)
        val out = ArrayList<ChatMessage>(ids.size)
        while (moveToNext()) {
            val id = getString(getColumnIndexOrThrow("id"))
            out.add(hydrate(partsByRevision[id].orEmpty()))
        }
        return out
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getInt(idx)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getLong(idx)
    }

    /**
     * `internal` 而非 private：单测要按这个常量构造「多批」的用例（改大小时测试跟着走，
     * 不会因为写死 501 而悄悄失去覆盖）。
     */
    internal companion object {
        /**
         * `IN (...)` 每批的变量数。SQLite 的 `SQLITE_MAX_VARIABLE_NUMBER` 在旧版本上是
         * 999，取 500 留足余量（`getAllForConversation` 在长会话上会超过 999 行）。
         */
        const val SQLITE_IN_CLAUSE_CHUNK = 500
    }
}
