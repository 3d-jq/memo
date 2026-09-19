package com.psyche.memo.data.db

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * How a backup's declared schema relates to what this build can read — port of
 * `core/database/schema_migrations.dart` (`BackupSchemaVerdict`) and the parts
 * of `ChatDatabaseRepository.prepareSnapshotForRestore` /
 * `normalizeForwardCompatibleSnapshot` that operate on a snapshot file.
 *
 * The two version axes move independently: [MemoSchema.DB_VERSION] governs the
 * SQLite payload while `BackupManifestCodec.FORMAT_VERSION` governs the
 * archive (entry names + manifest fields). A build can add an ignorable
 * directory without touching the schema, and a settings-only backup has no
 * schema at all.
 */
enum class BackupSchemaVerdict {
    /** Written by this exact schema; restore as-is. */
    CURRENT,

    /** Written by an older published schema; migrate it forward. */
    NEEDS_UPGRADE,

    /** Newer schema that declares this build can still read it. Restore after
     *  stripping what this build does not know. */
    FORWARD_COMPATIBLE,

    /** Newer schema that made no compatibility declaration, so whether it is
     *  readable is unknown. Restorable only with the user's informed consent. */
    FORWARD_UNDECLARED,

    /** Cannot be read: either a newer schema that declares it needs a newer
     *  build, or a version this build has never heard of. */
    UNREADABLE,
}

object SchemaMigrations {

    /** `AppDatabase.currentSchemaVersion`. */
    const val CURRENT_SCHEMA_VERSION = MemoSchema.DB_VERSION

    /**
     * The oldest schema that can read a backup written by this build
     * (`SchemaMigrations.minimumReadableSchemaVersion`). Forward compatibility
     * only started at schema 2, so it can never usefully be lower. Raise to
     * [CURRENT_SCHEMA_VERSION] in any release whose schema change is NOT
     * purely additive.
     */
    const val MINIMUM_READABLE_SCHEMA_VERSION = 2

    /** `AppDatabase.publishedSchemaVersions` — schemas this app has shipped. */
    val PUBLISHED_SCHEMA_VERSIONS = setOf(1, 2, 3)

    /** `SchemaMigrations.classifyBackup` — judges a backup from its manifest. */
    fun classifyBackup(schemaVersion: Int, declaredMinimumReadable: Int?): BackupSchemaVerdict {
        val current = CURRENT_SCHEMA_VERSION
        if (schemaVersion == current) return BackupSchemaVerdict.CURRENT
        if (schemaVersion < current) {
            return if (isPublished(schemaVersion)) BackupSchemaVerdict.NEEDS_UPGRADE
            // An unpublished version below ours never shipped; refuse rather
            // than guess what it is.
            else BackupSchemaVerdict.UNREADABLE
        }
        if (declaredMinimumReadable == null) return BackupSchemaVerdict.FORWARD_UNDECLARED
        return if (declaredMinimumReadable <= current) BackupSchemaVerdict.FORWARD_COMPATIBLE
        else BackupSchemaVerdict.UNREADABLE
    }

    /** Whether [version] is a schema this app has ever shipped. */
    fun isPublished(version: Int): Boolean = version in PUBLISHED_SCHEMA_VERSIONS

    /** Whether a file at [version] can and must be upgraded before use. */
    fun needsUpgrade(version: Int): Boolean =
        isPublished(version) && version < CURRENT_SCHEMA_VERSION

    /**
     * Reads `PRAGMA user_version` without otherwise touching [file]
     * (`SchemaMigrations.readSchemaVersion`). Read-only open: no journal
     * sidecar is created.
     */
    fun readSchemaVersion(file: File): Int {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            return db.version
        } finally {
            db.close()
        }
    }

    /**
     * Upgrades [file] to [CURRENT_SCHEMA_VERSION] in place
     * (`SchemaMigrations.upgradeFileInPlace`). The steps are the drift
     * migrations verbatim (`app_database.dart` from1To2 / from2To3):
     *
     *  - 1→2: `conversation_rows.chat_model_provider` / `chat_model_id`
     *    (nullable TEXT).
     *  - 2→3: `conversation_rows.extras_json`; `message_rows.updated_at` /
     *    `sender_id` / `extras_json`; `asset_rows.extras_json`; the
     *    `tombstone_rows` / `extension_entity_rows` tables and their index.
     *    Purely additive; `message_rows.updated_at` is nullable by design so
     *    no backfill is needed (null reads as "= timestamp").
     *
     * The DDL is the same text `assets/memo_schema_v3.sql` carries for these
     * objects (drift v3), so an upgraded file is byte-shape identical to a
     * freshly created one column-for-column.
     */
    fun upgradeFileInPlace(file: File) {
        val installed = readSchemaVersion(file)
        if (installed == CURRENT_SCHEMA_VERSION) return
        check(needsUpgrade(installed)) { "数据库 schema 版本无法升级（$installed）" }

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.beginTransaction()
            try {
                if (installed < 2) {
                    db.execSQL("ALTER TABLE \"conversation_rows\" ADD COLUMN \"chat_model_provider\" TEXT NULL")
                    db.execSQL("ALTER TABLE \"conversation_rows\" ADD COLUMN \"chat_model_id\" TEXT NULL")
                }
                if (installed < 3) {
                    db.execSQL("ALTER TABLE \"conversation_rows\" ADD COLUMN \"extras_json\" TEXT NOT NULL DEFAULT '{}'")
                    db.execSQL("ALTER TABLE \"message_rows\" ADD COLUMN \"updated_at\" INTEGER NULL")
                    db.execSQL("ALTER TABLE \"message_rows\" ADD COLUMN \"sender_id\" TEXT NULL")
                    db.execSQL("ALTER TABLE \"message_rows\" ADD COLUMN \"extras_json\" TEXT NOT NULL DEFAULT '{}'")
                    db.execSQL("ALTER TABLE \"asset_rows\" ADD COLUMN \"extras_json\" TEXT NOT NULL DEFAULT '{}'")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS \"tombstone_rows\" (\"scope\" TEXT NOT NULL CHECK(\"scope\" IS NOT ''), " +
                            "\"entity_id\" TEXT NOT NULL CHECK(\"entity_id\" IS NOT ''), \"deleted_at\" INTEGER NOT NULL, " +
                            "\"payload\" TEXT NOT NULL DEFAULT '{}', PRIMARY KEY (\"scope\", \"entity_id\"))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS \"extension_entity_rows\" (\"kind\" TEXT NOT NULL CHECK(\"kind\" IS NOT ''), " +
                            "\"id\" TEXT NOT NULL, \"sort_order\" INTEGER NOT NULL CHECK(\"sort_order\" >= 0), " +
                            "\"owner_id\" TEXT NULL, \"payload\" TEXT NOT NULL, \"updated_at\" INTEGER NOT NULL, " +
                            "PRIMARY KEY (\"kind\", \"id\"))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_extension_entities_kind_order ON extension_entity_rows (kind, sort_order)")
                }
                db.version = CURRENT_SCHEMA_VERSION
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Fold the upgrade back into the main file so the caller sees a
            // self-contained database, and a snapshot keeps no sidecars.
            runCatching {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        } finally {
            db.close()
        }
        check(readSchemaVersion(file) == CURRENT_SCHEMA_VERSION) { "数据库 schema 版本升级未生效" }
    }

    /**
     * Strips what a NEWER schema put into [file] that this build does not know
     * (`normalizeForwardCompatibleSnapshot`): unknown tables are dropped and
     * `user_version` is folded back to [CURRENT_SCHEMA_VERSION] so opening the
     * file does not trip a downgrade.
     *
     * **Scope deviation from upstream** (documented in PORTING §5.10.4): the
     * original additionally rebuilds tables to drop unknown *columns*. An
     * unknown column is harmless here — every Android statement names its
     * columns explicitly and there is no drift schema validator to satisfy —
     * and dropping one needs a table rebuild on pre-3.35 SQLite, so only the
     * table-level strip is ported.
     */
    fun normalizeForwardCompatible(file: File) {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            // Foreign keys stay off for the rewrite: dropping an unknown table
            // can transiently orphan rows in another unknown table dropped
            // later.
            db.execSQL("PRAGMA foreign_keys = OFF;")
            db.beginTransaction()
            try {
                val present = mutableListOf<String>()
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (!name.startsWith("sqlite_")) present.add(name)
                    }
                }
                for (table in present) {
                    if (table !in KNOWN_TABLES) db.execSQL("DROP TABLE IF EXISTS \"$table\"")
                }
                db.version = CURRENT_SCHEMA_VERSION
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            runCatching {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        } finally {
            db.close()
        }
    }

    /**
     * Every table `assets/memo_schema_v3.sql` declares. A table missing from
     * this set would be DROPPED by [normalizeForwardCompatible], so
     * [SchemaMigrationsTest] asserts it stays in lockstep with the asset SQL.
     */
    val KNOWN_TABLES = setOf(
        "asset_gc_rows",
        "asset_reference_dirty_rows",
        "asset_rows",
        "assistant_memory_rows",
        "assistant_rows",
        "assistant_tag_rows",
        "chat_storage_meta_rows",
        "conversation_mcp_server_rows",
        "conversation_rows",
        "extension_entity_rows",
        "gc_audit_rows",
        "generation_run_rows",
        "instruction_injection_rows",
        "mcp_server_rows",
        "memory_entry_rows",
        "message_asset_rows",
        "message_part_rows",
        "message_prompt_rows",
        "message_rows",
        "preference_rows",
        "provider_artifact_rows",
        "provider_group_rows",
        "provider_rows",
        "quick_phrase_rows",
        "search_service_rows",
        "tombstone_rows",
        "tts_service_rows",
        "user_profile_field_rows",
        "world_book_rows",
    )
}
