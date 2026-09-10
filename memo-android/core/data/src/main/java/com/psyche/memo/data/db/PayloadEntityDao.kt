package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Generic DAO for JSON-payload entity tables (drift v3 layout). Each row:
 * <[primaryKey]> PK, sort_order INT, payload TEXT, updated_at INT.
 * The primary key column differs per table — provider_rows uses
 * `provider_key`, all others use `id` (verified against the v3 schema).
 * Payload JSON is read/written verbatim (shape-preserving per port rules).
 */
class PayloadEntityDao(
    private val db: SQLiteDatabase,
    private val table: String,
    private val primaryKey: String = "id",
) {
    data class Row(
        val id: String,
        val sortOrder: Int,
        val payload: String,
        val updatedAt: Long,
    )

    fun getAll(): List<Row> {
        db.query(
            table, null, null, null, null, null, "sort_order ASC, ${quoted(primaryKey)} ASC",
        ).use { cursor ->
            val out = ArrayList<Row>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.toRow())
            return out
        }
    }

    fun get(id: String): Row? {
        db.query(table, null, "$primaryKey = ?", arrayOf(id), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toRow() else null
        }
    }

    fun upsert(id: String, payload: String, sortOrder: Int, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put(primaryKey, id)
            put("sort_order", sortOrder)
            put("payload", payload)
            put("updated_at", now)
        }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String) {
        db.delete(table, "$primaryKey = ?", arrayOf(id))
    }

    /**
     * Replaces the whole table with [rows] and returns how many were written.
     *
     * Used by backup restore: the archive is the complete truth for a table, so
     * rows absent from it must disappear rather than linger as ghosts. Callers
     * are expected to be inside a transaction.
     */
    fun replaceAll(rows: List<Row>): Int {
        db.delete(table, null, null)
        val now = System.currentTimeMillis()
        for (row in rows) {
            val values = ContentValues().apply {
                put(primaryKey, row.id)
                put("sort_order", row.sortOrder)
                put("payload", row.payload)
                put("updated_at", if (row.updatedAt != 0L) row.updatedAt else now)
            }
            db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return rows.size
    }

    /** Next sort_order (max + 1, 0 when empty) — matches drift semantics. */
    fun nextSortOrder(): Int {
        db.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM \"$table\"", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun quoted(column: String): String = "\"$column\""

    private fun Cursor.toRow(): Row = Row(
        id = getString(getColumnIndexOrThrow(primaryKey)),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        payload = getString(getColumnIndexOrThrow("payload")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )
}
