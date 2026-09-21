package com.psyche.memo.data.stats

import java.time.LocalDate

/**
 * Rows produced by StatsDao (chat_database_repository.dart L7807+
 * ChatStatsAggregate) and the snapshot builder — 1:1 port of
 * lib/features/stats/services/stats_aggregation_service.dart
 * buildDatabaseSnapshot.
 */
data class ChatStatsTotals(
    val messages: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
)

data class ChatStatsHeatmapRow(val day: LocalDate, val count: Long)

data class ChatStatsTrendRow(
    val day: LocalDate,
    val providerId: String,
    val activityCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val uncategorizedTokens: Long,
)

data class ChatStatsRankRow(
    val id: String,
    val label: String,
    val count: Long,
    val providerId: String? = null,
)

data class ChatStatsAggregate(
    val conversations: Long,
    val totals: ChatStatsTotals,
    val heatmap: List<ChatStatsHeatmapRow>,
    val trend: List<ChatStatsTrendRow>,
    val models: List<ChatStatsRankRow>,
    val assistants: List<ChatStatsRankRow>,
    val topics: List<ChatStatsRankRow>,
)

object StatsAggregation {

    /** L195-207 _trendRange: all-time shows the last 30 calendar days. */
    fun trendRange(now: LocalDate, range: StatsDateRange): Pair<LocalDate, LocalDate> {
        if (range.isAllTime) return now.minusDays(29) to now
        return (range.start ?: now.minusDays(29)) to (range.end ?: now)
    }

    /** L209-224 _buildHeatmap: fixed 365-day window ending today. */
    fun buildHeatmap(now: LocalDate, counts: Map<LocalDate, Long>): List<StatsHeatmapDay> {
        val start = now.minusDays(364)
        val days = ArrayList<StatsHeatmapDay>(365)
        var date = start
        while (!date.isAfter(now)) {
            days.add(StatsHeatmapDay(date, counts[date] ?: 0L))
            date = date.plusDays(1)
        }
        return days
    }

    /** L17-86 buildDatabaseSnapshot. */
    fun buildDatabaseSnapshot(
        now: LocalDate,
        range: StatsDateRange,
        aggregate: ChatStatsAggregate,
        launchCount: Long,
        unknownProviderLabel: String,
        unknownTopicLabel: String,
        assistantNames: Map<String, String> = emptyMap(),
        providerNames: Map<String, String> = emptyMap(),
    ): StatsSnapshot {
        val assistantCounts = LinkedHashMap<String, Long>()
        for (row in aggregate.assistants) {
            assistantCounts[row.id] = (assistantCounts[row.id] ?: 0L) + row.count
        }
        val heatmapCounts = aggregate.heatmap.associate { it.day to it.count }

        val (trendStart, trendEnd) = trendRange(now, range)
        val trendBuckets = LinkedHashMap<LocalDate, MutableMap<String, StatsTokenBucket>>()
        var day = trendStart
        while (!day.isAfter(trendEnd)) {
            trendBuckets[day] = mutableMapOf()
            day = day.plusDays(1)
        }
        for (row in aggregate.trend) {
            val providerLabel = if (row.providerId == "_unknown") {
                unknownProviderLabel
            } else {
                providerNames[row.providerId] ?: row.providerId
            }
            trendBuckets[row.day]?.put(
                providerLabel,
                StatsTokenBucket(
                    inputTokens = row.inputTokens,
                    outputTokens = row.outputTokens,
                    cachedTokens = row.cachedTokens,
                    uncategorizedTokens = row.uncategorizedTokens,
                    activityCount = row.activityCount,
                ),
            )
        }

        return StatsSnapshot(
            range = range,
            summary = StatsSummary(
                totalConversations = aggregate.conversations,
                totalMessages = aggregate.totals.messages,
                inputTokens = aggregate.totals.inputTokens,
                outputTokens = aggregate.totals.outputTokens,
                cachedTokens = aggregate.totals.cachedTokens,
                launchCount = launchCount,
            ),
            heatmap = buildHeatmap(now, heatmapCounts),
            trend = trendBuckets.map { (date, buckets) ->
                StatsTrendDay(date, buckets.toMap())
            },
            modelRank = aggregate.models.map { row ->
                StatsRankItem(id = row.id, label = row.label, value = row.count, providerId = row.providerId)
            },
            assistantRank = assistantRank(assistantCounts, assistantNames, hideUnresolved = assistantNames.isNotEmpty()),
            topicRank = aggregate.topics.map { row ->
                StatsRankItem(
                    id = row.id,
                    label = row.label.trim().ifEmpty { unknownTopicLabel },
                    value = row.count,
                )
            },
        )
    }

    /** L311-358 _assistantRank: merge by resolved label, hide unresolved. */
    fun assistantRank(
        counts: Map<String, Long>,
        assistantNames: Map<String, String>,
        hideUnresolved: Boolean = false,
    ): List<StatsRankItem> {
        val valuesByLabel = LinkedHashMap<String, Long>()
        val representativeIdByLabel = HashMap<String, String>()
        val representativeValueByLabel = HashMap<String, Long>()

        for ((id, value) in counts) {
            val isDefault = id == "_default"
            val isKnown = assistantNames.containsKey(id)
            if (hideUnresolved && !isDefault && !isKnown) continue

            val resolvedLabel = assistantNames[id]?.trim()
            val label = if (resolvedLabel.isNullOrEmpty()) id else resolvedLabel
            valuesByLabel[label] = (valuesByLabel[label] ?: 0L) + value
            val representativeValue = representativeValueByLabel[label]
            if (representativeValue == null || value > representativeValue) {
                representativeIdByLabel[label] = id
                representativeValueByLabel[label] = value
            }
        }

        return valuesByLabel.map { (label, value) ->
            StatsRankItem(
                id = representativeIdByLabel.getValue(label),
                label = label,
                value = value,
            )
        }.sortedWith(
            compareByDescending<StatsRankItem> { it.value }.thenBy { it.label },
        )
    }

    /** stats_heatmap.dart L200-204 _quantile on sorted active-day counts. */
    fun quantile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 1L
        val index = (sorted.size * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** stats_heatmap.dart L206-212 _level. */
    fun levelFor(count: Long, q1: Long, q2: Long, q3: Long): Int = when {
        count <= 0 -> 0
        count <= q1 -> 1
        count <= q2 -> 2
        count <= q3 -> 3
        else -> 4
    }
}
