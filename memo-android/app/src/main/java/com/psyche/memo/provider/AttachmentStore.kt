package com.psyche.memo.provider

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.psyche.memo.ChatViewModel
import java.io.File

/**
 * Attachment intake — copies a picked content URI into the app's upload
 * directory and reports the stored path (file_upload_service.dart semantics:
 * the picker's URI is never kept, only the managed copy).
 *
 * **命名与去重照原版**（`FileImportHelper.copyXFile` + `UploadDedupe`）：上传目录里
 * 存的是**原始文件名**（撞名才 `name(1).ext`），同样字节的文件复用已有副本。此前
 * 我们写成 `att_<millis>_<uuid>_<name>`，于是「聊天记录 → 存储 → 文件」里显示的
 * 是这串内部名（用户实测报「文件显示有问题」）。展示名取**落盘后的 basename**
 * （file_upload_service.dart:310 `p.basename(savedPath)`），与存储页一致。
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
        val safeName = safeFileName(displayName)

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        // 图片先过画质管线（file_upload_service.dart:56/77 的 ImageCompressor）；
        // 压过就是 JPEG（image_compressor.dart:105-107 强制 .jpg），名字与 mime 跟着走。
        val compressed = if (isImage) ImageCompressor.compress(bytes, config) else null
        // HEIC/HEIF 画质管线是不碰的（`detectFormat` 归 OTHER），上游那侧根本不会出现 HEIC，
        // 因为 image_picker 插件默认 heicToJpg；我们走 SAF/PhotoPicker 绕过了它，所以这里
        // 补一次**格式转换**。转不动（API 26/27 没有 HEIF 解码器）就整张丢掉并报提示 ——
        // 原字节发出去厂商会 400「unsupported image」，那是**整条消息都发不出去**。
        val heic = isImage && compressed == null && ImageCompressor.isHeic(bytes)
        val transcoded = if (heic) ImageCompressor.transcodeToJpeg(bytes) else null
        if (heic && transcoded == null) return null
        val data = compressed ?: transcoded ?: bytes
        val becameJpeg = compressed != null || transcoded != null
        val outputName = when {
            !isImage -> safeName
            becameJpeg -> safeName.substringBeforeLast('.', safeName) + ".jpg"
            else -> safeName
        }
        val saved = store(dir, data, outputName) ?: return null
        return ChatViewModel.PendingAttachment(
            uri = saved.absolutePath,
            mime = when {
                becameJpeg -> "image/jpeg"
                else -> mime ?: guessMime(ext, isImage)
            },
            name = saved.name,
            isImage = isImage,
        )
    }

    /**
     * 落盘的展示名 —— **原样沿用文件名**（中文、空格、括号、`#` 等一律保留；原版
     * `FileImportHelper.copyXFile` 直接用 `xFile.name`）。只做两件必要的事：
     * 把路径分隔符/NUL 换成 `_`（防目录穿越），以及给超长名兜一个 200 字节上限
     * （ext4 单段 255 字节，超了 `createNewFile` 抛错 → 导入会静默失败）。
     */
    internal fun safeFileName(rawName: String): String {
        val cleaned = rawName
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .trim()
            .ifEmpty { "attachment" }
        if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0) cleaned.substring(dot).take(16) else ""
        val base = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val budget = (MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        var out = base
        while (out.isNotEmpty() && out.toByteArray(Charsets.UTF_8).size > budget) out = out.dropLast(1)
        return out + ext
    }

    /** 单段文件名上限（ext4 是 255 字节；留点富余）。 */
    private const val MAX_NAME_BYTES = 200

    /**
     * 落盘：同名字段族里有字节相同的旧文件就复用它，否则用 `name`、`name(1)`…
     * 里第一个空位（`UploadDedupe.findIdentical` + `reserveUniqueFile`）。
     */
    internal fun store(dir: File, bytes: ByteArray, fileName: String): File? {
        findIdentical(dir, bytes, fileName)?.let { return it }
        val dest = reserveUnique(dir, fileName) ?: return null
        return runCatching { dest.writeBytes(bytes); dest }.getOrNull()
    }

    /** 同名字段族（`name.ext` 与 `name(1).ext`…）+ 同字节 → 复用已有文件。 */
    private fun findIdentical(dir: File, bytes: ByteArray, fileName: String): File? {
        val stored = runCatching { dir.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())
        if (stored.isEmpty()) return null
        val names = stored.mapTo(HashSet()) { it.name }
        val candidates = stored.filter { file ->
            val matchesName = file.name == fileName ||
                (fileName in names && isVersionOf(file.name, fileName))
            matchesName && runCatching { file.length() }.getOrDefault(-1L) == bytes.size.toLong()
        }
        if (candidates.isEmpty()) return null
        val digest = sha256(bytes)
        for (candidate in candidates) {
            val existing = runCatching { sha256(candidate.readBytes()) }.getOrNull() ?: continue
            if (existing.contentEquals(digest)) return candidate
        }
        return null
    }

    /** `name.ext`、`name(1).ext`… 里第一个能独占创建的名字。 */
    private fun reserveUnique(dir: File, fileName: String): File? {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 0
        while (counter < 1000) {
            val suffix = if (counter == 0) "" else "($counter)"
            val candidate = File(dir, "$base$suffix$ext")
            if (runCatching { candidate.createNewFile() }.getOrDefault(false)) return candidate
            if (!candidate.exists()) return null
            counter++
        }
        return null
    }

    /** `notes(2).txt` 是不是 `notes.txt` 的版本名（扩展名必须相同）。 */
    private fun isVersionOf(candidateName: String, fileName: String): Boolean {
        if (candidateName.substringAfterLast('.', "") != fileName.substringAfterLast('.', "")) return false
        val base = fileName.substringBeforeLast('.', fileName)
        val candidateBase = candidateName.substringBeforeLast('.', candidateName)
        if (!candidateBase.startsWith(base)) return false
        return Regex("^\\(\\d+\\)$").matches(candidateBase.substring(base.length))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

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
