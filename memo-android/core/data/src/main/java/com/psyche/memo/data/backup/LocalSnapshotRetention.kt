package com.psyche.memo.data.backup

import java.time.Duration
import java.time.Instant

/**
 * One local copy of the database, as the retention rules see it.
 *
 * Deliberately free of `File`: the rules decide what may be deleted from
 * metadata alone, so they stay testable without a filesystem and cannot depend
 * on anything the caller has not already established (local_snapshot_retention.dart).
 */
data class SnapshotRetentionEntry(
    val id: String,
    val createdAt: Instant,
    val bytes: Long,
    /** Messages the copy holds; drives the guards that stop an empty (or much
     * smaller) copy from evicting a fuller one. */
    val messageCount: Int,
    /** Never removed automatically: taken right before a restore, or kept by the user. */
    val pinned: Boolean = false,
) {
    val hasContent: Boolean get() = messageCount > 0
}

/**
 * How many local copies survive, and which ones.
 *
 * Slots give the set time depth: keeping "the newest N" alone means a day of
 * heavy use rotates out every older copy, and damage that went unnoticed for a
 * week becomes unrecoverable. The weekly and monthly slots cost one file each
 * and buy back that depth.
 */
data class LocalSnapshotRetentionPolicy(
    val keepRecent: Int = 3,
    val keepWeekly: Boolean = true,
    val keepMonthly: Boolean = true,
    /** Ceiling on the whole set; zero means the slots alone bound it. */
    val maximumTotalBytes: Long = 0,
) {
    init {
        require(keepRecent >= 1) { "keepRecent must be at least 1" }
    }

    /**
     * The copies automatic pruning may remove, oldest first.
     *
     * Every rule here can only ever move an entry from "delete" to "keep".
     * That asymmetry is the point: the cost of keeping one file too many is
     * disk space, and the cost of deleting one file too few is the incident
     * this whole mechanism exists to prevent.
     */
    fun selectForDeletion(
        entries: Collection<SnapshotRetentionEntry>,
        now: Instant,
    ): List<SnapshotRetentionEntry> {
        val ordered = entries.sortedByDescending { it.createdAt }
        if (ordered.size <= 1) return emptyList()

        val protectedIds = protectedIds(ordered, now)
        val retained = protectedIds + slotIds(ordered, now)

        val deletions = ordered.reversed().filter { it.id !in retained }.toMutableList()

        if (maximumTotalBytes <= 0) return deletions

        // Over budget: give up slots too, oldest first, but never anything
        // protected. Running over the ceiling is better than losing the last
        // copy that still has the user's data in it.
        val deleted = deletions.map { it.id }.toMutableSet()
        var total = ordered.filter { it.id !in deleted }.sumOf { it.bytes }
        for (entry in ordered.reversed()) {
            if (total <= maximumTotalBytes) break
            if (entry.id in deleted || entry.id in protectedIds) continue
            deletions.add(entry)
            deleted.add(entry.id)
            total -= entry.bytes
        }
        return deletions
    }

    /** Entries no policy setting may drop, however tight the budget. */
    private fun protectedIds(ordered: List<SnapshotRetentionEntry>, now: Instant): Set<String> {
        val protected = mutableSetOf(ordered.first().id)
        for (entry in ordered) {
            if (entry.pinned) protected.add(entry.id)
        }
        // An empty copy must never be able to evict one that still holds data —
        // the shape of a silent wipe is a fresh, valid, empty database.
        ordered.firstOrNull { it.hasContent }?.let { protected.add(it.id) }
        highWaterGuard(ordered, now)?.let { protected.add(it.id) }
        return protected
    }

    /**
     * The fullest copy, while something newer suggests the data shrank.
     *
     * Covers the case the "empty" guard above misses: a database that lost most
     * of its rows rather than all of them, where every later copy is still
     * non-empty and would otherwise rotate the evidence away.
     */
    private fun highWaterGuard(ordered: List<SnapshotRetentionEntry>, now: Instant): SnapshotRetentionEntry? {
        val highWater = ordered.maxByOrNull { it.messageCount } ?: return null
        if (!highWater.hasContent) return null
        if (highWater.id == ordered.first().id) return null
        if (Duration.between(highWater.createdAt, now) > DROP_GUARD_WINDOW) return null
        val floor = highWater.messageCount * DROP_GUARD_RATIO
        for (entry in ordered) {
            if (!entry.createdAt.isAfter(highWater.createdAt)) break
            if (entry.messageCount < floor) return highWater
        }
        return null
    }

    /** The recent, weekly and monthly slots. */
    private fun slotIds(ordered: List<SnapshotRetentionEntry>, now: Instant): Set<String> {
        val slots = ordered.take(maxOf(1, keepRecent)).map { it.id }.toMutableSet()
        if (keepWeekly) oldestSlot(ordered, now, WEEKLY_AGE)?.let { slots.add(it.id) }
        if (keepMonthly) oldestSlot(ordered, now, MONTHLY_AGE)?.let { slots.add(it.id) }
        return slots
    }

    /**
     * The newest entry that has already aged past [minimumAge].
     *
     * Newest rather than oldest: the slot should hold the most recent copy that
     * still satisfies the depth it stands for, so the set keeps sliding forward
     * instead of pinning one file the day it first qualifies.
     */
    private fun oldestSlot(
        ordered: List<SnapshotRetentionEntry>,
        now: Instant,
        minimumAge: Duration,
    ): SnapshotRetentionEntry? = ordered.firstOrNull { Duration.between(it.createdAt, now) >= minimumAge }

    companion object {
        /** The shape the app ships with: yesterday, the day before, one from
         * last week, one from last month. */
        val GFS_LITE = LocalSnapshotRetentionPolicy()

        val WEEKLY_AGE: Duration = Duration.ofDays(7)
        val MONTHLY_AGE: Duration = Duration.ofDays(30)

        /** How long a copy taken before a large content drop stays protected.
         * The window has to end: a user who really did delete their chats
         * should not have a copy of them kept forever. */
        val DROP_GUARD_WINDOW: Duration = Duration.ofDays(90)

        /** A later copy holding less than this share of the high-water copy
         * reads as a loss rather than as ordinary editing. */
        const val DROP_GUARD_RATIO = 0.5
    }
}
