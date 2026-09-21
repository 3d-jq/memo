package com.psyche.memo.data.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reads and writes Memo backup archives (`.zip`).
 *
 * Ported from `_StreamingZipWriter` + `_packZipSync` + `_extractZipSync`
 * (`data_sync.dart` L905-1355). The Dart side hand-rolls ZIP64 because it
 * targets the web and iOS without a zip library; on Android
 * [ZipOutputStream] already emits ZIP64 when needed, so this reuses the JDK
 * implementation and keeps only the parts that carry format meaning:
 *
 *  - entry naming (`settings.json`, `database/kelivo.db`, `upload/…`)
 *  - forward-slash separators regardless of platform
 *  - duplicate-entry collision detection
 *  - per-entry size + SHA-256 recorded in the manifest
 *  - extraction bounds and path-traversal defence
 *
 * Entry order is significant for reproducibility: settings, database, then the
 * asset directories in a fixed order, with the manifest last (it must be
 * written after every other entry so it can describe them).
 */
object BackupArchiveCodec {

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Directories packed when `includeFiles` is set, in write order.
     *
     * `videos` 是自研的「生成视频」产出目录（`filesDir/videos/`）—— 不加进来的话，
     * 归档恢复后对话里的视频卡会全部变成「文件不存在」（消息里的 FilePart 指着它）。
     */
    val ASSET_ROOTS = listOf("upload", "avatars", "images", "fonts", "videos")

    data class PackResult(val entries: Map<String, EntryMetadata>)

    sealed interface Progress {
        data class Entry(val name: String, val bytes: Long) : Progress
    }

    /**
     * Writes [archive] with the given [settingsFile], optional [databaseFile],
     * the asset directories under [assetDirs] and a trailing manifest built by
     * [manifestFor].
     *
     * [manifestFor] receives the completed entry table so the manifest can
     * describe it (including its own entry, matching the Dart writer which adds
     * the manifest entry before serialising the manifest).
     *
     * @throws IllegalStateException on entry-name collisions.
     */
    fun pack(
        archive: File,
        settingsFile: File,
        databaseFile: File?,
        assetDirs: Map<String, File>,
        includeFiles: Boolean,
        manifestFor: (Map<String, EntryMetadata>) -> String,
        onProgress: (Progress) -> Unit = {},
        onEntryWritten: (String, Long) -> Unit = { _, _ -> },
    ): PackResult {
        require(settingsFile.isFile) { "settings.json 源文件不存在: ${settingsFile.path}" }
        if (databaseFile != null) {
            require(databaseFile.isFile) { "快照数据库不存在: ${databaseFile.path}" }
        }

        val entries = LinkedHashMap<String, EntryMetadata>()
        val collisions = ZipEntryNames.CollisionLedger()

        archive.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(archive.outputStream(), BUFFER_SIZE)).use { zip ->
            zip.setLevel(6)

            writeEntry(zip, settingsFile, BackupManifestCodec.ENTRY_SETTINGS, entries, collisions, onProgress, onEntryWritten)

            if (databaseFile != null) {
                writeEntry(zip, databaseFile, BackupManifestCodec.ENTRY_DATABASE, entries, collisions, onProgress, onEntryWritten)
            }

            if (includeFiles) {
                for (root in ASSET_ROOTS) {
                    val dir = assetDirs[root] ?: continue
                    if (!dir.isDirectory) continue
                    writeDirectory(zip, dir, root, entries, collisions, onProgress, onEntryWritten)
                }
            }

            // The manifest is written last but must still describe itself, so
            // the entry table is handed over after everything else is known.
            val manifestJson = manifestFor(entries)
            val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
            val manifestEntry = manifestEntry(BackupManifestCodec.ENTRY_MANIFEST)
            zip.putNextEntry(manifestEntry)
            zip.write(manifestBytes)
            zip.closeEntry()
            entries[BackupManifestCodec.ENTRY_MANIFEST] = EntryMetadata(
                bytes = manifestBytes.size.toLong(),
                sha256 = sha256Of(manifestBytes),
            )
            onEntryWritten(BackupManifestCodec.ENTRY_MANIFEST, manifestBytes.size.toLong())
        }
        return PackResult(entries)
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
        entries: MutableMap<String, EntryMetadata>,
        collisions: ZipEntryNames.CollisionLedger,
        onProgress: (Progress) -> Unit,
        onEntryWritten: (String, Long) -> Unit,
    ) {
        val canonical = canonicalName(entryName)
        collisions.claim(canonical)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        zip.putNextEntry(manifestEntry(canonical))
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
                onProgress(Progress.Entry(canonical, written))
            }
        }
        zip.closeEntry()
        entries[canonical] = EntryMetadata(bytes = written, sha256 = digest.digest().toHex())
        onEntryWritten(canonical, written)
    }

    private fun writeDirectory(
        zip: ZipOutputStream,
        dir: File,
        zipPrefix: String,
        entries: MutableMap<String, EntryMetadata>,
        collisions: ZipEntryNames.CollisionLedger,
        onProgress: (Progress) -> Unit,
        onEntryWritten: (String, Long) -> Unit,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                writeDirectory(zip, child, "$zipPrefix/${child.name}", entries, collisions, onProgress, onEntryWritten)
            } else if (child.isFile) {
                // ZIP entries always use forward slashes, on every platform.
                writeEntry(zip, child, "$zipPrefix/${child.name}", entries, collisions, onProgress, onEntryWritten)
            }
        }
    }

    /** `_zipEntryName`: forward slashes, no leading slash. */
    fun canonicalName(name: String): String =
        name.replace('\\', '/').removePrefix("/").let { raw ->
            var s = raw
            while (s.startsWith("/")) s = s.substring(1)
            s
        }

    private fun manifestEntry(name: String): ZipEntry =
        ZipEntry(name).apply {
            // Fixed timestamp keeps packs byte-comparable across runs, which is
            // what lets `verifyPacked` compare digests meaningfully.
            setTime(0L)
        }

    // ── Extraction ──────────────────────────────────────────────────────────

    /**
     * Extracts [archive] into [targetDir], enforcing manifest-declared sizes
     * and the archive-wide budget. Only entries declared in [manifest] are
     * extracted; anything else is rejected rather than silently ignored, so a
     * tampered archive cannot smuggle extra files past verification.
     *
     * Returns the list of extracted entry names.
     */
    fun extract(
        archive: File,
        targetDir: File,
        manifest: BackupManifest,
        onProgress: (Progress) -> Unit = {},
    ): List<String> {
        val budget = ExtractionBudget()
        val extracted = ArrayList<String>()
        targetDir.mkdirs()

        ZipInputStream(archive.inputStream().buffered(BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = ZipEntryNames.validate(entry.name)
                ZipEntryNames.validateRoot(name)

                // The manifest describes every other entry but not itself (the
                // Dart writer serialises it before adding its own entry), so it
                // is read rather than extracted.
                if (name == BackupManifestCodec.ENTRY_MANIFEST) {
                    zip.closeEntry()
                    continue
                }

                budget.beginEntry()

                val declared = manifest.entries[name]
                requireNotNull(declared) { "备份包含未声明的条目: $name" }
                onProgress(Progress.Entry(name, 0))

                val entryBudget = BoundedEntryBudget(name, declared.bytes, budget)
                val digest = MessageDigest.getInstance("SHA-256")
                val outFile = File(targetDir, name)
                outFile.parentFile?.mkdirs()
                DigestOutputStream(outFile.outputStream().buffered(BUFFER_SIZE), digest).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        entryBudget.reserve(read.toLong())
                        written += read
                        onProgress(Progress.Entry(name, written))
                    }
                }
                entryBudget.verifyComplete()
                val actualDigest = digest.digest().toHex()
                check(actualDigest.equals(declared.sha256, ignoreCase = true)) {
                    "备份条目 $name 校验失败：期望 ${declared.sha256}，实际 $actualDigest"
                }
                extracted += name
                zip.closeEntry()
            }
        }
        return extracted
    }

    /**
     * Re-reads [archive] and confirms every entry still hashes to the value the
     * manifest recorded — the equivalent of `_verifyPackedBackupSync`. Run
     * after packing so a truncated write is caught before the file is offered
     * to the user as a valid backup.
     */
    fun verifyPacked(archive: File, expected: Map<String, EntryMetadata>) {
        ZipInputStream(archive.inputStream().buffered(BUFFER_SIZE)).use { zip ->
            var seen = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = ZipEntryNames.validate(entry.name)
                val want = expected[name] ?: continue
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    written += read
                }
                check(written == want.bytes) {
                    "备份条目 $name 大小不符：期望 ${want.bytes}，实际 $written"
                }
                val actual = digest.digest().toHex()
                check(actual.equals(want.sha256, ignoreCase = true)) {
                    "备份条目 $name 摘要不符"
                }
                seen += 1
                zip.closeEntry()
            }
            check(seen == expected.size) {
                "备份条目数不符：期望 ${expected.size}，实际 $seen"
            }
        }
    }

    /** Reads `manifest.json` without extracting the rest of the archive. */
    fun readManifest(archive: File): BackupManifest {
        val bytes = readEntry(archive, BackupManifestCodec.ENTRY_MANIFEST)
            ?: error("备份缺少 manifest.json")
        check(bytes.size <= BackupManifestCodec.MAX_MANIFEST_BYTES) { "manifest 过大" }
        return BackupManifestCodec.decode(bytes.toString(Charsets.UTF_8))
    }

    /** Reads one entry's bytes, or null when absent. Used for `settings.json`. */
    fun readEntry(archive: File, entryName: String): ByteArray? {
        ZipInputStream(archive.inputStream().buffered(BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = ZipEntryNames.validate(entry.name)
                if (name == entryName) {
                    return zip.readBytesBounded()
                }
                zip.closeEntry()
            }
        }
        return null
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
