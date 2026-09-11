package com.psyche.memo.ui

import android.content.Context
import java.io.File

/**
 * `_duplicateLocalFile` (assistant_provider.dart L172-205): duplicating an
 * assistant copies its local avatar/background file to a fresh name, so the
 * copy does not share a file with the original.
 *
 * Returns null when the value is not a local file (blank / http / data:) or the
 * source file is missing — the caller then keeps the original value, exactly
 * like the Dart helper's early returns.
 */
internal fun duplicateAssistantLocalFile(
    context: Context,
    rawPath: String?,
    newId: String,
    isAvatar: Boolean,
): String? {
    val raw = rawPath?.trim().orEmpty()
    if (raw.isEmpty() || raw.startsWith("http") || raw.startsWith("data:")) return null
    val src = File(raw)
    if (!src.isFile) return null

    val ext = raw.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.isNotEmpty() && it.length <= 6 }
        ?: "jpg"
    val dir = if (isAvatar) com.psyche.memo.AppDirs.avatars(context) else com.psyche.memo.AppDirs.images(context)
    if (!dir.exists()) dir.mkdirs()
    val prefix = if (isAvatar) "assistant" else "background"
    val dest = File(dir, "${prefix}_${newId}_${System.currentTimeMillis()}.$ext")
    return runCatching {
        src.copyTo(dest, overwrite = true)
        dest.absolutePath
    }.getOrNull()
}
