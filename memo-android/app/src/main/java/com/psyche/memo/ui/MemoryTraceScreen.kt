package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.snackbar.AppNotification
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * memory_trace_page.dart 1:1 (mobile branches) — debug viewer for the
 * background memory pipeline. Traces come from [MemoryTraceRecorder], which is
 * never persisted and is not yet fed by a Kotlin-side pipeline, so the list
 * renders the empty state until runs are recorded.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryTraceScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val prefs = remember { container.preferenceRepository }

    // memory_trace_enabled_v1 (settings_provider.dart L144, default true).
    var traceEnabled by remember {
        mutableStateOf(
            prefs.readJson("memory_trace_enabled_v1")
                ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonPrimitive.booleanOrNull }.getOrNull() }
                ?: true,
        )
    }
    var rev by remember { mutableStateOf(0) }
    var clearSheet by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MemoryTraceRecorder.Trace?>(null) }

    val traces = remember(rev) { MemoryTraceRecorder.traces }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(UiR.string.settings_page_back_button), tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(UiR.string.memory_trace_page_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // _SectionHeader + _GroupedCard + _ToggleRow (L72-83).
            TraceSectionHeader(stringResource(UiR.string.memory_trace_recording_section))
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(UiR.string.memory_trace_toggle_title),
                            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(UiR.string.memory_trace_toggle_subtitle),
                            style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, color = withAlpha(cs.onSurface, 0.56)),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IosSwitch(
                        value = traceEnabled,
                        onValueChanged = { v ->
                            traceEnabled = v
                            prefs.writeJson("memory_trace_enabled_v1", kotlinx.serialization.json.JsonPrimitive(v).toString())
                            rev++
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Runs header + clear action (L85-102).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(UiR.string.memory_trace_runs_section),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
                    modifier = Modifier.weight(1f),
                )
                if (traces.isNotEmpty()) {
                    IosTileButton(
                        label = stringResource(UiR.string.memory_trace_clear_action),
                        icon = Lucide.Trash2,
                        fontSize = 13.sp,
                        onClick = { clearSheet = true },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                !traceEnabled -> TraceEmptyState(
                    icon = Lucide.EyeOff,
                    title = stringResource(UiR.string.memory_trace_disabled_title),
                    subtitle = stringResource(UiR.string.memory_trace_disabled_subtitle),
                )
                traces.isEmpty() -> TraceEmptyState(
                    icon = Lucide.Brain,
                    title = stringResource(UiR.string.memory_trace_empty_title),
                    subtitle = stringResource(UiR.string.memory_trace_empty_subtitle),
                )
                else -> Column {
                    traces.forEach { trace ->
                        TraceCard(
                            trace = trace,
                            modifier = Modifier.padding(bottom = 10.dp),
                            onTap = { detail = trace },
                        )
                    }
                }
            }
        }
    }

    // _confirmClear (L141-204) — mobile bottom sheet.
    if (clearSheet) {
        ModalBottomSheet(onDismissRequest = { clearSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Text(
                    stringResource(UiR.string.memory_trace_clear_sheet_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(UiR.string.memory_trace_clear_sheet_message),
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = withAlpha(cs.onSurface, 0.66)),
                )
                Spacer(Modifier.height(18.dp))
                IosTileButton(
                    label = stringResource(UiR.string.memory_trace_clear_confirm),
                    icon = Lucide.Trash2,
                    backgroundColor = cs.error,
                    onClick = {
                        MemoryTraceRecorder.clear()
                        clearSheet = false
                        rev++
                        SnackbarManager.show(
                            AppNotification(
                                message = context.getString(UiR.string.memory_trace_cleared_toast),
                                type = NotificationType.SUCCESS,
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                IosTileButton(
                    label = stringResource(UiR.string.memory_trace_cancel),
                    icon = Lucide.X,
                    onClick = { clearSheet = false },
                )
            }
        }
    }

    // _openTraceDetail (L131-139) — mobile push variant.
    detail?.let { trace ->
        TraceDetailOverlay(trace = trace, onClose = { detail = null })
    }
}

@Composable
private fun TraceSectionHeader(title: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        title,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.8)),
        modifier = Modifier.padding(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 6.dp),
    )
}

/** _EmptyState (L976+). */
@Composable
private fun TraceEmptyState(icon: ImageVector, title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = withAlpha(cs.onSurface, 0.35))
        Spacer(Modifier.height(10.dp))
        Text(title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.7)))
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = withAlpha(cs.onSurface, 0.55)),
        )
    }
}

/** _TraceCard (L267-340) — simplified to the reachable field set. */
@Composable
private fun TraceCard(trace: MemoryTraceRecorder.Trace, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    TactileRow(onTap = onTap) { pressed ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(if (pressed) withAlpha(cs.onSurface, 0.04) else app.surfaceCard, RoundedCornerShape(14.dp))
                .border(0.6.dp, app.hairline, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmtTimestamp(trace.startedAtMs),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.9)),
                    modifier = Modifier.weight(1f),
                )
                if (trace.outcome != null) {
                    Text(
                        memoryOutcomeLabel(trace.outcome),
                        style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TracePill(label = trace.steps.size.toString() + " steps", tone = TracePillTone.NEUTRAL)
                val mutations = trace.steps.sumOf { it.mutations.size }
                if (mutations > 0) TracePill(label = mutations.toString(), tone = TracePillTone.SUCCESS)
            }
        }
    }
}

private enum class TracePillTone { NEUTRAL, SUCCESS, WARNING, ERROR }

/** _Pill (memory_trace_page.dart). */
@Composable
private fun TracePill(label: String, tone: TracePillTone) {
    val cs = MaterialTheme.colorScheme
    val color = when (tone) {
        TracePillTone.NEUTRAL -> withAlpha(cs.onSurface, 0.65)
        TracePillTone.SUCCESS -> cs.primary
        TracePillTone.WARNING -> LocalSemanticColors.current.warning
        TracePillTone.ERROR -> cs.error
    }
    Box(
        modifier = Modifier
            .background(withAlpha(color, 0.14), RoundedCornerShape(999.dp))
            .border(1.dp, withAlpha(color, 0.28), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color))
    }
}

private fun fmtTimestamp(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun two(v: Int) = v.toString().padStart(2, '0')
    return "${cal.get(java.util.Calendar.YEAR)}-${two(cal.get(java.util.Calendar.MONTH) + 1)}-${two(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}:${two(cal.get(java.util.Calendar.SECOND))}"
}

private fun fmtDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
}

/** MemoryTraceDetailPage (L208-237) — full step-by-step view of one trace. */
@Composable
private fun TraceDetailOverlay(trace: MemoryTraceRecorder.Trace, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(UiR.string.settings_page_back_button), tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(UiR.string.memory_trace_detail_title),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // Overview section (_OverviewCard).
            TraceSectionHeader(stringResource(UiR.string.memory_trace_section_overview))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(app.surfaceCard, RoundedCornerShape(14.dp))
                    .border(0.6.dp, app.hairline, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                TraceKv(stringResource(UiR.string.memory_trace_field_time), fmtTimestamp(trace.startedAtMs))
                val duration = (trace.endedAtMs ?: trace.startedAtMs) - trace.startedAtMs
                TraceKv(stringResource(UiR.string.memory_trace_field_duration), fmtDuration(duration))
                TraceKv(
                    stringResource(UiR.string.memory_trace_field_outcome),
                    trace.outcome?.let { memoryOutcomeLabel(it) } ?: "—",
                )
                if (trace.outcome != null) {
                    TraceKv(stringResource(UiR.string.memory_trace_field_error), memoryOutcomeLabel(trace.outcome))
                }
            }

            // Steps (_StepCard).
            TraceSectionHeader(stringResource(UiR.string.memory_trace_section_parsed))
            trace.steps.forEach { step ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .background(app.surfaceCard, RoundedCornerShape(14.dp))
                        .border(0.6.dp, app.hairline, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.BadgeInfo, contentDescription = null, modifier = Modifier.size(16.dp), tint = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            step.label ?: step.kind,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            fmtDuration(step.atMs % 60_000),
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.55)),
                        )
                    }
                    if (!step.detail.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            step.detail,
                            style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = withAlpha(cs.onSurface, 0.72)),
                        )
                    }
                    step.mutations.forEach { m ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(UiR.string.memory_trace_mutation_summary, m.kind),
                            style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceKv(key: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            key,
            style = TextStyle(fontSize = 12.5.sp, color = withAlpha(cs.onSurface, 0.55)),
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = TextStyle(fontSize = 12.5.sp, color = withAlpha(cs.onSurface, 0.9)),
            modifier = Modifier.weight(1f),
        )
    }
}
