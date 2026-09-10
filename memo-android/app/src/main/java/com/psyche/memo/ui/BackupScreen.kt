package com.psyche.memo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Box as BoxIcon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Cable
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.Upload
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.backup.RestoreMode
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.backup.BackupImportModeDialog
import com.psyche.memo.ui.backup.BackupRestartRequiredDialog
import com.psyche.memo.ui.backup.backupTaskLabels
import com.psyche.memo.ui.backup.rememberBackupTaskRunner
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI shell of `lib/features/backup/pages/backup_page.dart` (BackupPage).
 * Mirrors the original Flutter layout 1:1 — six sections, in this order:
 *   1. 备份管理 (Backup Management)   — 2 iosSwitchRow: chats + files
 *   2. 备份提醒 (Backup Reminder)     — _BackupReminderMobileSection
 *      (off / daily / weekly / monthly / custom + last backup)
 *   3. 本地副本 (Local Copies)         — _LocalSnapshotMobileSection
 *      (enabled / interval / keep count / space limit / take now +
 *      on-device copies list)
 *   4. 本地备份 (Local Backup)         — 4 nav rows: export to file /
 *      import backup file / import from Cherry Studio / from Chatbox
 *   5. WebDAV 备份 (WebDAV Backup)     — 3 nav rows: server settings
 *      (→ sub-page) / test connection / restore
 *   6. S3 备份 (S3 Backup)             — 3 nav rows: server settings
 *      (→ sub-page) / test connection / restore
 *
 * Section 4 is live (sub-block 1): export writes the archive and hands it to
 * SAF, import picks a `.zip`, asks for the restore mode and applies it.
 * Sections 2, 3, 5 and 6 stay as visual shells until sub-blocks 3-6 land.
 */
@Composable
fun BackupScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenLocalSnapshots: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberBackupTaskRunner()

    // "Chats" / "Files" switches (backup_page.dart L307 / L323). They select
    // what an export contains, exactly like the original pair of switches.
    var includeChats by remember { mutableStateOf(true) }
    var includeFiles by remember { mutableStateOf(true) }

    // Values only a @Composable can resolve, captured so the SAF callbacks
    // (plain lambdas) can still produce localized messages.
    val exportTitle = backupTaskLabels(UiR.string.backup_page_export_to_file)
    val importTitle = backupTaskLabels(UiR.string.backup_page_import_backup_file)
    val exportFailedPrefix = stringResource(UiR.string.backup_page_export_failed_message, "%s")
    val restoreFailedPrefix = stringResource(UiR.string.backup_page_restore_failed_message, "%s")
    val exportedAsTemplate = stringResource(UiR.string.message_export_sheet_exported_as, "%s")
    val schemaTooNew = stringResource(UiR.string.backup_page_schema_too_new_message)

    // ── pending state carried between the picker activity and its result ───
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var restoringFile by remember { mutableStateOf<File?>(null) }
    var showImportModeDialog by remember { mutableStateOf(false) }
    var restartReport by remember { mutableStateOf<RestoreReportUi?>(null) }

    fun toast(message: String, type: NotificationType) {
        SnackbarManager.show(AppNotification(message, type))
    }

    // ── export: CreateDocument("application/zip") ─────────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) {
            source?.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val name = source.name
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        container.backupService.copyInto(source, out)
                    } ?: error("无法写入所选位置")
                }
            }
            source.delete()
            if (written.isSuccess) {
                toast(exportedAsTemplate.format(name), NotificationType.SUCCESS)
            } else {
                toast(
                    exportFailedPrefix.format(written.exceptionOrNull()?.message ?: name),
                    NotificationType.ERROR,
                )
            }
        }
    }

    fun runExport() {
        scope.launch {
            var archive: File? = null
            val ok = runner.run(
                labels = exportTitle,
                errorMessage = { exportFailedPrefix.format(it.message ?: it.toString()) },
            ) { report, isCancelled ->
                archive = container.backupService.exportToCache(
                    includeChats = includeChats,
                    includeFiles = includeFiles,
                    onProgress = { report(it) },
                    isCancelled = isCancelled,
                )
            }
            val file = archive ?: return@launch
            if (!ok) {
                file.delete()
                return@launch
            }
            // The archive is ready; the picker decides where it lands. The
            // cache copy is deleted in the result callback either way.
            pendingExport = file
            exportLauncher.launch(file.name)
        }
    }

    // ── import: OpenDocument → mode dialog → restore ──────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // SAF cannot filter by extension, so copy the picked file to the
            // cache and let the manifest reader decide whether it is a backup.
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "memo_import_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("无法读取所选文件")
                    target.takeIf { it.length() > 0 } ?: error("所选文件为空")
                }.getOrNull()
            }
            if (staged == null) {
                toast(restoreFailedPrefix.format("所选文件为空或无法读取"), NotificationType.ERROR)
                return@launch
            }
            if (container.backupService.peekManifest(staged)?.acceptsFormat != true) {
                staged.delete()
                toast(schemaTooNew, NotificationType.ERROR)
                return@launch
            }
            restoringFile = staged
            showImportModeDialog = true
        }
    }

    fun runImport(file: File, mode: RestoreMode) {
        scope.launch {
            var report: com.psyche.memo.data.backup.RestoreReportView? = null
            val ok = runner.run(
                labels = importTitle,
                errorMessage = { restoreFailedPrefix.format(it.message ?: it.toString()) },
            ) { progress, isCancelled ->
                report = container.backupService.restoreFromFile(
                    archive = file,
                    mode = mode,
                    onProgress = { progress(it) },
                    isCancelled = isCancelled,
                )
            }
            file.delete()
            val done = report
            if (ok && done != null) {
                // The database swap closes and reopens the live connection, so
                // every cached repository in the container is stale; the same
                // restart prompt Flutter shows is the honest answer here too.
                restartReport = RestoreReportUi(
                    skippedConversations = done.skippedConversations,
                    details = reportDetails(done),
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(title = stringResource(UiR.string.backup_page_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ── 1. 备份管理 (Backup Management) ────────────────────────────
            // Both rows are `_iosSwitchRow` (backup_page.dart L307 / L323).
            BackupSection(title = stringResource(UiR.string.backup_page_backup_management), first = true) {
                BackupSwitchRow(
                    Lucide.MessageSquare,
                    stringResource(UiR.string.backup_page_chats_label),
                    value = includeChats,
                    onChange = { includeChats = it },
                )
                BackupDivider()
                BackupSwitchRow(
                    Lucide.FileText,
                    stringResource(UiR.string.backup_page_files_label),
                    value = includeFiles,
                    onChange = { includeFiles = it },
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 2. 备份提醒 (Backup Reminder) ─────────────────────────────
            // First row is `_iosSwitchRow` (backup_page.dart L1612); the other
            // two are `_iosNavRow` (Frequency / Last Backup). Wired in sub-block 4.
            BackupSection(title = stringResource(UiR.string.backup_reminder_section_title)) {
                BackupSwitchRow(
                    Lucide.Timer,
                    stringResource(UiR.string.backup_reminder_enable_title),
                    value = false,
                    onChange = {},
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Repeat,
                    stringResource(UiR.string.backup_reminder_frequency_title),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.History,
                    stringResource(UiR.string.backup_reminder_last_backup_title),
                    "",
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 3. 本地副本 (Local Copies) ─────────────────────────────────
            // Per `_LocalSnapshotMobileSection` (backup_page.dart L1546-1600):
            // only 2 rows on this page — the Enabled switch + a "Manage copies"
            // nav row that pushes the LocalSnapshotsPage. Wired in sub-block 3.
            BackupSection(title = stringResource(UiR.string.local_snapshot_section_title)) {
                BackupSwitchRow(
                    Lucide.Shield,
                    stringResource(UiR.string.local_snapshot_enabled_title),
                    value = false,
                    onChange = {},
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Database,
                    stringResource(UiR.string.local_snapshot_manage_copies),
                    "",
                    onTap = onOpenLocalSnapshots,
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 4. 本地备份 (Local Backup) — 4 import/export actions ─────
            BackupSection(title = stringResource(UiR.string.backup_page_local_backup)) {
                BackupPlaceholderRow(
                    Lucide.Upload,
                    stringResource(UiR.string.backup_page_export_to_file),
                    "",
                    onTap = { runExport() },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Download,
                    stringResource(UiR.string.backup_page_import_backup_file),
                    "",
                    // The picker offers every file; the manifest reader rejects
                    // anything that is not a backup (SAF has no extension filter).
                    onTap = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.BoxIcon,
                    stringResource(UiR.string.backup_page_import_from_cherry_studio),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.BoxIcon,
                    stringResource(UiR.string.backup_page_import_from_chatbox),
                    "",
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 5. WebDAV 备份 (WebDAV Backup) — 3 nav rows (sub-block 5) ─
            BackupSection(title = stringResource(UiR.string.backup_page_web_dav_backup)) {
                BackupPlaceholderRow(
                    Lucide.Settings,
                    stringResource(UiR.string.backup_page_web_dav_server_settings),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Cable,
                    stringResource(UiR.string.backup_page_test_connection),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Import,
                    stringResource(UiR.string.backup_page_restore),
                    "",
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 6. S3 备份 (S3 Backup) — 3 nav rows (sub-block 6) ────────
            BackupSection(title = stringResource(UiR.string.backup_page_s3_backup)) {
                BackupPlaceholderRow(
                    Lucide.Settings,
                    stringResource(UiR.string.backup_page_s3_server_settings),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Cable,
                    stringResource(UiR.string.backup_page_test_connection),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Import,
                    stringResource(UiR.string.backup_page_restore),
                    "",
                )
            }
        }
    }

    // ── dialogs ────────────────────────────────────────────────────────────
    if (showImportModeDialog) {
        BackupImportModeDialog(
            onSelect = { mode ->
                showImportModeDialog = false
                val file = restoringFile
                restoringFile = null
                if (file != null) runImport(file, mode)
            },
            onDismiss = {
                showImportModeDialog = false
                restoringFile?.delete()
                restoringFile = null
            },
        )
    }

    restartReport?.let { report ->
        BackupRestartRequiredDialog(
            skippedConversations = report.skippedConversations,
            details = report.details,
            onRestart = {
                restartReport = null
                com.psyche.memo.ui.backup.restartApp(context)
            },
            onDismiss = { restartReport = null },
        )
    }
}

/** What the restart prompt needs from a completed restore. */
private data class RestoreReportUi(
    val skippedConversations: Int,
    val details: String?,
)

/**
 * `_doImportLocal`'s summary (`backup_page.dart` L1510): the restart prompt
 * gets the same counts the Flutter build folds into its `details` string.
 */
private fun reportDetails(report: com.psyche.memo.data.backup.RestoreReportView): String? {
    if (report.entityRowsWritten == 0 && report.preferenceKeysWritten == 0) return null
    return buildString {
        append("设置项: ${report.preferenceKeysWritten}")
        append(" · 实体: ${report.entityRowsWritten}")
        if (report.databaseRestored) append(" · 会话已恢复")
    }
}

@Composable
private fun BackupSection(
    title: String,
    first: Boolean = false,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val top = if (first) 6.dp else 0.dp
    Text(
        text = title,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = withAlpha(cs.onSurface, 0.8),
        ),
        modifier = Modifier.padding(start = 12.dp, top = top, bottom = 6.dp),
    )
    SectionCard { content() }
}

@Composable
private fun BackupPlaceholderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    onTap: (() -> Unit)? = null,
) {
    EditNavRow(
        icon = icon,
        label = label,
        detailText = detail,
        onTap = onTap ?: {},
    )
}

@Composable
private fun BackupSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Matches `backup_page.dart` L2219 (`_iosSwitchRow`): the original
    // Flutter row uses `EdgeInsets.symmetric(horizontal: 12, vertical: 2)`,
    // which is intentionally tighter than `_iosNavRow`'s 11dp so the
    // IosSwitch (26dp) + 4dp pad = 30dp row sits more compact than the
    // 42dp nav row. 1:1 with upstream.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 15.sp,
                color = withAlpha(cs.onSurface, 0.9),
            ),
            modifier = Modifier.weight(1f),
        )
        IosSwitch(value = value, onValueChanged = onChange)
    }
}

@Composable
private fun BackupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(withAlpha(MaterialTheme.colorScheme.outlineVariant, 0.18)),
    )
}
