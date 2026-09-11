package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.backup.RestoreMode
import com.psyche.memo.ui.MemoryProviderV2
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Memory entries travel through a backup as typed `memory_entry_rows` rows
 * (the payload alone is not enough — the table's NOT NULL columns are rejected
 * by its CHECK constraints), so an export/restore round-trip has to bring them
 * back intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryBackupRoundTripTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        container.database.writableDatabase.execSQL("DELETE FROM memory_entry_rows")
        container.database.writableDatabase.execSQL("DELETE FROM preference_rows")
    }

    @Test
    fun `memory entries survive an export and restore`() {
        val provider = container.memoryProviderV2
        provider.initialize()
        provider.create(
            scope = MemoryScope.assistant,
            assistantId = "a1",
            type = MemoryType.identity,
            content = "Prefers dark mode",
            source = MemorySource.manual,
        )

        val archive = container.backupService.exportToCache(includeChats = false, includeFiles = false)
        container.database.writableDatabase.execSQL("DELETE FROM memory_entry_rows")

        container.backupService.restoreFromFile(archive, RestoreMode.OVERWRITE)
        archive.delete()

        val restored = MemoryProviderV2(container.database.writableDatabase)
        restored.initialize()
        val entry = restored.entries.single()
        assertEquals(MemoryScope.assistant, entry.scope)
        assertEquals("a1", entry.assistantId)
        assertEquals("Prefers dark mode", entry.content)
    }
}
