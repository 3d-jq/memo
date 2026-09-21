package com.psyche.memo.ui.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.X
import com.psyche.memo.data.backup.BackupPhase
import com.psyche.memo.data.backup.BackupProgress
import com.psyche.memo.data.backup.BackupProgressUnit
import com.psyche.memo.ui.IosTileButton
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.AppFontWeights
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.withAlpha

/**
 * Port of `lib/shared/widgets/task_progress_dialog.dart` +
 * `lib/features/backup/widgets/backup_progress_dialog.dart`.
 *
 * Layout is 1:1: padding 20/18/20/16, a 14sp bold title with an 18dp leading
 * outcome icon, a 6dp rounded progress bar, a 13sp phase label on the left with
 * either a spinner or a bold percentage on the right, an optional 12sp@60%
 * subtitle, and a bottom action area that swaps button sets with the outcome.
 */
enum class TaskProgressOutcome { RUNNING, SUCCESS, FAILURE }

@Composable
fun TaskProgressDialogCard(
    title: String,
    phaseLabel: String,
    fraction: Float?,
    subtitle: String?,
    phaseIcon: ImageVector?,
    cancellable: Boolean,
    onCancel: () -> Unit,
    cancelLabel: String,
    acknowledgeLabel: String,
    outcome: TaskProgressOutcome,
    onAcknowledge: (() -> Unit)? = null,
    onBackground: (() -> Unit)? = null,
    backgroundLabel: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    val showCancel = outcome == TaskProgressOutcome.RUNNING && cancellable
    val showAck = outcome == TaskProgressOutcome.FAILURE
    val showBackground =
        outcome == TaskProgressOutcome.RUNNING && onBackground != null && backgroundLabel != null

    val resolvedFraction = if (outcome == TaskProgressOutcome.SUCCESS) 1f else fraction
    val leadingIcon: ImageVector? = when (outcome) {
        TaskProgressOutcome.SUCCESS -> Lucide.Check
        TaskProgressOutcome.FAILURE -> Lucide.TriangleAlert
        TaskProgressOutcome.RUNNING -> phaseIcon
    }
    val leadingTint: Color = when (outcome) {
        TaskProgressOutcome.SUCCESS -> semantic.success
        TaskProgressOutcome.FAILURE -> cs.error
        TaskProgressOutcome.RUNNING -> cs.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = AppFontWeights.emphasis,
                    color = cs.onSurface,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        BackupProgressBar(fraction = resolvedFraction, height = 6.dp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // AnimatedContent mirrors Flutter's AnimatedTextSwap on the label.
            AnimatedContent(
                targetState = phaseLabel,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "backupPhaseLabel",
                modifier = Modifier.weight(1f),
            ) { label ->
                Text(
                    text = label,
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant),
                )
            }
            if (resolvedFraction == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = cs.primary,
                )
            } else {
                Text(
                    text = "${(resolvedFraction.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = AppFontWeights.emphasis,
                        color = cs.primary,
                    ),
                )
            }
        }
        AnimatedVisibility(
            visible = !subtitle.isNullOrEmpty(),
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) {
            Text(
                text = subtitle.orEmpty(),
                style = TextStyle(fontSize = 12.sp, color = withAlpha(cs.onSurface, 0.6)),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        AnimatedVisibility(
            visible = showCancel || showAck || showBackground,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // `showBackground` 已包含 `backgroundLabel != null` 的检查，原来的
                // `showBackground && backgroundLabel != null` 第二个条件永远为真。
                if (showBackground) {
                    IosTileButton(
                        label = backgroundLabel,
                        icon = Lucide.ChevronDown,
                        onClick = { onBackground?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showAck) {
                    IosTileButton(
                        label = acknowledgeLabel,
                        icon = Lucide.Check,
                        onClick = { onAcknowledge?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (showCancel) {
                    IosTileButton(
                        label = cancelLabel,
                        icon = Lucide.X,
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * `AnimatedProgressBar` (`shared/widgets/animated_progress_bar.dart`): a fully
 * rounded track at 8% onSurface with a primary fill that animates its width;
 * a null [fraction] swaps in the back-and-forth indeterminate sweep.
 */
@Composable
fun BackupProgressBar(fraction: Float?, height: Dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(withAlpha(cs.onSurface, 0.08)),
    ) {
        if (fraction == null) {
            IndeterminateSweep(color = cs.primary)
        } else {
            val target by animateFloatAsState(
                targetValue = fraction.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 220),
                label = "backupProgressFill",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(target)
                    .fillMaxHeight()
                    .background(cs.primary),
            )
        }
    }
}

/** The 30%-wide highlight travelling left↔right on a 1200ms ping-pong. */
@Composable
private fun IndeterminateSweep(color: Color) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweepTravel",
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val highlight = maxWidth * 0.3f
        val distance = (maxWidth - highlight).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .padding(start = distance * travel)
                .width(highlight)
                .fillMaxHeight()
                .background(color),
        )
    }
}

/**
 * Maps a [BackupPhase] to the leading icon the progress card shows. 1:1 with
 * `backupPhaseIcon` in `backup_progress_dialog.dart` L224-243.
 */
fun backupPhaseIcon(phase: BackupPhase): ImageVector = when (phase) {
    BackupPhase.PREPARING -> Lucide.Loader
    BackupPhase.SNAPSHOTTING_DATABASE -> Lucide.Database
    BackupPhase.PACKING -> Lucide.Folder
    BackupPhase.VERIFYING -> Lucide.Shield
    BackupPhase.UPLOADING -> Lucide.Upload
    BackupPhase.DOWNLOADING -> Lucide.Download
    BackupPhase.EXTRACTING -> Lucide.FolderOpen
    BackupPhase.VALIDATING -> Lucide.Shield
    BackupPhase.READING_SETTINGS -> Lucide.Settings
    BackupPhase.STAGING_CANDIDATE -> Lucide.HardDrive
    BackupPhase.COMMITTING -> Lucide.HardDrive
    BackupPhase.IMPORTING_SESSIONS -> Lucide.MessagesSquare
    BackupPhase.IMPORTING_MESSAGES -> Lucide.MessageSquare
    BackupPhase.MATERIALIZING_FILES -> Lucide.FileText
    BackupPhase.LISTING_REMOTE -> Lucide.CloudDownload
    BackupPhase.FINALIZING -> Lucide.Check
}

/** `backupPhaseLabel` (L245-264) — one resource id per [BackupPhase]. */
fun backupPhaseLabelRes(phase: BackupPhase): Int = when (phase) {
    BackupPhase.PREPARING -> UiR.string.backup_progress_preparing
    BackupPhase.SNAPSHOTTING_DATABASE -> UiR.string.backup_progress_snapshotting
    BackupPhase.PACKING -> UiR.string.backup_progress_packing
    BackupPhase.VERIFYING -> UiR.string.backup_progress_verifying
    BackupPhase.UPLOADING -> UiR.string.backup_progress_uploading
    BackupPhase.DOWNLOADING -> UiR.string.backup_progress_downloading
    BackupPhase.EXTRACTING -> UiR.string.backup_progress_extracting
    BackupPhase.VALIDATING -> UiR.string.backup_progress_validating
    BackupPhase.READING_SETTINGS -> UiR.string.backup_progress_reading_settings
    BackupPhase.STAGING_CANDIDATE -> UiR.string.backup_progress_staging
    BackupPhase.COMMITTING -> UiR.string.backup_progress_committing
    BackupPhase.IMPORTING_SESSIONS -> UiR.string.backup_progress_importing_sessions
    BackupPhase.IMPORTING_MESSAGES -> UiR.string.backup_progress_importing_messages
    BackupPhase.MATERIALIZING_FILES -> UiR.string.backup_progress_materializing_files
    BackupPhase.LISTING_REMOTE -> UiR.string.backup_progress_listing_remote
    BackupPhase.FINALIZING -> UiR.string.backup_progress_finalizing
}

/**
 * `backupProgressSubtitle` (L266-284): "done / total" for byte and item units,
 * nothing for a phase that has no meaningful count.
 */
@Composable
fun backupProgressSubtitle(progress: BackupProgress): String? {
    val total = progress.total ?: return null
    return when (progress.unit) {
        BackupProgressUnit.NONE -> null
        BackupProgressUnit.BYTES -> stringResource(
            UiR.string.backup_progress_bytes,
            formatBytes(progress.processed),
            formatBytes(total),
        )
        BackupProgressUnit.ITEMS -> stringResource(
            UiR.string.backup_progress_items,
            formatCount(progress.processed),
            formatCount(total),
        )
    }
}

/**
 * `formatBytes` (`shared/utils/format_bytes.dart`) — decimal units, one decimal.
 * Public so the storage screens can reuse it instead of re-deriving the rule.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000.0
    var index = 0
    while (value >= 1000.0 && index < units.lastIndex) {
        value /= 1000.0
        index += 1
    }
    return "${"%.1f".format(java.util.Locale.US, value)} ${units[index]}"
}

/** `NumberFormat.decimalPattern()` equivalent — grouped integers. */
fun formatCount(value: Long): String = String.format(java.util.Locale.US, "%,d", value)
