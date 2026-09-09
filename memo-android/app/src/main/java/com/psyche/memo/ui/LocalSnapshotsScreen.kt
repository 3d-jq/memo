package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CalendarPlus
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Shield
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.withAlpha

/**
 * UI shell of `lib/features/backup/pages/local_snapshots_page.dart`
 * (LocalSnapshotsPage). 1:1 with the original section structure:
 *
 *   Settings card (1:1 with _LocalSnapshotMobileSection + L88-138) — when
 *   `enabled`, exposes interval / keep recent / keep weekly / keep monthly /
 *   space limit / announce rows. Status line (last attempt) and the
 *   full-width primary `IosTileButton` "Save a copy now" sit between the
 *   settings card and the on-device copies list.
 *
 *   The on-device copies list is empty in this shell (the real page would
 *   load from `LocalSnapshotProvider` and show a `_CopyCard` per entry).
 *
 * Functionality is a later batch — RikkaHub's data-sync layer is the
 * candidate for reference.
 */
@Composable
fun LocalSnapshotsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ── Settings card ─────────────────────────────────────────────
            ShellSection(title = stringResource(UiR.string.local_snapshot_section_title), first = true) {
                LocalSnapshotSwitchRow(
                    Lucide.Shield,
                    stringResource(UiR.string.local_snapshot_enabled_title),
                    value = false,
                    onChange = {},
                )
                ShellDivider()
                EditNavRow(
                    Lucide.Repeat,
                    stringResource(UiR.string.local_snapshot_interval_title),
                    "",
                    onTap = {},
                )
                ShellDivider()
                EditNavRow(
                    Lucide.Layers,
                    stringResource(UiR.string.local_snapshot_keep_title),
                    "",
                    onTap = {},
                )
                ShellDivider()
                LocalSnapshotSwitchRow(
                    Lucide.CalendarPlus,
                    stringResource(UiR.string.local_snapshot_keep_weekly),
                    value = false,
                    onChange = {},
                )
                ShellDivider()
                LocalSnapshotSwitchRow(
                    Lucide.Calendar,
                    stringResource(UiR.string.local_snapshot_keep_monthly),
                    value = false,
                    onChange = {},
                )
                ShellDivider()
                EditNavRow(
                    Lucide.HardDrive,
                    stringResource(UiR.string.local_snapshot_maximum_title),
                    "",
                    onTap = {},
                )
                ShellDivider()
                LocalSnapshotSwitchRow(
                    Lucide.MessageSquare,
                    stringResource(UiR.string.local_snapshot_announce_title),
                    value = false,
                    onChange = {},
                )
            }
            // ── Subtitle / scope note (L1584-1600) ───────────────────────
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(UiR.string.local_snapshot_enabled_subtitle),
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = withAlpha(cs.onSurface, 0.55),
                ),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            // ── Status line (L146) — placeholder for now ────────────────
            Spacer(Modifier.height(10.dp))
            Text(
                text = "",
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
            // ── Full-width "Save a copy now" primary button (L148-153) ─
            IosTileButton(
                label = stringResource(UiR.string.local_snapshot_take_now),
                icon = Lucide.Download,
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = cs.primary,
                foregroundColor = cs.primary,
            )
            // ── On-device copies section header (L154-157) ───────────────
            Spacer(Modifier.height(18.dp))
            ShellSection(title = stringResource(UiR.string.local_snapshot_on_device_copies)) {
                Text(
                    text = stringResource(UiR.string.local_snapshot_status_never),
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = withAlpha(cs.onSurface, 0.6),
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun ShellSection(
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
internal fun ShellDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(withAlpha(MaterialTheme.colorScheme.outlineVariant, 0.18)),
    )
}

/** iOS-style settings row with a trailing IosSwitch toggle. */
@Composable
internal fun LocalSnapshotSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Matches `local_snapshots_page.dart` / `backup_page.dart` L2219: the
    // original Flutter `_iosSwitchRow` uses
    // `EdgeInsets.symmetric(horizontal: 12, vertical: 2)`, intentionally
    // tighter than `_iosNavRow`'s 11dp so the IosSwitch (26dp) + 4dp pad =
    // 30dp row sits more compact than the 42dp nav row. 1:1 with upstream.
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
