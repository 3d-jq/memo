package com.psyche.memo.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.db.BackupSchemaVerdict
import com.psyche.memo.data.db.SchemaMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The forward-compatibility gate's schema axis (`schema_migrations.dart`):
 * verdict classification, the in-place 1→2→3 upgrade, and the
 * forward-compatible snapshot normalization. The [KNOWN_TABLES] lock keeps the
 * drop-list in lockstep with the schema asset — a table missing there would be
 * silently DROPPED by [SchemaMigrations.normalizeForwardCompatible].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaMigrationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ── classify matrix ────────────────────────────────────────────────────

    @Test
    fun `classifyBackup follows the upstream verdict matrix`() {
        val current = SchemaMigrations.CURRENT_SCHEMA_VERSION
        assertEquals(BackupSchemaVerdict.CURRENT, SchemaMigrations.classifyBackup(current, null))
        // Older published schemas migrate forward; an unpublished version
        // below ours never shipped, so refuse rather than guess.
        assertEquals(BackupSchemaVerdict.NEEDS_UPGRADE, SchemaMigrations.classifyBackup(2, null))
        assertEquals(BackupSchemaVerdict.NEEDS_UPGRADE, SchemaMigrations.classifyBackup(1, null))
        assertEquals(BackupSchemaVerdict.UNREADABLE, SchemaMigrations.classifyBackup(0, null))
        // Newer schema: the declaration decides.
        assertEquals(
            BackupSchemaVerdict.FORWARD_COMPATIBLE,
            SchemaMigrations.classifyBackup(4, 3),
        )
        assertEquals(
            BackupSchemaVerdict.FORWARD_UNDECLARED,
            SchemaMigrations.classifyBackup(4, null),
        )
        assertEquals(BackupSchemaVerdict.UNREADABLE, SchemaMigrations.classifyBackup(4, 4))
    }

    @Test
    fun `published and upgrade helpers match upstream`() {
        assertEquals(setOf(1, 2, 3), SchemaMigrations.PUBLISHED_SCHEMA_VERSIONS)
        assertTrue(SchemaMigrations.isPublished(1))
        assertFalse(SchemaMigrations.isPublished(4))
        assertTrue(SchemaMigrations.needsUpgrade(2))
        assertFalse(SchemaMigrations.needsUpgrade(3))
        assertFalse(SchemaMigrations.needsUpgrade(4))
    }

    // ── in-place upgrade ───────────────────────────────────────────────────

    /** A v1-shaped subset: only the tables the migration steps touch. */
    private fun createV1Database(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE conversation_rows (\"id\" TEXT NOT NULL PRIMARY KEY, \"title\" TEXT NOT NULL, " +
                "\"created_at\" INTEGER NOT NULL, \"updated_at\" INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE message_rows (\"id\" TEXT NOT NULL PRIMARY KEY, \"conversation_id\" TEXT NOT NULL, " +
                "\"role\" TEXT NOT NULL, \"timestamp\" INTEGER NOT NULL, \"message_order\" INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE asset_rows (\"id\" TEXT NOT NULL PRIMARY KEY, \"path\" TEXT NOT NULL, " +
                "\"created_at\" INTEGER NOT NULL)",
        )
        db.version = 1
        db.close()
    }

    @Test
    fun `upgradeFileInPlace takes a v1 file to the current schema`() {
        val file = File(context.cacheDir, "up_v1_${System.nanoTime()}.db")
        createV1Database(file)
        try {
            SchemaMigrations.upgradeFileInPlace(file)
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(SchemaMigrations.CURRENT_SCHEMA_VERSION, db.version)
                val conversationColumns = columnNames(db, "conversation_rows")
                assertTrue("chat_model_provider" in conversationColumns)
                assertTrue("chat_model_id" in conversationColumns)
                assertTrue("extras_json" in conversationColumns)
                val messageColumns = columnNames(db, "message_rows")
                assertTrue("updated_at" in messageColumns)
                assertTrue("sender_id" in messageColumns)
                assertTrue("extras_json" in messageColumns)
                assertTrue("extras_json" in columnNames(db, "asset_rows"))
                assertTrue(tableNames(db).contains("tombstone_rows"))
                assertTrue(tableNames(db).contains("extension_entity_rows"))
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upgradeFileInPlace takes a v2 file to the current schema`() {
        val file = File(context.cacheDir, "up_v2_${System.nanoTime()}.db")
        createV1Database(file)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE \"conversation_rows\" ADD COLUMN \"chat_model_provider\" TEXT NULL")
            db.execSQL("ALTER TABLE \"conversation_rows\" ADD COLUMN \"chat_model_id\" TEXT NULL")
            db.version = 2
        }
        try {
            SchemaMigrations.upgradeFileInPlace(file)
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(SchemaMigrations.CURRENT_SCHEMA_VERSION, db.version)
                assertFalse("chat_model_provider" !in columnNames(db, "conversation_rows"))
            } finally {
                db.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upgradeFileInPlace refuses an unpublished version`() {
        val file = File(context.cacheDir, "up_bad_${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 99 }
        try {
            var thrown: IllegalStateException? = null
            try {
                SchemaMigrations.upgradeFileInPlace(file)
            } catch (e: IllegalStateException) {
                thrown = e
            }
            assertTrue(thrown != null)
        } finally {
            file.delete()
        }
    }

    // ── forward-compatible normalization ───────────────────────────────────

    @Test
    fun `normalizeForwardCompatible drops unknown tables and folds the version`() {
        val file = File(context.cacheDir, "fwd_${System.nanoTime()}.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE conversation_rows (\"id\" TEXT NOT NULL PRIMARY KEY, \"title\" TEXT NOT NULL)")
        db.execSQL("INSERT INTO conversation_rows (id, title) VALUES ('c1', 'kept')")
        db.execSQL("CREATE TABLE future_rows (\"id\" TEXT NOT NULL PRIMARY KEY, \"payload\" TEXT NOT NULL)")
        db.version = 4
        db.close()
        try {
            SchemaMigrations.normalizeForwardCompatible(file)
            val after = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(SchemaMigrations.CURRENT_SCHEMA_VERSION, after.version)
                val tables = tableNames(after)
                assertTrue("conversation_rows" in tables)
                assertFalse("future_rows" in tables)
                after.rawQuery("SELECT COUNT(*) FROM conversation_rows", null).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(1, cursor.getInt(0))
                }
            } finally {
                after.close()
            }
        } finally {
            file.delete()
        }
    }

    // ── the drop-list stays in lockstep with the schema asset ──────────────

    @Test
    fun `known tables match the schema asset exactly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val declared = mutableSetOf<String>()
        context.assets.open("memo_schema_v3.sql").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val marker = "CREATE TABLE IF NOT EXISTS \""
                val start = line.indexOf(marker)
                if (start >= 0) {
                    val name = line.substring(start + marker.length).substringBefore('"')
                    declared.add(name)
                }
            }
        }
        assertEquals(declared, SchemaMigrations.KNOWN_TABLES)
    }

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(1))
        }
        return names
    }

    private fun tableNames(db: SQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }
}
