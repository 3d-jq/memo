package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BadgeInfo
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.FileSearch
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.provider.MemoryTrace
import com.psyche.memo.provider.MemoryTraceMutation
import com.psyche.memo.provider.MemoryTraceMutationKind
import com.psyche.memo.provider.MemoryTraceRecorder
import com.psyche.memo.provider.MemoryTraceScope
import com.psyche.memo.provider.MemoryTraceStep
import com.psyche.memo.provider.MemoryTraceStepKind
import com.psyche.memo.provider.MemoryTraceStepStatus
import com.psyche.memo.provider.MemoryTraceTrigger
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import com.psyche.memo.ui.R as UiR

/**
 * memory_trace_page.dart — the debug viewer for the background memory pipeline.
 *
 * Traces come from [MemoryTraceRecorder]: a session-only ring buffer filled by
 * the pipeline (Gatekeeper → Extract → Smart Add → Distiller) and by the
 * `memory_*` tool calls, holding the exact prompts, raw responses and the
 * changes each step applied.
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

    // memory_trace_enabled_v1 (settings_provider.dart L144, default true). The
    // recorder is what the pipeline consults, so keep the two in step.
    var traceEnabled by remember { mutableStateOf(MemoryTraceRecorder.enabled) }
    LaunchedEffect(Unit) {
        container.syncMemoryTraceEnabled()
        traceEnabled = MemoryTraceRecorder.enabled
    }
    var clearSheet by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MemoryTrace?>(null) }

    val traces = MemoryTraceRecorder.traces

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.memory_trace_page_title),
            onBack = onBack,
        )

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
                            MemoryTraceRecorder.enabled = v
                            prefs.writeJson(
                                "memory_trace_enabled_v1",
                                kotlinx.serialization.json.JsonPrimitive(v).toString(),
                            )
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
        ModalBottomSheet(containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(), onDismissRequest = { clearSheet = false }, dragHandle = null) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                MemoSheetHandle()
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
    Text(
        title,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = settingsSectionHeaderColor(MaterialTheme.colorScheme),
        ),
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

/** _TraceCard (L267-380) — trigger, chat, outcome, then scope/step/change pills. */
@Composable
private fun TraceCard(trace: MemoryTrace, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val isDark = app.isDark

    val title = trace.conversationTitle?.trim().takeIf { !it.isNullOrEmpty() }
        ?: trace.conversationId
        ?: "—"
    val assistant = trace.assistantName?.trim().takeIf { !it.isNullOrEmpty() }
        ?: stringResource(UiR.string.memory_trace_scope_global)

    val subtitle = buildList {
        add(fmtTimestamp(trace.startedAt))
        add(assistant)
        trace.durationMs?.let { add(fmtDuration(it)) }
        if (trace.repeatCount > 1) add(stringResource(UiR.string.memory_trace_repeat_count, trace.repeatCount.toString()))
    }.joinToString(" · ")

    TactileRow(onTap = onTap) { pressed ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(if (pressed) withAlpha(cs.onSurface, 0.04) else app.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .border(
                    0.6.dp,
                    cs.outlineVariant.copy(alpha = if (isDark) 0.26f else 0.38f),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TracePill(
                    label = stringResource(triggerLabelRes(trace.trigger)),
                    color = cs.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.92)),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TracePill(
                    label = stringResource(outcomeLabelRes(trace)),
                    color = outcomeColor(trace),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.60)),
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TracePill(
                        label = stringResource(
                            if (trace.scope == MemoryTraceScope.ASSISTANT) {
                                UiR.string.memory_trace_scope_assistant
                            } else {
                                UiR.string.memory_trace_scope_global
                            },
                        ),
                        color = withAlpha(cs.onSurface, 0.65),
                    )
                    if (trace.steps.isNotEmpty()) {
                        TracePill(
                            label = stringResource(UiR.string.memory_trace_steps_count, trace.steps.size.toString()),
                            color = withAlpha(cs.onSurface, 0.65),
                        )
                    }
                    if (trace.mutationCount > 0) {
                        TracePill(
                            label = stringResource(UiR.string.memory_trace_mutations_count, trace.mutationCount.toString()),
                            color = cs.primary,
                        )
                    }
                }
                val failed = trace.steps.filter { it.status == MemoryTraceStepStatus.FAILED }
                if (failed.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        failed.forEach { step ->
                            TracePill(label = stepLabel(step), color = cs.error)
                        }
                    }
                }
            }
            if (trace.hasError) {
                Spacer(Modifier.height(10.dp))
                TraceErrorLine(
                    text = memoryOutcomeLabel(trace.error!!),
                    warning = !isSkipOutcome(trace.error!!),
                )
            }
        }
    }
}

/** MemoryTraceDetailPage (L208-237) — overview plus every recorded step. */
@Composable
private fun TraceDetailOverlay(trace: MemoryTrace, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    // 同屏二级页：系统返回回到追踪列表，别 pop 掉整个页面。
    OverlayBackHandler(onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 同 MemoTopBar：返回键要有按压反馈。这里原来是 M3 `IconButton`，本工程
            // 全局关闭 ripple，所以点下去毫无反应（用户 2026-09-15）。
            IosIconButton(
                icon = Lucide.ArrowLeft,
                onTap = onClose,
                color = cs.onSurface,
                size = 22.dp,
                contentPadding = 0.dp,
                minSize = 44.dp,
                semanticLabel = stringResource(UiR.string.settings_page_back_button),
            )
            Text(
                text = stringResource(UiR.string.memory_trace_detail_title),
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            TraceSectionHeader(stringResource(UiR.string.memory_trace_section_overview))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(app.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .border(0.6.dp, app.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(14.dp),
            ) {
                TraceKv(stringResource(UiR.string.memory_trace_field_time), fmtTimestamp(trace.startedAt))
                trace.durationMs?.let { TraceKv(stringResource(UiR.string.memory_trace_field_duration), fmtDuration(it)) }
                TraceKv(stringResource(UiR.string.memory_trace_field_trigger), stringResource(triggerLabelRes(trace.trigger)))
                TraceKv(
                    stringResource(UiR.string.memory_trace_field_scope),
                    stringResource(
                        if (trace.scope == MemoryTraceScope.ASSISTANT) {
                            UiR.string.memory_trace_scope_assistant
                        } else {
                            UiR.string.memory_trace_scope_global
                        },
                    ),
                )
                (trace.conversationTitle ?: trace.conversationId)?.let {
                    TraceKv(stringResource(UiR.string.memory_trace_field_conversation), it)
                }
                trace.assistantName?.let { TraceKv(stringResource(UiR.string.memory_trace_field_assistant), it) }
                trace.watermark?.let { TraceKv(stringResource(UiR.string.memory_trace_field_watermark), it.toString()) }
                if (trace.windowSize > 0) {
                    TraceKv(
                        stringResource(UiR.string.memory_trace_field_window),
                        stringResource(
                            UiR.string.memory_trace_window_value,
                            trace.windowSize.toString(),
                            (trace.windowStartOrder ?: 0).toString(),
                            (trace.windowEndOrder ?: 0).toString(),
                        ),
                    )
                }
                TraceKv(stringResource(UiR.string.memory_trace_field_outcome), stringResource(outcomeLabelRes(trace)))
                if (trace.hasError) {
                    TraceKv(stringResource(UiR.string.memory_trace_field_error), memoryOutcomeLabel(trace.error!!))
                }
            }

            if (trace.steps.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                return@Column
            }
            Spacer(Modifier.height(18.dp))
            TraceSectionHeader(stringResource(UiR.string.memory_trace_section_parsed))
            trace.steps.forEach { step ->
                TraceStepCard(step)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** _StepCard (L440-520). */
@Composable
private fun TraceStepCard(step: MemoryTraceStep) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val prompt = step.prompt
    val response = step.rawResponse
    val parsed = step.parsedResult.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .background(app.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, app.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(stepIcon(step.kind), contentDescription = null, modifier = Modifier.size(16.dp), tint = cs.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stepLabel(step),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            step.durationMs?.let {
                Text(
                    fmtDuration(it),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(cs.onSurface, 0.55)),
                )
                Spacer(Modifier.width(8.dp))
            }
            TracePill(label = stringResource(statusLabelRes(step.status)), color = statusColor(step.status))
        }
        if (!step.error.isNullOrEmpty()) {
            Spacer(Modifier.height(12.dp))
            TraceErrorLine(
                text = memoryOutcomeLabel(step.error!!),
                warning = !isSkipOutcome(step.error!!),
            )
        }
        if (prompt.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TraceCollapsibleCode(label = stringResource(UiR.string.memory_trace_section_prompt), text = prompt)
        }
        if (response.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TraceCollapsibleCode(label = stringResource(UiR.string.memory_trace_section_response), text = response)
        }
        if (parsed.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TraceCollapsibleCode(label = stringResource(UiR.string.memory_trace_section_parsed), text = parsed)
        }
        if (step.mutations.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TraceFieldLabel(stringResource(UiR.string.memory_trace_section_mutations))
            Spacer(Modifier.height(8.dp))
            step.mutations.forEachIndexed { index, mutation ->
                TraceMutationTile(mutation)
                if (index != step.mutations.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** _MutationTile (L522-592) — the change, its target, and before/after. */
@Composable
private fun TraceMutationTile(mutation: MemoryTraceMutation) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val header = listOfNotNull(mutation.targetId, mutation.label).joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .background(app.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(
                0.6.dp,
                cs.outlineVariant.copy(alpha = if (app.isDark) 0.22f else 0.34f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TracePill(
                label = stringResource(mutationLabelRes(mutation.kind)),
                color = mutationColor(mutation.kind),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                header,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = withAlpha(cs.onSurface, 0.62),
                ),
                modifier = Modifier.weight(1f),
            )
        }
        val before = mutation.before
        val after = mutation.after
        if (!before.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            TraceBeforeAfter(label = stringResource(UiR.string.memory_trace_before), text = before, color = withAlpha(cs.onSurface, 0.7))
        }
        if (!after.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            TraceBeforeAfter(label = stringResource(UiR.string.memory_trace_after), text = after, color = withAlpha(cs.onSurface, 0.9))
        }
    }
}

@Composable
private fun TraceBeforeAfter(label: String, text: String, color: Color) {
    Row {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = withAlpha(MaterialTheme.colorScheme.onSurface, 0.5)),
            modifier = Modifier.width(52.dp),
        )
        Text(
            text,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = color),
            modifier = Modifier.weight(1f),
        )
    }
}

/** _CollapsibleCode (L640-755) — first 420 characters, copy, expand. */
@Composable
private fun TraceCollapsibleCode(label: String, text: String) {
    val cs = MaterialTheme.colorScheme
    val app = LocalSemanticColors.current
    val context = LocalContext.current
    var expanded by remember(text) { mutableStateOf(false) }
    val long = text.length > PREVIEW_CHARS
    val shown = if (!long || expanded) text else text.substring(0, PREVIEW_CHARS) + "…"

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TraceFieldLabel(label, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("memo-trace", text))
                    SnackbarManager.show(
                        AppNotification(
                            message = context.getString(UiR.string.memory_trace_copied_toast),
                            type = NotificationType.SUCCESS,
                        ),
                    )
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Lucide.Copy,
                    contentDescription = stringResource(UiR.string.memory_trace_copy_action),
                    tint = cs.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(app.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .border(
                    0.6.dp,
                    cs.outlineVariant.copy(alpha = if (app.isDark) 0.22f else 0.34f),
                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                shown,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 16.1.sp,
                    color = withAlpha(cs.onSurface, 0.86),
                ),
            )
        }
        if (long) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) UiR.string.memory_trace_show_less else UiR.string.memory_trace_show_more,
                    ),
                    style = TextStyle(fontSize = 12.sp, color = cs.primary),
                )
            }
        }
    }
}

@Composable
private fun TraceFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = withAlpha(MaterialTheme.colorScheme.onSurface, 0.5),
        ),
        modifier = modifier,
    )
}

/** _ErrorLine (L918-975) — errors in red, skip reasons in a calm tone. */
@Composable
private fun TraceErrorLine(text: String, warning: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (warning) cs.error else withAlpha(cs.onSurface, 0.55)
    Row(
        Modifier
            .fillMaxWidth()
            .background(withAlpha(color, 0.10), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            if (warning) Lucide.TriangleAlert else Lucide.BadgeInfo,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = color), modifier = Modifier.weight(1f))
    }
}

/** _Pill (L1022+). */
@Composable
private fun TracePill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(withAlpha(color, 0.14), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .border(1.dp, withAlpha(color, 0.28), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, lineHeight = 12.sp))
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

// ── labels / tones ───────────────────────────────────────────────────────────

private const val PREVIEW_CHARS = 420

private fun triggerLabelRes(trigger: MemoryTraceTrigger): Int = when (trigger) {
    MemoryTraceTrigger.AUTO_TURNS -> UiR.string.memory_trace_trigger_auto
    MemoryTraceTrigger.MANUAL -> UiR.string.memory_trace_trigger_manual
    MemoryTraceTrigger.TOOL_CALL -> UiR.string.memory_trace_trigger_tool
    MemoryTraceTrigger.CONVERSATION_SUMMARY -> UiR.string.memory_trace_trigger_summary
}

@Composable
private fun stepLabel(step: MemoryTraceStep): String =
    step.label?.takeIf { it.isNotBlank() } ?: stringResource(stepLabelRes(step.kind))

private fun stepLabelRes(kind: MemoryTraceStepKind): Int = when (kind) {
    MemoryTraceStepKind.GATEKEEPER -> UiR.string.memory_trace_step_gatekeeper
    MemoryTraceStepKind.EXTRACT -> UiR.string.memory_trace_step_extract
    MemoryTraceStepKind.SMART_ADD -> UiR.string.memory_trace_step_smart_add
    MemoryTraceStepKind.PROFILE_DISTILLER -> UiR.string.memory_trace_step_distiller
    MemoryTraceStepKind.CONVERSATION_SUMMARY -> UiR.string.memory_trace_step_summary
    MemoryTraceStepKind.CHAT_SEARCH -> UiR.string.memory_trace_step_chat_search
    MemoryTraceStepKind.MEMORY_TOOL -> UiR.string.memory_trace_step_tool
}

private fun stepIcon(kind: MemoryTraceStepKind): ImageVector = when (kind) {
    MemoryTraceStepKind.GATEKEEPER -> Lucide.Layers
    MemoryTraceStepKind.EXTRACT -> Lucide.FileSearch
    MemoryTraceStepKind.SMART_ADD -> Lucide.Sparkles
    MemoryTraceStepKind.PROFILE_DISTILLER -> Lucide.Brain
    MemoryTraceStepKind.CONVERSATION_SUMMARY -> Lucide.CalendarClock
    MemoryTraceStepKind.CHAT_SEARCH -> Lucide.Search
    MemoryTraceStepKind.MEMORY_TOOL -> Lucide.Wrench
}

private fun statusLabelRes(status: MemoryTraceStepStatus): Int = when (status) {
    MemoryTraceStepStatus.RUNNING -> UiR.string.memory_trace_status_running
    MemoryTraceStepStatus.SKIPPED -> UiR.string.memory_trace_status_skipped
    MemoryTraceStepStatus.SUCCESS -> UiR.string.memory_trace_status_success
    MemoryTraceStepStatus.FAILED -> UiR.string.memory_trace_status_failed
}

@Composable
private fun statusColor(status: MemoryTraceStepStatus): Color {
    val cs = MaterialTheme.colorScheme
    return when (status) {
        MemoryTraceStepStatus.SUCCESS -> cs.primary
        MemoryTraceStepStatus.FAILED -> cs.error
        MemoryTraceStepStatus.SKIPPED -> withAlpha(cs.onSurface, 0.55)
        MemoryTraceStepStatus.RUNNING -> LocalSemanticColors.current.warning
    }
}

private fun mutationLabelRes(kind: MemoryTraceMutationKind): Int = when (kind) {
    MemoryTraceMutationKind.MEMORY_CREATED -> UiR.string.memory_trace_mutation_created
    MemoryTraceMutationKind.MEMORY_MERGED -> UiR.string.memory_trace_mutation_merged
    MemoryTraceMutationKind.MEMORY_EDITED -> UiR.string.memory_trace_mutation_edited
    MemoryTraceMutationKind.MEMORY_ARCHIVED -> UiR.string.memory_trace_mutation_archived
    MemoryTraceMutationKind.MEMORY_LINKED -> UiR.string.memory_trace_mutation_linked
    MemoryTraceMutationKind.PROFILE_FIELD_WRITTEN -> UiR.string.memory_trace_mutation_profile_written
    MemoryTraceMutationKind.PROFILE_FIELD_CLEARED -> UiR.string.memory_trace_mutation_profile_cleared
    MemoryTraceMutationKind.CONVERSATION_SUMMARY_WRITTEN -> UiR.string.memory_trace_mutation_summary
}

@Composable
private fun mutationColor(kind: MemoryTraceMutationKind): Color {
    val cs = MaterialTheme.colorScheme
    return when (kind) {
        MemoryTraceMutationKind.MEMORY_CREATED,
        MemoryTraceMutationKind.PROFILE_FIELD_WRITTEN,
        MemoryTraceMutationKind.CONVERSATION_SUMMARY_WRITTEN,
        -> cs.primary

        MemoryTraceMutationKind.MEMORY_ARCHIVED,
        MemoryTraceMutationKind.PROFILE_FIELD_CLEARED,
        -> cs.error

        else -> withAlpha(cs.onSurface, 0.7)
    }
}

/** `_OutcomePill` (L1094) — advanced / forced / held, with the error tone. */
private fun outcomeLabelRes(trace: MemoryTrace): Int = when {
    trace.hasError && !isSkipOutcome(trace.error!!) -> UiR.string.memory_trace_status_failed
    trace.forcedAdvance -> UiR.string.memory_trace_outcome_forced
    trace.advanced -> UiR.string.memory_trace_outcome_advanced
    else -> UiR.string.memory_trace_outcome_held
}

@Composable
private fun outcomeColor(trace: MemoryTrace): Color {
    val cs = MaterialTheme.colorScheme
    return when {
        trace.hasError && !isSkipOutcome(trace.error!!) -> cs.error
        trace.advanced -> cs.primary
        else -> withAlpha(cs.onSurface, 0.6)
    }
}

/** Codes that report a deliberate skip rather than a failure. */
private fun isSkipOutcome(code: String): Boolean =
    code in com.psyche.memo.provider.MemoryPipelineService.SKIP_REASON_CODES

private fun fmtTimestamp(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    fun two(v: Int) = v.toString().padStart(2, '0')
    return "${cal.get(java.util.Calendar.YEAR)}-${two(cal.get(java.util.Calendar.MONTH) + 1)}-${two(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}:${two(cal.get(java.util.Calendar.SECOND))}"
}

private fun fmtDuration(millis: Long): String = when {
    millis < 1000 -> "${millis}ms"
    millis < 60_000 -> "${millis / 1000}s"
    else -> "${millis / 60000}m ${(millis % 60000) / 1000}s"
}
