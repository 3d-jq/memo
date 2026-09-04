package com.psyche.memo.data.db

import android.database.sqlite.SQLiteDatabase

object SchemaVerifier {

    /** Returns human-readable violations; empty list means the DB matches v3. */
    fun check(db: SQLiteDatabase): List<String> {
        val violations = ArrayList<String>()

        val version = db.version
        if (version != MemoSchema.DB_VERSION) {
            violations.add("user_version=$version expected ${MemoSchema.DB_VERSION}")
        }

        var tables = -1
        var indexes = -1
        db.rawQuery(
            "SELECT type, COUNT(*) FROM sqlite_master WHERE type IN ('table','index')" +
                " AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' GROUP BY type",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.getString(0)) {
                    "table" -> tables = cursor.getInt(1)
                    "index" -> indexes = cursor.getInt(1)
                }
            }
        }
        if (tables != MemoSchema.EXPECTED_TABLES) {
            violations.add("tables=$tables expected ${MemoSchema.EXPECTED_TABLES}")
        }
        if (indexes != MemoSchema.EXPECTED_INDEXES) {
            violations.add("indexes=$indexes expected ${MemoSchema.EXPECTED_INDEXES}")
        }

        // PRAGMA foreign_keys is connection-local: a fresh verifier connection
        // always reports 0 even when onConfigure enabled it, so checking it
        // here would fail on every pass. Verify the schema *contract* instead —
        // the v3 export declares FKs (conversation_rows has one), and
        // MemoDatabase.onConfigure turns the pragma on for app connections.
        db.rawQuery("PRAGMA foreign_key_list(conversation_rows)", null).use { cursor ->
            if (cursor.count == 0) {
                violations.add("conversation_rows has no declared foreign keys — schema mismatch")
            }
        }
        return violations
    }

    fun verify(db: SQLiteDatabase): Boolean = check(db).isEmpty()
}
