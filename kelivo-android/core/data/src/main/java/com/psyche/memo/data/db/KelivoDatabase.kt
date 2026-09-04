package com.psyche.memo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object MemoSchema {
    const val DB_NAME = "memo.db"
    const val DB_VERSION = 3
    const val SCHEMA_ASSET = "memo_schema_v3.sql"
    const val EXPECTED_TABLES = 29
    const val EXPECTED_INDEXES = 17
}

/**
 * Drift-compatible v3 database. DDL comes verbatim from the drift schema export
 * (tools/drift_schema_to_sql.py -> assets/memo_schema_v3.sql), so a memo.db
 * produced by the original app can be opened in place.
 */
class MemoDatabase(context: Context) :
    SQLiteOpenHelper(context, MemoSchema.DB_NAME, null, MemoSchema.DB_VERSION) {

    private val appContext: Context = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            for (statement in loadSchemaStatements(appContext)) {
                db.execSQL(statement)
            }
            db.version = MemoSchema.DB_VERSION
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Schema migrations land at P4 (backup v2 restores). Until then, upgrading
     * recreates the schema, which is safe because v3 is the only released version.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        dropAll(db)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        dropAll(db)
        onCreate(db)
    }
}

fun loadSchemaStatements(context: Context): List<String> {
    val reader = context.assets.open(MemoSchema.SCHEMA_ASSET).bufferedReader(Charsets.UTF_8)
    val statements = ArrayList<String>()
    val pending = StringBuilder()
    for (line in reader.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
        pending.append(trimmed).append(' ')
        if (trimmed.endsWith(";")) {
            statements.add(pending.toString().trim().removeSuffix(";"))
            pending.setLength(0)
        }
    }
    if (pending.isNotEmpty()) {
        throw IllegalStateException("schema asset ends mid-statement")
    }
    return statements
}

fun dropAll(db: SQLiteDatabase) {
    db.execSQL("PRAGMA foreign_keys=OFF")
    val names = ArrayList<String>()
    db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%'",
        null,
    ).use { cursor ->
        while (cursor.moveToNext()) names.add(cursor.getString(0))
    }
    for (name in names) {
        db.execSQL("DROP TABLE IF EXISTS \"$name\"")
    }
    db.execSQL("PRAGMA foreign_keys=ON")
}
