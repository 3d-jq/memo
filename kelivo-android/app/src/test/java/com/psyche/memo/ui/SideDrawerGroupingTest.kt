package com.psyche.memo.ui

import com.psyche.memo.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Unit tests for the drawer's pure helpers (filter + date grouping). */
class SideDrawerGroupingTest {

    private val todayStart: Long by lazy {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.timeInMillis
    }

    private fun conv(title: String, at: Long, pinned: Boolean = false) =
        Conversation.create(title = title, now = at).let { c ->
            // create() sets updatedAt = now; copy isPinned after creation.
            if (pinned) {
                Conversation(
                    id = c.id,
                    title = c.title,
                    createdAt = c.createdAt,
                    updatedAt = c.updatedAt,
                    isPinned = true,
                    assistantId = c.assistantId,
                    truncateIndex = c.truncateIndex,
                    versionSelections = c.versionSelections,
                    summary = c.summary,
                    lastSummarizedMessageCount = c.lastSummarizedMessageCount,
                    chatSuggestions = c.chatSuggestions,
                    injectedMemoryHash = c.injectedMemoryHash,
                    lastMemoryExtractedOrder = c.lastMemoryExtractedOrder,
                    chatModelProvider = c.chatModelProvider,
                    chatModelId = c.chatModelId,
                    extras = c.extras,
                )
            } else c
        }

    @Test
    fun `blank query returns everything`() {
        val items = listOf(conv("A", todayStart + 1000), conv("B", todayStart + 2000))
        assertEquals(items, filterConversations(items, ""))
        assertEquals(items, filterConversations(items, "   "))
    }

    @Test
    fun `query matches title case-insensitively after trim`() {
        val items = listOf(
            conv("Alpha Chat", todayStart + 1000),
            conv("Beta", todayStart + 2000),
        )
        val result = filterConversations(items, "  alpHA ")
        assertEquals(1, result.size)
        assertEquals("Alpha Chat", result.first().title)
    }

    @Test
    fun `pinned group leads and rest is newest-first`() {
        val older = conv("Old", todayStart - 48 * 3600 * 1000L)
        val yesterday = conv("Yesterday", todayStart - 1000)
        val today = conv("Today", todayStart + 3600 * 1000L)
        val pinned = conv("Pinned", todayStart + 500, pinned = true)
        val sections = groupedRows(listOf(older, yesterday, today, pinned))

        assertEquals("pinned", sections.first().key)
        assertEquals(listOf("Pinned"), sections.first().items.map { it.title })

        val restKeys = sections.drop(1).map { it.key }
        assertTrue(restKeys.contains("today"))
        assertTrue(restKeys.contains("yesterday"))
        // Older-than-yesterday buckets fall back to a yyyyMMdd date key.
        val oldKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            .format(Date(older.updatedAt))
        assertTrue(restKeys.contains(oldKey))
    }

    @Test
    fun `empty input yields no sections`() {
        assertTrue(groupedRows(emptyList()).isEmpty())
    }
}
