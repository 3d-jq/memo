package com.psyche.memo.data.backup

import android.content.Context
import com.psyche.memo.data.db.BackupSchemaVerdict
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.SchemaMigrations
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.File

/**
 * Public entry point for backup and restore.
 *
 * Wraps the internal [BackupSnapshotBuilder] / [BackupRestorer] with the
 * cancellation and progress semantics the UI needs, and keeps the archive
 * format types ([BackupManifest], [EntryMetadata], …) out of the app module —
 * the UI only ever sees [BackupProgress] and [RestoreReportView].
 *
 * Ported from `BackupProvider.exportToFile` / `restoreFromLocalFile`
 * (`core/providers/backup_provider.dart` L130 / L2xx).
 */
class MemoBackupService(
    private val context: Context,
    private val database: MemoDatabase,
    private val preferenceRepository: PreferenceRepository,
    private val appVersion: String,
    private val httpClient: okhttp3.OkHttpClient? = null,
) {

    /**
     * Builds a backup archive inside the app cache and returns the file.
     *
     * The caller owns the returned file and must delete it after use — it is
     * only meant to be handed straight to a save dialog or a remote upload.
     *
     * @param onProgress receives phases from [BackupPhase]; [BackupProgress.total]
     *   is null while unknown.
     * @param isCancelled polled between steps; a cancelled build throws
     *   [BackupCancelledException] and removes the partial archive.
     */
    fun exportToCache(
        includeChats: Boolean = true,
        includeFiles: Boolean = true,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
    ): File {
        val bridge = ProgressBridge(onProgress)
        val target = File(context.cacheDir, defaultArchiveName())
        val builder = BackupSnapshotBuilder(context, database, preferenceRepository, appVersion)
        try {
            return builder.build(
                outputFile = target,
                request = BackupSnapshotBuilder.Request(
                    includeChats = includeChats,
                    includeFiles = includeFiles,
                ),
                onProgress = { phase, processed, total -> bridge.report(phase, processed, total) },
                isCancelled = { isCancelled() },
            ).archive
        } catch (cancelled: IllegalStateException) {
            // The builder signals cancellation through `check(!isCancelled())`,
            // so translate that specific message back into the typed exception
            // the UI is prepared to swallow silently.
            if (cancelled.message == CANCELLED_MESSAGE) throw BackupCancelledException()
            throw cancelled
        }
    }

    /**
     * Applies [archive] to this installation.
     *
     * @param databaseFile a staged copy of the archive, or the archive itself;
     *   restore reads `manifest.json` from it first so a foreign file is
     *   rejected before anything is written.
     * @param allowUnverifiedForwardCompatible the user's informed consent for
     *   a backup from a newer build that made no compatibility declaration
     *   (resolved by the app layer's consent dialog before calling this).
     */
    fun restoreFromFile(
        archive: File,
        mode: RestoreMode,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
        allowUnverifiedForwardCompatible: Boolean = false,
    ): RestoreReportView {
        val bridge = ProgressBridge(onProgress)
        val restorer = BackupRestorer(context, database, preferenceRepository)
        val report = try {
            restorer.restore(
                archive = archive,
                mode = mode,
                onProgress = { phase, processed, total -> bridge.report(phase, processed, total) },
                isCancelled = { isCancelled() },
                allowUnverifiedForwardCompatible = allowUnverifiedForwardCompatible,
            )
        } catch (cancelled: IllegalStateException) {
            if (cancelled.message == CANCELLED_MESSAGE) throw BackupCancelledException()
            throw cancelled
        }
        return RestoreReportView(
            mode = report.mode,
            entityRowsWritten = report.entityRowsWritten,
            preferenceKeysWritten = report.preferenceKeysWritten,
            databaseRestored = report.databaseRestored,
            assetFilesRestored = report.assetFilesRestored,
            skippedEntries = report.skippedEntries,
            extractedEntries = report.extractedEntries,
            mergedConversations = report.mergeReport?.importedConversations ?: 0,
            deduplicatedConversations = report.mergeReport?.deduplicatedConversations ?: 0,
        )
    }

    /**
     * Reads just the manifest, so the UI can settle the forward-compatibility
     * question before showing the progress dialog. Returns null when the file
     * is not a readable backup.
     */
    fun peekManifest(archive: File): BackupManifestView? = runCatching {
        val manifest = BackupArchiveCodec.readManifest(archive)
        BackupManifestView(
            format = manifest.format,
            formatVersion = manifest.formatVersion,
            minimumReadableFormatVersion = manifest.minimumReadableFormatVersion,
            appVersion = manifest.appVersion,
            includeChats = manifest.includeChats,
            includeFiles = manifest.includeFiles,
            conversationCount = manifest.database?.conversationCount,
            messageCount = manifest.database?.messageCount,
            schemaVersion = manifest.database?.schemaVersion,
            acceptsFormat = BackupManifestCodec.acceptsFormat(manifest),
            declaresNewerBuild = BackupManifestCodec.declaresNewerBuild(manifest),
        )
    }.getOrNull()

    /**
     * Streams [source] into [sink]. The caller supplies the destination stream
     * (usually SAF's `openOutputStream`), because the archive is far too large
     * to hand around as a byte array.
     */
    fun copyInto(source: File, sink: java.io.OutputStream) {
        source.inputStream().buffered(COPY_BUFFER).use { input ->
            input.copyTo(sink, COPY_BUFFER)
        }
    }

    /**
     * `memo_backup_<ISO8601 with colons as dashes>.zip` — the same shape
     * `data_sync.dart` L515 builds (`DateTime.now().toIso8601String()` with `:`
     * replaced by `-`), under our own brand: the suggested name shows up in the
     * SAF save dialog, and no user-visible string may carry the upstream name.
     * The archive *contents* stay byte-compatible either way — only the
     * suggested file name differs, and it is not part of the format.
     */
    fun defaultArchiveName(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
        val stamp = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS"))
        return "memo_backup_$stamp.zip"
    }

    // ── WebDAV（backup 子块 5）───────────────────────────────────────────────

    private fun client(config: WebDavConfig): WebDavClient =
        WebDavClient(httpClient ?: OkHttpClient(), config)

    private fun translate(throwable: Throwable): Throwable =
        if (throwable is IllegalStateException && throwable.message == CANCELLED_MESSAGE) {
            BackupCancelledException()
        } else {
            throwable
        }

    /** `webdav_config_v1` — the whole config as one preference row. */
    fun webDavConfig(): WebDavConfig =
        preferenceRepository.readJson(KEY_WEBDAV_CONFIG)?.let { raw ->
            runCatching {
                WebDavConfig.fromJson(BackupJson.parse(raw).jsonObject)
            }.getOrNull()
        } ?: WebDavConfig()

    fun saveWebDavConfig(config: WebDavConfig) {
        preferenceRepository.writeJson(KEY_WEBDAV_CONFIG, config.toJson().toString())
    }

    /** `testWebdav`. */
    fun testWebDav(config: WebDavConfig) = client(config).test()

    /** `listBackupFiles` with the listing phase reported. */
    fun listWebDav(
        config: WebDavConfig,
        onProgress: BackupProgressSink? = null,
    ): List<WebDavFileItem> {
        val bridge = ProgressBridge(onProgress)
        bridge.report(BackupPhase.wireOf(BackupPhase.LISTING_REMOTE), 0, -1)
        return client(config).list()
    }

    /**
     * `backupToWebDav` — export → ensure collection → streamed PUT → remove
     * the local archive.
     */
    fun backupToWebDav(
        config: WebDavConfig,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        val archive = exportToCache(
            includeChats = config.includeChats,
            includeFiles = config.includeFiles,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
        try {
            val bridge = ProgressBridge(onProgress)
            val client = client(config)
            client.ensureCollection()
            check(!isCancelled()) { CANCELLED_MESSAGE }
            client.upload(archive) { processed, total ->
                bridge.report(BackupPhase.wireOf(BackupPhase.UPLOADING), processed, total)
            }
            check(!isCancelled()) { CANCELLED_MESSAGE }
        } catch (cancelled: IllegalStateException) {
            if (cancelled.message == CANCELLED_MESSAGE) {
                // A cancelled upload must not leave a partial remote file
                // (`_deleteRemoteQuietly`, data_sync.dart L1912-1925).
                runCatching { client(config).deleteQuietly(client(config).fileUrl(archive.name)) }
                throw BackupCancelledException()
            }
            throw cancelled
        } finally {
            runCatching { archive.delete() }
        }
    }

    /**
     * What restoring a backup would mean for this build, determined from its
     * manifest alone (`DataSync.inspectBackupCompatibility`). Null = no
     * readable manifest or no SQLite payload — nothing to ask about; the
     * restore itself reports any real problem.
     */
    fun inspectBackupCompatibility(archive: File): BackupCompatibility? = runCatching {
        val manifest = BackupArchiveCodec.readManifest(archive)
        val database = manifest.database ?: return@runCatching null
        BackupCompatibility(
            schemaVersion = database.schemaVersion,
            verdict = SchemaMigrations.classifyBackup(
                schemaVersion = database.schemaVersion,
                declaredMinimumReadable = database.minimumReadableSchemaVersion,
            ),
        )
    }.getOrNull()

    /**
     * `restoreFromWebDav` — stream the archive down to cache, then run the
     * normal restore over it. [forwardCompatibilityPrompt] is asked once the
     * archive is on disk and before anything is read out of it (the progress
     * overlay is already up by then; a dialog stacks above it).
     */
    suspend fun restoreFromWebDav(
        config: WebDavConfig,
        item: WebDavFileItem,
        mode: RestoreMode,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
        forwardCompatibilityPrompt: ForwardCompatibilityPrompt? = null,
    ): RestoreReportView {
        val bridge = ProgressBridge(onProgress)
        val restorer = BackupRestorer(context, database, preferenceRepository)
        val staged = File(context.cacheDir, "webdav_restore_${System.nanoTime()}.zip")
        try {
            client(config).download(item, staged) { processed, total ->
                bridge.report(BackupPhase.wireOf(BackupPhase.DOWNLOADING), processed, total)
            }
            return restoreStaged(restorer, staged, mode, bridge, isCancelled, forwardCompatibilityPrompt)
        } finally {
            runCatching { staged.delete() }
        }
    }

    /**
     * Shared restore tail for the remote sources: settle the forward-
     * compatibility question through [forwardCompatibilityPrompt], then run
     * [restorer] over the already-downloaded archive and project the internal
     * report onto the public view the UI consumes.
     */
    private suspend fun restoreStaged(
        restorer: BackupRestorer,
        staged: File,
        mode: RestoreMode,
        bridge: ProgressBridge,
        isCancelled: () -> Boolean,
        forwardCompatibilityPrompt: ForwardCompatibilityPrompt?,
    ): RestoreReportView {
        var allowUnverified = false
        if (forwardCompatibilityPrompt != null) {
            // `_askForwardCompatibility`: a refusal is reported as a
            // cancellation — the prompt owns the explanation, so there is
            // nothing left for the restore to say.
            val compatibility = inspectBackupCompatibility(staged)
            if (compatibility != null) {
                when (forwardCompatibilityPrompt(compatibility)) {
                    ForwardCompatibilityAnswer.PROCEED -> {}
                    ForwardCompatibilityAnswer.PROCEED_UNVERIFIED -> allowUnverified = true
                    ForwardCompatibilityAnswer.REFUSE -> throw BackupCancelledException()
                }
            }
        }
        val report = try {
            restorer.restore(
                archive = staged,
                mode = mode,
                onProgress = { phase, processed, total -> bridge.report(phase, processed, total) },
                isCancelled = isCancelled,
                allowUnverifiedForwardCompatible = allowUnverified,
            )
        } catch (cancelled: IllegalStateException) {
            if (cancelled.message == CANCELLED_MESSAGE) throw BackupCancelledException()
            throw cancelled
        }
        return RestoreReportView(
            mode = report.mode,
            entityRowsWritten = report.entityRowsWritten,
            preferenceKeysWritten = report.preferenceKeysWritten,
            databaseRestored = report.databaseRestored,
            assetFilesRestored = report.assetFilesRestored,
            skippedEntries = report.skippedEntries,
            extractedEntries = report.extractedEntries,
            mergedConversations = report.mergeReport?.importedConversations ?: 0,
            deduplicatedConversations = report.mergeReport?.deduplicatedConversations ?: 0,
        )
    }

    /** `deleteWebDavBackupFile`. */
    fun deleteWebDavItem(config: WebDavConfig, item: WebDavFileItem) = client(config).delete(item)

    // ── S3（backup 子块 6）────────────────────────────────────────────────────

    private fun client(config: S3Config): S3Client =
        S3Client(httpClient ?: OkHttpClient(), config)

    /** `s3_config_v1` — the whole config as one preference row. */
    fun s3Config(): S3Config = S3Config.fromJsonString(
        preferenceRepository.readJson(KEY_S3_CONFIG),
    )

    fun saveS3Config(config: S3Config) {
        preferenceRepository.writeJson(KEY_S3_CONFIG, config.toJson().toString())
    }

    /** `S3BackupProvider.test`. */
    fun testS3(config: S3Config) = client(config).test()

    /** `S3BackupProvider.listRemote` with the listing phase reported. */
    fun listS3(
        config: S3Config,
        onProgress: BackupProgressSink? = null,
    ): List<S3FileItem> {
        ProgressBridge(onProgress).report(BackupPhase.wireOf(BackupPhase.LISTING_REMOTE), 0, -1)
        return client(config).list()
    }

    /**
     * `S3BackupProvider.backup` — export → streamed PUT → drop the local
     * archive. `includeChats`/`includeFiles` come from the S3 config, because
     * the S3 section carries its own two switches (the WebDAV section has no
     * equivalent and always exports both).
     */
    fun backupToS3(
        config: S3Config,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        val archive = exportToCache(
            includeChats = config.includeChats,
            includeFiles = config.includeFiles,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
        try {
            val bridge = ProgressBridge(onProgress)
            client(config).upload(
                file = archive,
                onProgress = { processed, total ->
                    bridge.report(BackupPhase.wireOf(BackupPhase.UPLOADING), processed, total)
                },
                isCancelled = isCancelled,
            )
        } finally {
            runCatching { archive.delete() }
        }
    }

    /**
     * `S3BackupProvider.restoreFromItem` — stream the object down to cache,
     * then run the normal restore over it.
     */
    suspend fun restoreFromS3(
        config: S3Config,
        item: S3FileItem,
        mode: RestoreMode,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
        forwardCompatibilityPrompt: ForwardCompatibilityPrompt? = null,
    ): RestoreReportView {
        val bridge = ProgressBridge(onProgress)
        val restorer = BackupRestorer(context, database, preferenceRepository)
        val staged = File(context.cacheDir, "s3_restore_${System.nanoTime()}.zip")
        try {
            client(config).download(
                key = item.key,
                destination = staged,
                expectedSize = item.size,
                onProgress = { processed, total ->
                    bridge.report(BackupPhase.wireOf(BackupPhase.DOWNLOADING), processed, total)
                },
                isCancelled = isCancelled,
            )
            return restoreStaged(restorer, staged, mode, bridge, isCancelled, forwardCompatibilityPrompt)
        } finally {
            runCatching { staged.delete() }
        }
    }

    /** `deleteObject` — used by the remote list sheet. */
    fun deleteS3Item(config: S3Config, item: S3FileItem) = client(config).delete(item.key)

    companion object {
        private const val COPY_BUFFER = 256 * 1024
        private const val KEY_WEBDAV_CONFIG = "webdav_config_v1"
        private const val KEY_S3_CONFIG = S3Config.PREF_KEY

        /** The builder's cancellation `check` message; see [exportToCache]. */
        internal const val CANCELLED_MESSAGE = "备份已取消"
    }
}

/**
 * What restoring a backup would mean for this build, determined from its
 * manifest alone (`BackupCompatibility` in `data_sync.dart`).
 */
data class BackupCompatibility(
    val schemaVersion: Int,
    val verdict: BackupSchemaVerdict,
)

/** How a caller answers a [ForwardCompatibilityPrompt]. */
enum class ForwardCompatibilityAnswer {
    /** Restore normally. */
    PROCEED,

    /** The backup is newer than this build and made no compatibility promise,
     *  and the user accepted the risk anyway. */
    PROCEED_UNVERIFIED,

    /** Do not restore. The prompt owns the explanation. */
    REFUSE,
}

/**
 * Asked once a backup is on disk and before anything is read out of it.
 * Exists for the WebDAV and S3 restores, which cannot inspect the archive
 * before downloading it. Runs on the caller's context, so it may show UI.
 */
typealias ForwardCompatibilityPrompt =
    suspend (BackupCompatibility) -> ForwardCompatibilityAnswer

/**
 * What the app layer may know about a manifest — no internal format types.
 */
data class BackupManifestView(
    val format: String,
    val formatVersion: Int,
    val minimumReadableFormatVersion: Int,
    val appVersion: String,
    val includeChats: Boolean,
    val includeFiles: Boolean,
    val conversationCount: Int?,
    val messageCount: Int?,
    val schemaVersion: Int?,
    val acceptsFormat: Boolean,
    val declaresNewerBuild: Boolean,
)

/**
 * Public view of a restore's outcome (the internal [RestoreReport] plus the
 * mode, which the UI needs to word the restart prompt).
 */
data class RestoreReportView(
    val mode: RestoreMode,
    val entityRowsWritten: Int,
    val preferenceKeysWritten: Int,
    val databaseRestored: Boolean,
    val assetFilesRestored: Int,
    val skippedEntries: List<String>,
    val extractedEntries: Int,
    /** Conversations imported / deduplicated by a merge restore (sub-block 2). */
    val mergedConversations: Int = 0,
    val deduplicatedConversations: Int = 0,
) {
    /** Conversations the restorer refused to merge (invalid message order). */
    val skippedConversations: Int
        get() = skippedEntries.count { it.startsWith("database/") }
}
