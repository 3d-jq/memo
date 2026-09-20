package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CalendarPlus
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.backup.LocalSnapshotEntry
import com.psyche.memo.data.backup.LocalSnapshotOrigin
import com.psyche.memo.data.backup.LocalSnapshotPreferences
import com.psyche.memo.data.backup.LocalSnapshotSettings
import com.psyche.memo.data.backup.LocalSnapshotSkipReason
import com.psyche.memo.data.backup.LocalSnapshotState
import com.psyche.memo.data.backup.RestoreMode
import com.psyche.memo.data.backup.RestoreReportView
import com.psyche.memo.ui.backup.BackupRestartRequiredDialog
import com.psyche.memo.ui.backup.backupTaskLabels
import com.psyche.memo.ui.backup.formatBytes
import com.psyche.memo.ui.backup.rememberBackupTaskRunner
import com.psyche.memo.ui.backup.restartApp
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import com.psyche.memo.ui.R as UiR

/**
 * `lib/features/backup/pages/local_snapshots_page.dart` — the settings card
 * (enabled / interval / keep recent / weekly / monthly / space limit /
 * announce), the last-attempt status line, "Save a copy now", and the list of
 * on-device copies with restore / export / pin / delete.
 *
 * Copies are the same backup archives the export flow produces, so a local copy
 * can be restored through the ordinary restore path (and the restart prompt it
 * ends with).
 */
@Composable
fun LocalSnapshotsScreen(container: AppContainerImpl, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val service = container.localSnapshots
    val preferences = remember(container) { LocalSnapshotPreferences(container.preferenceRepository) }
    val runner = rememberBackupTaskRunner()

    var settings by remember { mutableStateOf(preferences.readSettings()) }
    var state by remember { mutableStateOf(preferences.readState()) }
    var copies by remember { mutableStateOf<List<LocalSnapshotEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<SnapshotConfirm?>(null) }
    var intervalPicker by remember { mutableStateOf(false) }
    var keepPicker by remember { mutableStateOf(false) }
    var maximumPicker by remember { mutableStateOf(false) }
    var restartReport by remember { mutableStateOf<RestoreReportView?>(null) }
    // Resolved here: [backupTaskLabels] is composable, and the task lambdas run
    // outside a composition.
    val takeLabels = backupTaskLabels(UiR.string.local_snapshot_take_now)
    val restoreLabels = backupTaskLabels(UiR.string.local_snapshot_restore_preparing)

    fun refresh() {
        scope.launch {
            val (list, loadedSettings, loadedState) = withContext(Dispatchers.IO) {
                service.store().sweepIncomplete()
                Triple(service.list(), preferences.readSettings(), preferences.readState())
            }
            copies = list
            settings = loadedSettings
            state = loadedState
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun update(next: LocalSnapshotSettings) {
        settings = next
        scope.launch(Dispatchers.IO) { preferences.writeSettings(next) }
    }

    // ── Export: the copy is already an archive, so SAF just writes it out ────
    var pendingExport by remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    container.appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { input -> input.copyTo(out) }
                    } ?: error("stream failed")
                }
            }
            toast(
                if (written.isSuccess) {
                    container.appContext.getString(UiR.string.local_snapshot_export_done)
                } else {
                    container.appContext.getString(
                        UiR.string.local_snapshot_export_failed,
                        written.exceptionOrNull()?.message.orEmpty(),
                    )
                },
                if (written.isSuccess) NotificationType.SUCCESS else NotificationType.ERROR,
            )
        }
    }

    // ── 副本卡的四个动作 ────────────────────────────────────────────────────
    // 用「身份不变的 @Stable 持有者 + 每次重组重赋字段」，而不是每张卡现造四个
    // lambda：SnapshotCopyCard 的实参引用于是全程不变 ⇒ 备份进度刷新、设置开关这类
    // 与某一张卡无关的状态变化，不再把整列卡片重组合一遍（每张卡里还要各建一次
    // 时间 formatter 与四枚按钮）。
    val copyActions = remember { SnapshotCopyActions() }
    val copiesRef = rememberUpdatedState(copies)
    copyActions.restore = { entry -> confirm = SnapshotConfirm.Restore(entry) }
    copyActions.export = { entry ->
        pendingExport = entry.file
        exportLauncher.launch(entry.id)
    }
    copyActions.delete = { entry -> confirm = SnapshotConfirm.Delete(entry, copiesRef.value) }
    copyActions.togglePin = { entry ->
        service.setPinned(entry.id, !entry.pinned)
        refresh()
    }

    fun takeNow() {
        scope.launch {
            val ok = runner.run(
                labels = takeLabels,
                errorMessage = {
                    container.appContext.getString(UiR.string.local_snapshot_take_failed, it.message.orEmpty())
                },
            ) { report, _ ->
                service.take(origin = LocalSnapshotOrigin.MANUAL, onProgress = { report(it) })
                service.pruneNow()
            }
            if (ok) {
                toast(
                    container.appContext.getString(UiR.string.local_snapshot_take_done),
                    NotificationType.SUCCESS,
                )
            }
            refresh()
        }
    }

    fun restore(copy: LocalSnapshotEntry) {
        scope.launch {
            var report: RestoreReportView? = null
            val ok = runner.run(
                labels = restoreLabels,
            ) { progress, isCancelled ->
                // The copy taken here is what makes the restore reversible, so
                // it has to succeed before anything replaces the live database.
                service.take(
                    origin = LocalSnapshotOrigin.BEFORE_RESTORE,
                    pinned = true,
                    onProgress = { progress(it) },
                )
                report = container.backupService.restoreFromFile(
                    archive = copy.file,
                    mode = RestoreMode.OVERWRITE,
                    onProgress = { progress(it) },
                    isCancelled = isCancelled,
                )
            }
            refresh()
            if (ok && report != null) restartReport = report
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.local_snapshot_copies_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            ShellSection(title = stringResource(UiR.string.local_snapshot_section_title), first = true) {
                LocalSnapshotSwitchRow(
                    Lucide.Shield,
                    stringResource(UiR.string.local_snapshot_enabled_title),
                    value = settings.enabled,
                    onChange = { update(settings.copy(enabled = it)) },
                )
                if (settings.enabled) {
                    ShellDivider()
                    EditNavRow(
                        Lucide.Repeat,
                        stringResource(UiR.string.local_snapshot_interval_title),
                        intervalLabel(settings.intervalDays),
                        onTap = { intervalPicker = true },
                    )
                    ShellDivider()
                    EditNavRow(
                        Lucide.Layers,
                        stringResource(UiR.string.local_snapshot_keep_title),
                        icuString(UiR.string.local_snapshot_keep_value, "count" to settings.keepRecent),
                        onTap = { keepPicker = true },
                    )
                    ShellDivider()
                    LocalSnapshotSwitchRow(
                        Lucide.CalendarPlus,
                        stringResource(UiR.string.local_snapshot_keep_weekly),
                        value = settings.keepWeekly,
                        onChange = { update(settings.copy(keepWeekly = it)) },
                    )
                    ShellDivider()
                    LocalSnapshotSwitchRow(
                        Lucide.Calendar,
                        stringResource(UiR.string.local_snapshot_keep_monthly),
                        value = settings.keepMonthly,
                        onChange = { update(settings.copy(keepMonthly = it)) },
                    )
                    ShellDivider()
                    EditNavRow(
                        Lucide.HardDrive,
                        stringResource(UiR.string.local_snapshot_maximum_title),
                        if (settings.maximumTotalBytes <= 0) {
                            stringResource(UiR.string.local_snapshot_maximum_unlimited)
                        } else {
                            formatBytes(settings.maximumTotalBytes)
                        },
                        onTap = { maximumPicker = true },
                    )
                    ShellDivider()
                    LocalSnapshotSwitchRow(
                        Lucide.MessageSquare,
                        stringResource(UiR.string.local_snapshot_announce_title),
                        value = settings.announceResult,
                        onChange = { update(settings.copy(announceResult = it)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SnapshotNote(
                text = if (settings.enabled) {
                    stringResource(UiR.string.local_snapshot_keep_protected_note)
                } else {
                    stringResource(UiR.string.local_snapshot_enabled_subtitle)
                },
            )

            Spacer(Modifier.height(10.dp))
            SnapshotStatusLine(state)
            Spacer(Modifier.height(12.dp))
            IosTileButton(
                label = stringResource(UiR.string.local_snapshot_take_now),
                icon = Lucide.Download,
                onClick = { takeNow() },
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = cs.primary,
                foregroundColor = cs.primary,
            )

            Spacer(Modifier.height(18.dp))
            ShellSection(
                title = stringResource(UiR.string.local_snapshot_copies_title) + " · " +
                    icuString(
                        LocalSnapshotUsageRes,
                        "count" to copies.size,
                        "size" to formatBytes(copies.sumOf { it.bytes }),
                    ),
            ) {
                if (copies.isEmpty()) {
                    SnapshotEmptyState(loading)
                } else {
                    copies.forEachIndexed { index, copy ->
                        if (index > 0) ShellDivider()
                        // 卡片仍然整体待在 ShellSection 这一张卡里（原版形态）：拆成
                        // lazy item 会把卡片的圆角+描边切成一段一段，外观就变了。
                        // 省下来的重组开销走 copyActions（见上面的注释）。
                        SnapshotCopyCard(copy = copy, actions = copyActions)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SnapshotNote(stringResource(UiR.string.local_snapshot_copies_scope_note))
        }
    }

    // ── Pickers ─────────────────────────────────────────────────────────────
    if (intervalPicker) {
        MemoryOptionPickerSheet(
            selected = settings.intervalDays,
            options = LocalSnapshotSettings.INTERVAL_PRESETS.map { days ->
                MemoryPickerOption(
                    value = days,
                    label = intervalLabel(days),
                    subtitle = if (days <= LocalSnapshotSettings.AUTOMATIC_INTERVAL) {
                        stringResource(UiR.string.local_snapshot_interval_automatic_detail)
                    } else {
                        null
                    },
                )
            },
            onDismiss = { intervalPicker = false },
            onSelected = { update(settings.copy(intervalDays = it)) },
        )
    }
    if (keepPicker) {
        MemoryOptionPickerSheet(
            selected = settings.keepRecent,
            options = (LocalSnapshotSettings.MINIMUM_KEEP_RECENT..LocalSnapshotSettings.MAXIMUM_KEEP_RECENT)
                .map { count ->
                    MemoryPickerOption(
                        value = count,
                        label = icuString(UiR.string.local_snapshot_keep_value, "count" to count),
                    )
                },
            onDismiss = { keepPicker = false },
            onSelected = { update(settings.copy(keepRecent = it)) },
        )
    }
    if (maximumPicker) {
        MemoryOptionPickerSheet(
            selected = settings.maximumTotalBytes,
            options = LocalSnapshotSettings.TOTAL_BYTES_PRESETS.map { bytes ->
                MemoryPickerOption(
                    value = bytes,
                    label = if (bytes <= 0) {
                        stringResource(UiR.string.local_snapshot_maximum_unlimited)
                    } else {
                        formatBytes(bytes)
                    },
                )
            },
            onDismiss = { maximumPicker = false },
            onSelected = { update(settings.copy(maximumTotalBytes = it)) },
        )
    }

    // ── Confirmations ───────────────────────────────────────────────────────
    confirm?.let { request ->
        when (request) {
            is SnapshotConfirm.Restore -> SnapshotConfirmDialog(
                title = stringResource(UiR.string.local_snapshot_restore_title),
                message = stringResource(
                    UiR.string.local_snapshot_restore_message,
                    whenLabel(request.copy.createdAt),
                ),
                confirmLabel = stringResource(UiR.string.local_snapshot_action_restore),
                destructive = false,
                onConfirm = {
                    confirm = null
                    restore(request.copy)
                },
                onDismiss = { confirm = null },
            )

            is SnapshotConfirm.Delete -> {
                val withContent = request.all.filter { it.messageCount > 0 }
                val isLastWithContent = withContent.size == 1 && withContent.single().id == request.copy.id
                SnapshotConfirmDialog(
                    title = stringResource(UiR.string.local_snapshot_delete_title),
                    message = if (isLastWithContent) {
                        stringResource(UiR.string.local_snapshot_delete_last_warning) + "\n\n" +
                            stringResource(UiR.string.local_snapshot_delete_message)
                    } else {
                        stringResource(UiR.string.local_snapshot_delete_message)
                    },
                    confirmLabel = stringResource(UiR.string.local_snapshot_action_delete),
                    destructive = true,
                    onConfirm = {
                        confirm = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { service.delete(request.copy.id) }
                            }
                            toast(
                                if (result.isSuccess) {
                                    container.appContext.getString(UiR.string.local_snapshot_delete_done)
                                } else {
                                    result.exceptionOrNull()?.message.orEmpty()
                                },
                                if (result.isSuccess) NotificationType.SUCCESS else NotificationType.ERROR,
                            )
                            refresh()
                        }
                    },
                    onDismiss = { confirm = null },
                )
            }
        }
    }

    restartReport?.let { report ->
        BackupRestartRequiredDialog(
            skippedConversations = report.skippedConversations,
            details = null,
            onRestart = {
                restartReport = null
                restartApp(container.appContext)
            },
            onDismiss = { restartReport = null },
        )
    }
}

private sealed interface SnapshotConfirm {
    data class Restore(val copy: LocalSnapshotEntry) : SnapshotConfirm
    data class Delete(val copy: LocalSnapshotEntry, val all: List<LocalSnapshotEntry>) : SnapshotConfirm
}

/** `local_snapshot_usage` is an ICU message with two named arguments. */
private val LocalSnapshotUsageRes = UiR.string.local_snapshot_usage

/**
 * 一张副本卡的四个动作（身份不变的持有者：每次重组只重赋字段，见
 * [LocalSnapshotsScreen] 里的 `copyActions`）。
 */
@Stable
private class SnapshotCopyActions {
    var restore: (LocalSnapshotEntry) -> Unit = {}
    var export: (LocalSnapshotEntry) -> Unit = {}
    var delete: (LocalSnapshotEntry) -> Unit = {}
    var togglePin: (LocalSnapshotEntry) -> Unit = {}
}

/** `_CopyCard` L578-711 — archive icon, when/size, origin, contents, actions. */
@Composable
private fun SnapshotCopyCard(
    copy: LocalSnapshotEntry,
    actions: SnapshotCopyActions,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Database, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                whenLabel(copy.createdAt),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatBytes(copy.bytes),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (copy.origin) {
                LocalSnapshotOrigin.MANUAL -> stringResource(UiR.string.local_snapshot_origin_manual)
                LocalSnapshotOrigin.BEFORE_RESTORE -> stringResource(UiR.string.local_snapshot_origin_before_restore)
                LocalSnapshotOrigin.AUTOMATIC -> stringResource(UiR.string.local_snapshot_origin_automatic)
            } + if (copy.pinned) " · " + stringResource(UiR.string.local_snapshot_copy_pinned) else "",
            style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            icuString(
                UiR.string.local_snapshot_copy_contents,
                "conversations" to copy.conversationCount,
                "messages" to copy.messageCount,
            ),
            style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            IosTileButton(
                label = stringResource(UiR.string.local_snapshot_action_restore),
                icon = Lucide.RotateCcw,
                onClick = { actions.restore(copy) },
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IosTileButton(
                label = stringResource(UiR.string.local_snapshot_action_export),
                icon = Lucide.Share2,
                onClick = { actions.export(copy) },
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IosTileButton(
                label = stringResource(UiR.string.local_snapshot_action_delete),
                icon = Lucide.Trash2,
                onClick = { actions.delete(copy) },
                fontSize = 13.sp,
                backgroundColor = cs.error,
                foregroundColor = cs.error,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        IosTileButton(
            label = stringResource(
                if (copy.pinned) UiR.string.local_snapshot_action_unpin else UiR.string.local_snapshot_action_pin,
            ),
            icon = if (copy.pinned) Lucide.PinOff else Lucide.Pin,
            onClick = { actions.togglePin(copy) },
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `_StatusLine` L713-772 — failure > no space > last success > never. */
@Composable
private fun SnapshotStatusLine(state: LocalSnapshotState) {
    val cs = MaterialTheme.colorScheme
    val failure = state.lastFailureAt
    val success = state.lastSuccessAt
    val (text, warning) = when {
        failure != null -> stringResource(
            UiR.string.local_snapshot_status_failure,
            whenLabel(failure),
            state.lastFailureMessage.orEmpty(),
        ) to true

        state.lastSkipReason == LocalSnapshotSkipReason.INSUFFICIENT_SPACE ->
            stringResource(UiR.string.local_snapshot_status_skipped_space) to true

        success != null ->
            stringResource(UiR.string.local_snapshot_status_success, whenLabel(success)) to false

        else -> stringResource(UiR.string.local_snapshot_status_never) to false
    }

    Row(Modifier.padding(horizontal = 12.dp)) {
        Icon(
            if (warning) Lucide.TriangleAlert else Lucide.Check,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (warning) cs.error else withAlpha(cs.onSurface, 0.55),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.8.sp,
                color = if (warning) cs.error else withAlpha(cs.onSurface, 0.6),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SnapshotEmptyState(loading: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                Lucide.Database,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = withAlpha(cs.onSurface, 0.3),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(UiR.string.local_snapshot_copies_empty),
                style = TextStyle(fontSize = 14.sp, color = withAlpha(cs.onSurface, 0.6)),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(UiR.string.local_snapshot_copies_empty_hint),
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.8.sp, color = withAlpha(cs.onSurface, 0.45)),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** `_Note` L827-848. */
@Composable
private fun SnapshotNote(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = TextStyle(fontSize = 12.sp, lineHeight = 16.8.sp, color = withAlpha(cs.onSurface, 0.55)),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun SnapshotConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) cs.error else cs.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.home_page_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
            }
        },
    )
}

/** `_intervalLabel` L181-183. */
@Composable
private fun intervalLabel(days: Int): String = if (days <= LocalSnapshotSettings.AUTOMATIC_INTERVAL) {
    stringResource(UiR.string.local_snapshot_interval_automatic)
} else {
    icuString(UiR.string.local_snapshot_interval_days, "days" to days)
}

/** `_whenLabel` L447-451 — local time, minute resolution. */
// formatter 提成进程级缓存（键 = locale + 模式）：以前**每张卡**、每次重组都要
// `new SimpleDateFormat(...)` + `Locale.getDefault()`（构造要解析模式并取一遍
// locale 数据）。DateTimeFormatter 不可变且线程安全，可以共享；输出与旧写法逐字相同
// （由 LocalSnapshotWhenLabelTest 对拍钉住）。
private val snapshotWhenFormatters = ConcurrentHashMap<String, DateTimeFormatter>()

internal fun whenLabel(at: Instant): String {
    val locale = Locale.getDefault()
    val pattern = "yyyy-MM-dd HH:mm"
    val formatter = snapshotWhenFormatters.getOrPut("${locale.toLanguageTag()}|$pattern") {
        DateTimeFormatter.ofPattern(pattern, locale)
    }
    return formatter.format(at.atZone(ZoneId.systemDefault()))
}

private fun toast(message: String, type: NotificationType) {
    SnackbarManager.show(AppNotification(message, type))
}
