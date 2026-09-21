package com.psyche.memo.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.security.MessageDigest

/**
 * What one merge pass did (`BackupMergeReport`,
 * `chat_database_repository.dart:110`).
 */
data class BackupMergeReport(
    val importedConversations: Int,
    val deduplicatedConversations: Int,
    val skippedConversations: Int,
    val remappedConversationIds: Map<String, String>,
    val importedConversationIds: List<String>,
)

/**
 * Merges a backed-up database snapshot into the live one — sub-block 2's chat
 * half, a 1:1 port of `ChatDatabaseRepository.mergeBackupSnapshot`
 * (`chat_database_repository.dart:5057-5177`) plus its private helpers
 * (L5308-5655).
 *
 * Semantics per source conversation, in id order:
 *  1. A conversation whose `message_order` is negative/duplicated is skipped.
 *  2. A conversation whose fingerprint matches the local one with the same id
 *     is a duplicate and is skipped (fingerprints hash the semantic content —
 *     `updated_at`/streaming flags/attachment availability are excluded).
 *  3. Otherwise the conversation is imported; when its id (or any of its
 *     message ids) collides locally, the *whole* conversation lands under a
 *     deterministic `merge-<sha256>` id (and its message/group ids are
 *     remapped along), so a partially-shared conversation never interleaves.
 *
 * Runs in one transaction; `PRAGMA foreign_key_check` must come back empty.
 * The fingerprint is only ever compared within one merge run, so it needs to
 * be self-consistent rather than byte-identical to the Dart one.
 */
class DatabaseSnapshotMerger(private val db: SQLiteDatabase) {

    fun merge(snapshotFile: File): BackupMergeReport {
        require(snapshotFile.isFile) { "备份中的数据库快照不存在" }
        // ATTACH cannot run inside a transaction, and neither can DETACH.
        db.execSQL("ATTACH DATABASE '${snapshotFile.absolutePath.replace("'", "''")}' AS merge_source")
        try {
            db.beginTransaction()
            try {
                val report = mergeAttached()
                db.setTransactionSuccessful()
                return report
            } finally {
                db.endTransaction()
            }
        } finally {
            db.execSQL("DETACH DATABASE merge_source")
        }
    }

    private fun mergeAttached(): BackupMergeReport {
        val sourceIds = mutableListOf<String>()
        db.rawQuery("SELECT id FROM merge_source.conversation_rows ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) sourceIds.add(cursor.string("id"))
        }

        var imported = 0
        var deduplicated = 0
        var skipped = 0
        val remapped = LinkedHashMap<String, String>()
        val importedIds = mutableListOf<String>()

        for (sourceId in sourceIds) {
            try {
                requireValidMessageOrder(sourceId)
            } catch (e: IllegalStateException) {
                if (e.message != "conversation_message_order") throw e
                skipped += 1
                continue
            }
            val sourceFingerprint = conversationFingerprint(sourceId)
                ?: throw IllegalStateException("merge_source_conversation")
            val existingFingerprint = conversationFingerprint(sourceId, schema = "main")
            if (existingFingerprint == sourceFingerprint) {
                deduplicated += 1
                continue
            }

            val sourceMessageIds = messageIds(sourceId)
            val hasConversationConflict = existingFingerprint != null
            val hasMessageConflict = anyMessageIdExists(sourceMessageIds)
            var targetId = sourceId
            var remapWholeConversation = hasConversationConflict || hasMessageConflict
            var skip = false
            if (remapWholeConversation) {
                targetId = deterministicMergeId("conversation", sourceId, sourceFingerprint)
                var suffix = 0
                while (true) {
                    val candidateFingerprint = conversationFingerprint(targetId, schema = "main")
                    if (candidateFingerprint == null) break
                    if (candidateFingerprint == sourceFingerprint) {
                        deduplicated += 1
                        remapped[sourceId] = targetId
                        skip = true
                        break
                    }
                    suffix += 1
                    targetId = deterministicMergeId("conversation", sourceId, sourceFingerprint) + "-$suffix"
                }
                if (skip) continue
                remapped[sourceId] = targetId
            }

            val messageIdMap = LinkedHashMap<String, String>()
            for (messageId in sourceMessageIds) {
                messageIdMap[messageId] = if (remapWholeConversation) {
                    deterministicMergeId("message", messageId, sourceFingerprint)
                } else {
                    messageId
                }
            }
            insertMergedConversation(sourceId, targetId, messageIdMap)
            imported += 1
            importedIds.add(targetId)
        }

        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            check(!cursor.moveToFirst()) { "合并后的数据库外键校验失败" }
        }
        return BackupMergeReport(
            importedConversations = imported,
            deduplicatedConversations = deduplicated,
            skippedConversations = skipped,
            remappedConversationIds = remapped,
            importedConversationIds = importedIds,
        )
    }

    // ── validation ────────────────────────────────────────────────────────────

    /**
     * Message deletion preserves `message_order` gaps, so sparse orders are
     * valid; only negative or duplicate values are rejected (L5498-5511).
     */
    private fun requireValidMessageOrder(conversationId: String) {
        db.rawQuery(
            "SELECT message_order FROM merge_source.message_rows " +
                "WHERE conversation_id = ? ORDER BY message_order, id",
            arrayOf(conversationId),
        ).use { cursor ->
            var previous = -1L
            while (cursor.moveToNext()) {
                val order = cursor.getLong(0)
                if (order < 0 || (previous >= 0 && order <= previous)) {
                    throw IllegalStateException("conversation_message_order")
                }
                previous = order
            }
        }
    }

    private fun messageIds(conversationId: String): List<String> =
        db.rawQuery(
            "SELECT id FROM merge_source.message_rows WHERE conversation_id = ? ORDER BY message_order, id",
            arrayOf(conversationId),
        ).use { cursor -> cursor.map { it.string("id") } }

    private fun anyMessageIdExists(ids: List<String>): Boolean {
        for (id in ids) {
            db.rawQuery("SELECT 1 FROM main.message_rows WHERE id = ? LIMIT 1", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        }
        return false
    }

    // ── fingerprint ───────────────────────────────────────────────────────────

    /**
     * The conversation fingerprint: a SHA-256 over the conversation fields,
     * its MCP selection, and its messages with materialized payloads —
     * `updated_at`, streaming flags, ids, part ordinals and attachment
     * availability are excluded so equal content hashes equally (L5308-5467).
     */
    private fun conversationFingerprint(conversationId: String, schema: String = "merge_source"): String? {
        val conversation = db.rawQuery(
            "SELECT title, created_at, updated_at, is_pinned, assistant_id, " +
                "truncate_index, version_selections_json, summary, " +
                "last_summarized_message_count, chat_suggestions_json " +
                "FROM $schema.conversation_rows WHERE id = ?",
            arrayOf(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.rowData() else return null }

        val mcpRows = db.rawQuery(
            "SELECT server_id, ordinal FROM $schema.conversation_mcp_server_rows " +
                "WHERE conversation_id = ? ORDER BY ordinal, server_id",
            arrayOf(conversationId),
        ).use { cursor -> cursor.map { it.rowData() } }

        val messageRows = db.rawQuery(
            "SELECT id, role, timestamp, model_id, provider_id, " +
                "total_tokens, is_streaming, reasoning_start_at, " +
                "reasoning_finished_at, translation, reasoning_segments_json, group_id, " +
                "version, prompt_tokens, completion_tokens, cached_tokens, duration_ms, " +
                "message_order, sender_id, extras_json " +
                "FROM $schema.message_rows WHERE conversation_id = ? ORDER BY message_order, id",
            arrayOf(conversationId),
        ).use { cursor ->
            cursor.map { row ->
                row.string("id") to row.rowData()
            }
        }

        data class Part(val revisionId: String, val kind: String, val payload: String)

        val partRows = db.rawQuery(
            "SELECT p.revision_id, p.kind, p.payload " +
                "FROM $schema.message_part_rows p " +
                "INNER JOIN $schema.message_rows m ON m.id = p.revision_id " +
                "WHERE m.conversation_id = ? ORDER BY p.revision_id, p.ordinal",
            arrayOf(conversationId),
        ).use { cursor ->
            cursor.map { Part(it.string("revision_id"), it.string("kind"), it.string("payload")) }
        }
        val partPayloads = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
        val attachmentPayloads = LinkedHashMap<String, MutableList<String>>()
        for (part in partRows) {
            if (part.kind == "image" || part.kind == "file") {
                attachmentPayloads.getOrPut(part.revisionId) { mutableListOf() }
                    .add(fingerprintAttachmentPayload(part.kind, part.payload))
                continue
            }
            partPayloads.getOrPut(part.revisionId) { LinkedHashMap() }
                .getOrPut(part.kind) { mutableListOf() }
                .add(part.payload)
        }

        val signatures = db.rawQuery(
            "SELECT a.revision_id, a.payload " +
                "FROM $schema.provider_artifact_rows a " +
                "INNER JOIN $schema.message_rows m ON m.id = a.revision_id " +
                "WHERE m.conversation_id = ? AND a.kind = 'gemini_thought_signature'",
            arrayOf(conversationId),
        ).use { cursor ->
            val map = LinkedHashMap<String, String>()
            while (cursor.moveToNext()) map[cursor.string("revision_id")] = cursor.string("payload")
            map
        }

        val groupOrdinals = LinkedHashMap<String, Int>()
        val messages = buildJsonArray {
            for ((messageId, data) in messageRows) {
                val payloads = partPayloads[messageId]
                val normalized = LinkedHashMap(data)
                normalized["is_streaming"] = JsonPrimitive(0)
                for (field in listOf("timestamp", "reasoning_start_at", "reasoning_finished_at")) {
                    normalized[field] = fingerprintTimestamp(normalized[field])
                }
                val groupId = (normalized.remove("group_id") as? JsonPrimitive)?.content ?: ""
                normalized["group_ordinal"] = JsonPrimitive(
                    groupOrdinals.getOrPut(groupId) { groupOrdinals.size },
                )
                add(
                    buildJsonArray {
                        add(JsonObject(normalized))
                        add(payloadList(payloads?.get("text")))
                        add(payloadList(payloads?.get("reasoning")))
                        add(payloadList(payloads?.get("tool_call")))
                        add(attachmentPayloads[messageId]
                            ?.let { list -> JsonArray(list.map { JsonPrimitive(it) }) }
                            ?: JsonNull)
                        add(signatures[messageId]?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        }

        val normalizedConversation = normalizeConversationData(conversation, groupOrdinals)
        val encoded = BackupJson.codec.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                add(normalizedConversation)
                add(JsonArray(mcpRows))
                add(messages)
            },
        )
        return sha256Hex(encoded)
    }

    private fun payloadList(payloads: List<String>?): JsonElement =
        payloads?.let { list -> JsonArray(list.map { JsonPrimitive(it) }) } ?: JsonNull

    /** Timestamps hash at second granularity so cross-device copies compare equal. */
    private fun fingerprintTimestamp(value: JsonElement?): JsonElement {
        val primitive = value as? JsonPrimitive ?: return JsonNull
        val number = primitive.content.toLongOrNull() ?: return JsonPrimitive(primitive.content)
        return JsonPrimitive(number / 1_000_000L)
    }

    /** `version_selections_json`'s group ids become group ordinals (L5443-5455). */
    private fun normalizeConversationData(
        data: JsonObject,
        groupOrdinals: LinkedHashMap<String, Int>,
    ): JsonObject {
        val normalized = LinkedHashMap(data)
        for (field in listOf("created_at", "updated_at")) {
            normalized[field] = fingerprintTimestamp(normalized[field])
        }
        val rawSelections = (normalized["version_selections_json"] as? JsonPrimitive)?.content
        if (rawSelections != null) {
            val decoded = decodeStringIntMap(rawSelections)
            val selections = LinkedHashMap<String, Int>()
            for ((groupId, version) in decoded) {
                val ordinal = groupOrdinals[groupId]
                if (ordinal != null) selections[ordinal.toString()] = version
            }
            normalized["version_selections_json"] = JsonArray(
                selections.entries.sortedBy { it.key.toIntOrNull() ?: 0 }
                    .map { (key, value) ->
                        buildJsonObject {
                            put("k", JsonPrimitive(key))
                            put("v", JsonPrimitive(value))
                        }
                    },
            )
        }
        return JsonObject(normalized)
    }

    private fun decodeStringIntMap(raw: String): Map<String, Int> {
        val obj = runCatching { BackupJson.parse(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            (primitive.content.toIntOrNull() ?: return@mapNotNull null)?.let { key to it }
        }.toMap()
    }

    /**
     * Attachment identity drops the environment-state `unavailable` flag so the
     * same file available here but missing there still dedupes (L5458-5478).
     */
    private fun fingerprintAttachmentPayload(kind: String, payload: String): String {
        val decoded = runCatching { BackupJson.parse(payload) as? JsonObject }.getOrNull()
            ?: return buildJsonObject {
                put("kind", JsonPrimitive(kind))
                put("raw", JsonPrimitive(payload))
            }.toString()
        return buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("uri", decoded["uri"] ?: JsonNull)
            decoded["name"]?.let { put("name", it) }
            decoded["mime"]?.let { put("mime", it) }
            decoded["assetId"]?.let { put("assetId", it) }
        }.toString()
    }

    // ── insertion ─────────────────────────────────────────────────────────────

    private fun insertMergedConversation(sourceId: String, targetId: String, messageIdMap: Map<String, String>) {
        val sourceMessages = db.rawQuery(
            "SELECT id, group_id FROM merge_source.message_rows " +
                "WHERE conversation_id = ? ORDER BY message_order, id",
            arrayOf(sourceId),
        ).use { cursor -> cursor.map { it.string("id") to (it.stringOrNull("group_id")) } }

        val remapping = sourceId != targetId
        val groupIdMap = LinkedHashMap<String, String>()
        for ((id, groupId) in sourceMessages) {
            // A group is keyed by COALESCE(group_id, id): the first revision
            // keeps a null group_id and later versions carry that revision's id
            // (L5553-5562).
            val groupKey = groupId ?: id
            if (groupIdMap.containsKey(groupKey)) continue
            groupIdMap[groupKey] = messageIdMap[groupKey]
                ?: (if (remapping) deterministicMergeId("group", groupKey, targetId) else groupKey)
        }

        val sourceSelections = db.rawQuery(
            "SELECT version_selections_json FROM merge_source.conversation_rows WHERE id = ?",
            arrayOf(sourceId),
        ).use { cursor ->
            if (cursor.moveToFirst()) decodeStringIntMap(cursor.string("version_selections_json")) else emptyMap()
        }
        val targetSelections = LinkedHashMap<String, Int>()
        for ((groupId, version) in sourceSelections) {
            targetSelections[groupIdMap[groupId] ?: groupId] = version
        }
        val selectionsJson = BackupJson.codec.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            JsonArray(targetSelections.entries.map { (key, value) ->
                buildJsonObject {
                    put("k", JsonPrimitive(key))
                    put("v", JsonPrimitive(value))
                }
            }),
        )

        db.execSQL(
            "INSERT INTO main.conversation_rows " +
                "(id, title, created_at, updated_at, is_pinned, assistant_id, " +
                "truncate_index, version_selections_json, summary, " +
                "last_summarized_message_count, chat_suggestions_json, " +
                "injected_memory_hash, last_memory_extracted_order, " +
                "chat_model_provider, chat_model_id, extras_json) " +
                "SELECT ?, title, created_at, updated_at, is_pinned, assistant_id, " +
                "truncate_index, ?, summary, " +
                "last_summarized_message_count, chat_suggestions_json, " +
                "NULL, COALESCE((SELECT MAX(message_order) " +
                "FROM merge_source.message_rows WHERE conversation_id = ?), -1), " +
                "chat_model_provider, chat_model_id, extras_json " +
                "FROM merge_source.conversation_rows WHERE id = ?",
            arrayOf(targetId, selectionsJson, sourceId, sourceId),
        )
        db.execSQL(
            "INSERT INTO main.conversation_mcp_server_rows " +
                "(conversation_id, server_id, ordinal) " +
                "SELECT ?, server_id, ordinal FROM merge_source.conversation_mcp_server_rows " +
                "WHERE conversation_id = ?",
            arrayOf(targetId, sourceId),
        )
        for ((sourceMessageId, targetMessageId) in messageIdMap) {
            val sourceGroupId = sourceMessages.firstOrNull { it.first == sourceMessageId }?.second
            // Anchor revisions keep their null group_id so the merged rows
            // describe the same groups as the snapshot (L5593-5598).
            val targetGroupId = sourceGroupId?.let { groupIdMap[it] ?: it }
            db.execSQL(
                "INSERT INTO main.message_rows " +
                    "(id, conversation_id, role, timestamp, model_id, provider_id, " +
                    "total_tokens, is_streaming, reasoning_start_at, " +
                    "reasoning_finished_at, translation, reasoning_segments_json, group_id, " +
                    "version, prompt_tokens, completion_tokens, cached_tokens, duration_ms, " +
                    "message_order, updated_at, sender_id, extras_json) " +
                    "SELECT ?, ?, role, timestamp, model_id, provider_id, " +
                    "total_tokens, 0, reasoning_start_at, " +
                    "reasoning_finished_at, translation, reasoning_segments_json, " +
                    "?, version, " +
                    "prompt_tokens, completion_tokens, cached_tokens, duration_ms, " +
                    "message_order, updated_at, sender_id, extras_json " +
                    "FROM merge_source.message_rows WHERE id = ?",
                arrayOf(targetMessageId, targetId, targetGroupId, sourceMessageId),
            )
            db.execSQL(
                "INSERT INTO main.message_part_rows " +
                    "(conversation_id, revision_id, ordinal, kind, payload, " +
                    "created_at, updated_at) " +
                    "SELECT ?, ?, ordinal, kind, payload, created_at, updated_at " +
                    "FROM merge_source.message_part_rows WHERE revision_id = ?",
                arrayOf(targetId, targetMessageId, sourceMessageId),
            )
            // generation_run_rows are intentionally not copied: merged revisions
            // are always persisted as non-streaming (L5645-5646).
            db.execSQL(
                "INSERT INTO main.provider_artifact_rows " +
                    "(conversation_id, revision_id, kind, payload, created_at, updated_at) " +
                    "SELECT ?, ?, kind, payload, created_at, updated_at " +
                    "FROM merge_source.provider_artifact_rows WHERE revision_id = ?",
                arrayOf(targetId, targetMessageId, sourceMessageId),
            )
        }
        // Queue attachment-bearing revisions for the asset-reference backfill
        // before GC can treat their files as unreferenced (L5648-5653).
        db.execSQL(
            "INSERT OR IGNORE INTO asset_reference_dirty_rows(revision_id) " +
                "SELECT DISTINCT revision_id FROM main.message_part_rows " +
                "WHERE conversation_id = ? AND kind IN ('image', 'file')",
            arrayOf(targetId),
        )
    }

    private fun deterministicMergeId(kind: String, id: String, fingerprint: String): String =
        "merge-" + sha256Hex("$kind\u0000$id\u0000$fingerprint").take(32)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

// — Cursor helpers shared by the merger —

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.stringOrNull(column: String): String? =
    if (isNull(getColumnIndexOrThrow(column))) null else getString(getColumnIndexOrThrow(column))

/** One row as a JSON object (the fingerprint's map shape). */
private fun Cursor.rowData(): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for (index in 0 until columnCount) {
        val name = getColumnName(index)
        out[name] = when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JsonNull
            Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(android.util.Base64.encodeToString(getBlob(index), android.util.Base64.NO_WRAP))
            else -> JsonPrimitive(getString(index) ?: "")
        }
    }
    return JsonObject(out)
}

private inline fun <T> Cursor.map(transform: (Cursor) -> T): List<T> {
    val out = mutableListOf<T>()
    while (moveToNext()) out.add(transform(this))
    return out
}
