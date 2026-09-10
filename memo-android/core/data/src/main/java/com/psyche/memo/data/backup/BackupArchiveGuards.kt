package com.psyche.memo.data.backup

/**
 * Restore-side extraction budget, ported from `_ExtractionBudget` /
 * `_BoundedOutputFileStream` (`data_sync.dart` L3359-3432).
 *
 * A malicious or corrupt archive must not be able to fill the disk: every
 * write reserves against per-entry and total caps before it happens, so the
 * failure surfaces while there is still room to clean up.
 */
internal class ExtractionBudget {
    private var writtenBytes = 0L
    private var entryCount = 0

    fun beginEntry(): Unit {
        entryCount += 1
        check(entryCount <= BackupManifestCodec.MAX_RESTORE_ENTRIES) {
            "备份条目数超过上限（${BackupManifestCodec.MAX_RESTORE_ENTRIES}）"
        }
    }

    fun reserve(bytes: Long) {
        require(bytes >= 0) { "负数预留: $bytes" }
        writtenBytes += bytes
        check(writtenBytes <= BackupManifestCodec.MAX_RESTORE_TOTAL_BYTES) {
            "备份解压总量超过上限（${BackupManifestCodec.MAX_RESTORE_TOTAL_BYTES} 字节）"
        }
    }

    val totalWritten: Long get() = writtenBytes
    val entriesSeen: Int get() = entryCount
}

/**
 * Per-entry write limiter: a single archive entry may not exceed the entry cap,
 * and its declared size must match what was actually written.
 */
internal class BoundedEntryBudget(
    private val entryName: String,
    private val expectedBytes: Long?,
    private val total: ExtractionBudget,
) {
    private var entryBytes = 0L

    fun reserve(chunk: Long) {
        entryBytes += chunk
        check(entryBytes <= BackupManifestCodec.MAX_RESTORE_ENTRY_BYTES) {
            "备份条目 $entryName 超过单条目上限"
        }
        total.reserve(chunk)
    }

    /** Called after the entry is fully written. */
    fun verifyComplete() {
        if (expectedBytes != null && expectedBytes >= 0) {
            check(entryBytes == expectedBytes) {
                "备份条目 $entryName 大小不符：manifest 声明 $expectedBytes，实际 $entryBytes"
            }
        }
    }

    val bytesWritten: Long get() = entryBytes
}

/**
 * Zip entry-name normalisation and traversal defence, ported from
 * `_validatedZipEntryName` / `_validateZipPathPrefixes` (`data_sync.dart`
 * L1334-1368).
 */
internal object ZipEntryNames {

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

    /**
     * Entry-name collision ledger.
     *
     * `_addFileToZip` refuses to write a second entry under a name that was
     * already used, because the manifest's `entries` map is keyed by name: a
     * silent overwrite would leave the archive describing data it no longer
     * contains. Case-insensitive, matching the Dart `collisionKeys` set, since
     * Windows and macOS filesystems fold case.
     */
    class CollisionLedger {
        private val seen = HashSet<String>()

        fun claim(canonicalName: String) {
            check(seen.add(canonicalName.lowercase())) {
                "备份条目重名: $canonicalName"
            }
        }

        val count: Int get() = seen.size
    }

    /**
     * Rejects absolute paths, drive letters, `..` traversal and backslash
     * separators. Returns the normalised POSIX-style relative name.
     */
    fun validate(rawName: String): String {
        require(rawName.isNotEmpty()) { "备份条目名为空" }
        require(!rawName.contains('\u0000')) { "备份条目名含 NUL" }
        val unified = rawName.replace('\\', '/')
        require(!unified.startsWith("/")) { "备份条目为绝对路径: $rawName" }
        require(!DRIVE_PREFIX.containsMatchIn(unified)) { "备份条目含盘符: $rawName" }
        val segments = unified.split('/')
        require(segments.none { it == ".." }) { "备份条目越界: $rawName" }
        val cleaned = segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        require(cleaned.isNotEmpty()) { "备份条目名为空: $rawName" }
        return cleaned
    }

    /**
     * `_validateZipPathPrefixes`: entries must live under one of the known
     * top-level roots, so a rename in one build cannot silently write into an
     * unrelated directory in another.
     */
    val KNOWN_ROOTS: Set<String> = setOf(
        "upload", "avatars", "images", "fonts", "database",
    )

    fun validateRoot(entryName: String) {
        val first = entryName.substringBefore('/')
        if (first == BackupManifestCodec.ENTRY_MANIFEST) return
        if (first == BackupManifestCodec.ENTRY_SETTINGS) return
        require(first in KNOWN_ROOTS) { "备份条目根目录未知: $entryName" }
    }
}
