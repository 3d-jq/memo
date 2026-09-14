package com.psyche.memo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * drift v3 的通用扩展实体表 `extension_entity_rows`：
 * `(kind, id)` 复合主键 + `sort_order` + `owner_id` + `payload` + `updated_at`。
 *
 * 与 [PayloadEntityDao] 的区别：那张表是「一表一类实体」（表名即类型），这张是
 * 「一表多种 kind」，所以每个查询都要按 kind 收窄。用于 RikkaHub 有独立 Room 表、
 * 而 Memo 的 schema 是 drift 生成（不能加表）的那些实体 —— 目前是工作区
 * （`kind = "workspace"`）。
 */
class ExtensionEntityDao(
    private val db: SQLiteDatabase,
    private val kind: String,
) {
    data class Row(
        val id: String,
        val sortOrder: Int,
        val ownerId: String?,
        val payload: String,
        val updatedAt: Long,
    )

    fun getAll(): List<Row> {
        db.query(
            TABLE, null, "kind = ?", arrayOf(kind), null, null, "sort_order ASC, id ASC",
        ).use { cursor ->
            val out = ArrayList<Row>(cursor.count)
            while (cursor.moveToNext()) out.add(cursor.toRow())
            return out
        }
    }

    fun get(id: String): Row? {
        db.query(
            TABLE, null, "kind = ? AND id = ?", arrayOf(kind, id), null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toRow() else null
        }
    }

    fun upsert(id: String, payload: String, sortOrder: Int, ownerId: String? = null, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("kind", kind)
            put("id", id)
            put("sort_order", sortOrder)
            put("owner_id", ownerId)
            put("payload", payload)
            put("updated_at", now)
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String) {
        db.delete(TABLE, "kind = ? AND id = ?", arrayOf(kind, id))
    }

    /** 该 kind 下的下一个 sort_order（空表为 0）—— 与 drift 语义一致。 */
    fun nextSortOrder(): Int {
        db.rawQuery(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM \"$TABLE\" WHERE kind = ?",
            arrayOf(kind),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun Cursor.toRow(): Row = Row(
        id = getString(getColumnIndexOrThrow("id")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        ownerId = if (isNull(getColumnIndexOrThrow("owner_id"))) null else getString(getColumnIndexOrThrow("owner_id")),
        payload = getString(getColumnIndexOrThrow("payload")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    companion object {
        const val TABLE = "extension_entity_rows"
    }
}
