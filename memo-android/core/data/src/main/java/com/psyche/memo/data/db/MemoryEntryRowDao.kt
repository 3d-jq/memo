package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `memory_entry_rows` — long-term memory entries.
 *
 * Unlike the plain JSON-payload entity tables this one carries typed columns
 * (`scope` / `assistant_id` / `type` / `status` / `content` /
 * `content_normalized` / `entry_created_at` / `entry_updated_at`) with CHECK
 * constraints, so [PayloadEntityDao] cannot write it: an insert that leaves
 * them empty is rejected. The Dart side derives these columns from the payload
 * on every write (`BusinessRepository.synchronizeEntities`) and the payload
 * stays authoritative for reads (`_memoryEntriesFromPayloadRows`); that is the
 * split mirrored here — [MemoryEntryRow.fromPayload] projects the payload onto
 * the columns, and readers decode the payload again.
 */
class MemoryEntryRowDao(private val db: SQLiteDatabase) {

    data class Row(
        val id: String,
        val sortOrder: Int,
        val scope: String,
        val assistantId: String?,
        val type: String,
        val status: String,
        val content: String,
        val contentNormalized: String,
        val entryCreatedAt: Long,
        val entryUpdatedAt: Long,
        val payload: String,
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        companion object {
            /**
             * Projects a stored payload onto the typed columns.
             *
             * The payload is what the Dart model serialises, so it may carry
             * values the schema does not accept (an assistant-scoped entry
             * without an owner, an unknown type from a newer build, an
             * `updatedAt` before `createdAt`). Rather than let the CHECK abort
             * the write — which used to lose the whole entry — the projection
             * repairs it:
             *  - an assistant scope without an owner degrades to global,
             *  - unknown type/status fall back to the Dart enum defaults,
             *  - `entry_updated_at` is floored at `entry_created_at`.
             * The payload keeps the original values, so nothing is rewritten.
             */
            fun fromPayload(
                id: String,
                payload: String,
                sortOrder: Int,
                updatedAt: Long = System.currentTimeMillis(),
            ): Row {
                val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                fun str(key: String): String? = (obj?.get(key) as? JsonPrimitive)?.content
                fun long(key: String): Long = (obj?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L

                val assistantId = str("assistantId")?.takeIf { it.isNotBlank() }
                val scope = when (str("scope")) {
                    "assistant" -> if (assistantId == null) SCOPE_GLOBAL else "assistant"
                    else -> SCOPE_GLOBAL
                }
                val createdAt = long("createdAt")
                val updated = long("updatedAt")
                return Row(
                    id = id,
                    sortOrder = sortOrder,
                    scope = scope,
                    assistantId = if (scope == "assistant") assistantId else null,
                    type = str("type")?.takeIf { it in MEMORY_TYPES } ?: MEMORY_TYPES.first(),
                    status = str("status")?.takeIf { it in MEMORY_STATUSES } ?: MEMORY_STATUSES.first(),
                    content = str("content") ?: "",
                    contentNormalized = normalizeMemoryContent(str("content") ?: ""),
                    entryCreatedAt = createdAt,
                    entryUpdatedAt = maxOf(updated, createdAt),
                    payload = payload,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    fun getAll(): List<Row> {
        db.query(
            TABLE, null, null, null, null, null, "entry_updated_at DESC, id ASC",
        ).use { cursor ->
            val out = ArrayList<Row>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.toRow())
            return out
        }
    }

    fun count(): Int {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Replaces the whole table with [rows]. Callers are expected to be inside a
     * transaction (the store rewrites every entry on mutation, like the Dart
     * `JsonBlobStore.writeAll`), so rows absent from [rows] must disappear.
     */
    fun replaceAll(rows: List<Row>): Int {
        db.delete(TABLE, null, null)
        for (row in rows) {
            val values = ContentValues().apply {
                put("id", row.id)
                put("sort_order", row.sortOrder)
                put("scope", row.scope)
                put("assistant_id", row.assistantId)
                put("type", row.type)
                put("status", row.status)
                put("content", row.content)
                put("content_normalized", row.contentNormalized)
                put("entry_created_at", row.entryCreatedAt)
                put("entry_updated_at", row.entryUpdatedAt)
                put("payload", row.payload)
                put("updated_at", row.updatedAt)
            }
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return rows.size
    }

    fun deleteAll() {
        db.delete(TABLE, null, null)
    }

    private fun Cursor.toRow(): Row = Row(
        id = getString(getColumnIndexOrThrow("id")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        scope = getString(getColumnIndexOrThrow("scope")),
        assistantId = if (isNull(getColumnIndexOrThrow("assistant_id"))) {
            null
        } else {
            getString(getColumnIndexOrThrow("assistant_id"))
        },
        type = getString(getColumnIndexOrThrow("type")),
        status = getString(getColumnIndexOrThrow("status")),
        content = getString(getColumnIndexOrThrow("content")),
        contentNormalized = getString(getColumnIndexOrThrow("content_normalized")),
        entryCreatedAt = getLong(getColumnIndexOrThrow("entry_created_at")),
        entryUpdatedAt = getLong(getColumnIndexOrThrow("entry_updated_at")),
        payload = getString(getColumnIndexOrThrow("payload")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    companion object {
        const val TABLE = "memory_entry_rows"
        const val SCOPE_GLOBAL = "global"

        /** memory_entry.dart `type` wire values (schema CHECK IN (...)). */
        val MEMORY_TYPES = listOf("identity", "workflow", "voice", "instruction")

        /** memory_entry.dart `status` wire values (schema CHECK IN (...)). */
        val MEMORY_STATUSES = listOf("active", "archived")
    }
}

/**
 * memory_entry.dart L107-115 — trim, collapse whitespace, lowercase. Also the
 * `content_normalized` column's derivation, so the dedupe column and the
 * in-memory duplicate check can never drift apart.
 */
fun normalizeMemoryContent(content: String): String =
    content.trim().replace(Regex("\\s+"), " ").lowercase()
