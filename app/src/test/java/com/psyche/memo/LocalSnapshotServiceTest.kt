package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.backup.DatabaseChangeFingerprint
import com.psyche.memo.data.backup.LocalSnapshotOrigin
import com.psyche.memo.data.backup.LocalSnapshotPreferences
import com.psyche.memo.data.backup.LocalSnapshotRetentionPolicy
import com.psyche.memo.data.backup.LocalSnapshotRunResult
import com.psyche.memo.data.backup.LocalSnapshotSettings
import com.psyche.memo.data.backup.LocalSnapshotSkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * The on-device copies: settings/state persistence (the prefs layer) and the
 * service end to end — take a copy through the real backup engine, list it,
 * prune it, and let the schedule decide when a copy is due.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSnapshotServiceTest {

    private lateinit var container: AppContainerImpl
    private lateinit var preferences: LocalSnapshotPreferences

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        container.database.writableDatabase.execSQL("DELETE FROM preference_rows")
        // The run state lives in SharedPreferences (deliberately — see the
        // preference class), so clear it too.
        container.appContext.getSharedPreferences("memo_preferences", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        preferences = LocalSnapshotPreferences(container.preferenceRepository)
        // Start from an empty snapshots directory: files persist between tests
        // inside one Robolectric sandbox.
        container.localSnapshots.store().directory.deleteRecursively()
    }

    // ---- settings + state ----------------------------------------------------

    @Test
    fun `settings default to automatic interval and ten gigabyte cap`() {
        val settings = preferences.readSettings()
        assertEquals(true, settings.enabled)
        assertEquals(LocalSnapshotSettings.AUTOMATIC_INTERVAL, settings.intervalDays)
        assertEquals(3, settings.keepRecent)
        assertEquals(true, settings.keepWeekly)
        assertEquals(true, settings.keepMonthly)
        assertEquals(LocalSnapshotSettings.DEFAULT_MAXIMUM_TOTAL_BYTES, settings.maximumTotalBytes)
        assertEquals(false, settings.announceResult)
    }

    @Test
    fun `settings round-trip and clamp out-of-range values`() {
        preferences.writeSettings(
            LocalSnapshotSettings(
                enabled = false,
                intervalDays = 900,
                keepRecent = 99,
                keepWeekly = false,
                keepMonthly = false,
                maximumTotalBytes = -5,
                announceResult = true,
            ),
        )
        val stored = preferences.readSettings()
        assertEquals(false, stored.enabled)
        assertEquals(365, stored.intervalDays)
        assertEquals(LocalSnapshotSettings.MAXIMUM_KEEP_RECENT, stored.keepRecent)
        assertEquals(0L, stored.maximumTotalBytes)
        assertEquals(true, stored.announceResult)

        // The retention policy mirrors the clamped values.
        assertEquals(
            LocalSnapshotRetentionPolicy(
                keepRecent = LocalSnapshotSettings.MAXIMUM_KEEP_RECENT,
                keepWeekly = false,
                keepMonthly = false,
                maximumTotalBytes = 0,
            ),
            stored.retention,
        )
    }

    @Test
    fun `state records success, unchanged and failure`() {
        assertNull(preferences.readState().lastSuccessAt)

        val at = Instant.parse("2026-09-11T10:00:00Z")
        val fingerprint = DatabaseChangeFingerprint(1, 2, 3, 4)
        preferences.recordSuccess(at, fingerprint)
        var state = preferences.readState()
        assertEquals(at, state.lastSuccessAt)
        assertEquals(fingerprint, state.fingerprint)
        assertEquals(0, state.failureStreak)

        preferences.recordUnchanged(DatabaseChangeFingerprint(5, 6, 7, 8))
        state = preferences.readState()
        assertEquals(LocalSnapshotSkipReason.UNCHANGED, state.lastSkipReason)
        assertEquals(DatabaseChangeFingerprint(5, 6, 7, 8), state.fingerprint)

        preferences.recordFailure(at, "boom", previousStreak = 0)
        state = preferences.readState()
        assertEquals(at, state.lastFailureAt)
        assertEquals("boom", state.lastFailureMessage)
        assertEquals(1, state.failureStreak)
        // A failure clears the skip marker so the status line reports the error.
        assertNull(state.lastSkipReason)
    }

    // ---- the service ---------------------------------------------------------

    @Test
    fun `take publishes a restorable archive`() {
        val entry = container.localSnapshots.take(origin = LocalSnapshotOrigin.MANUAL)
        assertTrue(entry.file.exists())
        assertTrue(entry.bytes > 0)
        assertEquals(LocalSnapshotOrigin.MANUAL, entry.origin)
        assertNotNull(container.backupService.peekManifest(entry.file))

        val listed = container.localSnapshots.list()
        assertEquals(listOf(entry.id), listed.map { it.id })
        // Counts come from the archive's own manifest.
        assertTrue(entry.messageCount >= 0)
    }

    @Test
    fun `prune keeps only what the policy says`() {
        val first = container.localSnapshots.take(origin = LocalSnapshotOrigin.MANUAL, now = Instant.parse("2026-09-01T10:00:00Z"))
        val second = container.localSnapshots.take(origin = LocalSnapshotOrigin.MANUAL, now = Instant.parse("2026-09-02T10:00:00Z"))

        preferences.writeSettings(
            LocalSnapshotSettings(keepRecent = 1, keepWeekly = false, keepMonthly = false, maximumTotalBytes = 0),
        )
        val removed = container.localSnapshots.pruneNow(Instant.parse("2026-09-03T10:00:00Z"))

        assertEquals(listOf(first.id), removed.map { it.id })
        assertEquals(listOf(second.id), container.localSnapshots.list().map { it.id })
    }

    @Test
    fun `an existing snapshot adds to the set instead of replacing`() {
        container.localSnapshots.take(origin = LocalSnapshotOrigin.AUTOMATIC, now = Instant.parse("2026-09-01T10:00:00Z"))
        container.localSnapshots.take(origin = LocalSnapshotOrigin.MANUAL, now = Instant.parse("2026-09-01T10:00:01Z"))
        assertEquals(2, container.localSnapshots.list().size)
    }

    @Test
    fun `runIfDue waits out the first observation grace period`() {
        // Real time: the app's own launch hook runs `runIfDue` when the
        // Robolectric application is created, so the first observation is
        // already on record and only a later check can be due.
        val now = Instant.now()
        val first = container.localSnapshots.runIfDue(now)
        assertEquals(LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.NOT_DUE), first)
        assertNotNull(preferences.readState().firstObservedAt)

        // Inside the grace window nothing is taken even though no copy has ever
        // been written successfully.
        val second = container.localSnapshots.runIfDue(now.plus(Duration.ofMinutes(1)))
        assertEquals(LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.NOT_DUE), second)
        assertTrue(container.localSnapshots.list().isEmpty())
    }

    @Test
    fun `runIfDue takes a copy once due and then skips the unchanged database`() {
        val now = Instant.parse("2026-09-11T10:00:00Z")
        // Pretend this install has been observed long ago.
        preferences.recordFirstObserved(now.minus(Duration.ofDays(30)))

        val first = container.localSnapshots.runIfDue(now)
        assertTrue(first is LocalSnapshotRunResult.Created)
        val created = first as LocalSnapshotRunResult.Created
        assertEquals(LocalSnapshotOrigin.AUTOMATIC, created.entry.origin)
        assertEquals(1, container.localSnapshots.list().size)

        // Nothing changed since, so the next check skips instead of writing a
        // duplicate that would collapse the retention depth.
        val second = container.localSnapshots.runIfDue(now.plus(Duration.ofDays(30)))
        assertEquals(LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.UNCHANGED), second)
        assertEquals(1, container.localSnapshots.list().size)
    }

    @Test
    fun `the fingerprint reads the live database, not the files directory`() {
        // Android keeps the database in /data/data/<pkg>/databases, which is not
        // under filesDir — pointing the fingerprint at filesDir/databases would
        // make every check read "unchanged" and silently stop the schedule.
        assertTrue(container.localSnapshots.databaseFile.exists())
        val fingerprint = DatabaseChangeFingerprint.read(container.localSnapshots.databaseFile)
        assertTrue(fingerprint.databaseBytes > 0)
        assertTrue(fingerprint.matches(container.localSnapshots.databaseFile.let { DatabaseChangeFingerprint.read(it) }))
    }

    @Test
    fun `a changed database is copied again`() {
        val now = Instant.parse("2026-09-11T10:00:00Z")
        preferences.recordFirstObserved(now.minus(Duration.ofDays(30)))

        assertTrue(container.localSnapshots.runIfDue(now) is LocalSnapshotRunResult.Created)

        // Any write moves the -wal sidecar, which is exactly the signal the
        // schedule is supposed to notice.
        container.preferenceRepository.writeJson("snapshot_test_marker_v1", "\"changed\"")

        assertTrue(
            container.localSnapshots.runIfDue(now.plus(Duration.ofDays(2))) is LocalSnapshotRunResult.Created,
        )
        assertEquals(2, container.localSnapshots.list().size)
    }

    @Test
    fun `a disabled schedule takes nothing`() {
        preferences.writeSettings(LocalSnapshotSettings(enabled = false))
        preferences.recordFirstObserved(Instant.parse("2026-01-01T00:00:00Z"))
        val result = container.localSnapshots.runIfDue(Instant.parse("2026-09-11T10:00:00Z"))
        assertEquals(LocalSnapshotRunResult.Skipped(LocalSnapshotSkipReason.DISABLED), result)
        assertTrue(container.localSnapshots.list().isEmpty())
    }

    @Test
    fun `delete and pin survive through the service facade`() {
        val entry = container.localSnapshots.take(origin = LocalSnapshotOrigin.BEFORE_RESTORE, pinned = true)
        assertTrue(container.localSnapshots.list().single().pinned)

        container.localSnapshots.setPinned(entry.id, false)
        assertFalse(container.localSnapshots.list().single().pinned)

        container.localSnapshots.delete(entry.id)
        assertTrue(container.localSnapshots.list().isEmpty())
    }
}
