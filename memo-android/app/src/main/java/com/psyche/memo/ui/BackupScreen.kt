package com.psyche.memo.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Box
import com.composables.icons.lucide.Cable
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.Upload
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha

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
 * Functionality (BackupProvider / WebDAV / S3 / local snapshot) is a later
 * batch — placeholder rows match the original's visual structure (label +
 * chevron) but the sub-pages for WebDAV server / S3 server settings +
 * Cherry Studio import + Chatbox import will be wired in that batch.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onOpenLocalSnapshots: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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
                    value = false,
                    onChange = {},
                )
                BackupDivider()
                BackupSwitchRow(
                    Lucide.FileText,
                    stringResource(UiR.string.backup_page_files_label),
                    value = false,
                    onChange = {},
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 2. 备份提醒 (Backup Reminder) ─────────────────────────────
            // First row is `_iosSwitchRow` (backup_page.dart L1612); the other
            // two are `_iosNavRow` (Frequency / Last Backup).
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
            // nav row that pushes the LocalSnapshotsPage. All the deeper
            // settings (interval / keep count / weekly / monthly / space
            // limit / announce) live on the sub-page, not here.
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
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Download,
                    stringResource(UiR.string.backup_page_import_backup_file),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Box,
                    stringResource(UiR.string.backup_page_import_from_cherry_studio),
                    "",
                )
                BackupDivider()
                BackupPlaceholderRow(
                    Lucide.Box,
                    stringResource(UiR.string.backup_page_import_from_chatbox),
                    "",
                )
            }

            Spacer(Modifier.height(18.dp))
            // ── 5. WebDAV 备份 (WebDAV Backup) — 3 nav rows ──────────────
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
            // ── 6. S3 备份 (S3 Backup) — 3 nav rows ─────────────────────
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
