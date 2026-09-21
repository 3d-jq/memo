package com.psyche.memo.ui.backup

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.data.backup.BackupCompatibility
import com.psyche.memo.data.backup.ForwardCompatibilityAnswer
import com.psyche.memo.data.backup.ForwardCompatibilityPrompt
import com.psyche.memo.data.db.BackupSchemaVerdict
import com.psyche.memo.data.db.SchemaMigrations
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Port of `forward_compat_consent_dialog.dart`: decides whether a backup file
 * may be restored, asking the user only when the answer is theirs to give.
 *
 * Compose dialogs are declarative, so the suspend side raises requests on a
 * [ForwardCompatDialogs] host the screen mounts ([ForwardCompatDialogHost]) and
 * awaits the user's answer.
 */

/** Outcome of checking a backup file's schema before restoring it. */
enum class ForwardCompatDecision {
    /** Nothing in the way — start the restore normally. */
    PROCEED,

    /** The user chose not to import a backup from a newer version. */
    CANCELLED,

    /** The backup declares it needs a newer build; restoring is not offered. */
    UNREADABLE,

    /** The backup comes from a newer version that made no promise, and the
     *  user chose to import it anyway. */
    PROCEED_UNVERIFIED,
}

/**
 * The two dialogs the forward-compatibility question can raise, mounted by the
 * backup screen so suspend code (which may run on Dispatchers.IO) can ask and
 * await the answer.
 */
class ForwardCompatDialogs {
    internal data class ConsentArgs(val schemaVersion: Int, val currentVersion: Int)

    internal var consent by mutableStateOf<ConsentArgs?>(null)
    internal var showUnreadable by mutableStateOf(false)
    private var pending: CompletableDeferred<Boolean>? = null

    /** The consent dialog; true = the user accepts the risk. */
    suspend fun askConsent(schemaVersion: Int, currentVersion: Int): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        consent = ConsentArgs(schemaVersion, currentVersion)
        try {
            return deferred.await()
        } finally {
            pending = null
            consent = null
        }
    }

    /** The one-button "update the app" dialog for an unreadable backup. */
    suspend fun alertUnreadable() {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        showUnreadable = true
        try {
            deferred.await()
        } finally {
            pending = null
            showUnreadable = false
        }
    }

    internal fun settle(result: Boolean) {
        pending?.complete(result)
    }
}

/**
 * Maps an already-inspected [compatibility] to a decision, asking the user
 * only for the `forwardUndeclared` case. `null` compatibility (no SQLite
 * payload, or an unreadable manifest) proceeds — the restore itself reports
 * the real problem.
 */
suspend fun decideForwardCompatibility(
    compatibility: BackupCompatibility?,
    dialogs: ForwardCompatDialogs,
): ForwardCompatDecision = withContext(Dispatchers.Main) {
    when (compatibility?.verdict) {
        BackupSchemaVerdict.CURRENT,
        BackupSchemaVerdict.NEEDS_UPGRADE,
        BackupSchemaVerdict.FORWARD_COMPATIBLE,
        null,
        -> ForwardCompatDecision.PROCEED

        BackupSchemaVerdict.UNREADABLE -> ForwardCompatDecision.UNREADABLE

        BackupSchemaVerdict.FORWARD_UNDECLARED ->
            if (dialogs.askConsent(
                    schemaVersion = compatibility.schemaVersion,
                    currentVersion = SchemaMigrations.CURRENT_SCHEMA_VERSION,
                )
            ) {
                ForwardCompatDecision.PROCEED_UNVERIFIED
            } else {
                ForwardCompatDecision.CANCELLED
            }
    }
}

/**
 * Builds the prompt DataSync's WebDAV and S3 restores use to put the same
 * question to the user once the archive has finished downloading. The
 * progress overlay is already up by then; a dialog stacks above it and takes
 * input normally.
 */
fun forwardCompatibilityPrompt(dialogs: ForwardCompatDialogs): ForwardCompatibilityPrompt =
    { compatibility ->
        when (val decision = decideForwardCompatibility(compatibility, dialogs)) {
            ForwardCompatDecision.PROCEED -> ForwardCompatibilityAnswer.PROCEED
            ForwardCompatDecision.PROCEED_UNVERIFIED -> ForwardCompatibilityAnswer.PROCEED_UNVERIFIED
            ForwardCompatDecision.CANCELLED -> ForwardCompatibilityAnswer.REFUSE
            ForwardCompatDecision.UNREADABLE -> {
                // Refusing reports itself as a cancellation, so the reason has
                // to be shown from here.
                dialogs.alertUnreadable()
                ForwardCompatibilityAnswer.REFUSE
            }
        }
    }

/** Inspects + decides in one step, for local files readable up front. */
suspend fun resolveForwardCompatibility(
    inspect: () -> BackupCompatibility?,
    dialogs: ForwardCompatDialogs,
): ForwardCompatDecision = decideForwardCompatibility(
    withContext(Dispatchers.IO) { inspect() },
    dialogs,
)

/** Mounts the two dialogs the [ForwardCompatDialogs] host can raise. */
@Composable
fun ForwardCompatDialogHost(dialogs: ForwardCompatDialogs) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current

    dialogs.consent?.let { args ->
        AlertDialog(
            onDismissRequest = { dialogs.settle(false) },
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            title = {
                Text(
                    text = stringResource(UiR.string.backup_page_forward_compat_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            },
            text = {
                Text(
                    text = stringResource(
                        UiR.string.backup_page_forward_compat_body,
                        args.schemaVersion.toString(),
                        args.currentVersion.toString(),
                    ),
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                )
            },
            confirmButton = {},
            dismissButton = {
                Row {
                    TextButton(onClick = { dialogs.settle(false) }) {
                        Text(
                            text = stringResource(UiR.string.backup_page_forward_compat_cancel),
                            style = TextStyle(fontSize = 14.sp, color = cs.primary),
                        )
                    }
                    TextButton(onClick = { dialogs.settle(true) }) {
                        Text(
                            text = stringResource(UiR.string.backup_page_forward_compat_continue),
                            style = TextStyle(fontSize = 14.sp, color = cs.error),
                        )
                    }
                }
            },
        )
    }

    if (dialogs.showUnreadable) {
        AlertDialog(
            onDismissRequest = { dialogs.settle(false) },
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            title = {
                Text(
                    text = stringResource(UiR.string.backup_page_forward_compat_title),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            },
            text = {
                Text(
                    text = stringResource(UiR.string.backup_page_schema_too_new_message),
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dialogs.settle(false) }) {
                    Text(
                        text = stringResource(UiR.string.backup_page_forward_compat_cancel),
                        style = TextStyle(fontSize = 14.sp, color = cs.primary),
                    )
                }
            },
        )
    }
}
