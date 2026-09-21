package com.psyche.memo.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.AssistantMemoryRowDao
import com.psyche.memo.data.db.BackupSchemaVerdict
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.MemoSchema
import com.psyche.memo.data.db.SchemaMigrations
import com.psyche.memo.data.db.MemoryEntryRowDao
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
data class RestoreReport(
    val mode: RestoreMode,
    val entityRowsWritten: Int,
    val preferenceKeysWritten: Int,
    val databaseRestored: Boolean,
    val assetFilesRestored: Int,
    val skippedEntries: List<String>,
    val extractedEntries: Int = 0,
    val mergeReport: BackupMergeReport? = null,
)

/**
 * `RestoreMode` (`core/providers/backup_provider.dart`) — which components the
 * archive replaces and whether local data is kept.
 */
enum class RestoreMode { OVERWRITE, MERGE }

/**
 * Applies a backup archive to the current installation.
 *
 * Ported from `DataSync._restoreFromBackupFile` (`data_sync.dart` L2917):
 * extraction, verification, database application (file swap for
 * [RestoreMode.OVERWRITE], [DatabaseSnapshotMerger] for [RestoreMode.MERGE])
 * and the `settings.json` payload (wholesale for overwrite, merged per
 * [SettingsSnapshotMerger] for merge).
 *
 * Deliberately *not* here yet (sub-block 7): the crash-safe bundle staging /
 * lease / cutover pipeline the Flutter build uses for online restore. This
 * implementation writes the database by swapping the file while the app is
 * quiesced, which is correct for the offline, user-initiated local-file flow
 * but is not a substitute for the staged pipeline.
 */
class BackupRestorer(
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
     * @param allowUnverifiedForwardCompatible the user's informed consent for
     *   a backup from a NEWER build that made no compatibility declaration
     *   (`allowUnverifiedForwardCompatible` in `data_sync.dart`). Without it a
     *   `forwardUndeclared` schema is refused; the consent dialog lives in the
     *   app layer (`ForwardCompatDialogs`).
     */
    fun restore(
        archive: File,
        mode: RestoreMode,
        onProgress: (phase: String, processed: Long, total: Long) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
        allowUnverifiedForwardCompatible: Boolean = false,
    ): RestoreReport {
        require(archive.isFile) { "备份文件不存在" }

        onProgress(PHASE_READING_MANIFEST, 0, -1)
        var manifest = BackupArchiveCodec.readManifest(archive)
        check(BackupManifestCodec.acceptsFormat(manifest)) {
            "该备份文件格式版本不受支持（format=${manifest.format}，需要至少 ${manifest.minimumReadableFormatVersion}）"
        }

        // ── 前向兼容闸门（`_preflightVersionedBackup` 的判定部分）──────────
        // 归档里本版本不认识的条目：来自更新的构建 = 归档层的未知表/列，剥掉后
        // 继续；来自同版本或更旧 = 归档本身畸形，拒绝（`manifest_entry_scope`）。
        val unknownEntries = manifest.entries.keys.filter { name -> !isKnownEntryName(name) }
        if (unknownEntries.isNotEmpty()) {
            check(BackupManifestCodec.declaresNewerBuild(manifest)) {
                "备份内包含本版本不认识的条目（${unknownEntries.first()}），且备份未声明来自更新的版本"
            }
            // 从 manifest 里剥掉，等于上游“解压后从盘上删除”：之后的任何阶段
            // 都不会再碰它们。
            manifest = manifest.copy(entries = manifest.entries.filterKeys { it !in unknownEntries.toSet() })
        }
        var schemaVerdict: BackupSchemaVerdict? = null
        if (manifest.includeChats) {
            val database = requireNotNull(manifest.database) { "备份声明包含会话，但 manifest 缺少 database 块" }
            val declared = database.minimumReadableSchemaVersion
            check(declared == null || (declared in 1..database.schemaVersion)) { "备份的数据库兼容性声明无效" }
            schemaVerdict = SchemaMigrations.classifyBackup(database.schemaVersion, declared)
            when (schemaVerdict) {
                BackupSchemaVerdict.UNREADABLE ->
                    throw IllegalStateException("该备份的数据库格式比当前版本新，无法读取；请更新 Memo 后重试")
                BackupSchemaVerdict.FORWARD_UNDECLARED ->
                    check(allowUnverifiedForwardCompatible) {
                        "该备份来自更新的版本且未声明能否被本版本读取；如仍要导入，请在确认对话框中选择「仍要导入」"
                    }
                BackupSchemaVerdict.CURRENT,
                BackupSchemaVerdict.NEEDS_UPGRADE,
                BackupSchemaVerdict.FORWARD_COMPATIBLE,
                -> {}
            }
        } else {
            // settings-only payload must not carry a database block
            // (`manifest_database`).
            check(manifest.database == null) { "备份的 manifest 携带了与载荷不符的 database 块" }
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

            // ── database ────────────────────────────────────────────────────
            // The database goes first so the settings payload below lands in the
            // restored file, not in one that is about to be swapped away (the
            // original also persists business data last for exactly this reason).
            var databaseRestored = false
            var mergeReport: BackupMergeReport? = null
            if (manifest.includeChats) {
                check(!isCancelled()) { "恢复已取消" }
                onProgress(PHASE_APPLYING_DATABASE, 0, -1)
                val stagedDb = File(staging, BackupManifestCodec.ENTRY_DATABASE)
                require(stagedDb.isFile) { "备份声明包含会话，但归档内缺少 database/kelivo.db" }
                verifySqliteHeader(stagedDb)
                // `prepareSnapshotForRestore`：以文件里的 user_version 为准。
                // 老 schema 先原位迁移；forwardCompatible/forwardUndeclared 的
                // 新 schema 剥掉本版不认识的表并把 user_version 折回当前值，
                // 之后才允许换入/合并。
                val stagedVersion = SchemaMigrations.readSchemaVersion(stagedDb)
                if (SchemaMigrations.needsUpgrade(stagedVersion)) {
                    SchemaMigrations.upgradeFileInPlace(stagedDb)
                } else if (stagedVersion > SchemaMigrations.CURRENT_SCHEMA_VERSION) {
                    check(
                        schemaVerdict == BackupSchemaVerdict.FORWARD_COMPATIBLE ||
                            schemaVerdict == BackupSchemaVerdict.FORWARD_UNDECLARED,
                    ) { "该备份的数据库格式比当前版本新，无法读取；请更新 Memo 后重试" }
                    SchemaMigrations.normalizeForwardCompatible(stagedDb)
                } else if (stagedVersion != SchemaMigrations.CURRENT_SCHEMA_VERSION) {
                    throw IllegalStateException("备份的数据库 schema 版本无法识别（$stagedVersion）")
                }
                if (mode == RestoreMode.OVERWRITE) {
                    replaceDatabase(stagedDb)
                    databaseRestored = true
                } else {
                    mergeReport = DatabaseSnapshotMerger(database.writableDatabase).merge(stagedDb)
                    databaseRestored = true
                }
            }

            // ── settings ────────────────────────────────────────────────────
            onProgress(PHASE_APPLYING_SETTINGS, 0, -1)
            val settingsFile = File(staging, BackupManifestCodec.ENTRY_SETTINGS)
            val applied = applySettings(settingsFile, mode)

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
                mergeReport = mergeReport,
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
     * array ordered by `(sort_order, id)`.
     *
     * [RestoreMode.OVERWRITE] replaces each touched table wholesale (the array
     * index becomes the new `sort_order`, preserving the exporting device's
     * order). [RestoreMode.MERGE] goes through [SettingsSnapshotMerger] instead:
     * shared rows update in place, backup-only rows append, local-only rows
     * survive, and preferences are merged per the merger's rules rather than
     * overwritten.
     */
    private fun applySettings(settingsFile: File, mode: RestoreMode): AppliedSettings {
        require(settingsFile.isFile) { "备份缺少 settings.json" }
        val root = BackupJson.parse(settingsFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("settings.json 不是 JSON 对象")
        val merge = mode == RestoreMode.MERGE

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
                    val incoming = routeProviders(providers, root.orderArray(BackupSettingsSnapshot.KEY_PROVIDER_ORDER))
                    entityRows += if (merge) {
                        val existing = dao.getAll().map { row ->
                            SettingsSnapshotMerger.Row(row.id, row.sortOrder, BackupJson.parse(row.payload) as? JsonObject ?: JsonObject(emptyMap()))
                        }
                        val merged = SettingsSnapshotMerger.mergeProviders(
                            existing,
                            incoming,
                            preferIncomingOrder = root.containsKey(BackupSettingsSnapshot.KEY_PROVIDER_ORDER),
                        )
                        dao.replaceAll(merged.map { it.toDaoRow() })
                    } else {
                        // Provider keys are the map keys; ordering comes from the
                        // separate `providers_order_v1` array.
                        dao.replaceAll(incoming.map { it.toDaoRow() })
                    }
                } else {
                    val rows = element as? JsonArray ?: continue
                    // Array position becomes the new sort_order, preserving the
                    // ordering the exporting device had.
                    val payloads = rows.mapIndexedNotNull { index, payload ->
                        payload.idOrNull()?.let { id -> Triple(id, payload, index) }
                    }
                    entityRows += when (entry.sourceKey) {
                        "memory_entries_v1" -> {
                            val memoryDao = MemoryEntryRowDao(db)
                            val incoming = payloads.map { (id, payload, index) ->
                                MemoryEntryRowDao.Row.fromPayload(id, payload.toCompactJson(), sortOrder = index)
                            }
                            if (merge) {
                                val existing = memoryDao.getAll().map { row ->
                                    SettingsSnapshotMerger.Row(row.id, row.sortOrder, BackupJson.parse(row.payload) as? JsonObject ?: JsonObject(emptyMap()))
                                }
                                val incomingRows = payloads.map { (id, payload, index) ->
                                    SettingsSnapshotMerger.Row(id, index, payload as? JsonObject ?: JsonObject(emptyMap()))
                                }
                                val merged = SettingsSnapshotMerger.mergeEntities(entry.sourceKey, existing, incomingRows)
                                memoryDao.replaceAll(merged.map { mergedRow ->
                                    // Re-project through the payload so the typed
                                    // columns stay consistent with merged JSON.
                                    MemoryEntryRowDao.Row.fromPayload(mergedRow.id, mergedRow.payload.toCompactJson(), sortOrder = mergedRow.sortOrder)
                                })
                            } else {
                                memoryDao.replaceAll(incoming)
                            }
                        }
                        "assistant_memories_v1" -> {
                            val memoryDao = AssistantMemoryRowDao(db)
                            val incoming = payloads.map { (id, payload, index) ->
                                AssistantMemoryRowDao.Row.fromPayload(id, payload.toCompactJson(), sortOrder = index)
                            }
                            if (merge) {
                                val existing = memoryDao.getAll().map { row ->
                                    SettingsSnapshotMerger.Row(row.id, row.sortOrder, BackupJson.parse(row.payload) as? JsonObject ?: JsonObject(emptyMap()))
                                }
                                val incomingRows = payloads.map { (id, payload, index) ->
                                    SettingsSnapshotMerger.Row(id, index, payload as? JsonObject ?: JsonObject(emptyMap()))
                                }
                                val merged = SettingsSnapshotMerger.mergeEntities(entry.sourceKey, existing, incomingRows)
                                memoryDao.replaceAll(merged.map { mergedRow ->
                                    AssistantMemoryRowDao.Row.fromPayload(mergedRow.id, mergedRow.payload.toCompactJson(), sortOrder = mergedRow.sortOrder)
                                })
                            } else {
                                memoryDao.replaceAll(incoming)
                            }
                        }
                        else -> {
                            val toDaoRow = { id: String, payload: JsonElement, index: Int ->
                                PayloadEntityDao.Row(
                                    id = id,
                                    sortOrder = index,
                                    payload = payload.toCompactJson(),
                                    updatedAt = 0L,
                                )
                            }
                            if (merge) {
                                val existing = dao.getAll().map { row ->
                                    SettingsSnapshotMerger.Row(row.id, row.sortOrder, BackupJson.parse(row.payload) as? JsonObject ?: JsonObject(emptyMap()))
                                }
                                val incomingRows = payloads.map { (id, payload, index) ->
                                    SettingsSnapshotMerger.Row(id, index, payload as? JsonObject ?: JsonObject(emptyMap()))
                                }
                                val merged = SettingsSnapshotMerger.mergeEntities(entry.sourceKey, existing, incomingRows)
                                dao.replaceAll(merged.map { it.toDaoRow() })
                            } else {
                                dao.replaceAll(payloads.map { (id, payload, index) -> toDaoRow(id, payload, index) })
                            }
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Preferences are written outside the entity transaction because they
        // may target SharedPreferences rather than preference_rows.
        val preferences = root.filterKeys { isPreferenceKey(it) }
        if (merge) {
            val existing = preferences.keys.associateWith { key -> preferenceRepository.readJson(key) }
            val toWrite = SettingsSnapshotMerger.mergePreferences(existing, preferences, root.keys)
            for ((key, value) in toWrite) {
                preferenceRepository.writeJson(key, value)
                preferenceKeys += 1
            }
        } else {
            for ((key, value) in preferences) {
                preferenceRepository.writeJson(key, value.toCompactJson())
                preferenceKeys += 1
            }
        }

        return AppliedSettings(entityRows = entityRows, preferenceKeys = preferenceKeys)
    }

    /**
     * `BusinessSettingsRouter._routeProviders` — rows ordered by the backup's
     * `providers_order_v1` first, then any map keys the order array missed;
     * ids in the order array without a config become order-only placeholders.
     */
    private fun routeProviders(
        providers: JsonObject,
        order: List<String>,
    ): List<SettingsSnapshotMerger.Row> {
        val orderedKeys = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (key in order) {
            if (key.isNotEmpty() && seen.add(key)) orderedKeys.add(key)
        }
        for ((key, _) in providers) {
            if (seen.add(key)) orderedKeys.add(key)
        }
        val orderOnly = JsonObject(mapOf("enabled" to JsonPrimitive(SettingsSnapshotMerger.PROVIDER_ORDER_ONLY_ENABLED)))
        return orderedKeys.mapIndexed { index, key ->
            val payload = providers[key] as? JsonObject ?: orderOnly
            SettingsSnapshotMerger.Row(key, index, payload)
        }
    }

    private fun SettingsSnapshotMerger.Row.toDaoRow() = PayloadEntityDao.Row(
        id = id,
        sortOrder = sortOrder,
        payload = payload.toCompactJson(),
        updatedAt = 0L,
    )

    private fun JsonObject.orderArray(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { element -> (element as? JsonPrimitive)?.content }
            .orEmpty()

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
     * The manifest entry names this build has a use for
     * (`_preflightVersionedBackup`'s known-entry set): the two payload files
     * plus the four asset roots. Anything else is the archive-level
     * counterpart of an unknown table — tolerated only from a newer build.
     */
    private fun isKnownEntryName(name: String): Boolean =
        name == BackupManifestCodec.ENTRY_SETTINGS ||
            name == BackupManifestCodec.ENTRY_DATABASE ||
            BackupArchiveCodec.ASSET_ROOTS.any { name.startsWith("$it/") }

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
