package com.psyche.memo.data.stats

import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.ZoneId

/**
 * SQL aggregate for the stats page — 1:1 port of
 * chat_database_repository.dart L3364-3480 queryStatsAggregate. Android
 * stores epoch millis (the Flutter original stores micros), so the
 * strftime denominators are /1000.0 and the range binds are millis.
 */
class StatsDao(private val db: SQLiteDatabase) {

    fun queryAggregate(
        rangeStartMs: Long?,
        rangeEndExclusiveMs: Long?,
        heatmapStartMs: Long,
        trendStartMs: Long,
        trendEndExclusiveMs: Long,
    ): ChatStatsAggregate {
        val rangeClauses = buildList {
            if (rangeStartMs != null) add("m.timestamp >= ?")
            if (rangeEndExclusiveMs != null) add("m.timestamp < ?")
        }
        val rangeWhere = if (rangeClauses.isEmpty()) "" else "AND ${rangeClauses.joinToString(" AND ")}"
        val rangeVars = buildList {
            if (rangeStartMs != null) add(rangeStartMs)
            if (rangeEndExclusiveMs != null) add(rangeEndExclusiveMs)
        }
        val conversationRangeClauses = buildList {
            if (rangeStartMs != null) add("c.created_at >= ?")
            if (rangeEndExclusiveMs != null) add("c.created_at < ?")
        }
        val conversationRangeWhere = if (conversationRangeClauses.isEmpty()) {
            ""
        } else {
            "WHERE ${conversationRangeClauses.joinToString(" AND ")}"
        }
        val conversationRangeWherePlain = if (conversationRangeClauses.isEmpty()) {
            ""
        } else {
            "WHERE " + conversationRangeClauses.joinToString(" AND ").replace("c.", "")
        }
        val summaryArgs = rangeVars + rangeVars

        val summary = db.rawQuery(
            """
            SELECT
              (SELECT COUNT(*) FROM conversation_rows c
                $conversationRangeWhere) AS conversations,
              COUNT(*) AS messages,
              COALESCE(SUM(prompt_tokens), 0) AS input_tokens,
              COALESCE(SUM(completion_tokens), 0) AS output_tokens,
              COALESCE(SUM(cached_tokens), 0) AS cached_tokens
            FROM message_rows m WHERE 1 = 1 $rangeWhere
            """.trimIndent(),
            summaryArgs.map { it.toString() }.toTypedArray(),
        ).use { it.moveToFirst(); readSummary(it) }

        val heatmap = db.rawQuery(
            """
            SELECT strftime('%Y-%m-%d', m.timestamp / 1000.0,
                'unixepoch', 'localtime') AS day,
              COUNT(*) AS message_count
            FROM message_rows m
            WHERE m.timestamp >= ?
            GROUP BY day ORDER BY day
            """.trimIndent(),
            arrayOf(heatmapStartMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ChatStatsHeatmapRow(parseDay(c.getString(0)), c.getLong(1)))
                }
            }
        }

        val trend = db.rawQuery(
            """
            SELECT strftime('%Y-%m-%d', m.timestamp / 1000.0,
                'unixepoch', 'localtime') AS day,
              COALESCE(NULLIF(TRIM(m.provider_id), ''), '_unknown') AS provider_id,
              COUNT(*) AS activity_count,
              COALESCE(SUM(m.prompt_tokens), 0) AS input_tokens,
              COALESCE(SUM(m.completion_tokens), 0) AS output_tokens,
              COALESCE(SUM(m.cached_tokens), 0) AS cached_tokens,
              COALESCE(SUM(CASE WHEN COALESCE(m.prompt_tokens, 0) = 0
                AND COALESCE(m.completion_tokens, 0) = 0
                THEN COALESCE(m.total_tokens, 0) ELSE 0 END), 0) AS uncategorized_tokens
            FROM message_rows m
            WHERE m.timestamp >= ? AND m.timestamp < ?
              AND (NULLIF(TRIM(m.provider_id), '') IS NOT NULL
                OR COALESCE(m.prompt_tokens, 0) != 0
                OR COALESCE(m.completion_tokens, 0) != 0
                OR COALESCE(m.cached_tokens, 0) != 0
                OR COALESCE(m.total_tokens, 0) != 0)
            GROUP BY day, provider_id ORDER BY day, provider_id
            """.trimIndent(),
            arrayOf(trendStartMs.toString(), trendEndExclusiveMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChatStatsTrendRow(
                            day = parseDay(c.getString(0)),
                            providerId = c.getString(1),
                            activityCount = c.getLong(2),
                            inputTokens = c.getLong(3),
                            outputTokens = c.getLong(4),
                            cachedTokens = c.getLong(5),
                            uncategorizedTokens = c.getLong(6),
                        ),
                    )
                }
            }
        }

        val models = db.rawQuery(
            """
            SELECT m.model_id AS id, MIN(m.provider_id) AS provider_id,
              COUNT(*) AS item_count
            FROM message_rows m
            WHERE NULLIF(TRIM(m.model_id), '') IS NOT NULL $rangeWhere
            GROUP BY m.model_id ORDER BY item_count DESC, id
            """.trimIndent(),
            rangeVars.map { it.toString() }.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChatStatsRankRow(
                            id = c.getString(0),
                            label = c.getString(0),
                            count = c.getLong(2),
                            providerId = c.getString(1),
                        ),
                    )
                }
            }
        }

        val topics = db.rawQuery(
            """
            SELECT c.id AS id, c.title AS label, COUNT(*) AS item_count
            FROM message_rows m
            JOIN conversation_rows c ON c.id = m.conversation_id
            WHERE 1 = 1 $rangeWhere
            GROUP BY c.id, c.title ORDER BY item_count DESC, c.id
            """.trimIndent(),
            rangeVars.map { it.toString() }.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ChatStatsRankRow(id = c.getString(0), label = c.getString(1), count = c.getLong(2)))
                }
            }
        }

        val assistants = db.rawQuery(
            """
            SELECT COALESCE(NULLIF(TRIM(assistant_id), ''), '_default') AS id,
              COUNT(*) AS item_count
            FROM conversation_rows
            $conversationRangeWherePlain
            GROUP BY id ORDER BY item_count DESC, id
            """.trimIndent(),
            rangeVars.map { it.toString() }.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ChatStatsRankRow(id = c.getString(0), label = c.getString(0), count = c.getLong(1)))
                }
            }
        }

        return ChatStatsAggregate(
            conversations = summary.first,
            totals = summary.second,
            heatmap = heatmap,
            trend = trend,
            models = models,
            assistants = assistants,
            topics = topics,
        )
    }

    private fun readSummary(c: android.database.Cursor): Pair<Long, ChatStatsTotals> =
        Pair(
            c.getLong(0),
            ChatStatsTotals(
                messages = c.getLong(1),
                inputTokens = c.getLong(2),
                outputTokens = c.getLong(3),
                cachedTokens = c.getLong(4),
            ),
        )

    private fun parseDay(raw: String): LocalDate = LocalDate.parse(raw)

    companion object {
        /** Local-zone epoch millis at 00:00 of [date] (bind param helper). */
        fun startOfDayMs(date: LocalDate): Long =
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /** End-exclusive millis (Flutter binds addCalendarDays(end, 1)). */
        fun startOfNextDayMs(date: LocalDate): Long =
            date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
