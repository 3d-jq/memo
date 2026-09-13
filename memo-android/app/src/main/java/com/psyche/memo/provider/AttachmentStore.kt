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

    fun import(
        context: Context,
        uri: Uri,
        config: ImageCompressConfig,
    ): ChatViewModel.PendingAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        val displayName = queryName(context, uri) ?: uri.lastPathSegment ?: "attachment"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val isImage = (mime?.startsWith("image/") == true) || ext in imageExtensions
        val dir = File(context.filesDir, "upload").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)

        // 图片先过画质管线（file_upload_service.dart:56/77 的 ImageCompressor），
        // 非图片保持原来的流式拷贝（不把大文件整块读进内存）。
        if (!isImage) {
            val dest = File(dir, "att_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_$safeName")
            return runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                ChatViewModel.PendingAttachment(
                    uri = dest.absolutePath,
                    mime = mime ?: guessMime(ext, isImage),
                    name = displayName,
                    isImage = false,
                )
            }.getOrNull()
        }

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val compressed = ImageCompressor.compress(bytes, config)
        val data = compressed ?: bytes
        // 压过就是 JPEG（image_compressor.dart:105-107 强制 .jpg），mime 也跟着走。
        val outputName = if (compressed != null) {
            safeName.substringBeforeLast('.', safeName) + ".jpg"
        } else {
            safeName
        }
        val dest = File(dir, "att_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_$outputName")
        return runCatching {
            dest.writeBytes(data)
            ChatViewModel.PendingAttachment(
                uri = dest.absolutePath,
                mime = if (compressed != null) "image/jpeg" else (mime ?: guessMime(ext, isImage)),
                name = displayName,
                isImage = true,
            )
        }.getOrNull()
    }

    /**
     * Camera capture lands directly in the upload dir; caller passes the file.
     * 同样过一遍画质管线（file_upload_service.dart:156），原地覆盖。
     */
    fun fromCapturedFile(
        file: File,
        config: ImageCompressConfig,
    ): ChatViewModel.PendingAttachment {
        runCatching {
            val bytes = file.readBytes()
            val compressed = ImageCompressor.compress(bytes, config)
            if (compressed != null) file.writeBytes(compressed)
        }
        return ChatViewModel.PendingAttachment(
            uri = file.absolutePath,
            mime = "image/jpeg",
            name = file.name,
            isImage = true,
        )
    }

    /**
     * 长粘贴转文件（chat_input_bar.dart:1614-1665 `_reservePastedTextFile` + 写入）：
     * `<upload>/pasted_<ms>.txt`，同名时按 `(1)`、`(2)` 顺延（原版用
     * `File.create(exclusive: true)` 撞名重试）。写入失败返回 null，调用方退回
     * 「直接插入文本」。
     */
    fun importPastedText(context: Context, text: String): ChatViewModel.PendingAttachment? {
        val dir = File(context.filesDir, "upload").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        var index = 0
        while (true) {
            val name = if (index == 0) "pasted_$stamp.txt" else "pasted_$stamp($index).txt"
            val dest = File(dir, name)
            val created = runCatching { dest.createNewFile() }.getOrDefault(false)
            if (!created) {
                if (dest.exists()) {
                    index++
                    continue
                }
                return null
            }
            return runCatching {
                dest.writeText(text, Charsets.UTF_8)
            }.map {
                ChatViewModel.PendingAttachment(
                    uri = dest.absolutePath,
                    mime = "text/plain",
                    name = name,
                    isImage = false,
                )
            }.getOrNull()
        }
    }

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
