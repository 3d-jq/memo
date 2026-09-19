package com.psyche.memo.ui.backup

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.psyche.memo.MainActivity
import com.psyche.memo.ui.IosTileButton
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of `showBackupRestartRequiredDialog`
 * (`lib/features/backup/backup_restart_dialog.dart`).
 *
 * Shows a non-dismissible card explaining that the imported data only becomes
 * effective after a restart, with a single OK button that restarts the app.
 * [skippedConversations] > 0 swaps in the "N conversations were skipped"
 * wording, and [details] is an optional per-importer summary prepended above
 * the standard body.
 */
@Composable
fun BackupRestartRequiredDialog(
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    skippedConversations: Int = 0,
    details: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    val content = if (skippedConversations > 0) {
        stringResource(UiR.string.backup_page_restart_content_with_skipped, skippedConversations.toString())
    } else {
        stringResource(UiR.string.backup_page_restart_content)
    }
    val body = if (details.isNullOrBlank()) content else "$details\n\n$content"

    AlertDialog(
        // dismissible: false — the user must acknowledge the restart prompt.
        onDismissRequest = {},
        shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
        title = {
            Text(
                text = stringResource(UiR.string.backup_page_restart_required),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = body,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 19.6.sp, // 14 * 1.4, as in the Dart source
                        color = cs.onSurface,
                    ),
                )
                Spacer(Modifier.height(16.dp))
                IosTileButton(
                    label = stringResource(UiR.string.backup_page_o_k),
                    icon = Lucide.Check,
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/**
 * Relaunches the app.
 *
 * Flutter's `restartApp` uses an FFI `exit(0)`; Android cannot exit cleanly
 * without the process dying, so the equivalent is a one-shot
 * [AlarmManager] restart intent scheduled for "now" followed by
 * [Process.killProcess]. The alarm fires after this process is gone and starts
 * [MainActivity] fresh — same user-visible result, and unlike a bare
 * `startActivity` it does not leave the old process in the recents stack.
 */
fun restartApp(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    val pending = PendingIntent.getActivity(
        context,
        RESTART_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    // 100ms is the documented minimum delay for a "restart now" alarm.
    alarm.setExactAndAllowWhileIdle(
        AlarmManager.RTC,
        System.currentTimeMillis() + RESTART_DELAY_MS,
        pending,
    )
    Process.killProcess(Process.myPid())
}

private const val RESTART_REQUEST_CODE = 0x4D454D4F // "MEMO"
private const val RESTART_DELAY_MS = 100L
