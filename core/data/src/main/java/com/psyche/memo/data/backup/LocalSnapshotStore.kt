package com.psyche.memo.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.time.Instant

/** Why a local copy exists. Shown to the user, and consulted by pruning. */
enum class LocalSnapshotOrigin {
    /** Taken by the schedule. */
    AUTOMATIC,

    /** Taken because the user asked for one. */
    MANUAL,

    /** Taken immediately before a restore, so the restore itself is undoable. */
    BEFORE_RESTORE;

    val wire: String get() = name.lowercase()
}

/** `<appData>/snapshots/` naming (local_snapshot_schedule.dart `LocalSnapshotPaths`). */
object LocalSnapshotPaths {
    /** Kept inside the app data directory rather than somewhere hidden: a copy
     * sitting there is one more reason an unattended rebuild can never fire. */
    const val DIRECTORY_NAME = "snapshots"

    // Brandified: upstream writes `kelivo-snapshot-`; the store owns these names
    // and nothing else reads them.
    const val FILE_PREFIX = "memo-snapshot-"
    const val FILE_SUFFIX = ".zip"
    const val METADATA_SUFFIX = ".json"

    /** Written first, renamed into place only once durable. Anything still
     * carrying this prefix is the debris of an interrupted attempt. */
    const val TEMPORARY_PREFIX = ".incomplete-"

    fun directoryIn(appDataDirectory: File): File = File(appDataDirectory, DIRECTORY_NAME)

    /**
     * Sorts chronologically as text, which keeps listing free of parsing.
     * Nanosecond resolution: two copies taken in the same millisecond must not
     * collide on the same file name.
     */
    fun fileNameFor(createdAtUtc: Instant): String {
        val nanos = createdAtUtc.epochSecond * 1_000_000_000L + createdAtUtc.nano
        return "$FILE_PREFIX${nanos.toString().padStart(19, '0')}$FILE_SUFFIX"
    }

    fun createdAtFromFileName(fileName: String): Instant? {
        if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null
        val stamp = fileName.substring(FILE_PREFIX.length, fileName.length - FILE_SUFFIX.length)
        val nanos = stamp.toLongOrNull() ?: return null
        if (nanos < 0) return null
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)
    }
}

/** One local copy on disk: a standard backup archive plus what the app knows
 * about it that the archive itself does not record. */
data class LocalSnapshotEntry(
    val file: File,
    val createdAt: Instant,
    val bytes: Long,
    val origin: LocalSnapshotOrigin,
    val pinned: Boolean,
    val conversationCount: Int,
    val messageCount: Int,
    val appVersion: String? = null,
) {
    val id: String get() = file.name

    /**
     * A copy taken before a restore is the only way back from that restore, so
     * it is created already pinned. The flag is not re-derived from [origin]:
     * doing that would make the pin permanent and the unpin control inert, and
     * every restore would leave behind one more copy retention may not reclaim.
     */
    val retention: SnapshotRetentionEntry
        get() = SnapshotRetentionEntry(
            id = id,
            createdAt = createdAt,
            bytes = bytes,
            messageCount = messageCount,
            pinned = pinned,
        )
}

/**
 * Owns `<appData>/snapshots/` (local_snapshot_store.dart).
 *
 * Publishing is write-then-rename, and pruning only ever runs after the new
 * copy is durable and readable: the whole point of the directory is to hold the
 * copy that survives when the live database does not, so a half-written one
 * must never be able to displace a whole one.
 */
class LocalSnapshotStore(private val appDataDirectory: File) {

    val directory: File get() = LocalSnapshotPaths.directoryIn(appDataDirectory)

    fun ensureDirectory(): File {
        val target = directory
        if (!target.isDirectory) target.mkdirs()
        return target
    }

    fun list(): List<LocalSnapshotEntry> {
        val target = directory
        if (!target.isDirectory) return emptyList()
        val entries = target.listFiles()?.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val createdAt = LocalSnapshotPaths.createdAtFromFileName(file.name) ?: return@mapNotNull null
            readEntry(file, createdAt)
        }.orEmpty()
        return entries.sortedByDescending { it.createdAt }
    }

    fun totalBytes(): Long = list().sumOf { it.bytes }

    fun byId(id: String): LocalSnapshotEntry? = list().firstOrNull { it.id == id }

    /**
     * Moves a freshly packed archive into the store and publishes it.
     *
     * [prepared] is consumed: on success it no longer exists at its old path,
     * and on failure it is left alone for the caller to clean up.
     */
    fun publish(
        prepared: File,
        createdAtUtc: Instant,
        origin: LocalSnapshotOrigin,
        conversationCount: Int,
        messageCount: Int,
        appVersion: String? = null,
        pinned: Boolean = false,
    ): LocalSnapshotEntry {
        val target = ensureDirectory()
        val fileName = LocalSnapshotPaths.fileNameFor(createdAtUtc)
        val staged = File(target, LocalSnapshotPaths.TEMPORARY_PREFIX + fileName)
        val published = File(target, fileName)
        check(!published.exists()) { "local_snapshot_exists" }

        staged.delete()
        moveInto(prepared, staged)
        try {
            val bytes = staged.length()
            check(bytes > 0) { "local_snapshot_empty" }

            // The sidecar goes down before the archive takes its final name, so
            // a published archive always has its metadata beside it.
            writeMetadata(
                fileName = fileName,
                createdAtUtc = createdAtUtc,
                origin = origin,
                pinned = pinned,
                bytes = bytes,
                conversationCount = conversationCount,
                messageCount = messageCount,
                appVersion = appVersion,
            )
            check(staged.renameTo(published)) { "local_snapshot_publish_failed" }
            return LocalSnapshotEntry(
                file = published,
                createdAt = createdAtUtc,
                bytes = bytes,
                origin = origin,
                pinned = pinned,
                conversationCount = conversationCount,
                messageCount = messageCount,
                appVersion = appVersion,
            )
        } catch (e: Exception) {
            staged.delete()
            metadataFileFor(fileName).delete()
            throw e
        }
    }

    fun delete(id: String) {
        require(LocalSnapshotPaths.createdAtFromFileName(id) != null) { "invalid snapshot id: $id" }
        val archive = File(directory, id)
        // Metadata first: a sidecar left beside a missing archive is invisible
        // to [list], so failing the other way round would strand it forever.
        metadataFileFor(id).delete()
        archive.delete()
        check(!archive.exists()) { "local_snapshot_not_deleted" }
    }

    fun setPinned(id: String, pinned: Boolean) {
        val entry = byId(id) ?: return
        writeMetadata(
            fileName = entry.id,
            createdAtUtc = entry.createdAt,
            origin = entry.origin,
            pinned = pinned,
            bytes = entry.bytes,
            conversationCount = entry.conversationCount,
            messageCount = entry.messageCount,
            appVersion = entry.appVersion,
        )
    }

    /** Applies [policy] and removes what it selects. Returns what went. */
    fun prune(policy: LocalSnapshotRetentionPolicy, now: Instant = Instant.now()): List<LocalSnapshotEntry> {
        val entries = list()
        if (entries.size <= 1) return emptyList()
        val byId = entries.associateBy { it.id }
        val selected = policy.selectForDeletion(entries.map { it.retention }, now)
        val removed = mutableListOf<LocalSnapshotEntry>()
        for (candidate in selected) {
            val entry = byId[candidate.id] ?: continue
            try {
                delete(entry.id)
                removed.add(entry)
            } catch (_: Exception) {
                // One stubborn file must not stop the rest from being trimmed.
            }
        }
        return removed
    }

    /**
     * Clears the debris of an attempt that was interrupted before publishing:
     * a staged archive that never got its final name, and a sidecar whose
     * archive never arrived.
     */
    fun sweepIncomplete() {
        val target = directory
        if (!target.isDirectory) return
        for (file in target.listFiles().orEmpty()) {
            if (!file.isFile) continue
            if (file.name.startsWith(LocalSnapshotPaths.TEMPORARY_PREFIX)) {
                file.delete()
                continue
            }
            if (!file.name.endsWith(LocalSnapshotPaths.METADATA_SUFFIX)) continue
            val archiveName = file.name.dropLast(LocalSnapshotPaths.METADATA_SUFFIX.length)
            if (LocalSnapshotPaths.createdAtFromFileName(archiveName) == null) continue
            if (!File(target, archiveName).exists()) file.delete()
        }
    }

    private fun metadataFileFor(fileName: String): File =
        File(directory, fileName + LocalSnapshotPaths.METADATA_SUFFIX)

    private fun writeMetadata(
        fileName: String,
        createdAtUtc: Instant,
        origin: LocalSnapshotOrigin,
        pinned: Boolean,
        bytes: Long,
        conversationCount: Int,
        messageCount: Int,
        appVersion: String?,
    ) {
        val payload = buildString {
            append("{\"version\":").append(METADATA_VERSION)
            append(",\"createdAtUtc\":\"").append(createdAtUtc.toString()).append('"')
            append(",\"origin\":\"").append(origin.wire).append('"')
            append(",\"pinned\":").append(pinned)
            append(",\"bytes\":").append(bytes)
            append(",\"conversationCount\":").append(conversationCount)
            append(",\"messageCount\":").append(messageCount)
            if (appVersion != null) append(",\"appVersion\":\"").append(appVersion).append('"')
            append('}')
        }
        val file = metadataFileFor(fileName)
        file.outputStream().use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    /**
     * Reads one archive's metadata. A missing or unreadable sidecar does not
     * hide the archive — it is still the user's data and still restorable. It
     * degrades to "unknown content", which pruning then treats as worth keeping.
     */
    private fun readEntry(file: File, createdAt: Instant): LocalSnapshotEntry? {
        val bytes = file.length()
        if (bytes <= 0) return null

        val metadata = runCatching {
            val sidecar = metadataFileFor(file.name)
            if (!sidecar.exists()) return@runCatching null
            Json.parseToJsonElement(sidecar.readText()) as? JsonObject
        }.getOrNull()

        val originName = (metadata?.get("origin") as? JsonPrimitive)?.content
        return LocalSnapshotEntry(
            file = file,
            createdAt = createdAt,
            bytes = bytes,
            origin = LocalSnapshotOrigin.entries.firstOrNull { it.wire == originName }
                ?: LocalSnapshotOrigin.AUTOMATIC,
            pinned = (metadata?.get("pinned") as? JsonPrimitive)?.booleanOrNull == true,
            conversationCount = asCount(metadata?.get("conversationCount")),
            messageCount = asCount(metadata?.get("messageCount")),
            appVersion = (metadata?.get("appVersion") as? JsonPrimitive)?.content,
        )
    }

    private fun moveInto(source: File, target: File) {
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    companion object {
        const val METADATA_VERSION = 1

        /**
         * Unknown counts read as 1, not 0: zero is the value that lets pruning
         * treat a copy as empty and therefore expendable, and "we lost the
         * sidecar" is not evidence the archive is empty.
         */
        fun asCount(value: kotlinx.serialization.json.JsonElement?): Int =
            (value as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: 1
    }
}
