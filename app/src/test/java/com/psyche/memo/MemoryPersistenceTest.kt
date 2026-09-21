package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.ui.MemoryProviderV2
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Long-term memory must survive a restart: the store is rebuilt from
 * `memory_entry_rows` / `assistant_memory_rows` on every launch, so a create
 * followed by a fresh provider has to see the entry again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryPersistenceTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        container.database.writableDatabase.execSQL("DELETE FROM memory_entry_rows")
    }

    @Test
    fun `memory entries survive a provider restart`() {
        val first = container.memoryProviderV2
        first.initialize()
        val created = first.create(
            scope = MemoryScope.global,
            assistantId = null,
            type = MemoryType.identity,
            content = "Prefers dark mode",
            source = MemorySource.manual,
        )

        val reloaded = MemoryProviderV2(container.database.writableDatabase)
        reloaded.initialize()

        assertEquals(listOf(created.id), reloaded.entries.map { it.id })
        assertEquals("Prefers dark mode", reloaded.entries.single().content)
    }

    @Test
    fun `assistant scoped entries keep their owner across a restart`() {
        val first = container.memoryProviderV2
        first.initialize()
        first.create(
            scope = MemoryScope.assistant,
            assistantId = "a1",
            type = MemoryType.workflow,
            content = "Answer in bullet points",
            source = MemorySource.tool,
        )

        val reloaded = MemoryProviderV2(container.database.writableDatabase)
        reloaded.initialize()

        val entry = reloaded.entries.single()
        assertEquals(MemoryScope.assistant, entry.scope)
        assertEquals("a1", entry.assistantId)
        assertEquals(1, reloaded.visibleFor("a1").size)
        assertEquals(0, reloaded.visibleFor("other").size)
    }

    @Test
    fun `deleting an entry removes the row`() {
        val provider = container.memoryProviderV2
        provider.initialize()
        val created = provider.create(
            scope = MemoryScope.global,
            assistantId = null,
            type = MemoryType.voice,
            content = "Speaks plainly",
            source = MemorySource.manual,
        )
        provider.hardDelete(created.id)

        val reloaded = MemoryProviderV2(container.database.writableDatabase)
        reloaded.initialize()

        assertEquals(emptyList<String>(), reloaded.entries.map { it.id })
    }

    @Test
    fun `rows carry the derived columns the schema requires`() {
        val provider = container.memoryProviderV2
        provider.initialize()
        provider.create(
            scope = MemoryScope.assistant,
            assistantId = "a1",
            type = MemoryType.identity,
            content = "  Prefers   Tabs ",
            source = MemorySource.manual,
        )

        container.database.readableDatabase.rawQuery(
            "SELECT scope, assistant_id, type, status, content_normalized, " +
                "entry_updated_at - entry_created_at FROM memory_entry_rows",
            null,
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("assistant", cursor.getString(0))
            assertEquals("a1", cursor.getString(1))
            assertEquals("identity", cursor.getString(2))
            assertEquals("active", cursor.getString(3))
            assertEquals("prefers tabs", cursor.getString(4))
            assertEquals(0L, cursor.getLong(5))
        }
    }

    @Test
    fun `a second instance never drops entries it did not load`() {
        // The container's provider and the memory-entries screen each hold one;
        // the screen writes first, then the tool path writes through the other.
        val screen = MemoryProviderV2(container.database.writableDatabase)
        screen.initialize()
        screen.create(MemoryScope.global, null, MemoryType.identity, "Shared fact", MemorySource.manual)

        val tools = MemoryProviderV2(container.database.writableDatabase)
        tools.create(MemoryScope.global, null, MemoryType.voice, "Second fact", MemorySource.tool)

        val reloaded = MemoryProviderV2(container.database.writableDatabase)
        reloaded.initialize()
        assertEquals(
            setOf("Shared fact", "Second fact"),
            reloaded.entries.map { it.content }.toSet(),
        )
    }
}
