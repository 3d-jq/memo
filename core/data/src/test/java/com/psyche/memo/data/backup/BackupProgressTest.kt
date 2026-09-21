package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the progress model the UI reads: the phase wire values must stay in
 * sync with the strings the builder/restorer emit, because a mismatch silently
 * degrades every progress label to "Preparing".
 */
class BackupProgressTest {

    @Test
    fun `every phase round-trips through its wire value`() {
        for (phase in BackupPhase.entries) {
            val wire = BackupPhase.wireOf(phase)
            assertEquals(phase, BackupPhase.fromWire(wire))
        }
    }

    @Test
    fun `wire values match the constants the builder and restorer emit`() {
        assertEquals("preparing", BackupPhase.wireOf(BackupPhase.PREPARING))
        assertEquals("snapshotting_database", BackupPhase.wireOf(BackupPhase.SNAPSHOTTING_DATABASE))
        assertEquals("packing", BackupPhase.wireOf(BackupPhase.PACKING))
        assertEquals("verifying", BackupPhase.wireOf(BackupPhase.VERIFYING))
        assertEquals("extracting", BackupPhase.wireOf(BackupPhase.EXTRACTING))
        assertEquals("reading_settings", BackupPhase.wireOf(BackupPhase.READING_SETTINGS))
        assertEquals("validating", BackupPhase.wireOf(BackupPhase.VALIDATING))
        assertEquals("staging_candidate", BackupPhase.wireOf(BackupPhase.STAGING_CANDIDATE))
        assertEquals("finalizing", BackupPhase.wireOf(BackupPhase.FINALIZING))

        // The classes that actually emit these strings must agree, so a typo in
        // either side fails here rather than as a wrong label at runtime.
        assertEquals(BackupSnapshotBuilder.PHASE_PREPARING, BackupPhase.wireOf(BackupPhase.PREPARING))
        assertEquals(
            BackupSnapshotBuilder.PHASE_SNAPSHOTTING_DATABASE,
            BackupPhase.wireOf(BackupPhase.SNAPSHOTTING_DATABASE),
        )
        assertEquals(BackupSnapshotBuilder.PHASE_PACKING, BackupPhase.wireOf(BackupPhase.PACKING))
        assertEquals(BackupSnapshotBuilder.PHASE_VERIFYING, BackupPhase.wireOf(BackupPhase.VERIFYING))
        assertEquals(BackupRestorer.PHASE_READING_MANIFEST, BackupPhase.wireOf(BackupPhase.READING_SETTINGS))
        assertEquals(BackupRestorer.PHASE_EXTRACTING, BackupPhase.wireOf(BackupPhase.EXTRACTING))
        assertEquals(BackupRestorer.PHASE_APPLYING_SETTINGS, BackupPhase.wireOf(BackupPhase.VALIDATING))
        assertEquals(BackupRestorer.PHASE_APPLYING_DATABASE, BackupPhase.wireOf(BackupPhase.STAGING_CANDIDATE))
        assertEquals(BackupRestorer.PHASE_APPLYING, BackupPhase.wireOf(BackupPhase.FINALIZING))

        // And every emitting constant must be a value the enum recognises.
        for (wire in listOf(
            BackupSnapshotBuilder.PHASE_PREPARING,
            BackupSnapshotBuilder.PHASE_SNAPSHOTTING_DATABASE,
            BackupSnapshotBuilder.PHASE_PACKING,
            BackupSnapshotBuilder.PHASE_VERIFYING,
            BackupRestorer.PHASE_READING_MANIFEST,
            BackupRestorer.PHASE_EXTRACTING,
            BackupRestorer.PHASE_APPLYING_SETTINGS,
            BackupRestorer.PHASE_APPLYING_DATABASE,
            BackupRestorer.PHASE_APPLYING,
        )) {
            val phase = BackupPhase.fromWire(wire)
            assertEquals("$wire must round-trip", wire, BackupPhase.wireOf(phase))
        }
    }

    @Test
    fun `unknown wire value degrades to preparing instead of throwing`() {
        assertEquals(BackupPhase.PREPARING, BackupPhase.fromWire("who_knows"))
        assertEquals(BackupPhase.PREPARING, BackupPhase.fromWire(""))
    }

    @Test
    fun `fraction is null while the total is unknown`() {
        assertNull(progress(processed = 10, total = null).fraction)
        assertNull(progress(processed = 10, total = -1).fraction)
        assertNull(progress(processed = 0, total = 0).fraction)
    }

    @Test
    fun `fraction is clamped to the unit interval`() {
        assertEquals(0.5f, progress(processed = 5, total = 10).fraction!!, 1e-6f)
        assertEquals(1f, progress(processed = 20, total = 10).fraction!!, 1e-6f)
        assertEquals(0f, progress(processed = -5, total = 10).fraction!!, 1e-6f)
    }

    @Test
    fun `bridge forwards phases and maps totals to a unit`() {
        val seen = mutableListOf<BackupProgress>()
        val bridge = ProgressBridge { seen += it }

        bridge.report("packing", 100, -1)
        bridge.report("packing", 100, 400)
        bridge.report("packing", 100, 0)

        assertEquals(BackupPhase.PACKING, seen[0].phase)
        assertEquals(BackupProgressUnit.NONE, seen[0].unit)
        assertNull(seen[0].total)

        assertEquals(BackupProgressUnit.BYTES, seen[1].unit)
        assertEquals(400L, seen[1].total)

        assertEquals(BackupProgressUnit.BYTES, seen[2].unit)
        assertEquals(0L, seen[2].total)
    }

    @Test
    fun `bridge without a sink is a no-op`() {
        val bridge = ProgressBridge(null)
        bridge.report("preparing", 0, -1)
    }

    @Test
    fun `cancelled exception carries no stack-sensitive state`() {
        val error = assertThrows(BackupCancelledException::class.java) {
            throw BackupCancelledException()
        }
        assertTrue(error.message!!.isNotEmpty())
    }

    private fun progress(processed: Long, total: Long?) = BackupProgress(
        phase = BackupPhase.PACKING,
        processed = processed,
        total = total,
    )
}
