package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * local_snapshot_retention.dart — which local copies survive. The rules decide
 * from metadata alone, so they are exercised without a filesystem.
 */
class LocalSnapshotRetentionTest {

    private val now: Instant = Instant.parse("2026-09-11T12:00:00Z")

    private fun entry(
        id: String,
        ageDays: Long,
        messages: Int = 100,
        bytes: Long = 1_000,
        pinned: Boolean = false,
    ) = SnapshotRetentionEntry(
        id = id,
        createdAt = now.minus(Duration.ofDays(ageDays)),
        bytes = bytes,
        messageCount = messages,
        pinned = pinned,
    )

    private val policy = LocalSnapshotRetentionPolicy()

    @Test
    fun `a single copy is never pruned`() {
        assertEquals(
            emptyList<SnapshotRetentionEntry>(),
            policy.selectForDeletion(listOf(entry("a", 30)), now),
        )
    }

    @Test
    fun `the three most recent copies stay`() {
        val entries = (0..5L).map { entry("copy-$it", it) }
        val deleted = policy.selectForDeletion(entries, now).map { it.id }.toSet()
        assertEquals(setOf("copy-3", "copy-4", "copy-5"), deleted)
    }

    @Test
    fun `the weekly and monthly slots survive`() {
        val entries = (0..40L).map { entry("copy-$it", it) }
        val deleted = policy.selectForDeletion(entries, now).map { it.id }.toSet()
        // Ages 0,1,2 (recent), 7 (weekly) and 30 (monthly) are kept; everything
        // else goes, oldest first.
        assertEquals(setOf("copy-0", "copy-1", "copy-2", "copy-7", "copy-30"), entries.map { it.id }.toSet() - deleted)
        assertEquals("copy-40", policy.selectForDeletion(entries, now).first().id)
    }

    @Test
    fun `pinned copies are never pruned`() {
        val policyTight = LocalSnapshotRetentionPolicy(keepRecent = 1, keepWeekly = false, keepMonthly = false)
        val entries = (0..9L).map { entry("copy-$it", it, pinned = it == 9L) }
        val deleted = policyTight.selectForDeletion(entries, now).map { it.id }.toSet()
        assertTrue("copy-9" !in deleted)
        assertEquals((1..8L).map { "copy-$it" }.toSet(), deleted)
    }

    @Test
    fun `an empty copy can never evict one that still holds data`() {
        // The newest copies are empty (the shape of a silent wipe) and the only
        // copy with real content is far outside the recent slots, yet it must
        // survive: "no rows" is never evidence the data is gone.
        val policyTight = LocalSnapshotRetentionPolicy(keepRecent = 1, keepWeekly = false, keepMonthly = false)
        val entries = listOf(
            entry("empty", 0, messages = 0),
            entry("older-empty", 1, messages = 0),
            entry("filler", 2, messages = 100),
            entry("has-data", 40, messages = 500),
            entry("older-filler", 50, messages = 100),
        )
        val deleted = policyTight.selectForDeletion(entries, now).map { it.id }
        assertTrue("has-data" !in deleted)
        // The newest copy with content ("filler") is protected, and so is the
        // high-water one; only the two empties/older fillers may go.
        assertEquals(listOf("older-filler", "older-empty"), deleted)
    }

    @Test
    fun `a large content drop protects the fullest copy`() {
        // Every later copy holds a tenth of the high-water one, so the guard
        // keeps it even though it is far outside the recent slots.
        val policyTight = LocalSnapshotRetentionPolicy(keepRecent = 2, keepWeekly = false, keepMonthly = false)
        val entries = listOf(
            entry("new-small", 0, messages = 10),
            entry("new-small-2", 1, messages = 10),
            entry("high-water", 3, messages = 1000),
            entry("small-3", 4, messages = 10),
            entry("small-4", 5, messages = 10),
        )
        val deleted = policyTight.selectForDeletion(entries, now).map { it.id }
        assertTrue("high-water" !in deleted)
        assertEquals(listOf("small-4", "small-3"), deleted)
    }

    @Test
    fun `the drop guard expires after its window`() {
        // Past the 90-day window the guard stops protecting the fuller copy: a
        // user who really did delete their chats must not keep a copy forever.
        val policyTight = LocalSnapshotRetentionPolicy(keepRecent = 1, keepWeekly = false, keepMonthly = false)
        val entries = listOf(
            entry("new-small", 0, messages = 10),
            entry("filler", 2, messages = 100),
            entry("high-water", 120, messages = 1000),
            entry("older-filler", 130, messages = 100),
        )
        val deleted = policyTight.selectForDeletion(entries, now).map { it.id }
        // With the guard expired the high-water copy is no longer protected, and
        // neither is the middle filler (the newest copy already has content).
        assertEquals(listOf("older-filler", "high-water", "filler"), deleted)
    }

    @Test
    fun `the byte ceiling drops the oldest unprotected copies first`() {
        val entries = (0..4L).map { entry("copy-$it", it, bytes = 1_000) }
        val budget = LocalSnapshotRetentionPolicy(
            keepRecent = 2,
            keepWeekly = false,
            keepMonthly = false,
            maximumTotalBytes = 3_000,
        )
        val deleted = budget.selectForDeletion(entries, now).map { it.id }
        // The two recent slots are protected, so the three older copies go
        // (oldest first) — the budget only matters if that still exceeds it.
        assertEquals(listOf("copy-4", "copy-3", "copy-2"), deleted)
    }

    @Test
    fun `every deletion is the oldest-first order`() {
        val entries = (0..6L).map { entry("copy-$it", it) }
        val policyTight = LocalSnapshotRetentionPolicy(keepRecent = 1, keepWeekly = false, keepMonthly = false)
        assertEquals(
            listOf("copy-6", "copy-5", "copy-4", "copy-3", "copy-2", "copy-1"),
            policyTight.selectForDeletion(entries, now).map { it.id },
        )
    }
}
