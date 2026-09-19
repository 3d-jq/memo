package com.psyche.memo.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.MemoSchema
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.db.SchemaMigrations
import com.psyche.memo.data.settings.PreferenceRepository
import java.io.File

/**
 * Assembles a Memo backup archive from the current device state.
 *
 * This is the Android counterpart of `DataSync._prepareBackupArchive`
 * (`data_sync.dart` L498-632): it gathers the three payload sources, hands them
 * to [BackupArchiveCodec.pack] and verifies the result before returning.
 *
 * The three sources, and why each is staged to disk rather than streamed:
 *
 *  1. **settings.json** — the 13 business entity tables plus preference rows,
 *     serialised by [BackupSettingsSnapshot].
 *  2. **database/kelivo.db** — a consistent snapshot of the live database.
 *     Restoring a file that is still being written would be a corruption bug,
 *     so the snapshot is taken with SQLite's own `VACUUM INTO`, which also
 *     rewrites the file without a journal to replay.
 *  3. **asset directories** — `upload/`, `avatars/`, `images/`, `fonts/`.
 *
 * Everything is staged under a working directory that is always cleaned up,
 * even on failure, so a failed backup cannot leave hundreds of megabytes
 * behind.
 */
internal class BackupSnapshotBuilder(
    private val context: Context,
    private val database: MemoDatabase,
    private val preferenceRepository: PreferenceRepository,
    private val appVersion: String,
) {

    data class Request(
        val includeChats: Boolean = true,
        val includeFiles: Boolean = true,
    )

    data class Result(
        val archive: File,
        val entries: Map<String, EntryMetadata>,
        val manifest: BackupManifest,
        val workingDirectory: File,
    )

    /**
     * Builds the archive at [outputFile].
     *
     * @param onProgress receives `(phase, processedBytes, totalBytes)`; total is
     *   -1 while the archive size is not yet known.
     * @param isCancelled polled between steps so a cancelled backup stops
     *   promptly instead of running to completion.
     */
    fun build(
        outputFile: File,
        request: Request,
        onProgress: (phase: String, processed: Long, total: Long) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val working = File(context.cacheDir, "memo_backup_${System.currentTimeMillis()}")
        working.mkdirs()
        try {
            check(!isCancelled()) { "备份已取消" }

            onProgress(PHASE_PREPARING, 0, -1)

            // ── settings.json ───────────────────────────────────────────────
            val snapshot = BackupSettingsSnapshot.from(preferenceRepository)
            val export = snapshot.export { table, payloadKey ->
                PayloadEntityDao(database.readableDatabase, table, primaryKey = payloadKey).getAll()
            }
            val settingsFile = File(working, "_bk_settings.json")
            settingsFile.writeText(renderSettingsJson(export.settings), Charsets.UTF_8)

            // ── database snapshot ───────────────────────────────────────────
            var databaseFile: File? = null
            var databaseInfo: DatabaseInfo? = null
            if (request.includeChats) {
                check(!isCancelled()) { "备份已取消" }
                onProgress(PHASE_SNAPSHOTTING_DATABASE, 0, -1)
                databaseFile = File(working, "_bk_memo.db")
                snapshotDatabase(databaseFile)
                databaseInfo = DatabaseInfo(
                    entry = BackupManifestCodec.ENTRY_DATABASE,
                    schemaVersion = MemoSchema.DB_VERSION,
                    // Upstream writes the SchemaMigrations constant, not the
                    // current version: a purely additive future schema keeps
                    // older builds able to read our backups.
                    minimumReadableSchemaVersion = SchemaMigrations.MINIMUM_READABLE_SCHEMA_VERSION,
                    conversationCount = countRows(database.readableDatabase, "conversation_rows"),
                    messageCount = countRows(database.readableDatabase, "message_rows"),
                )
            }

            // ── assets ──────────────────────────────────────────────────────
            val assetDirs = if (request.includeFiles) {
                BackupArchiveCodec.ASSET_ROOTS.associateWith { File(context.filesDir, it) }
            } else {
                emptyMap()
            }

            check(!isCancelled()) { "备份已取消" }

            // ── pack ────────────────────────────────────────────────────────
            val total = estimateTotalBytes(settingsFile, databaseFile, assetDirs)
            var packed = 0L
            val result = BackupArchiveCodec.pack(
                archive = outputFile,
                settingsFile = settingsFile,
                databaseFile = databaseFile,
                assetDirs = assetDirs,
                includeFiles = request.includeFiles,
                manifestFor = { entries ->
                    BackupManifestCodec.encode(
                        entries = entries,
                        database = databaseInfo,
                        includeChats = request.includeChats,
                        includeFiles = request.includeFiles,
                        appVersion = appVersion,
                        createdAtUtc = isoUtcNow(),
                        businessEntityRowIds = export.entityRowIds,
                    )
                },
                onProgress = { progress ->
                    packed = (progress as BackupArchiveCodec.Progress.Entry).bytes
                    onProgress(PHASE_PACKING, packed, total.takeIf { it > 0 } ?: -1)
                },
            )

            check(!isCancelled()) { "备份已取消" }

            // ── verify ──────────────────────────────────────────────────────
            onProgress(PHASE_VERIFYING, 0, -1)
            BackupArchiveCodec.verifyPacked(outputFile, result.entries)

            val manifest = BackupManifestCodec.decode(
                BackupArchiveCodec.readEntry(outputFile, BackupManifestCodec.ENTRY_MANIFEST)!!
                    .toString(Charsets.UTF_8),
            )
            return Result(
                archive = outputFile,
                entries = result.entries,
                manifest = manifest,
                workingDirectory = working,
            )
        } catch (t: Throwable) {
            // A half-written archive must never be mistaken for a real backup.
            runCatching { outputFile.delete() }
            throw t
        } finally {
            runCatching { working.deleteRecursively() }
        }
    }

    /**
     * Writes a consistent copy of the live database to [target].
     *
     * `VACUUM INTO` is preferred: it produces a defragmented file with no
     * journal to replay, so the result is a valid database on its own. It
     * requires SQLite 3.27 (Android 11+). On older devices the WAL is
     * checkpointed and the file copied, which is the same guarantee the Flutter
     * implementation relied on.
     */
    private fun snapshotDatabase(target: File) {
        target.delete()
        val db = database.writableDatabase
        val vacuumSucceeded = runCatching {
            db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
        }.isSuccess

        if (!vacuumSucceeded) {
            // Fold the write-ahead log back into the main file so the copy is
            // self-contained, then copy it.
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
            val source = File(db.path)
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
        }
        check(target.isFile && target.length() > 0) { "数据库快照失败" }
    }

    private fun countRows(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun estimateTotalBytes(
        settingsFile: File,
        databaseFile: File?,
        assetDirs: Map<String, File>,
    ): Long {
        var total = settingsFile.length()
        databaseFile?.let { total += it.length() }
        for (dir in assetDirs.values) total += directorySize(dir)
        return total
    }

    private fun directorySize(dir: File): Long {
        if (!dir.isDirectory) return 0
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /** Renders the exported settings map as the flat JSON object the router expects. */
    private fun renderSettingsJson(settings: Map<String, kotlinx.serialization.json.JsonElement>): String =
        kotlinx.serialization.json.JsonObject(settings).toString()

    companion object {
        const val PHASE_PREPARING = "preparing"
        const val PHASE_SNAPSHOTTING_DATABASE = "snapshotting_database"
        const val PHASE_PACKING = "packing"
        const val PHASE_VERIFYING = "verifying"
        // Restore phases live with the restorer.
        const val PHASE_EXTRACTING = "extracting"
        const val PHASE_APPLYING = "applying"

        internal fun isoUtcNow(): String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
    }
}
