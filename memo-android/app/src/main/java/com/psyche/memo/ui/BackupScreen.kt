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
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clock
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
    onOpenWebDavSettings: () -> Unit = {},
    onOpenS3Settings: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberBackupTaskRunner()

    // "Chats" / "Files" switches (backup_page.dart L307 / L323). They select
    // what an export contains; the original's callback writes BOTH the WebDAV
    // and the S3 config, because the two remote targets each carry their own
    // copy of the pair.
    val initialRemoteConfig = remember { container.backupService.webDavConfig() }
    var includeChats by remember { mutableStateOf(initialRemoteConfig.includeChats) }
    var includeFiles by remember { mutableStateOf(initialRemoteConfig.includeFiles) }

    fun setIncludeChats(value: Boolean) {
        includeChats = value
        container.backupService.saveWebDavConfig(
            container.backupService.webDavConfig().copy(includeChats = value),
        )
        container.backupService.saveS3Config(
            container.backupService.s3Config().copy(includeChats = value),
        )
    }

    fun setIncludeFiles(value: Boolean) {
        includeFiles = value
        container.backupService.saveWebDavConfig(
            container.backupService.webDavConfig().copy(includeFiles = value),
        )
        container.backupService.saveS3Config(
            container.backupService.s3Config().copy(includeFiles = value),
        )
    }

    // Values only a @Composable can resolve, captured so the SAF callbacks
    // (plain lambdas) can still produce localized messages.
    val exportTitle = backupTaskLabels(UiR.string.backup_page_export_to_file)
    val importTitle = backupTaskLabels(UiR.string.backup_page_import_backup_file)
    val cherryImportTitle = backupTaskLabels(UiR.string.backup_page_import_from_cherry_studio)
    val chatboxImportTitle = backupTaskLabels(UiR.string.backup_page_import_from_chatbox)
    val exportFailedPrefix = stringResource(UiR.string.backup_page_export_failed_message, "%s")
    val restoreFailedPrefix = stringResource(UiR.string.backup_page_restore_failed_message, "%s")
    val exportedAsTemplate = stringResource(UiR.string.message_export_sheet_exported_as, "%s")
    val schemaTooNew = stringResource(UiR.string.backup_page_schema_too_new_message)

    // ── pending state carried between the picker activity and its result ───
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var restoringFile by remember { mutableStateOf<File?>(null) }
    var showImportModeDialog by remember { mutableStateOf(false) }
    // 子块 7 —— 前向兼容：本地导入先 inspect + 同意对话框；远端恢复把 prompt
    // 传进服务层（下载完成后在进度浮层之上弹）。上游顺序是先模式后兼容性，
    // 这里沿用既有「格式检查 → 模式」的位置，拒绝时少弹一次模式框。
    val forwardCompatDialogs = remember { com.psyche.memo.ui.backup.ForwardCompatDialogs() }
    var importAllowUnverified by remember { mutableStateOf(false) }
    var restartReport by remember { mutableStateOf<RestoreReportUi?>(null) }

    // ── WebDAV (sub-block 5) ──────────────────────────────────────────────
    val backupNowTitle = backupTaskLabels(UiR.string.backup_page_backup_now)
    val restoreTitle = backupTaskLabels(UiR.string.backup_page_restore)
    var webDavItems by remember { mutableStateOf<List<com.psyche.memo.data.backup.WebDavFileItem>>(emptyList()) }
    var showWebDavSheet by remember { mutableStateOf(false) }
    var webDavRestoreTarget by remember { mutableStateOf<com.psyche.memo.data.backup.WebDavFileItem?>(null) }
    var showWebDavModeDialog by remember { mutableStateOf(false) }
    var showWebDavDeleteConfirm by remember { mutableStateOf<com.psyche.memo.data.backup.WebDavFileItem?>(null) }

    // ── S3 (sub-block 6) ──────────────────────────────────────────────────
    var s3Items by remember {
        mutableStateOf<List<com.psyche.memo.data.backup.S3FileItem>>(emptyList())
    }
    var showS3Sheet by remember { mutableStateOf(false) }
    var s3RestoreTarget by remember { mutableStateOf<com.psyche.memo.data.backup.S3FileItem?>(null) }
    var showS3ModeDialog by remember { mutableStateOf(false) }
    var s3DeleteConfirm by remember { mutableStateOf<com.psyche.memo.data.backup.S3FileItem?>(null) }

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
                // backup_page.dart L1417-1423 —— 保存成功才算一次完整备份，
                // 推进提醒的下一次到期时间。
                container.backupReminder.recordBackupCompleted()
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
            importAllowUnverified = false
            when (val decision = com.psyche.memo.ui.backup.resolveForwardCompatibility(
                inspect = { container.backupService.inspectBackupCompatibility(staged) },
                dialogs = forwardCompatDialogs,
            )) {
                com.psyche.memo.ui.backup.ForwardCompatDecision.PROCEED -> {}
                com.psyche.memo.ui.backup.ForwardCompatDecision.PROCEED_UNVERIFIED ->
                    importAllowUnverified = true
                com.psyche.memo.ui.backup.ForwardCompatDecision.CANCELLED -> {
                    staged.delete()
                    return@launch
                }
                com.psyche.memo.ui.backup.ForwardCompatDecision.UNREADABLE -> {
                    staged.delete()
                    toast(schemaTooNew, NotificationType.ERROR)
                    return@launch
                }
            }
            restoringFile = staged
            showImportModeDialog = true
        }
    }

    fun runImport(file: File, mode: RestoreMode, allowUnverified: Boolean = false) {
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
                    allowUnverifiedForwardCompatible = allowUnverified,
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
    // ── Cherry / Chatbox 导入（backup 子块 8）────────────────────────────
    // Cherry: 实验性确认 sheet → 选文件 → 模式 → 导入（backup_page.dart L71-99 /
    // L1289-1345）。Chatbox: 选文件 → 模式 → 导入（L1356-1398）。
    var thirdPartySource by remember { mutableStateOf<String?>(null) } // "cherry" | "chatbox"
    var pendingThirdPartyFile by remember { mutableStateOf<File?>(null) }
    var showThirdPartyModeDialog by remember { mutableStateOf(false) }
    var showCherryConfirm by remember { mutableStateOf(false) }
    val cherryConfirmBody = stringResource(UiR.string.backup_page_cherry_studio_confirm_body)
    val cherryUnsupportedTemplate =
        stringResource(UiR.string.backup_page_cherry_studio_unsupported_backup_version)
    val importThirdPartyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "third_party_import_${System.currentTimeMillis()}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("无法读取所选文件")
                    target.takeIf { it.length() > 0 }
                }.getOrNull()
            }
            if (staged == null) {
                toast(restoreFailedPrefix.format("所选文件为空或无法读取"), NotificationType.ERROR)
                return@launch
            }
            pendingThirdPartyFile = staged
            showThirdPartyModeDialog = true
        }
    }

    fun runThirdPartyImport(source: String, file: File, mode: RestoreMode) {
        scope.launch {
            var details: String? = null
            val title = if (source == "cherry") cherryImportTitle else chatboxImportTitle
            val ok = runner.run(
                labels = title,
                errorMessage = { failure ->
                    if (failure is com.psyche.memo.data.backup.cherry.CherryUnsupportedBackupVersionException) {
                        cherryUnsupportedTemplate.format(failure.version.toString())
                    } else {
                        restoreFailedPrefix.format(failure.message ?: failure.toString())
                    }
                },
            ) { progress, isCancelled ->
                var providers = 0
                var assistants = 0
                var conversations = 0
                var messages = 0
                var files = 0
                withContext(Dispatchers.IO) {
                    if (source == "cherry") {
                        val result = com.psyche.memo.data.backup.cherry.CherryImporter
                            .importFromCherryStudio(
                                file = file,
                                mode = mode,
                                database = container.database,
                                preferenceRepository = container.preferenceRepository,
                                uploadDir = File(context.filesDir, "upload"),
                                onProgress = { progress(it) },
                                isCancelled = isCancelled,
                            )
                        providers = result.providers
                        assistants = result.assistants
                        conversations = result.conversations
                        messages = result.messages
                        files = result.files
                    } else {
                        val result = com.psyche.memo.data.backup.chatbox.ChatboxImportRunner
                            .importFromChatbox(
                                file = file,
                                mode = mode,
                                database = container.database,
                                preferenceRepository = container.preferenceRepository,
                                uploadDir = File(context.filesDir, "upload"),
                                onProgress = { progress(it) },
                                isCancelled = isCancelled,
                            )
                        providers = result.providers
                        assistants = result.assistants
                        conversations = result.conversations
                        messages = result.messages
                    }
                }
                // Upstream renders these count lines in English in every locale
                // (backup_page.dart L1336-1345), so the labels stay literal.
                details = buildString {
                    append(if (source == "cherry") cherryImportTitle else chatboxImportTitle)
                    append(":\n")
                    append(" • Providers: ").append(providers).append('\n')
                    append(" • Assistants: ").append(assistants).append('\n')
                    append(" • Conversations: ").append(conversations).append('\n')
                    append(" • Messages: ").append(messages)
                    if (source == "cherry") {
                        append('\n').append(" • Files: ").append(files)
                    }
                }
            }
            file.delete()
            if (ok && details != null) {
                restartReport = RestoreReportUi(
                    skippedConversations = 0,
                    details = details,
                )
            }
        }
    }

// ── 5. WebDAV 备份 (WebDAV Backup) — 4 nav rows, sub-block 5 ──
// 服务器设置 / 测试连接 / 恢复（远端列表 sheet → 模式 → 恢复）/
// 立即备份（导出 → ensureCollection → PUT），对齐 backup_page L347-800。
val webDavConfig = remember { container.backupService.webDavConfig() }
val testDone = stringResource(UiR.string.backup_page_test_done)
val backupUploaded = stringResource(UiR.string.backup_page_backup_uploaded)
fun runWebDavTest() {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) { container.backupService.testWebDav(webDavConfig) }
        }.onSuccess {
            toast(testDone, NotificationType.SUCCESS)
        }.onFailure {
            toast(it.message ?: it.toString(), NotificationType.ERROR)
        }
    }
}
fun runWebDavRestore(item: com.psyche.memo.data.backup.WebDavFileItem, mode: RestoreMode) {
    scope.launch {
        var report: com.psyche.memo.data.backup.RestoreReportView? = null
        val ok = runner.run(
            labels = restoreTitle,
            errorMessage = { restoreFailedPrefix.format(it.message ?: it.toString()) },
        ) { progress, isCancelled ->
            report = container.backupService.restoreFromWebDav(
                config = webDavConfig,
                item = item,
                mode = mode,
                onProgress = { progress(it) },
                isCancelled = isCancelled,
                forwardCompatibilityPrompt = com.psyche.memo.ui.backup.forwardCompatibilityPrompt(
                    forwardCompatDialogs,
                ),
            )
        }
        val done = report
        if (ok && done != null) {
            restartReport = RestoreReportUi(
                skippedConversations = done.skippedConversations,
                details = reportDetails(done),
            )
        }
    }
}
fun runWebDavBackupNow() {
    scope.launch {
        val ok = runner.run(
            labels = backupNowTitle,
            errorMessage = { it.message ?: it.toString() },
        ) { progress, isCancelled ->
            container.backupService.backupToWebDav(
                config = webDavConfig,
                onProgress = { progress(it) },
                isCancelled = isCancelled,
            )
        }
        if (!ok) return@launch
        container.backupReminder.recordBackupCompleted()
        toast(backupUploaded, NotificationType.INFO)
    }
}
fun runWebDavList() {
    scope.launch {
        val ok = runner.run(
            labels = restoreTitle,
            errorMessage = { it.message ?: it.toString() },
        ) { progress, _ ->
            webDavItems = container.backupService.listWebDav(
                webDavConfig,
                onProgress = { progress(it) },
            )
        }
        if (ok) showWebDavSheet = true
    }
}
fun deleteWebDavItem(item: com.psyche.memo.data.backup.WebDavFileItem) {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) { container.backupService.deleteWebDavItem(webDavConfig, item) }
        }
        // `_deleteAndReload`（backup_page.dart L560 附近）：删完刷新列表。
        runCatching {
            withContext(Dispatchers.IO) {
                webDavItems = container.backupService.listWebDav(webDavConfig)
            }
        }
    }
}

// ── 6. S3 备份 (S3 Backup) — 4 nav rows, sub-block 6 ──
// 服务器设置 / 测试连接 / 恢复（远端列表 sheet → 模式 → 恢复）/ 立即备份，
// 对齐 backup_page.dart 的 S3 分区（L802-1240）。配置存在 `s3_config_v1`，
// 每次操作前重新读取，这样从设置子页返回后立即生效。
fun currentS3Config() = container.backupService.s3Config()
fun runS3Test() {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) { container.backupService.testS3(currentS3Config()) }
        }.onSuccess {
            toast(testDone, NotificationType.SUCCESS)
        }.onFailure {
            toast(it.message ?: it.toString(), NotificationType.ERROR)
        }
    }
}
fun runS3List() {
    scope.launch {
        val ok = runner.run(
            labels = restoreTitle,
            errorMessage = { it.message ?: it.toString() },
        ) { progress, _ ->
            s3Items = container.backupService.listS3(
                currentS3Config(),
                onProgress = { progress(it) },
            )
        }
        if (ok) showS3Sheet = true
    }
}
fun runS3BackupNow() {
    scope.launch {
        val ok = runner.run(
            labels = backupNowTitle,
            errorMessage = { it.message ?: it.toString() },
        ) { progress, isCancelled ->
            container.backupService.backupToS3(
                config = currentS3Config(),
                onProgress = { progress(it) },
                isCancelled = isCancelled,
            )
        }
        if (!ok) return@launch
        container.backupReminder.recordBackupCompleted()
        toast(backupUploaded, NotificationType.INFO)
    }
}
fun runS3Restore(item: com.psyche.memo.data.backup.S3FileItem, mode: RestoreMode) {
    scope.launch {
        var report: com.psyche.memo.data.backup.RestoreReportView? = null
        val ok = runner.run(
            labels = restoreTitle,
            errorMessage = { restoreFailedPrefix.format(it.message ?: it.toString()) },
        ) { progress, isCancelled ->
            report = container.backupService.restoreFromS3(
                config = currentS3Config(),
                item = item,
                mode = mode,
                onProgress = { progress(it) },
                isCancelled = isCancelled,
                forwardCompatibilityPrompt = com.psyche.memo.ui.backup.forwardCompatibilityPrompt(
                    forwardCompatDialogs,
                ),
            )
        }
        val done = report
        if (ok && done != null) {
            restartReport = RestoreReportUi(
                skippedConversations = done.skippedConversations,
                details = reportDetails(done),
            )
        }
    }
}
fun deleteS3Item(item: com.psyche.memo.data.backup.S3FileItem) {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                container.backupService.deleteS3Item(currentS3Config(), item)
            }
        }
        // 删完刷新列表（与 WebDAV 的 `_deleteAndReload` 同语义）。
        runCatching {
            withContext(Dispatchers.IO) {
                s3Items = container.backupService.listS3(currentS3Config())
            }
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
                    onChange = { setIncludeChats(it) },
                )
                BackupDivider()
                BackupSwitchRow(
                    Lucide.FileText,
                    stringResource(UiR.string.backup_page_files_label),
                    value = includeFiles,
                    onChange = { setIncludeFiles(it) },
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 2. 备份提醒 (Backup Reminder) — sub-block 4, wired ──────────
            // Rows mirror `_BackupReminderMobileSection` (backup_page.dart
            // L1602-1694): the enable switch (which asks for a time first when
            // none is set), then Frequency / Time / Last Backup / Next Reminder
            // once enabled. The record hooks live on the export path below.
            val reminderState = com.psyche.memo.ui.backup.rememberBackupReminderState(container)
            var showReminderTimeSheet by remember { mutableStateOf(false) }
            var showReminderFrequencySheet by remember { mutableStateOf(false) }
            // Frequency picked while no time is set: remember the days until the
            // time sheet saves (upstream chains the two dialogs the same way).
            var pendingDays by remember { mutableStateOf<Int?>(null) }
            val enableReminder = com.psyche.memo.ui.backup.rememberBackupReminderEnableHandler(
                container = container,
                onNeedTimePicker = {
                    pendingDays = null
                    showReminderTimeSheet = true
                },
            )
            BackupSection(title = stringResource(UiR.string.backup_reminder_section_title)) {
                BackupSwitchRow(
                    Lucide.Timer,
                    stringResource(UiR.string.backup_reminder_enable_title),
                    value = reminderState.enabled,
                    onChange = enableReminder,
                )
                if (reminderState.enabled) {
                    BackupDivider()
                    BackupPlaceholderRow(
                        Lucide.Repeat,
                        stringResource(UiR.string.backup_reminder_frequency_title),
                        com.psyche.memo.ui.backup.backupReminderFrequencyLabel(reminderState.intervalDays),
                        onTap = { showReminderFrequencySheet = true },
                    )
                    BackupDivider()
                    BackupPlaceholderRow(
                        Lucide.Clock,
                        stringResource(UiR.string.backup_reminder_time_title),
                        com.psyche.memo.ui.backup.backupReminderTimeLabel(
                            context,
                            reminderState.reminderMinutesOfDay,
                        ),
                        onTap = { showReminderTimeSheet = true },
                    )
                    BackupDivider()
                    BackupPlaceholderRow(
                        Lucide.CircleCheck,
                        stringResource(UiR.string.backup_reminder_last_backup_title),
                        com.psyche.memo.ui.backup.backupReminderDateTimeLabel(
                            context,
                            reminderState.lastBackupAt,
                        ),
                    )
                    BackupDivider()
                    BackupPlaceholderRow(
                        Lucide.Calendar,
                        stringResource(UiR.string.backup_reminder_next_reminder_title),
                        com.psyche.memo.ui.backup.backupReminderNextLabel(
                            context,
                            reminderState.reminder.nextReminderAt(),
                        ),
                    )
                }
            }

            // 时间滚轮 / 频率 sheet（备份提醒的编辑入口）。
            if (showReminderTimeSheet) {
                com.psyche.memo.ui.backup.BackupReminderTimeSheet(
                    initialMinutes = reminderState.reminderMinutesOfDay,
                    onDismiss = {
                        showReminderTimeSheet = false
                        pendingDays = null
                    },
                    onSave = { minutes ->
                        showReminderTimeSheet = false
                        container.backupReminder.saveSchedule(
                            enabled = true,
                            intervalDays = pendingDays ?: reminderState.intervalDays,
                            reminderMinutesOfDay = minutes,
                        )
                        pendingDays = null
                    },
                )
            }
            if (showReminderFrequencySheet) {
                com.psyche.memo.ui.backup.BackupReminderFrequencySheet(
                    intervalDays = reminderState.intervalDays,
                    onDismiss = { showReminderFrequencySheet = false },
                    onPick = { days ->
                        showReminderFrequencySheet = false
                        val minutes = reminderState.reminderMinutesOfDay
                        if (minutes == null) {
                            pendingDays = days
                            showReminderTimeSheet = true
                        } else {
                            container.backupReminder.saveSchedule(true, days, minutes)
                        }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 3. 本地副本 (Local Copies) ─────────────────────────────────
            // Per `_LocalSnapshotMobileSection` (backup_page.dart L1546-1600):
            // only 2 rows on this page — the Enabled switch + a "Manage copies"
            // nav row that pushes the LocalSnapshotsPage. (Sub-block 3; the
            // switch used to be a dead placeholder.)
            val localSnapshotPrefs = remember { container.localSnapshots.preferences }
            var snapshotEnabled by remember {
                mutableStateOf(localSnapshotPrefs.readSettings().enabled)
            }
            BackupSection(title = stringResource(UiR.string.local_snapshot_section_title)) {
                BackupSwitchRow(
                    Lucide.Shield,
                    stringResource(UiR.string.local_snapshot_enabled_title),
                    value = snapshotEnabled,
                    onChange = { next ->
                        snapshotEnabled = next
                        scope.launch(Dispatchers.IO) {
                            localSnapshotPrefs.writeSettings(
                                localSnapshotPrefs.readSettings().copy(enabled = next),
                            )
                        }
                    },
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
                    onTap = { importThirdPartyLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.BoxIcon,
                    stringResource(UiR.string.backup_page_import_from_chatbox),
                    "",
                    onTap = { importThirdPartyLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*")) },
                )
            }

            Spacer(Modifier.height(18.dp))
            BackupSection(title = stringResource(UiR.string.backup_page_web_dav_backup)) {
                BackupPlaceholderRow(
                    Lucide.Settings,
                    stringResource(UiR.string.backup_page_web_dav_server_settings),
                    "",
                    onTap = onOpenWebDavSettings,
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Cable,
                    stringResource(UiR.string.backup_page_test_connection),
                    "",
                    onTap = { runWebDavTest() },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Import,
                    stringResource(UiR.string.backup_page_restore),
                    "",
                    onTap = { runWebDavList() },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Upload,
                    stringResource(UiR.string.backup_page_backup_now),
                    "",
                    onTap = { runWebDavBackupNow() },
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 6. S3 备份 (S3 Backup) — 4 nav rows (sub-block 6) ────────
            BackupSection(title = stringResource(UiR.string.backup_page_s3_backup)) {
                BackupPlaceholderRow(
                    Lucide.Settings,
                    stringResource(UiR.string.backup_page_s3_server_settings),
                    "",
                    onTap = onOpenS3Settings,
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Cable,
                    stringResource(UiR.string.backup_page_test_connection),
                    "",
                    onTap = { runS3Test() },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Import,
                    stringResource(UiR.string.backup_page_restore),
                    "",
                    onTap = { runS3List() },
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Upload,
                    stringResource(UiR.string.backup_page_backup_now),
                    "",
                    onTap = { runS3BackupNow() },
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
                val allowUnverified = importAllowUnverified
                importAllowUnverified = false
                if (file != null) runImport(file, mode, allowUnverified)
            },
            onDismiss = {
                showImportModeDialog = false
                importAllowUnverified = false
                restoringFile?.delete()
                restoringFile = null
            },
        )
    }
    com.psyche.memo.ui.backup.ForwardCompatDialogHost(forwardCompatDialogs)

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

    // ── WebDAV sheets/dialogs（远端列表 → 模式 → 恢复 / 删除确认）─────────
    if (showWebDavSheet) {
        com.psyche.memo.ui.backup.RemoteBackupListSheet(
            items = webDavItems,
            rowOf = {
                com.psyche.memo.ui.backup.RemoteBackupRow(it.displayName, it.size)
            },
            onRestore = { item ->
                showWebDavSheet = false
                webDavRestoreTarget = item
                showWebDavModeDialog = true
            },
            onDelete = { item ->
                showWebDavSheet = false
                showWebDavDeleteConfirm = item
            },
            onDismiss = { showWebDavSheet = false },
        )
    }
    if (showWebDavModeDialog) {
        BackupImportModeDialog(
            onSelect = { mode ->
                showWebDavModeDialog = false
                val item = webDavRestoreTarget
                webDavRestoreTarget = null
                if (item != null) runWebDavRestore(item, mode)
            },
            onDismiss = {
                showWebDavModeDialog = false
                webDavRestoreTarget = null
            },
        )
    }
    showWebDavDeleteConfirm?.let { item ->
        val deleteLabel = stringResource(UiR.string.backup_page_delete_tooltip)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWebDavDeleteConfirm = null },
            title = { Text(stringResource(UiR.string.backup_page_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        UiR.string.backup_page_delete_confirm_content,
                        item.displayName,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showWebDavDeleteConfirm = null
                        deleteWebDavItem(item)
                    },
                ) {
                    Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showWebDavDeleteConfirm = null }) {
                    Text(
                        stringResource(UiR.string.backup_page_cancel),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                    )
                }
            },
        )
    }

    // ── S3 sheets/dialogs（远端列表 → 模式 → 恢复 / 删除确认）─────────────
    if (showS3Sheet) {
        com.psyche.memo.ui.backup.RemoteBackupListSheet(
            items = s3Items,
            rowOf = {
                com.psyche.memo.ui.backup.RemoteBackupRow(it.displayName, it.size)
            },
            onRestore = { item ->
                showS3Sheet = false
                s3RestoreTarget = item
                showS3ModeDialog = true
            },
            onDelete = { item ->
                showS3Sheet = false
                s3DeleteConfirm = item
            },
            onDismiss = { showS3Sheet = false },
        )
    }
    if (showS3ModeDialog) {
        BackupImportModeDialog(
            onSelect = { mode ->
                showS3ModeDialog = false
                val item = s3RestoreTarget
                s3RestoreTarget = null
                if (item != null) runS3Restore(item, mode)
            },
            onDismiss = {
                showS3ModeDialog = false
                s3RestoreTarget = null
            },
        )
    }
    s3DeleteConfirm?.let { item ->
        val deleteLabel = stringResource(UiR.string.backup_page_delete_tooltip)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { s3DeleteConfirm = null },
            title = { Text(stringResource(UiR.string.backup_page_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        UiR.string.backup_page_delete_confirm_content,
                        item.displayName,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        s3DeleteConfirm = null
                        deleteS3Item(item)
                    },
                ) {
                    Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { s3DeleteConfirm = null }) {
                    Text(
                        stringResource(UiR.string.backup_page_cancel),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                    )
                }
            },
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
