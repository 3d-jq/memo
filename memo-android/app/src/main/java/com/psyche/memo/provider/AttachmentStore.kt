package com.psyche.memo.provider

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.psyche.memo.ChatViewModel
import java.io.File
import java.util.UUID

/**
 * Attachment intake — copies a picked content URI into the app's upload
 * directory and reports the stored path (file_upload_service.dart semantics:
 * the picker's URI is never kept, only the managed copy).
 */
object AttachmentStore {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    fun import(context: Context, uri: Uri): ChatViewModel.PendingAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        val displayName = queryName(context, uri) ?: uri.lastPathSegment ?: "attachment"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val isImage = (mime?.startsWith("image/") == true) || ext in imageExtensions
        val dir = File(context.filesDir, "upload").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val dest = File(dir, "att_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_$safeName")
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            ChatViewModel.PendingAttachment(
                uri = dest.absolutePath,
                mime = mime ?: guessMime(ext, isImage),
                name = displayName,
                isImage = isImage,
            )
        }.getOrNull()
    }

    /** Camera capture lands directly in the upload dir; caller passes the file. */
    fun fromCapturedFile(file: File): ChatViewModel.PendingAttachment =
        ChatViewModel.PendingAttachment(
            uri = file.absolutePath,
            mime = "image/jpeg",
            name = file.name,
            isImage = true,
        )

    fun captureFile(context: Context): File {
        val dir = File(context.filesDir, "upload").apply { mkdirs() }
        return File(dir, "cam_${System.currentTimeMillis()}.jpg")
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()

    private fun guessMime(ext: String, isImage: Boolean): String? = when {
        !isImage -> null
        ext == "png" -> "image/png"
        ext == "gif" -> "image/gif"
        ext == "webp" -> "image/webp"
        ext == "heic" || ext == "heif" -> "image/heic"
        else -> "image/jpeg"
    }
}
