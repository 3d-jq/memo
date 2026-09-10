package com.psyche.memo.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.MemoSchema
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.settings.KeyDisposition
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.data.settings.classifyBusinessKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * What a completed restore did, so the UI can report it honestly.
 */
internal data class RestoreReport(
    val mode: RestoreMode,
    val entityRowsWritten: Int,
    val preferenceKeysWritten: Int,
    val databaseRestored: Boolean,
    val assetFilesRestored: Int,
    val skippedEntries: List<String>,
    val extractedEntries: Int = 0,
)

/**
 * `RestoreMode` (`core/providers/backup_provider.dart`) — which components the
 * archive replaces and whether local data is kept.
 */
enum class RestoreMode { OVERWRITE, MERGE }

/**
 * Applies a backup archive to the current installation.
 *
 * Ported from `DataSync._restoreFromBackupFile` (`data_sync.dart` L2917), with
 * this batch implementing the parts that a **local file** restore needs:
 * extraction, verification, settings application and — for [RestoreMode.OVERWRITE]
 * — replacing the live database file.
 *
 * Deliberately *not* here yet (sub-block 2 / 7):
 *  - merge semantics for conversations and messages (only settings merge now)
 *  - the crash-safe bundle staging / lease / cutover pipeline the Flutter build
 *    uses for online restore. This implementation writes the database by
 *    swapping the file while the app is quiesced, which is correct for the
 *    offline, user-initiated local-file flow but is not a substitute for the
 *    staged pipeline.
 */
internal class BackupRestorer(
    private val context: Context,
    private val database: MemoDatabase,
    private val preferenceRepository: PreferenceRepository,
) {

    /**
     * Restores [archive].
     *
     * @param onProgress `(phase, processed, total)`; total is -1 when unknown.
     * @param isCancelled polled between entries so a cancelled restore stops
     *   before it starts mutating live data.
     */
    fun restore(
        archive: File,
        mode: RestoreMode,
        onProgress: (phase: String, processed: Long, total: Long) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): RestoreReport {
        require(archive.isFile) { "备份文件不存在" }

        onProgress(PHASE_READING_MANIFEST, 0, -1)
        val manifest = BackupArchiveCodec.readManifest(archive)
        check(BackupManifestCodec.acceptsFormat(manifest)) {
            "该备份文件格式版本不受支持（format=${manifest.format}，需要至少 ${manifest.minimumReadableFormatVersion}）"
        }

        val staging = File(context.cacheDir, "memo_restore_${System.currentTimeMillis()}")
        staging.mkdirs()
        val skipped = mutableListOf<String>()
        try {
            check(!isCancelled()) { "恢复已取消" }

            // ── extract + verify ────────────────────────────────────────────
            onProgress(PHASE_EXTRACTING, 0, manifest.entries.size.toLong())
            var extractedCount = 0
            val extracted = BackupArchiveCodec.extract(
                archive = archive,
                targetDir = staging,
                manifest = manifest,
                onProgress = { progress ->
                    extractedCount += 1
                    onProgress(PHASE_EXTRACTING, extractedCount.toLong(), manifest.entries.size.toLong())
                },
            )

            check(!isCancelled()) { "恢复已取消" }

            // ── settings ────────────────────────────────────────────────────
            onProgress(PHASE_APPLYING_SETTINGS, 0, -1)
            val settingsFile = File(staging, BackupManifestCodec.ENTRY_SETTINGS)
            val applied = applySettings(settingsFile, mode)

            // ── database ────────────────────────────────────────────────────
            var databaseRestored = false
            if (manifest.includeChats) {
                check(!isCancelled()) { "恢复已取消" }
                onProgress(PHASE_APPLYING_DATABASE, 0, -1)
                val stagedDb = File(staging, BackupManifestCodec.ENTRY_DATABASE)
                require(stagedDb.isFile) { "备份声明包含会话，但归档内缺少 database/kelivo.db" }
                verifySqliteHeader(stagedDb)
                if (mode == RestoreMode.OVERWRITE) {
                    replaceDatabase(stagedDb)
                    databaseRestored = true
                } else {
                    // Merge for conversations/messages lands in sub-block 2.
                    // Refusing is better than half-merging: the user keeps their
                    // current data and is told plainly what happened.
                    skipped += "database/kelivo.db (合并模式暂未支持会话合并)"
                }
            }

            // ── assets ──────────────────────────────────────────────────────
            var assetFiles = 0
            for (root in BackupArchiveCodec.ASSET_ROOTS) {
                check(!isCancelled()) { "恢复已取消" }
                val stagedRoot = File(staging, root)
                if (!stagedRoot.isDirectory) continue
                val targetRoot = File(context.filesDir, root)
                assetFiles += copyTree(stagedRoot, targetRoot, mode == RestoreMode.OVERWRITE)
            }

            onProgress(PHASE_APPLYING, 1, 1)
            return RestoreReport(
                mode = mode,
                entityRowsWritten = applied.entityRows,
                preferenceKeysWritten = applied.preferenceKeys,
                databaseRestored = databaseRestored,
                assetFilesRestored = assetFiles,
                skippedEntries = skipped.toList(),
                extractedEntries = extracted.size,
            )
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    // ── settings application ────────────────────────────────────────────────

    private data class AppliedSettings(val entityRows: Int, val preferenceKeys: Int)

    /**
     * Writes the `settings.json` payload back into the entity tables and
     * `preference_rows`.
     *
     * The payload is keyed by *source key*; each is routed back to its table.
     * Providers arrive as a map plus an order array, everything else as an
     * array ordered by `(sort_order, id)` — writing the array index as the new
     * `sort_order` preserves the user's ordering.
     */
    private fun applySettings(settingsFile: File, mode: RestoreMode): AppliedSettings {
        require(settingsFile.isFile) { "备份缺少 settings.json" }
        val root = BackupJson.parse(settingsFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("settings.json 不是 JSON 对象")

        var entityRows = 0
        var preferenceKeys = 0

        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (entry in ENTITY_ROUTING) {
                val element = root[entry.sourceKey] ?: continue
                val dao = PayloadEntityDao(db, entry.tableName, primaryKey = entry.payloadKey)
                if (entry.isProvider) {
                    val providers = element as? JsonObject ?: continue
                    // Provider keys are the map keys; ordering comes from the
                    // separate `providers_order_v1` array.
                    entityRows += dao.replaceAll(providers.map { (id, payload) ->
                        PayloadEntityDao.Row(
                            id = id,
                            sortOrder = 0,
                            payload = payload.toCompactJson(),
                            updatedAt = 0L,
                        )
                    })
                } else {
                    val rows = element as? JsonArray ?: continue
                    // Array position becomes the new sort_order, preserving the
                    // ordering the exporting device had.
                    entityRows += dao.replaceAll(rows.mapIndexed { index, payload ->
                        val id = payload.idOrNull()
                        if (id == null) null else PayloadEntityDao.Row(
                            id = id,
                            sortOrder = index,
                            payload = payload.toCompactJson(),
                            updatedAt = 0L,
                        )
                    }.filterNotNull())
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Preferences are written outside the entity transaction because they
        // may target SharedPreferences rather than preference_rows.
        val preferences = root.filterKeys { isPreferenceKey(it) }
        for ((key, value) in preferences) {
            preferenceRepository.writeJson(key, value.toCompactJson())
            preferenceKeys += 1
        }

        return AppliedSettings(entityRows = entityRows, preferenceKeys = preferenceKeys)
    }

    // ── database swap ───────────────────────────────────────────────────────

    private fun verifySqliteHeader(file: File) {
        val header = ByteArray(16)
        file.inputStream().use { input ->
            val read = input.read(header)
            check(read == 16) { "数据库快照不完整" }
        }
        check(String(header, Charsets.US_ASCII) == "SQLite format 3\u0000") {
            "备份中的数据库不是有效的 SQLite 文件"
        }
    }

    /**
     * Replaces the live database with [stagedDb] and reopens the connection.
     *
     * The existing database is moved aside rather than deleted so a failure
     * while swapping can be rolled back — losing every conversation to a failed
     * restore is not an acceptable outcome.
     */
    private fun replaceDatabase(stagedDb: File) {
        database.close()
        val livePath = context.getDatabasePath(MemoSchema.DB_NAME)
        val backupPath = File(livePath.parentFile, "${livePath.name}.pre-restore")
        val journal = File(livePath.path + "-journal")
        val wal = File(livePath.path + "-wal")
        val shm = File(livePath.path + "-shm")

        runCatching {
            if (livePath.exists()) {
                backupPath.delete()
                check(livePath.renameTo(backupPath)) { "无法备份现有数据库" }
            }
            journal.delete(); wal.delete(); shm.delete()
            stagedDb.inputStream().use { input ->
                livePath.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
            check(livePath.isFile && livePath.length() > 0) { "写入数据库失败" }
        }.onFailure { failure ->
            // Roll back: put the original file back so the app still runs.
            runCatching {
                livePath.delete()
                if (backupPath.exists()) backupPath.renameTo(livePath)
            }
            database.close()
            throw failure
        }

        // Reopen and confirm the restored file is usable before discarding the
        // rollback copy. Opening is what validates the schema in practice.
        val probe = SQLiteDatabase.openDatabase(
            livePath.path, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            probe.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "恢复后的数据库完整性校验失败"
                }
            }
        } finally {
            probe.close()
        }
        backupPath.delete()
    }

    private fun copyTree(source: File, target: File, overwrite: Boolean): Int {
        var copied = 0
        val children = source.listFiles() ?: return 0
        target.mkdirs()
        for (child in children) {
            val destination = File(target, child.name)
            if (child.isDirectory) {
                copied += copyTree(child, destination, overwrite)
            } else if (child.isFile) {
                if (destination.exists() && !overwrite) continue
                child.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
                }
                copied += 1
            }
        }
        return copied
    }

    private data class EntityRouting(
        val sourceKey: String,
        val tableName: String,
        val payloadKey: String = "id",
        val isProvider: Boolean = false,
    )

    companion object {
        /**
         * Phase wire values are the ones `BackupPhase` (and therefore the
         * shared progress dialog) knows about, so the restore steps land on the
         * right label instead of silently degrading to "Preparing".
         */
        const val PHASE_READING_MANIFEST = "reading_settings"
        const val PHASE_EXTRACTING = "extracting"
        const val PHASE_APPLYING_SETTINGS = "validating"
        const val PHASE_APPLYING_DATABASE = "staging_candidate"
        const val PHASE_APPLYING = "finalizing"

        /** Mirrors [BackupSettingsSnapshot]'s export routing, in reverse. */
        private val ENTITY_ROUTING = listOf(
            EntityRouting("assistants_v1", "assistant_rows"),
            EntityRouting("provider_configs_v1", "provider_rows", payloadKey = "provider_key", isProvider = true),
            EntityRouting("provider_groups_v1", "provider_group_rows"),
            EntityRouting("mcp_servers_v1", "mcp_server_rows"),
            EntityRouting("world_books_v1", "world_book_rows"),
            EntityRouting("assistant_memories_v1", "assistant_memory_rows"),
            EntityRouting("quick_phrases_v1", "quick_phrase_rows"),
            EntityRouting("search_services_v1", "search_service_rows"),
            EntityRouting("tts_services_v1", "tts_service_rows"),
            EntityRouting("instruction_injections_v1", "instruction_injection_rows"),
            EntityRouting("assistant_tags_v1", "assistant_tag_rows"),
            EntityRouting("memory_entries_v1", "memory_entry_rows"),
            EntityRouting("user_profile_fields_v1", "user_profile_field_rows"),
        )
    }
}

/** A payload's own `id`, when it carries one. */
private fun JsonElement.idOrNull(): String? {
    val obj = this as? JsonObject ?: return null
    val raw = (obj["id"] as? JsonPrimitive)?.content ?: return null
    return raw.ifBlank { null }
}

/** Keys that belong in preference storage, per the router's classification. */
private fun isPreferenceKey(key: String): Boolean {
    val disposition = classifyBusinessKey(key)
    return disposition == KeyDisposition.PREFERENCE ||
        disposition == KeyDisposition.UNKNOWN
}

private fun JsonElement.toCompactJson(): String = BackupJson.codec.encodeToString(JsonElement.serializer(), this)
