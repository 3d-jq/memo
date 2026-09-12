package com.psyche.memo.data.backup

import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** What a [LocalSnapshotService.runIfDue] attempt did. */
sealed interface LocalSnapshotRunResult {
    data class Created(
        val entry: LocalSnapshotEntry,
        val pruned: List<LocalSnapshotEntry>,
    ) : LocalSnapshotRunResult

    data class Skipped(val reason: LocalSnapshotSkipReason) : LocalSnapshotRunResult

    data class Failed(val error: Throwable) : LocalSnapshotRunResult
}

/**
 * local_snapshot_service.dart — takes automatic and on-demand copies of the
 * database into [LocalSnapshotStore], and prunes them by policy.
 *
 * [buildArchive] packs the live database; the app wires it to
 * [MemoBackupService.exportToCache].
 */
class LocalSnapshotService(
    private val appDataDirectory: File,
    /** Where the live database actually is — the change fingerprint reads it. */
    val databaseFile: File,
    private val backupService: MemoBackupService,
    val preferences: LocalSnapshotPreferences,
    private val store: LocalSnapshotStore = LocalSnapshotStore(appDataDirectory),
) {
    private val running = AtomicBoolean(false)

    /**
     * Takes a copy if one is due. Never throws: a failure here is recorded and
     * surfaced in settings, and must not disturb whatever the user is doing.
     */
    fun runIfDue(now: Instant = Instant.now()): LocalSnapshotRunResult {
        return try {
            runIfDueBody(now)
        } catch (error: Throwable) {
            LocalSnapshotRunResult.Failed(error)
        }
    }

    private fun runIfDueBody(now: Instant): LocalSnapshotRunResult {
        if (running.get()) return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.BUSY)

        val settings = preferences.readSettings()
        if (!settings.enabled) return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.DISABLED)
        val state = preferences.readState()
        val databaseBytes = databaseFile.length()

        // Ordered by cost: nothing below is allowed to touch the database until
        // the cheap answers have all said "yes".
        val dueSkip = LocalSnapshotSchedule.dueSkipReason(
            now = now,
            enabled = settings.enabled,
            interval = settings.intervalFor(databaseBytes),
            lastSuccessAt = state.lastSuccessAt,
            lastFailureAt = state.lastFailureAt,
            backoff = state.failureBackoff,
        )
        if (dueSkip != null) return LocalSnapshotRunResult.Skipped(dueSkip)

        // Everyone upgrading to a build with this feature has no recorded copy,
        // so without a grace period every one of them would pay a full pack at
        // the moment the app is busiest. The copy is not skipped, only moved off
        // the launch the user just triggered.
        val firstObserved = state.firstObservedAt
        if (firstObserved == null) {
            preferences.recordFirstObserved(now)
            return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.NOT_DUE)
        }
        if (state.lastSuccessAt == null && !firstObserved.isAfter(now) &&
            java.time.Duration.between(firstObserved, now) < LocalSnapshotSchedule.INITIAL_GRACE
        ) {
            return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.NOT_DUE)
        }

        val fingerprint = DatabaseChangeFingerprint.read(databaseFile)
        if (fingerprint.matches(state.fingerprint)) {
            // Writing another identical copy would not add a copy — it would
            // replace the oldest one with a duplicate of the newest, collapsing
            // the time depth the slots exist to provide.
            preferences.recordUnchanged(fingerprint)
            return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.UNCHANGED)
        }

        val free = appDataDirectory.usableSpace.takeIf { it > 0 }
        if (free != null &&
            free - LocalSnapshotSchedule.estimatedSpaceRequired(databaseBytes) < LocalSnapshotSchedule.FREE_SPACE_FLOOR
        ) {
            preferences.recordSkip(LocalSnapshotSkipReason.INSUFFICIENT_SPACE)
            return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.INSUFFICIENT_SPACE)
        }

        return try {
            val entry = take(origin = LocalSnapshotOrigin.AUTOMATIC, now = now)
            val pruned = store.prune(settings.retention, now)
            // Deliberately the fingerprint from before the copy: the copy holds
            // the state the database was in when it started, so anything written
            // while it ran must still read as a change next time.
            preferences.recordSuccess(at = now, fingerprint = fingerprint)
            LocalSnapshotRunResult.Created(entry, pruned)
        } catch (error: Throwable) {
            if (error is IllegalStateException && error.message == "local_snapshot_running") {
                // Raced with a copy the user asked for: not a failure, and it
                // must not count towards the backoff that exists for real ones.
                return LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.BUSY)
            }
            preferences.recordFailure(at = now, message = error.toString(), previousStreak = state.failureStreak)
            LocalSnapshotRunResult.Failed(error)
        }
    }

    /**
     * Packs the live database and publishes it into the store. Throws on
     * failure so callers that asked for a copy explicitly can say so.
     */
    fun take(
        origin: LocalSnapshotOrigin,
        pinned: Boolean = false,
        now: Instant = Instant.now(),
        onProgress: BackupProgressSink? = null,
    ): LocalSnapshotEntry {
        check(running.compareAndSet(false, true)) { "local_snapshot_running" }
        try {
            store.sweepIncomplete()
            val prepared = backupService.exportToCache(onProgress = onProgress)
            return try {
                val manifest = backupService.peekManifest(prepared)
                store.publish(
                    prepared = prepared,
                    createdAtUtc = now,
                    origin = origin,
                    pinned = pinned,
                    conversationCount = manifest?.conversationCount ?: 0,
                    messageCount = manifest?.messageCount ?: 0,
                )
            } finally {
                runCatching { prepared.delete() }
            }
        } finally {
            running.set(false)
        }
    }

    /** Trims the set to the current policy without taking a new copy. */
    fun pruneNow(now: Instant = Instant.now()): List<LocalSnapshotEntry> =
        store.prune(preferences.readSettings().retention, now)

    fun list(): List<LocalSnapshotEntry> = store.list()

    fun delete(id: String) = store.delete(id)

    fun setPinned(id: String, pinned: Boolean) = store.setPinned(id, pinned)

    fun store(): LocalSnapshotStore = store
}
