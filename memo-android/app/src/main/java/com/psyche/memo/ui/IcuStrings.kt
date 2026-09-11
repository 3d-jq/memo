package com.psyche.memo.ui

import android.content.Context
import android.icu.text.MessageFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Formats one of the ICU messages `tools/arb_to_android.py` emits verbatim
 * (plural/select entries such as `local_snapshot_keep_value`), which
 * `Context.getString` would otherwise hand back as raw `{count, plural, …}`.
 *
 * The CLI has used `intl` for these since the Flutter original, so the ICU
 * syntax is the portable form of the same message.
 */
fun icuString(context: Context, resId: Int, arguments: Map<String, Any>): String {
    val template = context.getString(resId)
    return runCatching {
        MessageFormat(template, Locale.getDefault()).format(arguments)
    }.getOrDefault(template)
}

/** [icuString] for use inside a composition. */
@Composable
fun icuString(resId: Int, vararg arguments: Pair<String, Any>): String =
    icuString(LocalContext.current, resId, arguments.toMap())
