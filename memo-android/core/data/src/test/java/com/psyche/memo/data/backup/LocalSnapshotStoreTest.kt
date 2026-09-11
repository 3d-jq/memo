package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * local_snapshot_store.dart — naming, publish (write-then-rename + sidecar),
 * listing, pinning, pruning and debris sweeping, on a real temp directory.
 */
class LocalSnapshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = LocalSnapshotStore(temp.root)

    private fun prepared(bytes: Int = 64): File =
        File(temp.root, "cache-archive.zip").apply { writeBytes(ByteArray(bytes) { 7 }) }

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    @Test
    fun `file names round-trip through the timestamp`() {
        val name = LocalSnapshotPaths.fileNameFor(t0)
        assertTrue(name.startsWith(LocalSnapshotPaths.FILE_PREFIX))
        assertTrue(name.endsWith(LocalSnapshotPaths.FILE_SUFFIX))
        assertEquals(t0, LocalSnapshotPaths.createdAtFromFileName(name))

        assertNull(LocalSnapshotPaths.createdAtFromFileName("random.zip"))
        assertNull(LocalSnapshotPaths.createdAtFromFileName("memo-snapshot-abc.zip"))
        assertNull(LocalSnapshotPaths.createdAtFromFileName("memo-snapshot-0001.txt"))
    }

    @Test
    fun `publish moves the archive in and writes the sidecar`() {
        val store = store()
        val source = prepared()
        val entry = store.publish(
            prepared = source,
            createdAtUtc = t0,
            origin = LocalSnapshotOrigin.MANUAL,
            conversationCount = 3,
            messageCount = 42,
            appVersion = "1.2.5+2073",
        )

        assertFalse("the prepared archive is consumed", source.exists())
        assertTrue(entry.file.exists())
        assertTrue(File(entry.file.path + LocalSnapshotPaths.METADATA_SUFFIX).exists())
        assertEquals(LocalSnapshotOrigin.MANUAL, entry.origin)
        assertEquals(42, entry.messageCount)

        val listed = store.list().single()
        assertEquals(entry.id, listed.id)
        assertEquals(64L, listed.bytes)
        assertEquals(3, listed.conversationCount)
        assertEquals("1.2.5+2073", listed.appVersion)
        assertFalse(listed.pinned)
    }

    @Test
    fun `publish refuses an existing name and an empty archive`() {
        val store = store()
        store.publish(prepared(), t0, LocalSnapshotOrigin.MANUAL, 1, 1)

        val duplicate = runCatching {
            store.publish(prepared(), t0, LocalSnapshotOrigin.MANUAL, 1, 1)
        }
        assertEquals("local_snapshot_exists", duplicate.exceptionOrNull()?.message)

        val empty = runCatching {
            store.publish(prepared(bytes = 0), t0.plusSeconds(1), LocalSnapshotOrigin.MANUAL, 0, 0)
        }
        assertEquals("local_snapshot_empty", empty.exceptionOrNull()?.message)
        // The failed attempt leaves no debris behind.
        assertEquals(1, store.list().size)
    }

    @Test
    fun `delete removes the archive and its sidecar`() {
        val store = store()
        val entry = store.publish(prepared(), t0, LocalSnapshotOrigin.MANUAL, 1, 1)
        val sidecar = File(entry.file.path + LocalSnapshotPaths.METADATA_SUFFIX)

        store.delete(entry.id)

        assertFalse(entry.file.exists())
        assertFalse(sidecar.exists())
        assertTrue(store.list().isEmpty())

        val bogus = runCatching { store.delete("not-a-snapshot.zip") }
        assertTrue(bogus.isFailure)
    }

    @Test
    fun `pinning survives a re-read`() {
        val store = store()
        val entry = store.publish(prepared(), t0, LocalSnapshotOrigin.BEFORE_RESTORE, 1, 1, pinned = true)
        assertTrue(store.list().single().pinned)

        store.setPinned(entry.id, false)
        assertFalse(store.list().single().pinned)

        // Unknown ids are ignored rather than throwing.
        store.setPinned("memo-snapshot-0000000000000000000.zip", true)
    }

    @Test
    fun `prune trims the set and reports what went`() {
        val store = store()
        val ids = (0..4).map { index ->
            store.publish(
                prepared(),
                t0.plusSeconds(index.toLong()),
                LocalSnapshotOrigin.AUTOMATIC,
                1,
                100,
            ).id
        }

        val removed = store.prune(
            LocalSnapshotRetentionPolicy(keepRecent = 1, keepWeekly = false, keepMonthly = false),
            now = t0.plusSeconds(60),
        )

        assertEquals(4, removed.size)
        assertEquals(ids.first(), removed.first().id)
        assertEquals(listOf(ids.last()), store.list().map { it.id })
    }

    @Test
    fun `a missing sidecar does not hide the archive`() {
        val store = store()
        val entry = store.publish(prepared(), t0, LocalSnapshotOrigin.MANUAL, 5, 50)
        File(entry.file.path + LocalSnapshotPaths.METADATA_SUFFIX).delete()

        val listed = store.list().single()
        assertEquals(1, listed.messageCount) // unknown counts as "has content"
        assertFalse(listed.pinned)
    }

    @Test
    fun `listing ignores foreign files and empty archives`() {
        val store = store()
        store.publish(prepared(), t0, LocalSnapshotOrigin.MANUAL, 1, 1)
        File(store.directory, "notes.txt").writeText("hi")
        File(store.directory, LocalSnapshotPaths.fileNameFor(t0.plusSeconds(5))).writeBytes(ByteArray(0))

        assertEquals(1, store.list().size)
    }

    @Test
    fun `sweep clears interrupted attempts and keeps healthy pairs`() {
        val store = store()
        val entry = store.publish(prepared(), t0, LocalSnapshotOrigin.MANUAL, 1, 1)
        val staged = File(store.directory, LocalSnapshotPaths.TEMPORARY_PREFIX + LocalSnapshotPaths.fileNameFor(t0))
        staged.writeBytes(ByteArray(10))
        val orphanSidecar = File(store.directory, LocalSnapshotPaths.fileNameFor(t0.plusSeconds(9)) + LocalSnapshotPaths.METADATA_SUFFIX)
        orphanSidecar.writeText("{}")

        store.sweepIncomplete()

        assertFalse(staged.exists())
        assertFalse(orphanSidecar.exists())
        assertTrue(entry.file.exists())
        assertNotNull(store.byId(entry.id))
    }

    @Test
    fun `total bytes sums the listed copies`() {
        val store = store()
        store.publish(prepared(bytes = 100), t0, LocalSnapshotOrigin.MANUAL, 1, 1)
        store.publish(prepared(bytes = 250), t0.plusSeconds(1), LocalSnapshotOrigin.MANUAL, 1, 1)
        assertEquals(350L, store.totalBytes())
    }
}
