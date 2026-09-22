package com.psyche.memo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object MemoSchema {
    const val DB_NAME = "memo.db"
    const val DB_VERSION = 3
    const val SCHEMA_ASSET = "memo_schema_v3.sql"
    const val EXPECTED_TABLES = 29

    /** drift 导出里的索引数（`memo_schema_v3.sql`，与上游逐字一致）。 */
    const val EXPECTED_INDEXES = 17

    /**
     * 本工程**运行时自建**的索引（不在 drift 导出里，所以上面那个计数不含它）。
     *
     * 为什么自建而不是改 schema：DDL 是生成物且门禁校验零 diff，改生成物等于谎报"与上游一致"。
     * `message_rows(timestamp)` 是全表按时间排序/取尾窗要用的，上游没有这条。
     * 见 PORTING §5.41。
     */
    const val INDEX_MESSAGE_TIMESTAMP = "idx_message_rows_timestamp"
}

/**
 * Drift-compatible v3 database. DDL comes verbatim from the drift schema export
 * (tools/drift_schema_to_sql.py -> assets/memo_schema_v3.sql), so a memo.db
 * produced by the original app can be opened in place.
 */
class MemoDatabase(context: Context) :
    SQLiteOpenHelper(context, MemoSchema.DB_NAME, null, MemoSchema.DB_VERSION) {

    private val appContext: Context = context.applicationContext

    // WAL 暂不开：数据安全那半已经审过没问题（快照走 `VACUUM INTO`、回退分支先
    // `wal_checkpoint(TRUNCATE)`、恢复显式删 `-journal/-wal/-shm`），但**变更判据**那半不行 ——
    // 本机副本的 `DatabaseChangeFingerprint` 同时看主文件与 `-wal` 的大小/时间，而 checkpoint /
    // 连接关闭会让 `-wal` 侧车出现或消失，判据于是认为"变了"，每次启动可能白存一份副本
    // （2026-09-21 由 `LocalSnapshotServiceTest` 的 UNCHANGED 用例抓到）。
    // 要开先把指纹换成 WAL 稳定的规则（合计字节数或语义计数器）并补测试，见 PORTING §5.41。

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
            createRuntimeIndexes(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 老库（本版本之前建的）在每次打开时补一次；`IF NOT EXISTS` 让它在建好之后只是一个空操作。
     * 不放进 `onUpgrade`：那会把 DB_VERSION 顶上去，而 schema 本身没变。
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) createRuntimeIndexes(db)
    }

    private fun createRuntimeIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS ${MemoSchema.INDEX_MESSAGE_TIMESTAMP}" +
                " ON message_rows(timestamp)",
        )
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
