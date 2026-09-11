package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `assistant_memory_rows` — the legacy (V1) memory list.
 *
 * Same shape problem as [MemoryEntryRowDao]: an extra NOT NULL `assistant_id`
 * column means [PayloadEntityDao] cannot insert a row, so the typed column is
 * projected from the payload here. Dart's `AssistantMemory.toJson()` keeps the
 * numeric id inside the payload; the row key is its decimal text.
 */
class AssistantMemoryRowDao(private val db: SQLiteDatabase) {

    data class Row(
        val id: String,
        val sortOrder: Int,
        val assistantId: String,
        val payload: String,
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        companion object {
            /** `assistant_id` is NOT NULL upstream; an absent owner stores "". */
            fun fromPayload(
                id: String,
                payload: String,
                sortOrder: Int,
                updatedAt: Long = System.currentTimeMillis(),
            ): Row {
                val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                val assistantId = (obj?.get("assistantId") as? JsonPrimitive)?.content.orEmpty()
                return Row(
                    id = id,
                    sortOrder = sortOrder,
                    assistantId = assistantId,
                    payload = payload,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    fun getAll(): List<Row> {
        db.query(
            TABLE, null, null, null, null, null, "sort_order ASC, id ASC",
        ).use { cursor ->
            val out = ArrayList<Row>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.toRow())
            return out
        }
    }

    /** Replaces the whole table with [rows] (see [MemoryEntryRowDao.replaceAll]). */
    fun replaceAll(rows: List<Row>): Int {
        db.delete(TABLE, null, null)
        for (row in rows) {
            val values = ContentValues().apply {
                put("id", row.id)
                put("sort_order", row.sortOrder)
                put("assistant_id", row.assistantId)
                put("payload", row.payload)
                put("updated_at", row.updatedAt)
            }
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return rows.size
    }

    private fun Cursor.toRow(): Row = Row(
        id = getString(getColumnIndexOrThrow("id")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        assistantId = getString(getColumnIndexOrThrow("assistant_id")),
        payload = getString(getColumnIndexOrThrow("payload")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    companion object {
        const val TABLE = "assistant_memory_rows"
    }
}
