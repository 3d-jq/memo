package com.psyche.memo.ui.backup

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.psyche.memo.data.backup.BackupCancelledException
import com.psyche.memo.data.backup.BackupPhase
import com.psyche.memo.data.backup.BackupProgress
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Text the progress dialog needs, resolved by the composable that hosts it. */
internal data class BackupTaskLabels(
    val title: String,
    val cancel: String,
    val cancelled: String,
    val acknowledge: String,
)

/**
 * Port of `showBackupProgressDialog` + `runBackupTask`
 * (`lib/features/backup/widgets/backup_progress_dialog.dart` and
 * `lib/features/backup/backup_task_runner.dart`).
 *
 * Owns the shared progress dialog's state machine:
 *
 *  - the task runs on [Dispatchers.IO] while the dialog renders [BackupProgress]
 *  - cancel flips an [AtomicBoolean] the task polls, which becomes
 *    [BackupCancelledException] and a plain "Cancelled" toast
 *  - success shows a full bar for 600ms before the dialog closes, so the 100%
 *    state is actually visible instead of the dialog vanishing mid-flight
 *  - a failure keeps the dialog open and swaps the button for an "OK"
 *    acknowledgement, so the error is never swallowed
 *
 * [run] returns true only when the task finished successfully.
 */
internal class BackupTaskRunner(
    private val scope: CoroutineScope,
    private val onShowSnackbar: (AppNotification) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)

    private var visible by mutableStateOf(false)
    private var labels: BackupTaskLabels? = null
    private var progress by mutableStateOf(BackupProgress(BackupPhase.PREPARING, 0))
    private var outcome by mutableStateOf(TaskProgressOutcome.RUNNING)

    /** Renders the dialog while a task is in flight. Call from the screen body. */
    @Composable
    fun Host() {
        val currentLabels = labels ?: return
        if (!visible) return
        val current = progress
        val currentOutcome = outcome

        AlertDialog(
            // Not dismissible: the task is running and the user must pick an
            // action (cancel or acknowledge) instead of dismissing silently.
            onDismissRequest = {},
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            text = {
                TaskProgressDialogCard(
                    title = currentLabels.title,
                    phaseLabel = stringResource(backupPhaseLabelRes(current.phase)),
                    fraction = current.fraction,
                    subtitle = backupProgressSubtitle(current),
                    phaseIcon = backupPhaseIcon(current.phase),
                    cancellable = current.cancellable && currentOutcome == TaskProgressOutcome.RUNNING,
                    onCancel = { cancelled.set(true) },
                    cancelLabel = currentLabels.cancel,
                    acknowledgeLabel = currentLabels.acknowledge,
                    outcome = currentOutcome,
                    onAcknowledge = {
                        visible = false
                        outcome = TaskProgressOutcome.RUNNING
                    },
                )
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    /**
     * Runs [task] behind the progress dialog.
     *
     * @return true on success; false on cancel (info toast) or failure (error
     *   toast, dialog left open with an OK button).
     */
    suspend fun run(
        labels: BackupTaskLabels,
        errorMessage: ((Throwable) -> String)? = null,
        task: suspend (report: (BackupProgress) -> Unit, isCancelled: () -> Boolean) -> Unit,
    ): Boolean {
        this.labels = labels
        outcome = TaskProgressOutcome.RUNNING
        progress = BackupProgress(BackupPhase.PREPARING, 0)
        visible = true
        cancelled.set(false)

        val report: (BackupProgress) -> Unit = { next ->
            // A late report from a still-running task must not resurrect the bar
            // after a failure has been shown.
            if (outcome == TaskProgressOutcome.RUNNING) progress = next
        }

        val failure = withContext(Dispatchers.IO) {
            runCatching { task(report) { cancelled.get() } }.exceptionOrNull()
        }

        return when (failure) {
            null -> {
                outcome = TaskProgressOutcome.SUCCESS
                // Let the full bar render before the dialog disappears.
                delay(600)
                visible = false
                outcome = TaskProgressOutcome.RUNNING
                true
            }
            is BackupCancelledException -> {
                visible = false
                outcome = TaskProgressOutcome.RUNNING
                onShowSnackbar(AppNotification(labels.cancelled, NotificationType.INFO))
                false
            }
            else -> {
                outcome = TaskProgressOutcome.FAILURE
                onShowSnackbar(
                    AppNotification(
                        message = errorMessage?.invoke(failure)
                            ?: (failure.message ?: failure.toString()),
                        type = NotificationType.ERROR,
                    ),
                )
                false
            }
        }
    }
}

/**
 * Remembers a [BackupTaskRunner], renders its dialog, and resolves the
 * localized labels the dialog needs.
 */
@Composable
internal fun rememberBackupTaskRunner(): BackupTaskRunner {
    val scope = rememberCoroutineScope()
    val runner = remember(scope) {
        BackupTaskRunner(scope) { SnackbarManager.show(it) }
    }
    runner.Host()
    return runner
}

/** Builds the localized label set for one task, from inside a composable. */
@Composable
internal fun backupTaskLabels(titleRes: Int): BackupTaskLabels = BackupTaskLabels(
    title = stringResource(titleRes),
    cancel = stringResource(UiR.string.backup_progress_cancel),
    cancelled = stringResource(UiR.string.backup_progress_cancelled),
    acknowledge = stringResource(UiR.string.backup_page_o_k),
)
