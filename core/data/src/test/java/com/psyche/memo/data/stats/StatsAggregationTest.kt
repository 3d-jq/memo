package com.psyche.memo.data.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the stats port — models (stats_models.dart) and the
 * snapshot builder (stats_aggregation_service.dart buildDatabaseSnapshot,
 * _assistantRank, _trendRange, _buildHeatmap, _quantile, _level).
 */
class StatsAggregationTest {

    // ---------------------------------------------------------- date ranges

    @Test
    fun last30DaysCoversNowMinus29ToNow() {
        val range = StatsDateRange.last30Days(LocalDate.of(2026, 9, 6))
        assertEquals(LocalDate.of(2026, 8, 8), range.start)
        assertEquals(LocalDate.of(2026, 9, 6), range.end)
    }

    @Test
    fun previousMonthMatchesCalendarMonth() {
        val range = StatsDateRange.previousMonth(LocalDate.of(2026, 9, 6))
        assertEquals(LocalDate.of(2026, 8, 1), range.start)
        assertEquals(LocalDate.of(2026, 8, 31), range.end)
    }

    @Test
    fun previousMonthCrossesYearBoundary() {
        val range = StatsDateRange.previousMonth(LocalDate.of(2026, 1, 15))
        assertEquals(LocalDate.of(2025, 12, 1), range.start)
        assertEquals(LocalDate.of(2025, 12, 31), range.end)
    }

    @Test
    fun previousQuarterMatchesCalendarQuarter() {
        // Sep is in Q3 (7-9); previous quarter is Q2 (4-6).
        val range = StatsDateRange.previousQuarter(LocalDate.of(2026, 9, 6))
        assertEquals(LocalDate.of(2026, 4, 1), range.start)
        assertEquals(LocalDate.of(2026, 6, 30), range.end)
    }

    @Test
    fun previousQuarterCrossesYearBoundary() {
        val range = StatsDateRange.previousQuarter(LocalDate.of(2026, 1, 20))
        assertEquals(LocalDate.of(2025, 10, 1), range.start)
        assertEquals(LocalDate.of(2025, 12, 31), range.end)
    }

    @Test
    fun customRejectsEndBeforeStart() {
        try {
            StatsDateRange.custom(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 1))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("End date must not precede start", expected.message)
        }
    }

    @Test
    fun containsEpochMillisUsesLocalCalendarDays() {
        val range = StatsDateRange.custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))
        // 2026-09-02 12:00 local.
        val noon = LocalDate.of(2026, 9, 2).atStartOfDay(java.time.ZoneId.systemDefault())
            .plusHours(12).toInstant().toEpochMilli()
        assertTrue(range.containsEpochMillis(noon))
        // 2026-09-05 is outside [Sep 1, Sep 3].
        val sep5 = LocalDate.of(2026, 9, 5).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertFalse(range.containsEpochMillis(sep5))
        // All-time contains everything.
        assertTrue(StatsDateRange.allTime().containsEpochMillis(0L))
    }

    // ---------------------------------------------------------- aggregation

    @Test
    fun trendRangeAllTimeFallsBackToLast30Days() {
        val now = LocalDate.of(2026, 9, 6)
        assertEquals(LocalDate.of(2026, 8, 8) to now, StatsAggregation.trendRange(now, StatsDateRange.allTime()))
        val custom = StatsDateRange.custom(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10))
        assertEquals(LocalDate.of(2026, 5, 1) to LocalDate.of(2026, 5, 10), StatsAggregation.trendRange(now, custom))
    }

    @Test
    fun buildHeatmapFills365DaysWithZeroes() {
        val now = LocalDate.of(2026, 9, 6)
        val counts = mapOf(now to 7L, now.minusDays(1) to 3L)
        val heatmap = StatsAggregation.buildHeatmap(now, counts)
        assertEquals(365, heatmap.size)
        assertEquals(now.minusDays(364), heatmap.first().date)
        assertEquals(now, heatmap.last().date)
        assertEquals(0L, heatmap.first().count)
        assertEquals(3L, heatmap[363].count)
        assertEquals(7L, heatmap[364].count)
    }

    private fun aggregate(
        trend: List<ChatStatsTrendRow> = emptyList(),
        models: List<ChatStatsRankRow> = emptyList(),
        assistants: List<ChatStatsRankRow> = emptyList(),
        topics: List<ChatStatsRankRow> = emptyList(),
        heatmap: List<ChatStatsHeatmapRow> = emptyList(),
    ) = ChatStatsAggregate(
        conversations = 12,
        totals = ChatStatsTotals(messages = 100, inputTokens = 1000, outputTokens = 2000, cachedTokens = 300),
        heatmap = heatmap,
        trend = trend,
        models = models,
        assistants = assistants,
        topics = topics,
    )

    @Test
    fun snapshotMapsSummaryAndRanks() {
        val now = LocalDate.of(2026, 9, 6)
        val snapshot = StatsAggregation.buildDatabaseSnapshot(
            now = now,
            range = StatsDateRange.allTime(),
            aggregate = aggregate(
                models = listOf(
                    ChatStatsRankRow("gpt-4o", "gpt-4o", 9, "openai"),
                    ChatStatsRankRow("glm-4", "glm-4", 3, "zhipu"),
                ),
                topics = listOf(ChatStatsRankRow("c1", "  Travel plan  ", 5)),
            ),
            launchCount = 42,
            unknownProviderLabel = "未知服务商",
            unknownTopicLabel = "无标题会话",
        )
        assertEquals(12, snapshot.summary.totalConversations)
        assertEquals(100, snapshot.summary.totalMessages)
        assertEquals(1000, snapshot.summary.inputTokens)
        assertEquals(2000, snapshot.summary.outputTokens)
        assertEquals(300, snapshot.summary.cachedTokens)
        assertEquals(42, snapshot.summary.launchCount)
        assertEquals(9, snapshot.modelRank[0].value)
        assertEquals("openai", snapshot.modelRank[0].providerId)
        // Topic labels are trimmed; empty labels fall back to the unknown label.
        assertEquals("Travel plan", snapshot.topicRank[0].label)
    }

    @Test
    fun snapshotTrimsEmptyTopicLabelToUnknown() {
        val now = LocalDate.of(2026, 9, 6)
        val snapshot = StatsAggregation.buildDatabaseSnapshot(
            now = now,
            range = StatsDateRange.allTime(),
            aggregate = aggregate(topics = listOf(ChatStatsRankRow("c1", "   ", 5))),
            launchCount = 0,
            unknownProviderLabel = "未知服务商",
            unknownTopicLabel = "无标题会话",
        )
        assertEquals("无标题会话", snapshot.topicRank[0].label)
    }

    @Test
    fun trendBucketsPadMissingDaysAndResolveLabels() {
        val now = LocalDate.of(2026, 9, 6)
        val snapshot = StatsAggregation.buildDatabaseSnapshot(
            now = now,
            range = StatsDateRange.custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5)),
            aggregate = aggregate(
                trend = listOf(
                    ChatStatsTrendRow(LocalDate.of(2026, 9, 3), "_unknown", 4, 10, 20, 0, 5),
                ),
            ),
            launchCount = 0,
            unknownProviderLabel = "未知服务商",
            unknownTopicLabel = "无标题会话",
        )
        // 5-day window Sep 1..5 fully materialized.
        assertEquals(5, snapshot.trend.size)
        assertEquals(LocalDate.of(2026, 9, 1), snapshot.trend.first().date)
        assertEquals(LocalDate.of(2026, 9, 5), snapshot.trend.last().date)
        val day3 = snapshot.trend.first { it.date == LocalDate.of(2026, 9, 3) }
        assertEquals(setOf("未知服务商"), day3.providerTokens.keys)
        val bucket = day3.providerTokens.getValue("未知服务商")
        assertEquals(10, bucket.inputTokens)
        assertEquals(20, bucket.outputTokens)
        assertEquals(5, bucket.uncategorizedTokens)
        assertEquals(4, bucket.activityCount)
        // Days without rows map to empty buckets.
        assertTrue(snapshot.trend.first { it.date == LocalDate.of(2026, 9, 1) }.providerTokens.isEmpty())
    }

    @Test
    fun assistantRankMergesByResolvedLabelAndSorts() {
        val rank = StatsAggregation.assistantRank(
            counts = linkedMapOf("a2" to 3L, "_default" to 5L, "a1" to 4L),
            assistantNames = mapOf("a1" to "翻译君", "a2" to "写作助手"),
        )
        // _default keeps its raw id as label when unresolved; sorted by value desc.
        assertEquals("_default", rank[0].label)
        assertEquals(5L, rank[0].value)
        assertEquals("翻译君", rank[1].label)
        assertEquals("a1", rank[1].id)
        assertEquals("写作助手", rank[2].label)
    }

    @Test
    fun assistantRankMergesDuplicateLabelsAndKeepsTopRepresentative() {
        // Two ids resolving to the same display name merge their counts; the
        // representative id is the one with the largest single count.
        val rank = StatsAggregation.assistantRank(
            counts = linkedMapOf("a1" to 2L, "a2" to 9L, "a3" to 4L),
            assistantNames = mapOf("a1" to "双子", "a2" to "双子", "a3" to "独行者"),
        )
        assertEquals(2, rank.size)
        assertEquals("双子", rank[0].label)
        assertEquals(11L, rank[0].value)
        assertEquals("a2", rank[0].id) // largest single count wins the id
        assertEquals("独行者", rank[1].label)
    }

    @Test
    fun assistantRankHidesUnresolvedWhenNamesKnown() {
        val rank = StatsAggregation.assistantRank(
            counts = linkedMapOf("ghost" to 7L, "_default" to 2L, "a1" to 1L),
            assistantNames = mapOf("a1" to "翻译君"),
            hideUnresolved = true,
        )
        assertEquals(listOf("_default", "翻译君"), rank.map { it.label })
    }

    @Test
    fun assistantRankKeepsAllWhenNoNamesAvailable() {
        val rank = StatsAggregation.assistantRank(
            counts = linkedMapOf("ghost" to 7L, "a1" to 1L),
            assistantNames = emptyMap(),
            // The service derives hideUnresolved = names.isNotEmpty() → false here.
            hideUnresolved = false,
        )
        assertEquals(listOf("ghost", "a1"), rank.map { it.label })
    }

    // -------------------------------------------------------- heatmap bands

    @Test
    fun quantileIsEmptySafeAndIndexBased() {
        assertEquals(1L, StatsAggregation.quantile(emptyList(), 0.25))
        assertEquals(5L, StatsAggregation.quantile(listOf(5L), 0.99))
        // size 4 * 0.25 = index 1.
        assertEquals(2L, StatsAggregation.quantile(listOf(1L, 2L, 3L, 4L), 0.25))
    }

    @Test
    fun levelForMatchesDartBands() {
        assertEquals(0, StatsAggregation.levelFor(0, 1, 2, 3))
        assertEquals(1, StatsAggregation.levelFor(1, 1, 2, 3))
        assertEquals(2, StatsAggregation.levelFor(2, 1, 2, 3))
        assertEquals(3, StatsAggregation.levelFor(3, 1, 2, 3))
        assertEquals(4, StatsAggregation.levelFor(4, 1, 2, 3))
    }

    @Test
    fun tokenBucketWeightsFollowDartRules() {
        // totalTokens excludes cached; chartWeight falls back to activity.
        val bucket = StatsTokenBucket(inputTokens = 1, outputTokens = 2, cachedTokens = 100, uncategorizedTokens = 4)
        assertEquals(7L, bucket.totalTokens)
        assertEquals(7L, bucket.chartWeight)
        assertEquals(9L, StatsTokenBucket(activityCount = 9).chartWeight)
        assertEquals(0L, StatsTokenBucket().chartWeight)
        val merged = bucket.add(outputTokens = 1, activityCount = 1)
        assertEquals(8L, merged.totalTokens)
        assertEquals(100L, merged.cachedTokens)
        assertEquals(3L, merged.outputTokens)
    }
}
