package com.psyche.memo.data.backup

import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Why a due local copy did not get taken.
 *
 * Carried all the way to the settings screen: a copy that silently stops
 * happening is the failure mode this whole mechanism was added to prevent, so
 * every skip has to be nameable (local_snapshot_schedule.dart).
 */
enum class LocalSnapshotSkipReason {
    DISABLED,
    NOT_DUE,

    /** A previous attempt failed and the retry window has not elapsed. */
    BACKOFF,

    /** Another snapshot, backup or restore already holds the database. */
    BUSY,

    /** A reply is still streaming; the copy waits rather than competing for I/O. */
    GENERATING,

    /** Taking one would leave the device too close to full to be safe. */
    INSUFFICIENT_SPACE,

    /** Nothing has been written since the last copy. */
    UNCHANGED;

    val wire: String get() = name.lowercase()
}

/**
 * A cheap, exact "did anything change" signal: two `stat` calls, no SQL.
 *
 * Counting rows would mean a full scan of a table that can hold millions of
 * them, on the launch path, for a question a file's size and mtime already
 * answer. In WAL mode commits land in the -wal sidecar, so it is the half that
 * usually moves.
 */
data class DatabaseChangeFingerprint(
    val databaseBytes: Long,
    val databaseModifiedMillis: Long,
    val walBytes: Long,
    val walModifiedMillis: Long,
) {
    fun encode(): String = "$databaseBytes:$databaseModifiedMillis:$walBytes:$walModifiedMillis"

    /**
     * Deliberately not `equals` on an unreadable stat: a negative component
     * means "could not tell", and two of those in a row must not read as "same".
     */
    fun matches(other: DatabaseChangeFingerprint?): Boolean {
        if (other == null) return false
        if (databaseBytes < 0 || walBytes < 0 || databaseModifiedMillis < 0 || walModifiedMillis < 0) return false
        return databaseBytes == other.databaseBytes &&
            databaseModifiedMillis == other.databaseModifiedMillis &&
            walBytes == other.walBytes &&
            walModifiedMillis == other.walModifiedMillis
    }

    companion object {
        fun read(databaseFile: File): DatabaseChangeFingerprint {
            val database = statOf(databaseFile)
            val wal = statOf(File(databaseFile.path + "-wal"))
            return DatabaseChangeFingerprint(
                databaseBytes = database.first,
                databaseModifiedMillis = database.second,
                walBytes = wal.first,
                walModifiedMillis = wal.second,
            )
        }

        fun decode(value: String?): DatabaseChangeFingerprint? {
            if (value.isNullOrEmpty()) return null
            val parts = value.split(':')
            if (parts.size != 4) return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            return DatabaseChangeFingerprint(numbers[0], numbers[1], numbers[2], numbers[3])
        }

        /** Absent is a state, not a failure: a checkpointed database has no
         * `-wal` beside it, and two launches that both find none really are
         * unchanged. Only a stat that could not be taken is unknown. */
        private fun statOf(file: File): Pair<Long, Long> {
            if (!file.exists()) return 0L to 0L
            if (!file.isFile) return -1L to -1L
            return runCatching { file.length() to file.lastModified() }.getOrElse { -1L to -1L }
        }
    }
}

/**
 * When the next automatic local copy is allowed to run: time-based only, and
 * evaluated on launch and on resume rather than from a timer or a background
 * task — a scheduled wake-up would have to run the admission path unattended.
 */
object LocalSnapshotSchedule {

    /**
     * Interval when the user has not chosen one. The database is pure text, so
     * a heavy user's can reach a gigabyte or more, where one copy costs minutes
     * of CPU and a comparable amount of flash writes; large databases get a
     * longer default rather than a worse experience.
     */
    fun defaultIntervalFor(databaseBytes: Long): Duration = when {
        databaseBytes >= 1024L * 1024 * 1024 -> Duration.ofDays(7)
        databaseBytes >= 200L * 1024 * 1024 -> Duration.ofDays(3)
        else -> Duration.ofDays(1)
    }

    /** How long after this feature first sees an install before it takes its
     * very first copy: keeps the one-off cost off the launch that follows an
     * app update, when migrations and the first screen already compete for disk. */
    val INITIAL_GRACE: Duration = Duration.ofMinutes(10)

    /** Minimum gap after a failure. Without it a database that fails the same
     * way every time would re-run a full vacuum on every resume. */
    val FAILURE_BACKOFF: Duration = Duration.ofHours(1)

    /** Free space that must remain after a copy is written: a nearly full
     * device is how a healthy database becomes a corrupt one. */
    const val FREE_SPACE_FLOOR = 2L * 1024 * 1024 * 1024

    /** Rough peak cost of taking one: the vacuumed intermediate is the size of
     * the database, and the compressed result is kept alongside it briefly. */
    fun estimatedSpaceRequired(databaseBytes: Long): Long =
        (databaseBytes * 1.2).toLong() + databaseBytes / 3

    fun dueSkipReason(
        now: Instant,
        enabled: Boolean,
        interval: Duration,
        lastSuccessAt: Instant?,
        lastFailureAt: Instant?,
        backoff: Duration = FAILURE_BACKOFF,
    ): LocalSnapshotSkipReason? {
        if (!enabled) return LocalSnapshotSkipReason.DISABLED
        if (lastFailureAt != null && !lastFailureAt.isAfter(now) &&
            Duration.between(lastFailureAt, now) < backoff
        ) {
            return LocalSnapshotSkipReason.BACKOFF
        }
        if (lastSuccessAt == null) return null
        // A clock moved backwards must not park the schedule in the future.
        if (lastSuccessAt.isAfter(now)) return null
        if (Duration.between(lastSuccessAt, now) < interval) return LocalSnapshotSkipReason.NOT_DUE
        return null
    }
}
