package com.psyche.memo.data.backup

import android.content.Context
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.settings.PreferenceRepository
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
     */
    fun restoreFromFile(
        archive: File,
        mode: RestoreMode,
        onProgress: BackupProgressSink? = null,
        isCancelled: () -> Boolean = { false },
    ): RestoreReportView {
        val bridge = ProgressBridge(onProgress)
        val restorer = BackupRestorer(context, database, preferenceRepository)
        val report = try {
            restorer.restore(
                archive = archive,
                mode = mode,
                onProgress = { phase, processed, total -> bridge.report(phase, processed, total) },
                isCancelled = { isCancelled() },
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

    companion object {
        private const val COPY_BUFFER = 256 * 1024

        /** The builder's cancellation `check` message; see [exportToCache]. */
        internal const val CANCELLED_MESSAGE = "备份已取消"
    }
}

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
) {
    /** Conversations the restorer refused to merge (sub-block 2 fills this in). */
    val skippedConversations: Int
        get() = skippedEntries.count { it.startsWith("database/") }
}
