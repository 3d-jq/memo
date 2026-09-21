package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** local_snapshot_schedule.dart — the due decision and the change fingerprint. */
class LocalSnapshotScheduleTest {

    private val now: Instant = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun `the default interval follows the database size`() {
        assertEquals(Duration.ofDays(1), LocalSnapshotSchedule.defaultIntervalFor(10L * 1024 * 1024))
        assertEquals(Duration.ofDays(3), LocalSnapshotSchedule.defaultIntervalFor(200L * 1024 * 1024))
        assertEquals(Duration.ofDays(7), LocalSnapshotSchedule.defaultIntervalFor(1024L * 1024 * 1024))
    }

    @Test
    fun `the interval follows the chosen days when set`() {
        assertEquals(Duration.ofDays(14), LocalSnapshotSettings(intervalDays = 14).intervalFor(0))
        assertEquals(
            Duration.ofDays(1),
            LocalSnapshotSettings(intervalDays = LocalSnapshotSettings.AUTOMATIC_INTERVAL).intervalFor(0),
        )
    }

    @Test
    fun `estimated space covers the intermediate and the packed copy`() {
        val bytes = 300L * 1024 * 1024
        assertEquals((bytes * 1.2).toLong() + bytes / 3, LocalSnapshotSchedule.estimatedSpaceRequired(bytes))
    }

    @Test
    fun `a disabled schedule skips as disabled`() {
        assertEquals(
            LocalSnapshotSkipReason.DISABLED,
            LocalSnapshotSchedule.dueSkipReason(now, enabled = false, interval = Duration.ofDays(1), lastSuccessAt = null, lastFailureAt = null),
        )
    }

    @Test
    fun `a recent failure backs off`() {
        assertEquals(
            LocalSnapshotSkipReason.BACKOFF,
            LocalSnapshotSchedule.dueSkipReason(
                now,
                enabled = true,
                interval = Duration.ofDays(1),
                lastSuccessAt = now.minus(Duration.ofDays(5)),
                lastFailureAt = now.minus(Duration.ofMinutes(10)),
            ),
        )
        // Past the backoff it is due again.
        assertNull(
            LocalSnapshotSchedule.dueSkipReason(
                now,
                enabled = true,
                interval = Duration.ofDays(1),
                lastSuccessAt = now.minus(Duration.ofDays(5)),
                lastFailureAt = now.minus(Duration.ofHours(3)),
            ),
        )
    }

    @Test
    fun `a copy taken inside the interval is not due yet`() {
        assertEquals(
            LocalSnapshotSkipReason.NOT_DUE,
            LocalSnapshotSchedule.dueSkipReason(
                now,
                enabled = true,
                interval = Duration.ofDays(3),
                lastSuccessAt = now.minus(Duration.ofDays(1)),
                lastFailureAt = null,
            ),
        )
        assertNull(
            LocalSnapshotSchedule.dueSkipReason(
                now,
                enabled = true,
                interval = Duration.ofDays(3),
                lastSuccessAt = now.minus(Duration.ofDays(4)),
                lastFailureAt = null,
            ),
        )
    }

    @Test
    fun `a clock moved backwards does not park the schedule`() {
        assertNull(
            LocalSnapshotSchedule.dueSkipReason(
                now,
                enabled = true,
                interval = Duration.ofDays(1),
                lastSuccessAt = now.plus(Duration.ofHours(6)),
                lastFailureAt = null,
            ),
        )
    }

    @Test
    fun `the failure backoff grows with the streak`() {
        assertEquals(LocalSnapshotSchedule.FAILURE_BACKOFF, LocalSnapshotState(failureStreak = 1).failureBackoff)
        assertEquals(Duration.ofHours(2), LocalSnapshotState(failureStreak = 2).failureBackoff)
        assertEquals(Duration.ofHours(8), LocalSnapshotState(failureStreak = 4).failureBackoff)
        // The streak is capped so the wait cannot explode.
        assertEquals(Duration.ofHours(16), LocalSnapshotState(failureStreak = 9).failureBackoff)
    }

    @Test
    fun `the fingerprint round-trips its wire form`() {
        val fingerprint = DatabaseChangeFingerprint(100, 200, 300, 400)
        assertEquals(fingerprint, DatabaseChangeFingerprint.decode(fingerprint.encode()))
        assertNull(DatabaseChangeFingerprint.decode("1:2:3"))
        assertNull(DatabaseChangeFingerprint.decode("a:b:c:d"))
        assertNull(DatabaseChangeFingerprint.decode(null))
    }

    @Test
    fun `fingerprints match only when both sides were readable`() {
        val fingerprint = DatabaseChangeFingerprint(100, 200, 300, 400)
        assertTrue(fingerprint.matches(DatabaseChangeFingerprint(100, 200, 300, 400)))
        assertFalse(fingerprint.matches(DatabaseChangeFingerprint(100, 200, 300, 401)))
        assertFalse(fingerprint.matches(null))
        // An unreadable stat reads as a change, so an unchanged launch cannot
        // skip a copy it should have taken.
        val unknown = DatabaseChangeFingerprint(-1, -1, -1, -1)
        assertFalse(unknown.matches(unknown))
        assertFalse(fingerprint.matches(unknown))
    }

    @Test
    fun `a snapshot without a wal file still compares equal`() {
        // A checkpointed database has no -wal beside it; two launches that both
        // find none really are unchanged.
        val first = DatabaseChangeFingerprint(10, 20, 0, 0)
        val second = DatabaseChangeFingerprint(10, 20, 0, 0)
        assertTrue(first.matches(second))
    }
}
