package com.psyche.memo.data.backup

/**
 * Port of `core/services/backup/backup_task_progress.dart`.
 *
 * The phase enum drives both the label and the leading icon in the shared
 * progress dialog, so the constant order/names mirror the Dart enum exactly
 * and the UI maps them 1:1.
 */
enum class BackupPhase {
    PREPARING,
    SNAPSHOTTING_DATABASE,
    PACKING,
    VERIFYING,
    UPLOADING,
    DOWNLOADING,
    EXTRACTING,
    VALIDATING,
    READING_SETTINGS,
    STAGING_CANDIDATE,
    COMMITTING,
    IMPORTING_SESSIONS,
    IMPORTING_MESSAGES,
    MATERIALIZING_FILES,
    LISTING_REMOTE,
    FINALIZING,
    ;

    companion object {
        /**
         * Phases travel through the data layer as strings (the builder and
         * restorer do not depend on this enum), so the wire values are pinned
         * here rather than derived from `name`.
         */
        private val WIRE = mapOf(
            "preparing" to PREPARING,
            "snapshotting_database" to SNAPSHOTTING_DATABASE,
            "packing" to PACKING,
            "verifying" to VERIFYING,
            "uploading" to UPLOADING,
            "downloading" to DOWNLOADING,
            "extracting" to EXTRACTING,
            "validating" to VALIDATING,
            "reading_settings" to READING_SETTINGS,
            "staging_candidate" to STAGING_CANDIDATE,
            "committing" to COMMITTING,
            "importing_sessions" to IMPORTING_SESSIONS,
            "importing_messages" to IMPORTING_MESSAGES,
            "materializing_files" to MATERIALIZING_FILES,
            "listing_remote" to LISTING_REMOTE,
            "finalizing" to FINALIZING,
        )

        /** Unknown wire values report [PREPARING] rather than crashing a UI update. */
        fun fromWire(value: String): BackupPhase = WIRE[value] ?: PREPARING

        fun wireOf(phase: BackupPhase): String = WIRE.entries.first { it.value == phase }.key
    }
}

enum class BackupProgressUnit { NONE, BYTES, ITEMS }

/**
 * `BackupProgress` (backup_task_progress.dart). [total] of -1 (or null) means
 * "unknown yet", which the dialog renders as an indeterminate bar.
 */
data class BackupProgress(
    val phase: BackupPhase,
    val processed: Long,
    val total: Long? = null,
    val unit: BackupProgressUnit = BackupProgressUnit.NONE,
    val cancellable: Boolean = true,
    val detail: String? = null,
) {
    val fraction: Float?
        get() {
            val t = total
            return if (t != null && t > 0) (processed.toDouble() / t).coerceIn(0.0, 1.0).toFloat() else null
        }
}

fun interface BackupProgressSink {
    fun report(progress: BackupProgress)
}

/** Thrown when the user cancels a task; the UI maps it to a plain "Cancelled". */
class BackupCancelledException : Exception("备份已取消")

/**
 * Drives a progress sink from the `(phase, processed, total)` triples the
 * builder/restorer emit, so those classes stay free of UI concerns.
 */
internal class ProgressBridge(
    private val sink: BackupProgressSink?,
) {
    fun report(phase: String, processed: Long, total: Long) {
        val sink = sink ?: return
        sink.report(
            BackupProgress(
                phase = BackupPhase.fromWire(phase),
                processed = processed,
                total = total.takeIf { it >= 0 },
                unit = if (total >= 0) BackupProgressUnit.BYTES else BackupProgressUnit.NONE,
            ),
        )
    }
}
