package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.stats.ChatStatsAggregate
import com.psyche.memo.data.stats.ChatStatsTotals
import com.psyche.memo.data.stats.StatsAggregation
import com.psyche.memo.data.stats.StatsDao
import com.psyche.memo.data.stats.StatsDateRange
import com.psyche.memo.data.stats.StatsDateRangePreset
import com.psyche.memo.data.stats.StatsHeatmapDay
import com.psyche.memo.data.stats.StatsRankItem
import com.psyche.memo.data.stats.StatsSnapshot
import com.psyche.memo.data.stats.StatsTrendDay
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HeatCellSize = 11.dp
private val HeatCellPadding = 1.5.dp
private val HeatCellPitch = 14.dp // size + padding * 2
private val HeatWeekGap = 1.dp
private val HeatMonthLabelHeight = 12.dp
private val HeatMonthLabelGap = 4.dp
private val HeatWeekdayLabelWidth = 18.dp

/**
 * Stats page — lib/features/stats/pages/stats_page.dart 1:1: range chips,
 * 365-day heatmap, summary metric grid, per-provider usage trend chart and
 * model/assistant/topic rank sections over the SQL aggregate
 * (chat_database_repository.dart queryStatsAggregate).
 */
@Composable
fun StatsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    val unknownProviderLabel = stringResource(UiR.string.stats_page_unknown_provider)
    val unknownTopicLabel = stringResource(UiR.string.stats_page_unknown_topic)
    val unknownAssistantLabel = stringResource(UiR.string.stats_page_unknown_assistant)

    var range by remember { mutableStateOf(StatsDateRange.allTime()) }
    var snapshot by remember { mutableStateOf<StatsSnapshot?>(null) }
    var assistantsById by remember { mutableStateOf<Map<String, Assistant>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var customRangeSheet by remember { mutableStateOf(false) }
    var fullRank by remember { mutableStateOf<RankSpec?>(null) }

    LaunchedEffect(range) {
        loading = true
        val (result, assistants) = withContext(Dispatchers.IO) {
            // Assistant/provider names mirror the page's maps
            // (stats_page.dart L184-195).
            //
            // 原来这里是自己 new 一个 PayloadEntityDao 把两张表**整表解一遍**、还每行
            // 现造一个 `Json {}`；现在走容器那两个缓存过的入口（`assistantStore.getAll()`
            // 会把每行写进 AssistantCache，`ProviderConfigCache` 同），既不再重复解码，
            // 也顺手把抽屉/助手页要用的缓存喂暖。
            val parsedAssistants = runCatching { container.assistantStore.getAll() }
                .getOrDefault(emptyList())
            val assistantNames = buildMap {
                parsedAssistants.forEach { assistant ->
                    put(assistant.id, assistant.name.trim().ifEmpty { unknownAssistantLabel })
                }
                put("_default", unknownAssistantLabel)
            }
            val providerJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val providerNames = buildMap {
                runCatching {
                    PayloadEntityDao(
                        container.database.readableDatabase,
                        "provider_rows",
                        primaryKey = "provider_key",
                    ).getAll()
                }.getOrDefault(emptyList()).forEach { row ->
                    val config = com.psyche.memo.data.db.ProviderConfigCache.get(row.id)
                        ?: runCatching {
                            ProviderConfig.fromJsonString(providerJson, row.payload)
                        }.getOrNull()?.also { com.psyche.memo.data.db.ProviderConfigCache.put(row.id, it) }
                    config?.let { put(it.id, it.name) }
                }
            }
            val launchCount = runCatching {
                container.preferenceRepository.readJson("app_launch_count_v1")?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)

            val today = LocalDate.now()
            val trendStart = if (range.isAllTime) today.minusDays(29) else (range.start ?: today.minusDays(29))
            val trendEnd = if (range.isAllTime) today else (range.end ?: today)
            val aggregate = runCatching {
                StatsDao(container.database.readableDatabase).queryAggregate(
                    rangeStartMs = range.start?.let { StatsDao.startOfDayMs(it) },
                    rangeEndExclusiveMs = range.end?.let { StatsDao.startOfNextDayMs(it) },
                    heatmapStartMs = StatsDao.startOfDayMs(today.minusDays(364)),
                    trendStartMs = StatsDao.startOfDayMs(trendStart),
                    trendEndExclusiveMs = StatsDao.startOfNextDayMs(trendEnd),
                )
            }.getOrElse {
                ChatStatsAggregate(0L, ChatStatsTotals(0, 0, 0, 0), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
            StatsAggregation.buildDatabaseSnapshot(
                now = today,
                range = range,
                aggregate = aggregate,
                launchCount = launchCount,
                unknownProviderLabel = unknownProviderLabel,
                unknownTopicLabel = unknownTopicLabel,
                assistantNames = assistantNames,
                providerNames = providerNames,
            ) to parsedAssistants.associateBy { it.id }
        }
        snapshot = result
        assistantsById = assistants
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.stats_page_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // _RangeSelector — L68.
            item {
                RangeSelector(
                    selected = range,
                    onChanged = { preset ->
                        val now = LocalDate.now()
                        range = when (preset) {
                            StatsDateRangePreset.ALL_TIME -> StatsDateRange.allTime()
                            StatsDateRangePreset.LAST_30_DAYS -> StatsDateRange.last30Days(now)
                            StatsDateRangePreset.PREVIOUS_MONTH -> StatsDateRange.previousMonth(now)
                            StatsDateRangePreset.PREVIOUS_QUARTER -> StatsDateRange.previousQuarter(now)
                            StatsDateRangePreset.CUSTOM -> range
                        }
                    },
                    onCustom = { customRangeSheet = true },
                )
                Spacer(Modifier.height(8.dp))
                // L74-82 — 2px loading bar.
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
            val active = snapshot
            if (active == null) {
                // 加载期间**不渲染内容**、只画加载态（照 RikkaHub `StatsPage.kt:73-81`）。
                //
                // 这里原来是 `snapshot ?: StatsAggregation.buildDatabaseSnapshot(...)`：
                // 兜底在**组合期（主线程）**跑一遍聚合，而同一个东西在 133 行的
                // LaunchedEffect 里已经在 IO 上算过 —— 算两遍、其中一遍卡主线程，用户
                // 2026-09-15「点击统计界面会卡一下」就是它。加载态本身是上方那条 2px
                // 进度条（`loading` 为真），所以这里只补一个居中指示器、不再兜底渲染。
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = cs.primary,
                        )
                    }
                }
                return@LazyColumn
            }
            // Categorized sections (user request): overview group for the raw
            // data cards, ranking group for the three rank cards — matching
            // the SectionHeader + card form of the settings home.
            //
            // 分区即 item：原先这里是 `Column + verticalScroll`，进页面把热力图、指标网格、
            // 趋势图、三张排行榜**一次性组合**（2026-09-15 卡顿审计里记着）；改成 LazyColumn
            // 后每块各占一个 item，滚到才组合。
            item { SectionHeader(stringResource(UiR.string.stats_page_section_overview), first = true) }
            item {
                StatsSectionCard(title = stringResource(UiR.string.stats_page_heatmap_title)) {
                    StatsHeatmapPanel(days = active.heatmap)
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                StatsSectionCard(title = stringResource(UiR.string.stats_page_summary_title)) {
                    StatsMetricGridPanel(summary = active.summary)
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                StatsSectionCard(title = stringResource(UiR.string.stats_page_usage_trend_title)) {
                    StatsUsageChartPanel(days = active.trend)
                }
                Spacer(Modifier.height(12.dp))
            }
            // L99-156 — three rank sections stacked on phones (<820).
            item { SectionHeader(stringResource(UiR.string.stats_page_section_ranking)) }
            item {
                Column {
                    val sections = listOf(
                        RankSpec(
                            title = stringResource(UiR.string.stats_page_model_usage_title),
                            leftHeader = stringResource(UiR.string.stats_page_model_column),
                            rightHeader = stringResource(UiR.string.stats_page_messages_column),
                            items = active.modelRank,
                            leading = LeadingKind.MODEL,
                        ),
                        RankSpec(
                            title = stringResource(UiR.string.stats_page_assistant_usage_title),
                            leftHeader = stringResource(UiR.string.stats_page_assistant_column),
                            rightHeader = stringResource(UiR.string.stats_page_topics_column),
                            items = active.assistantRank,
                            leading = LeadingKind.ASSISTANT,
                        ),
                        RankSpec(
                            title = stringResource(UiR.string.stats_page_topic_volume_title),
                            leftHeader = stringResource(UiR.string.stats_page_topic_column),
                            rightHeader = stringResource(UiR.string.stats_page_messages_column),
                            items = active.topicRank,
                            leading = LeadingKind.ICON,
                        ),
                    )
                    sections.forEachIndexed { index, spec ->
                        StatsRankCard(
                            spec = spec,
                            assistantById = assistantsById,
                            onShowAll = { fullRank = spec },
                        )
                        if (index != sections.size - 1) Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    if (customRangeSheet) {
        CustomRangeSheet(
            initial = range,
            onApply = { start, end ->
                customRangeSheet = false
                range = StatsDateRange.custom(start, end)
            },
            onDismiss = { customRangeSheet = false },
        )
    }

    fullRank?.let { spec ->
        RankFullPageOverlay(
            spec = spec,
            assistantById = assistantsById,
            onDismiss = { fullRank = null },
        )
    }
}

private enum class LeadingKind { MODEL, ASSISTANT, ICON }

private data class RankSpec(
    val title: String,
    val leftHeader: String,
    val rightHeader: String,
    val items: List<StatsRankItem>,
    val leading: LeadingKind,
)

/** _RangeSelector/_RangeButton — L353-489. */
@Composable
private fun RangeSelector(
    selected: StatsDateRange,
    onChanged: (StatsDateRangePreset) -> Unit,
    onCustom: () -> Unit,
) {
    val options = listOf(
        Triple(StatsDateRangePreset.ALL_TIME, stringResource(UiR.string.stats_page_range_all_time), selected.preset == StatsDateRangePreset.ALL_TIME),
        Triple(StatsDateRangePreset.LAST_30_DAYS, stringResource(UiR.string.stats_page_range_last30_days), selected.preset == StatsDateRangePreset.LAST_30_DAYS),
        Triple(StatsDateRangePreset.PREVIOUS_MONTH, stringResource(UiR.string.stats_page_range_previous_month), selected.preset == StatsDateRangePreset.PREVIOUS_MONTH),
        Triple(StatsDateRangePreset.PREVIOUS_QUARTER, stringResource(UiR.string.stats_page_range_previous_quarter), selected.preset == StatsDateRangePreset.PREVIOUS_QUARTER),
        Triple(StatsDateRangePreset.CUSTOM, stringResource(UiR.string.stats_page_range_custom), selected.preset == StatsDateRangePreset.CUSTOM),
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, (preset, label, isSelected) ->
            RangeChip(label = label, selected = isSelected) {
                if (preset == StatsDateRangePreset.CUSTOM) onCustom() else onChanged(preset)
            }
            if (index != options.size - 1) Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val background = if (selected) {
        cs.onSurface.copy(alpha = if (semantic.isDark) 0.16f else 0.14f)
    } else {
        semantic.surfaceFill
    }
    Box(
        modifier = Modifier
            .height(32.dp)
            .background(background, RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = cs.onSurface.copy(alpha = if (selected) 0.9f else if (semantic.isDark) 0.7f else 0.62f),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
        )
    }
}

/** StatsSectionCard — stats_section_card.dart: surfaceCard r12 hairline 0.6. */
@Composable
private fun StatsSectionCard(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.9f)),
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** StatsHeatmap — stats_heatmap.dart. */
@Composable
private fun StatsHeatmapPanel(days: List<StatsHeatmapDay>) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val activeCounts = days.filter { it.count > 0 }.map { it.count }.sorted()
    val q1 = StatsAggregation.quantile(activeCounts, 0.25)
    val q2 = StatsAggregation.quantile(activeCounts, 0.50)
    val q3 = StatsAggregation.quantile(activeCounts, 0.75)
    val weeks = remember(days) { calendarWeeks(days) }
    val hScroll = rememberScrollState()
    // _scheduleScrollToLatest — jump to the latest week on first layout.
    LaunchedEffect(days) {
        kotlin.runCatching {
            androidx.compose.runtime.snapshotFlow { hScroll.maxValue }.collect { max ->
                if (max > 0) hScroll.scrollTo(max)
            }
        }
    }
    val levelOf: (Long) -> Int = { StatsAggregation.levelFor(it, q1, q2, q3) }

    Column {
        Row {
            HeatWeekdayLabels()
            Row(Modifier.horizontalScroll(hScroll)) {
                Column {
                    Row {
                        weeks.forEachIndexed { index, week ->
                            MonthLabel(week)
                            if (index != weeks.size - 1) Spacer(Modifier.width(HeatWeekGap))
                        }
                    }
                    Spacer(Modifier.height(HeatMonthLabelGap))
                    Row {
                        weeks.forEachIndexed { index, week ->
                            Column {
                                week.forEach { day ->
                                    Box(Modifier.padding(HeatCellPadding)) {
                                        HeatCell(level = levelOf(day.count))
                                    }
                                }
                            }
                            if (index != weeks.size - 1) Spacer(Modifier.width(HeatWeekGap))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(HeatWeekdayLabelWidth))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(UiR.string.stats_page_heatmap_less),
                        style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.56f)),
                    )
                    Spacer(Modifier.width(6.dp))
                    for (level in 0..4) {
                        HeatCell(level = level, size = 10.dp)
                        Spacer(Modifier.width(3.dp))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(UiR.string.stats_page_heatmap_more),
                        style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.56f)),
                    )
                }
            }
        }
    }
}

private fun calendarWeeks(days: List<StatsHeatmapDay>): List<List<StatsHeatmapDay>> {
    if (days.isEmpty()) return emptyList()
    val byDate = days.associateBy { it.date }
    val first = days.first().date
    val last = days.last().date
    // Dart: first.weekday % 7 (Mon=1..Sun=7 → 1..0) — Sunday-start weeks.
    val start = first.minusDays(((first.dayOfWeek.value) % 7).toLong())
    val weeks = ArrayList<List<StatsHeatmapDay>>()
    var weekStart = start
    while (!weekStart.isAfter(last)) {
        val weekEnd = weekStart.plusDays(6)
        val visibleEnd = if (weekEnd.isAfter(last)) last else weekEnd
        val week = ArrayList<StatsHeatmapDay>(7)
        var date = weekStart
        while (!date.isAfter(visibleEnd)) {
            week.add(byDate[date] ?: StatsHeatmapDay(date, 0))
            date = date.plusDays(1)
        }
        weeks.add(week)
        weekStart = weekStart.plusDays(7)
    }
    return weeks
}

@Composable
private fun HeatWeekdayLabels() {
    val cs = MaterialTheme.colorScheme
    // L260-313: narrow weekday letters at Mon/Wed/Fri, Sunday-first columns.
    val labels = remember {
        listOf(7, 1, 2, 3, 4, 5, 6).map { dayNumber ->
            DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
                .format(LocalDate.of(2024, 1, 7).plusDays(dayNumber.toLong()))
        }
    }
    Column(Modifier.width(HeatWeekdayLabelWidth)) {
        Spacer(Modifier.height(HeatMonthLabelHeight + HeatMonthLabelGap))
        listOf(7, 1, 2, 3, 4, 5, 6).forEachIndexed { index, dayNumber ->
            Box(Modifier.height(HeatCellPitch), contentAlignment = Alignment.CenterStart) {
                if (dayNumber == 1 || dayNumber == 3 || dayNumber == 5) {
                    Text(
                        text = labels[index],
                        maxLines = 1,
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.46f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthLabel(week: List<StatsHeatmapDay>) {
    val cs = MaterialTheme.colorScheme
    val firstOfMonth = week.firstOrNull { it.date.dayOfMonth == 1 }?.date
    Box(Modifier.width(14.dp).height(HeatMonthLabelHeight), contentAlignment = Alignment.CenterStart) {
        if (firstOfMonth != null) {
            Text(
                text = DateTimeFormatter.ofPattern("MMM", Locale.getDefault()).format(firstOfMonth),
                maxLines = 1,
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.46f)),
            )
        }
    }
}

@Composable
private fun HeatCell(level: Int, size: androidx.compose.ui.unit.Dp = HeatCellSize) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val color = if (level == 0) {
        cs.onSurface.copy(alpha = if (semantic.isDark) 0.14f else 0.12f)
    } else {
        val alpha = when (level) {
            1 -> 0.25f
            2 -> 0.45f
            3 -> 0.68f
            else -> 0.92f
        }
        cs.primary.copy(alpha = alpha)
    }
    Box(
        Modifier
            .size(size)
            .background(color, RoundedCornerShape(3.dp)),
    )
}

/** StatsMetricGrid — stats_metric_grid.dart (2 columns at phone widths). */
@Composable
private fun StatsMetricGridPanel(summary: com.psyche.memo.data.stats.StatsSummary) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val items = listOf(
        MetricTileData(Lucide.MessagesSquare, stringResource(UiR.string.stats_page_total_conversations), formatCompact(summary.totalConversations)),
        MetricTileData(Lucide.MessageCircle, stringResource(UiR.string.stats_page_total_messages), formatCompact(summary.totalMessages)),
        MetricTileData(Lucide.Activity, stringResource(UiR.string.stats_page_input_tokens), formatCompact(summary.inputTokens)),
        MetricTileData(Lucide.Activity, stringResource(UiR.string.stats_page_output_tokens), formatCompact(summary.outputTokens)),
        MetricTileData(Lucide.Zap, stringResource(UiR.string.stats_page_cached_tokens), formatCompact(summary.cachedTokens)),
        MetricTileData(Lucide.Activity, stringResource(UiR.string.stats_page_launch_count), formatCompact(summary.launchCount)),
    )
    BoxWithConstraints {
        val columns = when {
            maxWidth >= 720.dp -> 3
            maxWidth >= 420.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { data ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(78.dp)
                                .background(
                                    if (semantic.isDark) cs.onSurface.copy(alpha = 0.06f)
                                    else cs.surfaceContainerHighest.copy(alpha = 0.38f),
                                    RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(data.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = data.value,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = data.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                                )
                            }
                        }
                    }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class MetricTileData(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val value: String,
)

/**
 * 总览六格的数字。上游是 `_formatCompact`（≥1000 折成 `1.2K`／`3.45M`），
 * **这里有意偏离**：用户 2026-09-20「总览不要 K、M 这些单位，直接就显示数字」——
 * 缩写看着像概览、还读数不准，所以改成千分位全量数字（`12,345,678`）。
 */
private fun formatCompact(value: Long): String =
    String.format(java.util.Locale.getDefault(), "%,d", value)

/** StatsUsageChart — stats_usage_chart.dart. */
@Composable
private fun StatsUsageChartPanel(days: List<StatsTrendDay>) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val providers = remember(days) {
        val set = LinkedHashSet<String>()
        var maxDayTotal = 0L
        days.forEach { day ->
            var dayTotal = 0L
            day.providerTokens.forEach { (provider, bucket) ->
                if (bucket.chartWeight > 0) set.add(provider)
                dayTotal += bucket.chartWeight
            }
            if (dayTotal > maxDayTotal) maxDayTotal = dayTotal
        }
        set.toList()
    }
    val maxDetailRows = days.maxOfOrNull { day ->
        day.providerTokens.values.count { it.totalTokens > 0 }
    } ?: 0
    val chartHeight = (82 + maxDetailRows * 31).coerceIn(160, 360)
    var selectedDayIndex by remember(days) { mutableStateOf<Int?>(null) }

    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(chartHeight.dp)) {
            if (days.isNotEmpty()) {
                val barWidth = if (days.size > 45) 8.dp else 12.dp
                val gap = if (days.size > 45) 3.dp else 5.dp
                val slot = barWidth + gap
                val contentWidth = barWidth * days.size + gap * (days.size - 1)
                val chartWidth = if (contentWidth < maxWidth) maxWidth else contentWidth
                val density = LocalDensity.current
                val hScroll = rememberScrollState()

                Row(
                    modifier = Modifier
                        .horizontalScroll(hScroll, reverseScrolling = true)
                        // L84-104: tap selects a day; drag pans via scroll.
                        .pointerInput(days) {
                            detectTapGestures { offset ->
                                val dxDp = with(density) { offset.x.toDp() }
                                selectedDayIndex = dayIndexAt(dxDp, days.size, barWidth, gap)
                            }
                        },
                ) {
                    Box(Modifier.width(chartWidth).fillMaxHeight()) {
                        val series = semantic.chartSeries
                        val baselineColor = cs.onSurface.copy(alpha = if (semantic.isDark) 0.08f else 0.10f)
                        val selectedStroke = cs.onSurface.copy(alpha = if (semantic.isDark) 0.72f else 0.46f)
                        Canvas(Modifier.fillMaxSize()) {
                            val barWidthPx = barWidth.toPx()
                            val gapPx = gap.toPx()
                            val maxTotal = days.maxOf { day -> day.providerTokens.values.sumOf { it.chartWeight } }
                            days.forEachIndexed { i, day ->
                                val x = i * (barWidthPx + gapPx)
                                // L346-356 — 3dp baseline pill.
                                drawRoundRect(
                                    color = baselineColor,
                                    topLeft = Offset(x, size.height - 3.dp.toPx()),
                                    size = androidx.compose.ui.geometry.Size(barWidthPx, 3.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx()),
                                )
                                val total = day.providerTokens.values.sumOf { it.chartWeight }
                                if (total <= 0 || maxTotal <= 0) return@forEachIndexed
                                val barHeight = (size.height * total / maxTotal)
                                    .coerceIn(8.dp.toPx(), size.height)
                                val barRect = Rect(
                                    left = x,
                                    top = size.height - barHeight,
                                    right = x + barWidthPx,
                                    bottom = size.height,
                                )
                                val clip = Path().apply {
                                    addRoundRect(
                                        androidx.compose.ui.geometry.RoundRect(
                                            barRect,
                                            CornerRadius(4.dp.toPx()),
                                        ),
                                    )
                                }
                                clipPath(clip, ClipOp.Intersect) {
                                    var segmentBottom = size.height
                                    providers.forEachIndexed { providerIndex, provider ->
                                        val bucket = day.providerTokens[provider]
                                        val weight = bucket?.chartWeight ?: 0L
                                        if (weight <= 0) return@forEachIndexed
                                        val segmentHeight = barHeight * weight / total
                                        drawRect(
                                            color = series[providerIndex % series.size],
                                            topLeft = Offset(x, segmentBottom - segmentHeight),
                                            size = androidx.compose.ui.geometry.Size(barWidthPx, segmentHeight),
                                        )
                                        segmentBottom -= segmentHeight
                                    }
                                }
                                if (selectedDayIndex == i) {
                                    val inflate = 2.dp.toPx()
                                    val outline = Rect(
                                        left = barRect.left - inflate,
                                        top = barRect.top - inflate,
                                        right = barRect.right + inflate,
                                        bottom = barRect.bottom + inflate,
                                    )
                                    drawRoundRect(
                                        color = selectedStroke,
                                        topLeft = outline.topLeft,
                                        size = outline.size,
                                        cornerRadius = CornerRadius(5.dp.toPx()),
                                        style = Stroke(width = 1.4.dp.toPx()),
                                    )
                                }
                            }
                        }
                        // L145-155 — detail bubble over the selected bar.
                        selectedDayIndex?.takeIf { it in days.indices }?.let { index ->
                            val barLeft = (barWidth + gap) * index + barWidth / 2
                            UsageDetailBubble(
                                day = days[index],
                                providers = providers,
                                barLeft = barLeft,
                                chartWidth = chartWidth,
                            )
                        }
                    }
                }
            }
        }
        if (providers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            // L190-219 — legend chips.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                providers.forEachIndexed { index, provider ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(semantic.chartSeries[index % semantic.chartSeries.size], RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = provider,
                            style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                        )
                    }
                }
            }
        }
    }
}

/** L301-317 _dayIndexAt — hit test with half-gap slop. */
private fun dayIndexAt(
    dx: androidx.compose.ui.unit.Dp,
    daysLength: Int,
    barWidth: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
): Int? {
    if (daysLength <= 0 || dx <= 0.dp) return null
    val slot = barWidth + gap
    val index = (dx / slot).toInt()
    if (index < 0 || index >= daysLength) return null
    val slotStart = slot * index
    val hitStart = slotStart - gap / 2
    val hitEnd = slotStart + barWidth + gap / 2
    if (dx < hitStart || dx > hitEnd) return null
    return index
}

/** L425-506 _UsageDetailBubble — 228dp card, clamped over the bar. */
@Composable
private fun UsageDetailBubble(
    day: StatsTrendDay,
    providers: List<String>,
    barLeft: androidx.compose.ui.unit.Dp,
    chartWidth: androidx.compose.ui.unit.Dp,
) {
    val cs = MaterialTheme.colorScheme
    val bubbleWidth = 228.dp
    val maxLeft = (chartWidth - bubbleWidth).coerceAtLeast(0.dp)
    val left = (barLeft - bubbleWidth / 2).coerceIn(0.dp, maxLeft)
    val rows = providers.mapIndexedNotNull { index, provider ->
        val bucket = day.providerTokens[provider]
        if (bucket != null && bucket.totalTokens > 0) Triple(index, provider, bucket) else null
    }
    if (rows.isEmpty()) return
    Box(
        Modifier
            .offset(x = left, y = 8.dp)
            .width(bubbleWidth)
            .shadow(9.dp, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .background(cs.surfaceContainerHigh, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 11.dp),
    ) {
        Column {
            Text(
                text = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(day.date),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.82f)),
            )
            Spacer(Modifier.height(8.dp))
            rows.forEach { (providerIndex, provider, bucket) ->
                Column(Modifier.padding(bottom = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val semantic = LocalSemanticColors.current
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(semantic.chartSeries[providerIndex % semantic.chartSeries.size], RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = provider,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.86f)),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(UiR.string.token_detail_total_tokens, bucket.totalTokens.toString()),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.7f)),
                        )
                    }
                }
            }
        }
    }
}

/** StatsRankSection — stats_rank_section.dart. */
@Composable
private fun StatsRankCard(
    spec: RankSpec,
    assistantById: Map<String, Assistant>,
    onShowAll: () -> Unit,
) {
    StatsSectionCard(title = spec.title) {
        if (spec.items.size > 5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onShowAll, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Lucide.Maximize,
                        contentDescription = stringResource(UiR.string.stats_page_show_all_tooltip),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(28.dp))
        }
        RankBody(
            leftHeader = spec.leftHeader,
            rightHeader = spec.rightHeader,
            items = spec.items.take(5),
            kind = spec.leading,
            assistantById = assistantById,
        )
    }
}

/** _RankBody — L150-214. */
@Composable
private fun RankBody(
    leftHeader: String,
    rightHeader: String,
    items: List<StatsRankItem>,
    kind: LeadingKind,
    assistantById: Map<String, Assistant>,
) {
    val cs = MaterialTheme.colorScheme
    if (items.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(UiR.string.stats_page_empty_title),
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.58f)),
            )
        }
        return
    }
    val maxValue = items.fold(0L) { acc, item -> if (item.value > acc) item.value else acc }
    Column {
        Row(Modifier.padding(bottom = 8.dp)) {
            Text(
                text = leftHeader,
                style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.52f), fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = rightHeader,
                style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.52f), fontWeight = FontWeight.SemiBold),
            )
        }
        items.forEach { item ->
            RankRow(item = item, maxValue = maxValue, kind = kind, assistantById = assistantById)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** _RankRow — L235-331: fill pill 0.36..1.0 of the max ratio. */
@Composable
private fun RankRow(
    item: StatsRankItem,
    maxValue: Long,
    kind: LeadingKind,
    assistantById: Map<String, Assistant>,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val ratio = if (maxValue <= 0) 0.0 else item.value.toDouble() / maxValue
    val widthFactor = (0.36 + ratio * 0.64).coerceIn(0.36, 1.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(34.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(widthFactor.toFloat())
                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp)),
            )
            Row(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (kind) {
                    LeadingKind.MODEL -> {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            ModelRankIcon(providerId = item.providerId, modelId = item.id, size = 32.dp)
                        }
                    }
                    LeadingKind.ASSISTANT -> {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            AssistantAvatarMini(
                                assistant = assistantById[item.id],
                                fallbackName = item.label,
                                size = 20.dp,
                            )
                        }
                    }
                    LeadingKind.ICON -> {
                        Icon(
                            Lucide.MessageSquare,
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.58f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.86f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.value.toString(),
            textAlign = TextAlign.Right,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.76f)),
            modifier = Modifier.width(52.dp),
        )
    }
}

/** CurrentModelIcon (model_icon.dart) with withBackground=false. */
@Composable
private fun ModelRankIcon(providerId: String?, modelId: String, size: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val asset = remember(providerId, modelId) {
        BrandAssets.assetForName(modelId) ?: providerId?.let { BrandAssets.assetForName(it) }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (asset != null) {
            val mono = semantic.isDark && BrandAssets.assetNeedsDarkInvert(asset)
            AsyncImage(
                model = asset,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                colorFilter = if (mono) ColorFilter.tint(cs.onSurface) else null,
            )
        } else {
            Text(
                text = modelId.trim().take(1).uppercase().ifEmpty { "?" },
                style = TextStyle(fontSize = (size.value * 0.43f).sp, fontWeight = FontWeight.ExtraBold, color = cs.primary),
            )
        }
    }
}

/** AssistantAvatar (assistant_avatar.dart) — http / local file / emoji / initial. */
@Composable
private fun AssistantAvatarMini(
    assistant: Assistant?,
    fallbackName: String,
    size: androidx.compose.ui.unit.Dp,
) {
    // Delegates to the shared四态 renderer. The old copy here only handled
    // http + emoji and drew a 0.5dp ring the Flutter widget does not have
    // (stats_page.dart:121 just uses AssistantAvatar), so a gallery-picked
    // avatar showed up as the first character of its file path.
    AssistantListAvatar(
        item = assistant ?: Assistant(name = fallbackName),
        size = size,
    )
}

/** _RankFullPage — L110-148 (mobile <560 branch as an overlay screen). */
@Composable
private fun RankFullPageOverlay(
    spec: RankSpec,
    assistantById: Map<String, Assistant>,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 同屏二级页：系统返回回到统计页，别 pop 掉整个 stats 路由。
    OverlayBackHandler(onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 与 MemoTopBar 的返回键同一套触感（IosIconButton：按压变色 + 缩放）。
            // 原来是 M3 `IconButton`，而本工程全局关掉了 ripple → 点它没有任何反馈
            //（用户 2026-09-15「多界面返回箭头带有点击阴影」）。
            IosIconButton(
                icon = Lucide.ArrowLeft,
                onTap = onDismiss,
                color = cs.onSurface,
                size = 22.dp,
                contentPadding = 0.dp,
                minSize = 44.dp,
                semanticLabel = stringResource(UiR.string.settings_page_back_button),
            )
            Text(
                text = spec.title,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            RankBody(
                leftHeader = spec.leftHeader,
                rightHeader = spec.rightHeader,
                items = spec.items,
                kind = spec.leading,
                assistantById = assistantById,
            )
        }
    }
}

/** _CustomRangeSheet — L491-655 + _showStatsDatePicker panels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeSheet(
    initial: StatsDateRange,
    onApply: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val today = LocalDate.now()
    var start by remember { mutableStateOf(initial.start ?: today.minusDays(29)) }
    var end by remember { mutableStateOf(initial.end ?: today) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null, // 原版自绘容器，禁用 Material 默认 handle
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(UiR.string.stats_page_custom_range_title),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.92f)),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Box(Modifier.weight(1f)) {
                    DateField(
                        label = stringResource(UiR.string.stats_page_custom_range_start),
                        date = start,
                        onTap = { pickingStart = true },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    DateField(
                        label = stringResource(UiR.string.stats_page_custom_range_end),
                        date = end,
                        onTap = { pickingEnd = true },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                Box(
                    Modifier
                        .weight(1f)
                        .background(cs.onSurface.copy(alpha = if (semantic.isDark) 0.08f else 0.09f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(UiR.string.stats_page_custom_range_cancel),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.74f)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .background(cs.onSurface.copy(alpha = if (semantic.isDark) 0.16f else 0.14f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                        .clickable { onApply(start, end) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(UiR.string.stats_page_custom_range_apply),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.9f)),
                    )
                }
            }
        }
    }

    if (pickingStart) {
        StatsDatePickerSheet(
            firstDate = LocalDate.of(2000, 1, 1),
            lastDate = today,
            initialDate = start,
            onSelected = { date ->
                pickingStart = false
                start = date
                if (end.isBefore(start)) end = start
            },
            onDismiss = { pickingStart = false },
        )
    }
    if (pickingEnd) {
        StatsDatePickerSheet(
            firstDate = LocalDate.of(2000, 1, 1),
            lastDate = today,
            initialDate = end,
            onSelected = { date ->
                pickingEnd = false
                end = date
                if (start.isAfter(end)) start = end
            },
            onDismiss = { pickingEnd = false },
        )
    }
}

/** _DateField — L1098-1142. */
@Composable
private fun DateField(label: String, date: LocalDate, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.onSurface.copy(alpha = if (semantic.isDark) 0.07f else 0.06f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.52f)),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(date),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.9f)),
        )
    }
}

/** _StatsDatePickerPanel — L693-895 (day + year-month modes). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsDatePickerSheet(
    firstDate: LocalDate,
    lastDate: LocalDate,
    initialDate: LocalDate,
    onSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var selectedDate by remember { mutableStateOf(initialDate) }
    var visibleMonth by remember { mutableStateOf(initialDate.withDayOfMonth(1)) }
    var monthMode by remember { mutableStateOf(false) }

    fun clampMonth(month: LocalDate): LocalDate {
        val firstMonth = firstDate.withDayOfMonth(1)
        val lastMonth = lastDate.withDayOfMonth(1)
        return when {
            month.isBefore(firstMonth) -> firstMonth
            month.isAfter(lastMonth) -> lastMonth
            else -> month
        }
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 14.dp)) {
            // Header — L741-792.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        visibleMonth = clampMonth(
                            if (monthMode) visibleMonth.minusYears(1) else visibleMonth.minusMonths(1),
                        )
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Lucide.ChevronLeft, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier
                            .background(cs.onSurface.copy(alpha = if (semantic.isDark) 0.08f else 0.06f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                            .clickable { monthMode = !monthMode }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (monthMode) {
                                DateTimeFormatter.ofPattern("yyyy").format(visibleMonth)
                            } else {
                                DateTimeFormatter.ofPattern("yyyy-MM").format(visibleMonth)
                            },
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.9f)),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        visibleMonth = clampMonth(
                            if (monthMode) visibleMonth.plusYears(1) else visibleMonth.plusMonths(1),
                        )
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!monthMode) {
                // Weekday labels — L886-894 (week starts Monday).
                val labels = remember {
                    (0..6).map { i ->
                        DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
                            .format(LocalDate.of(2026, 5, 4).plusDays(i.toLong()))
                    }
                }
                Row {
                    labels.forEach { label ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface.copy(alpha = 0.42f)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
                // _MonthGrid — L899-942: 42 cells, Monday-first.
                val gridStart = visibleMonth.minusDays(((visibleMonth.dayOfWeek.value + 6) % 7).toLong())
                val cells = remember(visibleMonth, firstDate, lastDate, selectedDate) {
                    (0 until 42).map { index ->
                        val date = gridStart.plusDays(index.toLong())
                        Triple(
                            date,
                            date.monthValue == visibleMonth.monthValue,
                            !date.isBefore(firstDate) && !date.isAfter(lastDate),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    cells.chunked(7).forEach { week ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            week.forEach { (date, inMonth, enabled) ->
                                val isSelected = date == selectedDate
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .background(
                                            if (isSelected) cs.onSurface.copy(alpha = if (semantic.isDark) 0.18f else 0.14f) else Color.Transparent,
                                            RoundedCornerShape(MemoRadius.INNER_DP.dp),
                                        )
                                        .clickable(enabled = enabled) {
                                            selectedDate = date
                                            onSelected(date)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = cs.onSurface.copy(
                                                alpha = when {
                                                    !enabled -> 0.18f
                                                    inMonth -> 0.82f
                                                    else -> 0.34f
                                                },
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // _YearMonthGrid — L944-1002: 4 columns, aspect 1.85.
                val formatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..12).chunked(4).forEach { months ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            months.forEach { month ->
                                val monthDate = LocalDate.of(visibleMonth.year, month, 1)
                                val enabled = !monthDate.withDayOfMonth(1).isBefore(firstDate.withDayOfMonth(1)) &&
                                    !monthDate.isAfter(lastDate)
                                val isSelected = selectedDate.year == visibleMonth.year && selectedDate.monthValue == month
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .background(
                                            if (isSelected) cs.onSurface.copy(alpha = if (semantic.isDark) 0.18f else 0.14f)
                                            else semantic.surfaceFill,
                                            RoundedCornerShape(MemoRadius.PILL_DP.dp),
                                        )
                                        .clickable(enabled = enabled) {
                                            visibleMonth = LocalDate.of(visibleMonth.year, month, 1)
                                            monthMode = false
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = formatter.format(monthDate),
                                        maxLines = 1,
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = cs.onSurface.copy(alpha = if (enabled) 0.82f else 0.22f),
                                        ),
                                    )
                                }
                            }
                            if (months.size < 4) repeat(4 - months.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
