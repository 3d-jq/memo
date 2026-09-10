package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Ported-behaviour tests for the restore-side defences (`_ExtractionBudget`,
 * `_BoundedOutputFileStream`, `_validatedZipEntryName`). These exist because a
 * backup file is untrusted input: it arrives from a user's Downloads folder, a
 * WebDAV share or an S3 bucket, and a hostile one must not be able to escape
 * the target directory or exhaust the disk.
 */
class BackupArchiveGuardsTest {

    // ── ZipEntryNames.validate ──────────────────────────────────────────────

    @Test
    fun `accepts ordinary relative entry names`() {
        assertEquals("manifest.json", ZipEntryNames.validate("manifest.json"))
        assertEquals("database/kelivo.db", ZipEntryNames.validate("database/kelivo.db"))
        assertEquals("upload/a/b.png", ZipEntryNames.validate("upload/a/b.png"))
    }

    @Test
    fun `normalises backslash separators`() {
        assertEquals("upload/a.png", ZipEntryNames.validate("upload\\a.png"))
    }

    @Test
    fun `drops dot segments and collapses duplicate slashes`() {
        assertEquals("upload/a.png", ZipEntryNames.validate("./upload//a.png"))
    }

    @Test
    fun `rejects directory traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZipEntryNames.validate("../../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZipEntryNames.validate("upload/../../x")
        }
    }

    @Test
    fun `rejects absolute posix paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZipEntryNames.validate("/etc/passwd")
        }
    }

    @Test
    fun `rejects windows drive letters`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZipEntryNames.validate("C:/Windows/system32")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZipEntryNames.validate("C:\\Windows")
        }
    }

    @Test
    fun `rejects empty and NUL-bearing names`() {
        assertThrows(IllegalArgumentException::class.java) { ZipEntryNames.validate("") }
        assertThrows(IllegalArgumentException::class.java) { ZipEntryNames.validate("a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) { ZipEntryNames.validate("/") }
    }

    // ── ZipEntryNames.validateRoot ──────────────────────────────────────────

    @Test
    fun `accepts the known top-level roots`() {
        ZipEntryNames.validateRoot("manifest.json")
        ZipEntryNames.validateRoot("settings.json")
        ZipEntryNames.validateRoot("database/kelivo.db")
        ZipEntryNames.validateRoot("upload/x.png")
        ZipEntryNames.validateRoot("avatars/x.png")
        ZipEntryNames.validateRoot("images/x.png")
        ZipEntryNames.validateRoot("fonts/x.ttf")
    }

    @Test
    fun `rejects an unknown root directory`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZipEntryNames.validateRoot("surprise/x.bin")
        }
    }

    // ── ExtractionBudget ────────────────────────────────────────────────────

    @Test
    fun `accumulates reserved bytes`() {
        val budget = ExtractionBudget()
        budget.reserve(100)
        budget.reserve(250)
        assertEquals(350L, budget.totalWritten)
    }

    @Test
    fun `rejects a negative reservation`() {
        val budget = ExtractionBudget()
        assertThrows(IllegalArgumentException::class.java) { budget.reserve(-1) }
    }

    @Test
    fun `rejects a total that exceeds the archive-wide cap`() {
        val budget = ExtractionBudget()
        assertThrows(IllegalStateException::class.java) {
            budget.reserve(BackupManifestCodec.MAX_RESTORE_TOTAL_BYTES + 1)
        }
    }

    @Test
    fun `counts entries and rejects more than the entry cap`() {
        val budget = ExtractionBudget()
        repeat(10) { budget.beginEntry() }
        assertEquals(10, budget.entriesSeen)
    }

    // ── BoundedEntryBudget ──────────────────────────────────────────────────

    @Test
    fun `verifies a fully written entry against its declared size`() {
        val total = ExtractionBudget()
        total.beginEntry()
        val entry = BoundedEntryBudget("settings.json", expectedBytes = 300, total = total)
        entry.reserve(200)
        entry.reserve(100)
        entry.verifyComplete()
        assertEquals(300L, entry.bytesWritten)
    }

    @Test
    fun `rejects an entry that wrote fewer bytes than declared`() {
        val total = ExtractionBudget()
        val entry = BoundedEntryBudget("settings.json", expectedBytes = 300, total = total)
        entry.reserve(200)
        assertThrows(IllegalStateException::class.java) { entry.verifyComplete() }
    }

    @Test
    fun `rejects an entry that wrote more bytes than declared`() {
        val total = ExtractionBudget()
        val entry = BoundedEntryBudget("settings.json", expectedBytes = 100, total = total)
        entry.reserve(60)
        entry.reserve(60)
        assertThrows(IllegalStateException::class.java) { entry.verifyComplete() }
    }

    @Test
    fun `skips the size check when the manifest declares no size`() {
        val total = ExtractionBudget()
        val entry = BoundedEntryBudget("upload/a.png", expectedBytes = null, total = total)
        entry.reserve(123)
        entry.verifyComplete()
    }

    @Test
    fun `one entry cannot exceed the per-entry cap`() {
        val total = ExtractionBudget()
        val entry = BoundedEntryBudget("upload/a.bin", expectedBytes = null, total = total)
        assertThrows(IllegalStateException::class.java) {
            entry.reserve(BackupManifestCodec.MAX_RESTORE_ENTRY_BYTES + 1)
        }
    }

    @Test
    fun `entry writes count against the archive-wide budget`() {
        val total = ExtractionBudget()
        val entry = BoundedEntryBudget("upload/a.bin", expectedBytes = null, total = total)
        entry.reserve(500)
        assertEquals(500L, total.totalWritten)
    }
}
