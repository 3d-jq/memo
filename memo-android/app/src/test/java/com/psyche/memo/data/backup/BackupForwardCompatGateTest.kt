package com.psyche.memo.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.SchemaMigrations
import com.psyche.memo.data.db.loadSchemaStatements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The restore-side forward-compatibility gate (`_preflightVersionedBackup`'s
 * verdict section): unknown entries are fatal unless the manifest declares a
 * newer build, a newer schema without a declaration needs the user's consent,
 * and an unreadable declaration is refused outright.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupForwardCompatGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainerImpl

    private fun newRestorer() = BackupRestorer(context, container.database, container.preferenceRepository)

    private fun settingsFile(dir: File): File =
        File(dir, "settings.json").apply { writeText("{}") }

    /** Builds a real v3 database carrying one unknown table, at user_version 4. */
    private fun newerSchemaDatabase(dir: File): File {
        val file = File(dir, "kelivo.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (statement in loadSchemaStatements(context)) db.execSQL(statement)
            db.execSQL("CREATE TABLE future_rows (\"id\" TEXT NOT NULL PRIMARY KEY, \"payload\" TEXT NOT NULL)")
            db.version = 4
        }
        return file
    }

    /** Packs an archive whose manifest is written by the caller. */
    private fun packArchive(
        working: File,
        manifestFor: (Map<String, EntryMetadata>) -> String,
    ): File {
        val settings = settingsFile(working)
        val archive = File(working, "backup.zip")
        BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor,
        )
        return archive
    }

    private fun entryJson(entries: Map<String, EntryMetadata>): String =
        entries.entries.joinToString(",") { (name, meta) ->
            "\"$name\":{\"bytes\":${meta.bytes},\"sha256\":\"${meta.sha256}\"}"
        }

    private fun baseManifest(
        entries: Map<String, EntryMetadata>,
        payloadKind: String,
        includeChats: Boolean,
        databaseBlock: String = "",
    ): String = "{\"format\":\"kelivo-backup\",\"formatVersion\":2," +
        "\"minimumReadableFormatVersion\":2," +
        "\"payloadKind\":\"$payloadKind\",\"createdAtUtc\":\"2026-09-19T00:00:00.000Z\"," +
        "\"appVersion\":\"test\",\"includeChats\":$includeChats,\"includeFiles\":false," +
        "\"secretsIncluded\":true$databaseBlock,\"entries\":{${entryJson(entries)}}}"

    private fun databaseBlock(schemaVersion: Int, declared: Int?): String =
        ",\"database\":{\"entry\":\"database/kelivo.db\",\"schemaVersion\":$schemaVersion" +
            (declared?.let { ",\"minimumReadableSchemaVersion\":$it" } ?: "") +
            ",\"conversationCount\":0,\"messageCount\":0}"

    private fun assertMessage(block: () -> Unit, contains: String) {
        var message: String? = null
        try {
            block()
        } catch (e: IllegalStateException) {
            message = e.message
        }
        assertTrue(
            "expected an IllegalStateException containing \"$contains\", got: $message",
            message?.contains(contains) == true,
        )
    }

    @Test
    fun `unknown entries from a same-format archive are refused`() {
        container = AppContainerImpl(context)
        val working = File(context.cacheDir, "gate_a_${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = packArchive(working) { entries ->
                // Claim one entry the zip does not contain: the unknown-entry
                // verdict fires before anything is extracted.
                baseManifest(
                    entries = entries + ("future/extra.bin" to EntryMetadata(1L, "0".repeat(64))),
                    payloadKind = "settings-only",
                    includeChats = false,
                )
            }
            assertMessage({ newRestorer().restore(archive, RestoreMode.MERGE) }, "不认识的条目")
        } finally {
            working.deleteRecursively()
        }
    }

    @Test
    fun `a newer undeclared schema needs the user's consent`() {
        container = AppContainerImpl(context)
        val working = File(context.cacheDir, "gate_b_${System.nanoTime()}").apply { mkdirs() }
        try {
            val dbFile = newerSchemaDatabase(working)
            val archive = File(working, "backup.zip")
            BackupArchiveCodec.pack(
                archive = archive,
                settingsFile = settingsFile(working),
                databaseFile = dbFile,
                assetDirs = emptyMap(),
                includeFiles = false,
                manifestFor = { entries ->
                    baseManifest(entries, "sqlite", includeChats = true, databaseBlock = databaseBlock(4, null))
                },
            )
            assertMessage({
                newRestorer().restore(archive, RestoreMode.MERGE, allowUnverifiedForwardCompatible = false)
            }, "未声明")
        } finally {
            working.deleteRecursively()
        }
    }

    @Test
    fun `consented forward-undeclared restore normalizes and succeeds`() {
        container = AppContainerImpl(context)
        val working = File(context.cacheDir, "gate_c_${System.nanoTime()}").apply { mkdirs() }
        try {
            val dbFile = newerSchemaDatabase(working)
            val archive = File(working, "backup.zip")
            BackupArchiveCodec.pack(
                archive = archive,
                settingsFile = settingsFile(working),
                databaseFile = dbFile,
                assetDirs = emptyMap(),
                includeFiles = false,
                manifestFor = { entries ->
                    baseManifest(entries, "sqlite", includeChats = true, databaseBlock = databaseBlock(4, null))
                },
            )
            val report = newRestorer().restore(
                archive,
                RestoreMode.MERGE,
                allowUnverifiedForwardCompatible = true,
            )
            assertTrue(report.databaseRestored)
            assertEquals(0, report.mergeReport?.importedConversations ?: 0)
        } finally {
            working.deleteRecursively()
        }
    }

    @Test
    fun `a newer schema demanding a newer build is refused outright`() {
        container = AppContainerImpl(context)
        val working = File(context.cacheDir, "gate_d_${System.nanoTime()}").apply { mkdirs() }
        try {
            val dbFile = newerSchemaDatabase(working)
            val archive = File(working, "backup.zip")
            BackupArchiveCodec.pack(
                archive = archive,
                settingsFile = settingsFile(working),
                databaseFile = dbFile,
                assetDirs = emptyMap(),
                includeFiles = false,
                manifestFor = { entries ->
                    // Schema 5 declaring "readable from 4" — a declaration in
                    // range (1..5) but above this build's 3 → UNREADABLE.
                    baseManifest(entries, "sqlite", includeChats = true, databaseBlock = databaseBlock(5, 4))
                },
            )
            assertMessage({
                newRestorer().restore(archive, RestoreMode.MERGE, allowUnverifiedForwardCompatible = true)
            }, "无法读取")
        } finally {
            working.deleteRecursively()
        }
    }

    @Test
    fun `an older schema snapshot migrates forward before the merge`() {
        container = AppContainerImpl(context)
        val working = File(context.cacheDir, "gate_e_${System.nanoTime()}").apply { mkdirs() }
        try {
            // A real v2 snapshot: every table at full v3 shape EXCEPT the three
            // the migration touches, which sit at their v1 columns; stamped at
            // user_version 2. The restorer must upgrade it in place BEFORE the
            // merge, or the merger reads a schema it doesn't describe.
            val dbFile = File(working, "kelivo.db")
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                // The v3 asset minus the columns the migrations add, for the
                // three tables they touch: the exact v2 shape, indexes
                // included.
                for (statement in loadSchemaStatements(context)) {
                    val shaped = when {
                        statement.startsWith("CREATE TABLE IF NOT EXISTS \"conversation_rows\"") ->
                            statement
                                .replace("\"chat_model_provider\" TEXT NULL, ", "")
                                .replace("\"chat_model_id\" TEXT NULL, ", "")
                                .replace("\"extras_json\" TEXT NOT NULL DEFAULT '{}', ", "")
                        statement.startsWith("CREATE TABLE IF NOT EXISTS \"message_rows\"") ->
                            statement
                                .replace("\"updated_at\" INTEGER NULL, \"sender_id\" TEXT NULL, ", "")
                                .replace("\"extras_json\" TEXT NOT NULL DEFAULT '{}', ", "")
                        statement.startsWith("CREATE TABLE IF NOT EXISTS \"asset_rows\"") ->
                            statement.replace("\"extras_json\" TEXT NOT NULL DEFAULT '{}', ", "")
                        else -> statement
                    }
                    if (shaped === statement &&
                        (statement.startsWith("CREATE TABLE IF NOT EXISTS \"conversation_rows\"") ||
                            statement.startsWith("CREATE TABLE IF NOT EXISTS \"message_rows\"") ||
                            statement.startsWith("CREATE TABLE IF NOT EXISTS \"asset_rows\""))
                    ) {
                        continue
                    }
                    db.execSQL(shaped)
                }
                db.version = 2
            }
            val archive = File(working, "backup.zip")
            BackupArchiveCodec.pack(
                archive = archive,
                settingsFile = settingsFile(working),
                databaseFile = dbFile,
                assetDirs = emptyMap(),
                includeFiles = false,
                manifestFor = { entries ->
                    baseManifest(entries, "sqlite", includeChats = true, databaseBlock = databaseBlock(2, 2))
                },
            )
            val report = newRestorer().restore(archive, RestoreMode.MERGE)
            assertTrue(report.databaseRestored)
            assertEquals(0, report.mergeReport?.importedConversations ?: 0)
        } finally {
            working.deleteRecursively()
        }
    }

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(1))
        }
        return names
    }
}
