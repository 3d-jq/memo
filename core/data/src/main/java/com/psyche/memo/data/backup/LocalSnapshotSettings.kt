package com.psyche.memo.data.backup

import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

/** What the user chose about automatic local copies (local_snapshot_settings.dart). */
data class LocalSnapshotSettings(
    val enabled: Boolean = true,
    val intervalDays: Int = AUTOMATIC_INTERVAL,
    val keepRecent: Int = 3,
    val keepWeekly: Boolean = true,
    val keepMonthly: Boolean = true,
    val maximumTotalBytes: Long = DEFAULT_MAXIMUM_TOTAL_BYTES,
    /** Whether a finished automatic copy says so. Off by default: a copy that
     * worked is not news. Failures are reported either way. */
    val announceResult: Boolean = false,
) {
    val retention: LocalSnapshotRetentionPolicy
        get() = LocalSnapshotRetentionPolicy(
            keepRecent = keepRecent.coerceIn(MINIMUM_KEEP_RECENT, MAXIMUM_KEEP_RECENT),
            keepWeekly = keepWeekly,
            keepMonthly = keepMonthly,
            maximumTotalBytes = if (maximumTotalBytes < 0) 0 else maximumTotalBytes,
        )

    fun intervalFor(databaseBytes: Long): java.time.Duration =
        if (intervalDays <= AUTOMATIC_INTERVAL) {
            LocalSnapshotSchedule.defaultIntervalFor(databaseBytes)
        } else {
            java.time.Duration.ofDays(intervalDays.toLong())
        }

    companion object {
        /** Let the interval follow the database size instead of a fixed number. */
        const val AUTOMATIC_INTERVAL = 0

        val INTERVAL_PRESETS = listOf(0, 1, 3, 7, 14, 30)
        const val MINIMUM_KEEP_RECENT = 1
        const val MAXIMUM_KEEP_RECENT = 10

        /** Generous on purpose: a heavy user's database is text-only but can
         * still reach a gigabyte, and the retention slots already bound the
         * count. This is the backstop, not the primary control. */
        const val GIB = 1024L * 1024L * 1024L
        const val DEFAULT_MAXIMUM_TOTAL_BYTES = 10 * GIB

        val TOTAL_BYTES_PRESETS = listOf(0L, GIB, 2 * GIB, 5 * GIB, DEFAULT_MAXIMUM_TOTAL_BYTES, 20 * GIB)
    }
}

/** What happened last time, so the settings screen can say so. */
data class LocalSnapshotState(
    val lastSuccessAt: Instant? = null,
    val lastFailureAt: Instant? = null,
    val lastFailureMessage: String? = null,
    val lastSkipReason: LocalSnapshotSkipReason? = null,
    val failureStreak: Int = 0,
    /** Database state at the last copy, so an unchanged launch can skip. */
    val fingerprint: DatabaseChangeFingerprint? = null,
    /** When the schedule first ran on this install. */
    val firstObservedAt: Instant? = null,
) {
    /**
     * Repeated failures back off well past the base window. A device that is
     * simply full should be retried on the scale of hours, not minutes, and
     * certainly not on every resume.
     */
    val failureBackoff: java.time.Duration
        get() = if (failureStreak <= 1) {
            LocalSnapshotSchedule.FAILURE_BACKOFF
        } else {
            java.time.Duration.ofHours(1L shl (failureStreak - 1).coerceIn(0, 4))
        }
}

/** Reads and writes both of the above through the preference store. */
class LocalSnapshotPreferences(private val prefs: PreferenceRepository) {

    fun readSettings(): LocalSnapshotSettings {
        val defaults = LocalSnapshotSettings()
        return LocalSnapshotSettings(
            enabled = readBool(enabledKey) ?: defaults.enabled,
            intervalDays = normalizeInterval(readInt(intervalDaysKey) ?: defaults.intervalDays),
            keepRecent = (readInt(keepRecentKey) ?: defaults.keepRecent)
                .coerceIn(LocalSnapshotSettings.MINIMUM_KEEP_RECENT, LocalSnapshotSettings.MAXIMUM_KEEP_RECENT),
            keepWeekly = readBool(keepWeeklyKey) ?: defaults.keepWeekly,
            keepMonthly = readBool(keepMonthlyKey) ?: defaults.keepMonthly,
            maximumTotalBytes = normalizeTotalBytes(readLong(maximumTotalBytesKey)),
            announceResult = readBool(announceResultKey) ?: defaults.announceResult,
        )
    }

    fun writeSettings(settings: LocalSnapshotSettings) {
        writeBool(enabledKey, settings.enabled)
        writeInt(intervalDaysKey, normalizeInterval(settings.intervalDays))
        writeInt(
            keepRecentKey,
            settings.keepRecent.coerceIn(LocalSnapshotSettings.MINIMUM_KEEP_RECENT, LocalSnapshotSettings.MAXIMUM_KEEP_RECENT),
        )
        writeBool(keepWeeklyKey, settings.keepWeekly)
        writeBool(keepMonthlyKey, settings.keepMonthly)
        writeLong(maximumTotalBytesKey, normalizeTotalBytes(settings.maximumTotalBytes))
        writeBool(announceResultKey, settings.announceResult)
    }

    // ── state: device-local, in SharedPreferences ──────────────────────────
    //
    // Deliberately *not* in preference_rows: the change fingerprint watches the
    // database file, so recording a run inside that same file would make every
    // check look like a change and the "unchanged" gate would never fire.
    // Upstream keeps them in its preferences blob for the same reason.

    fun readState(): LocalSnapshotState {
        val rawSkip = readLocalString(lastSkipReasonKey)
        return LocalSnapshotState(
            lastSuccessAt = readLocalInstant(lastSuccessAtKey),
            lastFailureAt = readLocalInstant(lastFailureAtKey),
            lastFailureMessage = readLocalString(lastFailureMessageKey),
            lastSkipReason = LocalSnapshotSkipReason.entries.firstOrNull { it.wire == rawSkip },
            failureStreak = readLocalInt(failureStreakKey) ?: 0,
            fingerprint = DatabaseChangeFingerprint.decode(readLocalString(fingerprintKey)),
            firstObservedAt = readLocalInstant(firstObservedAtKey),
        )
    }

    fun recordFirstObserved(at: Instant) = writeLocalString(firstObservedAtKey, at.toString())

    fun recordSuccess(at: Instant, fingerprint: DatabaseChangeFingerprint) {
        writeLocalString(lastSuccessAtKey, at.toString())
        writeLocalString(fingerprintKey, fingerprint.encode())
        writeLocalInt(failureStreakKey, 0)
        prefs.removeLocal(lastFailureAtKey)
        prefs.removeLocal(lastFailureMessageKey)
        prefs.removeLocal(lastSkipReasonKey)
    }

    fun recordFailure(at: Instant, message: String, previousStreak: Int) {
        writeLocalString(lastFailureAtKey, at.toString())
        writeLocalString(lastFailureMessageKey, message)
        writeLocalInt(failureStreakKey, previousStreak + 1)
        prefs.removeLocal(lastSkipReasonKey)
    }

    fun recordSkip(reason: LocalSnapshotSkipReason) = writeLocalString(lastSkipReasonKey, reason.wire)

    /**
     * Advances the change fingerprint without claiming a copy was written.
     *
     * Used when nothing changed since the last copy: re-checking the same
     * unchanged database on every resume is wasted work, but the timestamp of
     * the newest actual copy must not move, or the next real change would wait
     * a whole interval longer than the user asked for.
     */
    fun recordUnchanged(fingerprint: DatabaseChangeFingerprint) {
        writeLocalString(fingerprintKey, fingerprint.encode())
        writeLocalString(lastSkipReasonKey, LocalSnapshotSkipReason.UNCHANGED.wire)
    }

    // ── preference plumbing (values are JSON text, like the rest of the app) ──

    private fun readString(key: String): String? =
        prefs.readJson(key)?.let { raw ->
            runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    private fun readBool(key: String): Boolean? =
        prefs.readJson(key)?.let { raw ->
            runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.booleanOrNull }.getOrNull()
        }

    private fun readInt(key: String): Int? =
        prefs.readJson(key)?.let { raw ->
            runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.intOrNull }.getOrNull()
        }

    private fun readLong(key: String): Long? =
        prefs.readJson(key)?.let { raw ->
            runCatching {
                (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content?.toLongOrNull()
            }.getOrNull()
        }

    private fun writeString(key: String, value: String) =
        prefs.writeJson(key, JsonPrimitive(value).toString())

    private fun writeBool(key: String, value: Boolean) =
        prefs.writeJson(key, JsonPrimitive(value).toString())

    private fun writeInt(key: String, value: Int) =
        prefs.writeJson(key, JsonPrimitive(value).toString())

    private fun writeLong(key: String, value: Long) =
        prefs.writeJson(key, JsonPrimitive(value).toString())

    private fun readInstant(key: String): Instant? =
        readString(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun readLocalString(key: String): String? =
        prefs.readLocal(key)?.takeIf { it.isNotEmpty() }

    private fun readLocalInt(key: String): Int? = prefs.readLocal(key)?.toIntOrNull()

    private fun readLocalInstant(key: String): Instant? =
        readLocalString(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun writeLocalString(key: String, value: String) = prefs.writeLocal(key, value)

    private fun writeLocalInt(key: String, value: Int) = prefs.writeLocal(key, value.toString())

    private fun normalizeInterval(value: Int?): Int = when {
        value == null -> LocalSnapshotSettings.AUTOMATIC_INTERVAL
        value <= 0 -> LocalSnapshotSettings.AUTOMATIC_INTERVAL
        value > 365 -> 365
        else -> value
    }

    private fun normalizeTotalBytes(value: Long?): Long = when {
        value == null -> LocalSnapshotSettings.DEFAULT_MAXIMUM_TOTAL_BYTES
        value < 0 -> 0
        else -> value
    }

    companion object {
        const val enabledKey = "local_snapshot_enabled_v1"
        const val intervalDaysKey = "local_snapshot_interval_days_v1"
        const val keepRecentKey = "local_snapshot_keep_recent_v1"
        const val keepWeeklyKey = "local_snapshot_keep_weekly_v1"
        const val keepMonthlyKey = "local_snapshot_keep_monthly_v1"
        const val maximumTotalBytesKey = "local_snapshot_max_total_bytes_v1"
        const val announceResultKey = "local_snapshot_announce_v1"
        const val lastSuccessAtKey = "local_snapshot_last_success_at_v1"
        const val lastFailureAtKey = "local_snapshot_last_failure_at_v1"
        const val lastFailureMessageKey = "local_snapshot_last_failure_v1"
        const val lastSkipReasonKey = "local_snapshot_last_skip_v1"
        const val failureStreakKey = "local_snapshot_failure_streak_v1"
        const val fingerprintKey = "local_snapshot_fingerprint_v1"
        const val firstObservedAtKey = "local_snapshot_first_observed_at_v1"
    }
}
