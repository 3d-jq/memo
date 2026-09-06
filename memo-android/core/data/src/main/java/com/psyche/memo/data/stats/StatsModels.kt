package com.psyche.memo.data.stats

import java.time.LocalDate
import java.time.ZoneId

/**
 * Stats domain models — 1:1 port of lib/features/stats/models/
 * stats_models.dart. Dates are calendar-local (LocalDate mirrors Dart's
 * normalized DateTime(y, m, d)).
 */
enum class StatsDateRangePreset { ALL_TIME, LAST_30_DAYS, PREVIOUS_MONTH, PREVIOUS_QUARTER, CUSTOM }

data class StatsDateRange(
    val preset: StatsDateRangePreset,
    val start: LocalDate?,
    val end: LocalDate?,
) {
    val isAllTime: Boolean get() = preset == StatsDateRangePreset.ALL_TIME

    companion object {
        fun allTime(): StatsDateRange = StatsDateRange(StatsDateRangePreset.ALL_TIME, null, null)

        /** L30-37: [end-29, end] with end = normalized today. */
        fun last30Days(now: LocalDate): StatsDateRange =
            StatsDateRange(StatsDateRangePreset.LAST_30_DAYS, now.minusDays(29), now)

        /** L39-51: previous calendar month. */
        fun previousMonth(now: LocalDate): StatsDateRange {
            val monthStart = now.withDayOfMonth(1)
            val previousMonthEnd = monthStart.minusDays(1)
            return StatsDateRange(
                StatsDateRangePreset.PREVIOUS_MONTH,
                previousMonthEnd.withDayOfMonth(1),
                previousMonthEnd,
            )
        }

        /** L53-71: previous calendar quarter. */
        fun previousQuarter(now: LocalDate): StatsDateRange {
            val currentQuarterStartMonth = ((now.monthValue - 1) / 3) * 3 + 1
            val currentQuarterStart = LocalDate.of(now.year, currentQuarterStartMonth, 1)
            val previousQuarterEnd = currentQuarterStart.minusDays(1)
            // Dart derives the start month from the END month's own quarter:
            // ((end.month - 1) ~/ 3) * 3 + 1 — a June end yields an April start.
            val previousQuarterStartMonth = ((previousQuarterEnd.monthValue - 1) / 3) * 3 + 1
            return StatsDateRange(
                StatsDateRangePreset.PREVIOUS_QUARTER,
                LocalDate.of(previousQuarterEnd.year, previousQuarterStartMonth, 1),
                previousQuarterEnd,
            )
        }

        /** L73-84: custom, end must not precede start. */
        fun custom(start: LocalDate, end: LocalDate): StatsDateRange {
            require(!end.isBefore(start)) { "End date must not precede start" }
            return StatsDateRange(StatsDateRangePreset.CUSTOM, start, end)
        }
    }

    /** L94-102 contains(DateTime) — timestamp in epoch millis, local zone. */
    fun containsEpochMillis(value: Long): Boolean {
        if (isAllTime) return true
        val date = value.toLocalDate()
        if (start != null && date.isBefore(start)) return false
        if (end != null && date.isAfter(end)) return false
        return true
    }
}

fun Long.toLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** StatsSummary — stats_models.dart L105-121 (tokens are 64-bit). */
data class StatsSummary(
    val totalConversations: Long,
    val totalMessages: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val launchCount: Long,
)

/** StatsRankItem — stats_models.dart L123-135. */
data class StatsRankItem(
    val id: String,
    val label: String,
    val value: Long,
    val providerId: String? = null,
)

/** StatsHeatmapDay — stats_models.dart L137-142. */
data class StatsHeatmapDay(
    val date: LocalDate,
    val count: Long,
)

/** StatsTokenBucket — stats_models.dart L144-177. */
data class StatsTokenBucket(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val uncategorizedTokens: Long = 0,
    val activityCount: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + uncategorizedTokens
    val chartWeight: Long get() = if (totalTokens > 0) totalTokens else activityCount

    fun add(
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        cachedTokens: Long = 0,
        uncategorizedTokens: Long = 0,
        activityCount: Long = 0,
    ): StatsTokenBucket = copy(
        inputTokens = this.inputTokens + inputTokens,
        outputTokens = this.outputTokens + outputTokens,
        cachedTokens = this.cachedTokens + cachedTokens,
        uncategorizedTokens = this.uncategorizedTokens + uncategorizedTokens,
        activityCount = this.activityCount + activityCount,
    )
}

/** StatsTrendDay — stats_models.dart L179-184. */
data class StatsTrendDay(
    val date: LocalDate,
    val providerTokens: Map<String, StatsTokenBucket>,
)

/** StatsSnapshot — stats_models.dart L186-204. */
data class StatsSnapshot(
    val range: StatsDateRange,
    val summary: StatsSummary,
    val heatmap: List<StatsHeatmapDay>,
    val trend: List<StatsTrendDay>,
    val modelRank: List<StatsRankItem>,
    val assistantRank: List<StatsRankItem>,
    val topicRank: List<StatsRankItem>,
)
