package com.psyche.memo.ui.backup

import com.psyche.memo.data.backup.BackupPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic half of the backup progress UI, 1:1 with
 * `lib/features/backup/widgets/backup_progress_dialog.dart`
 * (`backupPhaseIcon` L224-243, `backupPhaseLabel` L245-264) plus the shared
 * `format_bytes.dart` helpers the subtitle line uses.
 *
 * Composables themselves are not exercised here — this covers the exhaustive
 * `when` tables (so a new [BackupPhase] can never fall through silently) and
 * the formatter edge cases.
 */
class BackupProgressUiTest {

    @Test
    fun everyPhase_mapsToAnIcon() {
        // Exhaustive by construction: the `when` in backupPhaseIcon has no else,
        // so the compiler enforces coverage; this pins the count too.
        assertEquals(16, BackupPhase.entries.size)
        for (phase in BackupPhase.entries) {
            val icon = backupPhaseIcon(phase)
            assertTrue("icon for $phase must be non-empty", icon.name.isNotEmpty())
        }
    }

    @Test
    fun iconTable_matchesDartSwitchCases() {
        // Dart groups by (icon -> phases); assert the grouping stays intact.
        assertEquals(
            backupPhaseIcon(BackupPhase.VERIFYING),
            backupPhaseIcon(BackupPhase.VALIDATING),
        )
        assertEquals(
            backupPhaseIcon(BackupPhase.STAGING_CANDIDATE),
            backupPhaseIcon(BackupPhase.COMMITTING),
        )
        // Distinct icons where Dart uses distinct ones.
        assertNotEquals(
            backupPhaseIcon(BackupPhase.IMPORTING_SESSIONS),
            backupPhaseIcon(BackupPhase.IMPORTING_MESSAGES),
        )
        assertNotEquals(
            backupPhaseIcon(BackupPhase.PREPARING),
            backupPhaseIcon(BackupPhase.FINALIZING),
        )
    }

    @Test
    fun everyPhase_mapsToALabelResource() {
        for (phase in BackupPhase.entries) {
            assertNotEquals("label for $phase must not be 0", 0, backupPhaseLabelRes(phase))
        }
    }

    @Test
    fun labelTable_hasNoAccidentalSharing() {
        val byRes = BackupPhase.entries.groupBy { backupPhaseLabelRes(it) }
        val shared = byRes.filterValues { it.size > 1 }
        assertEquals("each phase must own its label", emptyMap<Int, List<BackupPhase>>(), shared)
    }

    @Test
    fun formatBytes_belowThousand_isRawBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1 B", formatBytes(1))
        assertEquals("999 B", formatBytes(999))
    }

    @Test
    fun formatBytes_usesDecimalUnits() {
        assertEquals("1.0 KB", formatBytes(1000))
        assertEquals("1.5 KB", formatBytes(1500))
        assertEquals("1.0 MB", formatBytes(1_000_000))
        assertEquals("1.0 GB", formatBytes(1_000_000_000))
        assertEquals("1.0 TB", formatBytes(1_000_000_000_000))
    }

    @Test
    fun formatBytes_stopsAtTerabytes() {
        // Beyond TB the value keeps growing inside the last unit (Dart parity).
        assertEquals("1000.0 TB", formatBytes(1_000_000_000_000_000))
    }

    @Test
    fun formatCount_groupsThousands() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,000", formatCount(1000))
        assertEquals("1,234,567", formatCount(1_234_567))
    }
}
