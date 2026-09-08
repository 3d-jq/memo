package com.psyche.memo.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Box
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.User
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// StorageUsageService port (lib/core/services/storage/storage_usage_service.dart).
// Path mapping: appData = filesDir, chat database family = databases/memo.db*,
// app cache = filesDir/cache, avatar cache = filesDir/cache/avatars, system
// cache = cacheDir. Legacy Hive artifacts / restore traces / displaced
// databases exist only when present, and the always-visible rule follows
// _isAlwaysVisibleCategory.
// ---------------------------------------------------------------------------

enum class StorageCategoryKey {
    IMAGES, FILES, CHAT_DATA, LEGACY_CHAT_DATA, RESTORE_TRACES,
    DISPLACED_DATABASES, LOCAL_SNAPSHOTS, ASSISTANT_DATA, CACHE, LOGS, OTHER,
}

data class StorageStats(val fileCount: Int = 0, val bytes: Long = 0)

data class StorageSubcategory(val id: String, val stats: StorageStats, val path: String)

data class StorageCategory(val key: StorageCategoryKey, val stats: StorageStats, val subcategories: List<StorageSubcategory> = emptyList())

data class StorageReport(
    val totalBytes: Long,
    val totalFiles: Int,
    val clearable: StorageStats,
    val categories: List<StorageCategory>,
)

data class StorageFileEntry(
    val path: String,
    val name: String,
    val bytes: Long,
    val modifiedAt: Long,
)

object StorageUsage {
    private const val DATABASE_NAME = "memo.db"
    private const val DISPLACED_PREFIX = "${DATABASE_NAME}.displaced-"
    private val hiveArtifacts = setOf("conversations.hive", "messages.hive", "tool_events_v1.hive")

    private val categoryOrder = listOf(
        StorageCategoryKey.IMAGES,
        StorageCategoryKey.FILES,
        StorageCategoryKey.CHAT_DATA,
        StorageCategoryKey.LEGACY_CHAT_DATA,
        StorageCategoryKey.RESTORE_TRACES,
        StorageCategoryKey.DISPLACED_DATABASES,
        StorageCategoryKey.LOCAL_SNAPSHOTS,
        StorageCategoryKey.ASSISTANT_DATA,
        StorageCategoryKey.CACHE,
        StorageCategoryKey.LOGS,
        StorageCategoryKey.OTHER,
    )

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".heic") ||
            lower.endsWith(".heif") || lower.endsWith(".bmp") || lower.endsWith(".ico")
    }

    private class MutableStats {
        var count = 0
        var bytes = 0L
        fun add(b: Long) {
            count += 1
            bytes += b
        }

        fun stats() = StorageStats(count, bytes)
    }

    private fun dbFamilySub(name: String): String? = when (name) {
        DATABASE_NAME -> "sqlite_database"
        "$DATABASE_NAME-wal" -> "sqlite_wal"
        "$DATABASE_NAME-shm" -> "sqlite_shm"
        else -> null
    }

    private fun walk(dir: File, onFile: (File) -> Unit) {
        if (!dir.exists()) return
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            val children = runCatching { f.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.add(child) else onFile(child)
            }
        }
    }

    fun computeReport(context: Context): StorageReport {
        val root = context.filesDir
        val dbDir = java.util.Objects.requireNonNull(context.getDatabasePath(DATABASE_NAME)).parentFile
        val systemCache = context.cacheDir

        val byCat = StorageCategoryKey.entries.associateWith { MutableStats() }
        val chatSubs = mutableMapOf(
            "sqlite_database" to MutableStats(),
            "sqlite_wal" to MutableStats(),
            "sqlite_shm" to MutableStats(),
        )
        val assistantSubs = mutableMapOf("avatars" to MutableStats())
        val otherSubs = mutableMapOf(
            "fonts" to MutableStats(),
            "local_models" to MutableStats(),
            "app" to MutableStats(),
        )
        val cacheSubs = mutableMapOf(
            "avatar_cache" to MutableStats(),
            "other_cache" to MutableStats(),
            "system_cache" to MutableStats(),
        )
        val logsSubs = mutableMapOf(
            "context_logs" to MutableStats(),
            "request_logs" to MutableStats(),
            "flutter_logs" to MutableStats(),
            "other_logs" to MutableStats(),
        )
        var totalBytes = 0L
        var totalFiles = 0

        fun classify(file: File, top: String, depth: Int, relParts: List<String>) {
            val bytes = runCatching { file.length() }.getOrDefault(0L)
            totalFiles += 1
            totalBytes += bytes
            when (top) {
                "snapshots" -> byCat.getValue(StorageCategoryKey.LOCAL_SNAPSHOTS).add(bytes)
                "upload" -> {
                    if (isImage(file.name)) {
                        byCat.getValue(StorageCategoryKey.IMAGES).add(bytes)
                    } else {
                        byCat.getValue(StorageCategoryKey.FILES).add(bytes)
                    }
                }
                "avatars" -> {
                    byCat.getValue(StorageCategoryKey.ASSISTANT_DATA).add(bytes)
                    assistantSubs.getValue("avatars").add(bytes)
                }
                "fonts" -> {
                    byCat.getValue(StorageCategoryKey.OTHER).add(bytes)
                    otherSubs.getValue("fonts").add(bytes)
                }
                "asr_models" -> {
                    byCat.getValue(StorageCategoryKey.OTHER).add(bytes)
                    otherSubs.getValue("local_models").add(bytes)
                }
                "images" -> byCat.getValue(StorageCategoryKey.IMAGES).add(bytes)
                "cache" -> {
                    byCat.getValue(StorageCategoryKey.CACHE).add(bytes)
                    if (relParts.size >= 2 && relParts[1].lowercase() == "avatars") {
                        cacheSubs.getValue("avatar_cache").add(bytes)
                    } else {
                        cacheSubs.getValue("other_cache").add(bytes)
                    }
                }
                "logs" -> {
                    byCat.getValue(StorageCategoryKey.LOGS).add(bytes)
                    val name = file.name.lowercase()
                    when {
                        name.startsWith("context_logs") -> logsSubs.getValue("context_logs").add(bytes)
                        name.startsWith("flutter_logs") -> logsSubs.getValue("flutter_logs").add(bytes)
                        name.startsWith("logs") -> logsSubs.getValue("request_logs").add(bytes)
                        else -> logsSubs.getValue("other_logs").add(bytes)
                    }
                }
                else -> {
                    byCat.getValue(StorageCategoryKey.OTHER).add(bytes)
                    otherSubs.getValue("app").add(bytes)
                }
            }
        }

        // filesDir walk (appData): root-level files → other/app; legacy hive
        // artifacts (absent on this build, mirrored for parity).
        walk(root) { file ->
            val rel = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault("")
            val parts = rel.split('/')
            val bytes = runCatching { file.length() }.getOrDefault(0L)
            totalFiles += 1
            totalBytes += bytes
            when {
                parts.size == 1 -> {
                    val name = parts.first()
                    val chatSub = dbFamilySub(name)
                    when {
                        name.startsWith(DISPLACED_PREFIX) ->
                            byCat.getValue(StorageCategoryKey.DISPLACED_DATABASES).add(bytes)
                        chatSub != null -> {
                            byCat.getValue(StorageCategoryKey.CHAT_DATA).add(bytes)
                            chatSubs.getValue(chatSub).add(bytes)
                        }
                        name in hiveArtifacts -> {
                            byCat.getValue(StorageCategoryKey.LEGACY_CHAT_DATA).add(bytes)
                        }
                        else -> {
                            byCat.getValue(StorageCategoryKey.OTHER).add(bytes)
                            otherSubs.getValue("app").add(bytes)
                        }
                    }
                }
                else -> classify(file, parts.first().lowercase(), parts.size, parts)
            }
        }

        // Database directory: root-level memo.db family + displaced copies.
        if (dbDir != null && dbDir.exists()) {
            dbDir.listFiles()?.forEach { file ->
                if (file.isDirectory) return@forEach
                val name = file.name
                val bytes = runCatching { file.length() }.getOrDefault(0L)
                if (name.startsWith(DISPLACED_PREFIX)) {
                    byCat.getValue(StorageCategoryKey.DISPLACED_DATABASES).add(bytes)
                    totalFiles += 1
                    totalBytes += bytes
                } else {
                    val chatSub = dbFamilySub(name)
                    if (chatSub != null) {
                        byCat.getValue(StorageCategoryKey.CHAT_DATA).add(bytes)
                        chatSubs.getValue(chatSub).add(bytes)
                        totalFiles += 1
                        totalBytes += bytes
                    }
                }
            }
        }

        // Platform cache directory (system cache subcategory).
        walk(systemCache) { file ->
            val bytes = runCatching { file.length() }.getOrDefault(0L)
            totalFiles += 1
            totalBytes += bytes
            byCat.getValue(StorageCategoryKey.CACHE).add(bytes)
            cacheSubs.getValue("system_cache").add(bytes)
        }

        val clearable = StorageStats(
            fileCount = byCat.getValue(StorageCategoryKey.CACHE).count +
                byCat.getValue(StorageCategoryKey.LOGS).count +
                byCat.getValue(StorageCategoryKey.LEGACY_CHAT_DATA).count +
                byCat.getValue(StorageCategoryKey.RESTORE_TRACES).count,
            bytes = byCat.getValue(StorageCategoryKey.CACHE).bytes +
                byCat.getValue(StorageCategoryKey.LOGS).bytes +
                byCat.getValue(StorageCategoryKey.LEGACY_CHAT_DATA).bytes +
                byCat.getValue(StorageCategoryKey.RESTORE_TRACES).bytes,
        )

        val categories = buildList {
            add(StorageCategory(StorageCategoryKey.IMAGES, byCat.getValue(StorageCategoryKey.IMAGES).stats()))
            add(StorageCategory(StorageCategoryKey.FILES, byCat.getValue(StorageCategoryKey.FILES).stats()))
            add(
                StorageCategory(
                    StorageCategoryKey.CHAT_DATA,
                    byCat.getValue(StorageCategoryKey.CHAT_DATA).stats(),
                    chatSubs.filter { it.value.bytes > 0 || it.value.count > 0 }.map { (id, s) ->
                        StorageSubcategory(id, s.stats(), File(dbDir, id.toDbFileName()).absolutePath)
                    },
                ),
            )
            if (byCat.getValue(StorageCategoryKey.LEGACY_CHAT_DATA).count > 0) {
                add(
                    StorageCategory(
                        StorageCategoryKey.LEGACY_CHAT_DATA,
                        byCat.getValue(StorageCategoryKey.LEGACY_CHAT_DATA).stats(),
                        hiveArtifacts.filter { File(root, it).exists() }.map {
                            StorageSubcategory(it, StorageStats(1, File(root, it).length()), File(root, it).absolutePath)
                        },
                    ),
                )
            }
            if (byCat.getValue(StorageCategoryKey.RESTORE_TRACES).count > 0) {
                val dir = File(File(root, ".memo_restore"), "completed")
                add(
                    StorageCategory(
                        StorageCategoryKey.RESTORE_TRACES,
                        byCat.getValue(StorageCategoryKey.RESTORE_TRACES).stats(),
                        listOf(StorageSubcategory("completed_restore_runs", byCat.getValue(StorageCategoryKey.RESTORE_TRACES).stats(), dir.absolutePath)),
                    ),
                )
            }
            if (byCat.getValue(StorageCategoryKey.DISPLACED_DATABASES).count > 0) {
                add(
                    StorageCategory(
                        StorageCategoryKey.DISPLACED_DATABASES,
                        byCat.getValue(StorageCategoryKey.DISPLACED_DATABASES).stats(),
                        listOf(StorageSubcategory("displaced_databases", byCat.getValue(StorageCategoryKey.DISPLACED_DATABASES).stats(), (dbDir?.absolutePath ?: root.absolutePath))),
                    ),
                )
            }
            if (byCat.getValue(StorageCategoryKey.LOCAL_SNAPSHOTS).count > 0) {
                add(
                    StorageCategory(
                        StorageCategoryKey.LOCAL_SNAPSHOTS,
                        byCat.getValue(StorageCategoryKey.LOCAL_SNAPSHOTS).stats(),
                        listOf(StorageSubcategory("local_snapshots", byCat.getValue(StorageCategoryKey.LOCAL_SNAPSHOTS).stats(), File(root, "snapshots").absolutePath)),
                    ),
                )
            }
            add(
                StorageCategory(
                    StorageCategoryKey.ASSISTANT_DATA,
                    byCat.getValue(StorageCategoryKey.ASSISTANT_DATA).stats(),
                    listOf(StorageSubcategory("avatars", assistantSubs.getValue("avatars").stats(), File(root, "avatars").absolutePath)),
                ),
            )
            add(
                StorageCategory(
                    StorageCategoryKey.CACHE,
                    byCat.getValue(StorageCategoryKey.CACHE).stats(),
                    buildList {
                        add(StorageSubcategory("avatar_cache", cacheSubs.getValue("avatar_cache").stats(), File(File(root, "cache"), "avatars").absolutePath))
                        add(StorageSubcategory("other_cache", cacheSubs.getValue("other_cache").stats(), File(root, "cache").absolutePath))
                        val sys = cacheSubs.getValue("system_cache")
                        if (sys.bytes > 0 || sys.count > 0) {
                            add(StorageSubcategory("system_cache", sys.stats(), systemCache.absolutePath))
                        }
                    },
                ),
            )
            add(
                StorageCategory(
                    StorageCategoryKey.LOGS,
                    byCat.getValue(StorageCategoryKey.LOGS).stats(),
                    buildList {
                        val logsDir = File(root, "logs")
                        add(StorageSubcategory("context_logs", logsSubs.getValue("context_logs").stats(), logsDir.absolutePath))
                        add(StorageSubcategory("request_logs", logsSubs.getValue("request_logs").stats(), logsDir.absolutePath))
                        add(StorageSubcategory("flutter_logs", logsSubs.getValue("flutter_logs").stats(), logsDir.absolutePath))
                        val other = logsSubs.getValue("other_logs")
                        if (other.bytes > 0 || other.count > 0) {
                            add(StorageSubcategory("other_logs", other.stats(), logsDir.absolutePath))
                        }
                    },
                ),
            )
            add(
                StorageCategory(
                    StorageCategoryKey.OTHER,
                    byCat.getValue(StorageCategoryKey.OTHER).stats(),
                    buildList {
                        val fonts = otherSubs.getValue("fonts")
                        if (fonts.count > 0) add(StorageSubcategory("fonts", fonts.stats(), File(root, "fonts").absolutePath))
                        val models = otherSubs.getValue("local_models")
                        if (models.count > 0) add(StorageSubcategory("local_models", models.stats(), File(root, "asr_models").absolutePath))
                        val app = otherSubs.getValue("app")
                        if (app.count > 0) add(StorageSubcategory("app", app.stats(), root.absolutePath))
                    },
                ),
            )
        }.sortedBy { categoryOrder.indexOf(it.key) }

        return StorageReport(totalBytes, totalFiles, clearable, categories)
    }

    private fun String.toDbFileName(): String = when (this) {
        "sqlite_wal" -> "$DATABASE_NAME-wal"
        "sqlite_shm" -> "$DATABASE_NAME-shm"
        else -> DATABASE_NAME
    }

    private fun deleteContents(dir: File?) {
        if (dir == null || !dir.exists()) return
        val stack = ArrayDeque<File>()
        stack.add(dir)
        val dirs = ArrayList<File>()
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            val children = runCatching { f.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    dirs.add(child)
                    stack.add(child)
                } else {
                    runCatching {
                        if (!child.delete()) child.writeText("")
                    }
                }
            }
        }
        dirs.sortedByDescending { it.path.length }.forEach { runCatching { it.delete() } }
    }

    fun clearCache(context: Context, avatarsOnly: Boolean) {
        if (avatarsOnly) {
            deleteContents(File(File(context.filesDir, "cache"), "avatars"))
            return
        }
        deleteContents(File(context.filesDir, "cache"))
        deleteContents(context.cacheDir)
    }

    fun clearOtherCache(context: Context) {
        val cacheDir = File(context.filesDir, "cache")
        val avatarAbs = File(cacheDir, "avatars").absoluteFile.invariantSeparatorsPath
        cacheDir.listFiles()?.forEach { ent ->
            if (ent.absoluteFile.invariantSeparatorsPath == avatarAbs) return@forEach
            runCatching { ent.deleteRecursively() }
        }
    }

    fun clearSystemCache(context: Context) = deleteContents(context.cacheDir)

    fun clearLogs(context: Context) {
        deleteContents(File(context.filesDir, "logs"))
    }

    fun clearLegacyChatData(context: Context) {
        hiveArtifacts.forEach { runCatching { File(context.filesDir, it).delete() } }
    }

    fun clearRestoreTraces(context: Context) {
        deleteContents(File(File(context.filesDir, ".memo_restore"), "completed"))
    }

    fun clearDisplacedDatabases(context: Context) {
        val dbDir = context.getDatabasePath(DATABASE_NAME).parentFile ?: return
        dbDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(DISPLACED_PREFIX)) runCatching { file.delete() }
        }
    }

    /** listUploadEntries — upload/ (attachments) + images/ (generated). */
    fun listUploadEntries(context: Context, images: Boolean): List<StorageFileEntry> {
        val out = ArrayList<StorageFileEntry>()
        fun addFrom(dir: File?, includeImages: Boolean) {
            if (dir == null || !dir.exists()) return
            walk(dir) { f ->
                val img = isImage(f.name)
                if (img != includeImages) return@walk
                out.add(
                    StorageFileEntry(
                        path = f.absolutePath,
                        name = f.name,
                        bytes = runCatching { f.length() }.getOrDefault(0L),
                        modifiedAt = runCatching { f.lastModified() }.getOrDefault(0L),
                    ),
                )
            }
        }
        addFrom(File(context.filesDir, "upload"), images)
        if (images) addFrom(File(context.filesDir, "images"), true)
        return out.sortedByDescending { it.modifiedAt }
    }

    fun deleteUploadFiles(context: Context, paths: List<String>, images: Boolean): Int {
        val roots = mutableListOf(
            File(context.filesDir, "upload").absoluteFile.invariantSeparatorsPath,
        )
        if (images) roots.add(File(context.filesDir, "images").absoluteFile.invariantSeparatorsPath)
        var deleted = 0
        paths.forEach { raw ->
            runCatching {
                val abs = File(raw).absoluteFile
                val allowed = roots.any { abs.invariantSeparatorsPath == it || abs.invariantSeparatorsPath.startsWith("$it/") }
                if (allowed && abs.exists() && abs.delete()) deleted += 1
            }
        }
        return deleted
    }

    fun fmtBytes(bytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(java.util.Locale.US, "%.2f GB", bytes / gb.toDouble())
            bytes >= mb -> String.format(java.util.Locale.US, "%.2f MB", bytes / mb.toDouble())
            bytes >= kb -> String.format(java.util.Locale.US, "%.1f KB", bytes / kb.toDouble())
            else -> "$bytes B"
        }
    }
}

// ---------------------------------------------------------------------------
// Screens — storage_space_page.dart mobile layout.
// ---------------------------------------------------------------------------

internal fun storageIconFor(key: StorageCategoryKey): ImageVector = when (key) {
    StorageCategoryKey.IMAGES -> Lucide.Image
    StorageCategoryKey.FILES -> Lucide.Paperclip
    StorageCategoryKey.CHAT_DATA -> Lucide.MessagesSquare
    StorageCategoryKey.LEGACY_CHAT_DATA -> Lucide.History
    StorageCategoryKey.RESTORE_TRACES -> Lucide.RotateCcw
    StorageCategoryKey.DISPLACED_DATABASES -> Lucide.Database
    StorageCategoryKey.LOCAL_SNAPSHOTS -> Lucide.Shield
    StorageCategoryKey.ASSISTANT_DATA -> Lucide.Bot
    StorageCategoryKey.CACHE -> Lucide.Boxes
    StorageCategoryKey.LOGS -> Lucide.FileText
    StorageCategoryKey.OTHER -> Lucide.Box
}

@Composable
internal fun storageTitleFor(key: StorageCategoryKey): String = when (key) {
    StorageCategoryKey.IMAGES -> stringResource(UiR.string.storage_space_category_images)
    StorageCategoryKey.FILES -> stringResource(UiR.string.storage_space_category_files)
    StorageCategoryKey.CHAT_DATA -> stringResource(UiR.string.storage_space_category_chat_data)
    StorageCategoryKey.LEGACY_CHAT_DATA -> stringResource(UiR.string.storage_space_category_legacy_chat_data)
    StorageCategoryKey.RESTORE_TRACES -> stringResource(UiR.string.storage_space_category_restore_traces)
    StorageCategoryKey.DISPLACED_DATABASES -> stringResource(UiR.string.storage_space_category_displaced_databases)
    StorageCategoryKey.LOCAL_SNAPSHOTS -> stringResource(UiR.string.local_snapshot_section_title)
    StorageCategoryKey.ASSISTANT_DATA -> stringResource(UiR.string.storage_space_category_assistant_data)
    StorageCategoryKey.CACHE -> stringResource(UiR.string.storage_space_category_cache)
    StorageCategoryKey.LOGS -> stringResource(UiR.string.storage_space_category_logs)
    StorageCategoryKey.OTHER -> stringResource(UiR.string.storage_space_category_other)
}

@Composable
internal fun storageSubTitleFor(id: String): String = when (id) {
    "messages" -> stringResource(UiR.string.storage_space_sub_chat_messages)
    "conversations" -> stringResource(UiR.string.storage_space_sub_chat_conversations)
    "tool_events_v1" -> stringResource(UiR.string.storage_space_sub_chat_tool_events)
    "sqlite_database" -> stringResource(UiR.string.storage_space_sub_chat_database)
    "sqlite_wal" -> stringResource(UiR.string.storage_space_sub_chat_write_ahead_log)
    "sqlite_shm" -> stringResource(UiR.string.storage_space_sub_chat_shared_memory)
    "completed_restore_runs" -> stringResource(UiR.string.storage_space_sub_completed_restore_runs)
    "displaced_databases" -> stringResource(UiR.string.storage_space_sub_displaced_databases)
    "local_snapshots" -> stringResource(UiR.string.local_snapshot_enabled_subtitle)
    "fonts" -> stringResource(UiR.string.storage_space_category_fonts)
    "local_models" -> stringResource(UiR.string.storage_space_category_local_models)
    "app" -> stringResource(UiR.string.storage_space_sub_other_app)
    "avatars" -> stringResource(UiR.string.storage_space_sub_assistant_avatars)
    "images" -> stringResource(UiR.string.storage_space_sub_assistant_images)
    "avatar_cache" -> stringResource(UiR.string.storage_space_sub_cache_avatars)
    "other_cache" -> stringResource(UiR.string.storage_space_sub_cache_other)
    "system_cache" -> stringResource(UiR.string.storage_space_sub_cache_system)
    "context_logs" -> stringResource(UiR.string.storage_space_sub_logs_context)
    "flutter_logs" -> stringResource(UiR.string.storage_space_sub_logs_flutter)
    "request_logs" -> stringResource(UiR.string.storage_space_sub_logs_requests)
    "other_logs" -> stringResource(UiR.string.storage_space_sub_logs_other)
    else -> id
}

/** _barColorFor: chart series colors, other = neutral. */
@Composable
private fun barColorFor(key: StorageCategoryKey): Color {
    val cs = MaterialTheme.colorScheme
    if (key == StorageCategoryKey.OTHER) return cs.onSurface.copy(alpha = 0.22f)
    val series = LocalSemanticColors.current.chartSeries
    val order = listOf(
        StorageCategoryKey.IMAGES, StorageCategoryKey.FILES, StorageCategoryKey.CHAT_DATA,
        StorageCategoryKey.LEGACY_CHAT_DATA, StorageCategoryKey.RESTORE_TRACES,
        StorageCategoryKey.DISPLACED_DATABASES, StorageCategoryKey.LOCAL_SNAPSHOTS,
        StorageCategoryKey.ASSISTANT_DATA, StorageCategoryKey.CACHE, StorageCategoryKey.LOGS,
    )
    return series[order.indexOf(key).mod(series.size)]
}

/** Storage page (L631-771 mobile layout): total card + category list. */
@Composable
fun StorageSpaceScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
    onOpenCategory: (StorageCategoryKey) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                report = withContext(Dispatchers.IO) { StorageUsage.computeReport(context) }
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(UiR.string.settings_page_back_button), modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(UiR.string.storage_space_page_title),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refresh() }, enabled = !loading) {
                Icon(
                    Lucide.RefreshCw,
                    contentDescription = stringResource(UiR.string.storage_space_refresh_tooltip),
                )
            }
        }
        val rep = report
        if (rep == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(UiR.string.settings_page_calculating),
                    style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
                )
            }
        } else {
            StorageListContent(
                report = rep,
                onOpenCategory = onOpenCategory,
            )
        }
    }
}

@Composable
private fun StorageListContent(report: StorageReport, onOpenCategory: (StorageCategoryKey) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            SectionCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(UiR.string.storage_space_total_label),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = StorageUsage.fmtBytes(report.totalBytes),
                        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface),
                    )
                    Spacer(Modifier.height(10.dp))
                    UsageBar(report.categories, report.totalBytes)
                    Spacer(Modifier.height(10.dp))
                    UsageLegend(report.categories)
                    if (report.clearable.bytes > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(UiR.string.storage_space_clearable_hint, StorageUsage.fmtBytes(report.clearable.bytes)),
                            style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            SectionCard {
                report.categories.forEachIndexed { i, category ->
                    SettingsRow(
                        icon = storageIconFor(category.key),
                        label = storageTitleFor(category.key),
                        detailText = "${StorageUsage.fmtBytes(category.stats.bytes)} · " +
                            stringResource(UiR.string.storage_space_files_count, category.stats.fileCount.toString()),
                        onTap = { onOpenCategory(category.key) },
                    )
                    if (i != report.categories.lastIndex) DividerRow()
                }
            }
        }
    }
}

/** _UsageBar: rounded 12dp bar split by category flex. */
@Composable
private fun UsageBar(categories: List<StorageCategory>, totalBytes: Long) {
    val items = categories.filter { it.stats.bytes > 0 }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
    ) {
        if (items.isNotEmpty() && totalBytes > 0) {
            Row(modifier = Modifier.fillMaxSize().background(Color.Transparent, RoundedCornerShape(10.dp))) {
                items.forEach { c ->
                    var flex = ((c.stats.bytes.toDouble() / totalBytes) * 1000).toInt()
                    if (flex <= 0) flex = 1
                    Box(modifier = Modifier.weight(flex.toFloat()).height(12.dp).background(barColorFor(c.key)))
                }
            }
        }
    }
}

/** _UsageLegend: wrap of dot + title. */
@Composable
private fun UsageLegend(categories: List<StorageCategory>) {
    val items = categories.filter { it.stats.bytes > 0 }
    if (items.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(barColorFor(c.key), CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = storageTitleFor(c.key),
                    style = TextStyle(fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)),
                )
            }
        }
    }
}

/**
 * _StorageCategoryPage: hint + action tile buttons + breakdowns + upload
 * manager for images/files (selection/sort/delete).
 */
@Composable
fun StorageCategoryScreen(
    container: AppContainerImpl,
    categoryKey: StorageCategoryKey,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }

    suspend fun refresh() {
        report = withContext(Dispatchers.IO) { StorageUsage.computeReport(context) }
    }
    LaunchedEffect(categoryKey) { refresh() }

    val category = report?.categories?.firstOrNull { it.key == categoryKey }
    val cs = MaterialTheme.colorScheme

    // Hoisted strings (format templates are filled with String.format in
    // click handlers, where stringResource is not legal).
    val avatarCacheName = stringResource(UiR.string.storage_space_sub_cache_avatars)
    val cacheName = stringResource(UiR.string.storage_space_category_cache)
    val logsName = stringResource(UiR.string.storage_space_category_logs)
    val legacyName = stringResource(UiR.string.storage_space_category_legacy_chat_data)
    val restoreName = stringResource(UiR.string.storage_space_category_restore_traces)
    val displacedName = stringResource(UiR.string.storage_space_category_displaced_databases)
    val uploadsName = stringResource(
        if (categoryKey == StorageCategoryKey.IMAGES) {
            UiR.string.storage_space_category_images
        } else {
            UiR.string.storage_space_category_files
        },
    )
    val doneTemplate = stringResource(UiR.string.storage_space_clear_done)
    val failTemplate = stringResource(UiR.string.storage_space_clear_failed)
    val deletedTemplate = stringResource(UiR.string.storage_space_deleted_uploads_done)
    val deleteUploadsTemplate = stringResource(UiR.string.storage_space_delete_uploads_confirm_message)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(UiR.string.settings_page_back_button), modifier = Modifier.size(22.dp))
            }
            Text(
                text = storageTitleFor(categoryKey),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        if (category == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(UiR.string.settings_page_calculating),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                )
            }
            return@Column
        }

        val safeToClear = categoryKey in setOf(
            StorageCategoryKey.CACHE,
            StorageCategoryKey.LOGS,
            StorageCategoryKey.LEGACY_CHAT_DATA,
            StorageCategoryKey.RESTORE_TRACES,
        )
        val hint = when (categoryKey) {
            StorageCategoryKey.LEGACY_CHAT_DATA -> stringResource(UiR.string.storage_space_legacy_chat_data_hint)
            StorageCategoryKey.RESTORE_TRACES -> stringResource(UiR.string.storage_space_restore_traces_hint)
            StorageCategoryKey.OTHER -> stringResource(UiR.string.storage_space_other_hint)
            else ->
                if (safeToClear) stringResource(UiR.string.storage_space_safe_to_clear_hint)
                else stringResource(UiR.string.storage_space_not_safe_to_clear_hint)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                Text(
                    text = "${StorageUsage.fmtBytes(category.stats.bytes)} · " +
                        stringResource(UiR.string.storage_space_files_count, category.stats.fileCount.toString()),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.65f)),
                )
            }
            if (categoryKey == StorageCategoryKey.CACHE) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IosTileButton(
                            label = stringResource(UiR.string.storage_space_clear_avatar_cache_button),
                            icon = Lucide.User,
                            enabled = !clearing,
                            onClick = {
                                confirm = ConfirmSpec(
                                    target = avatarCacheName,
                                    action = { StorageUsage.clearCache(context, avatarsOnly = true) },
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IosTileButton(
                            label = stringResource(UiR.string.storage_space_clear_cache_button),
                            icon = Lucide.Trash2,
                            enabled = !clearing,
                            onClick = {
                                confirm = ConfirmSpec(
                                    target = cacheName,
                                    action = { StorageUsage.clearCache(context, avatarsOnly = false) },
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (categoryKey == StorageCategoryKey.LOGS) {
                item {
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(UiR.string.storage_space_clear_logs_button),
                        icon = Lucide.Trash2,
                        enabled = !clearing,
                        onClick = {
                            confirm = ConfirmSpec(
                                target = logsName,
                                action = { StorageUsage.clearLogs(context) },
                            )
                        },
                    )
                }
            }
            if (categoryKey == StorageCategoryKey.LEGACY_CHAT_DATA) {
                item {
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(UiR.string.storage_space_clear_legacy_chat_data_button),
                        icon = Lucide.Trash2,
                        enabled = !clearing,
                        onClick = {
                            confirm = ConfirmSpec(
                                target = legacyName,
                                action = { StorageUsage.clearLegacyChatData(context) },
                            )
                        },
                    )
                }
            }
            if (categoryKey == StorageCategoryKey.RESTORE_TRACES) {
                item {
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(UiR.string.storage_space_clear_restore_traces_button),
                        icon = Lucide.Trash2,
                        enabled = !clearing,
                        onClick = {
                            confirm = ConfirmSpec(
                                target = restoreName,
                                action = { StorageUsage.clearRestoreTraces(context) },
                            )
                        },
                    )
                }
            }
            if (categoryKey == StorageCategoryKey.DISPLACED_DATABASES) {
                item {
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(UiR.string.storage_space_clear_displaced_databases_button),
                        icon = Lucide.Trash2,
                        enabled = !clearing,
                        onClick = {
                            confirm = ConfirmSpec(
                                target = displacedName,
                                action = { StorageUsage.clearDisplacedDatabases(context) },
                            )
                        },
                    )
                }
            }
            if (category.subcategories.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    SectionCard {
                        category.subcategories.forEachIndexed { i, sub ->
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = storageSubTitleFor(sub.id),
                                        style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${StorageUsage.fmtBytes(sub.stats.bytes)} · " +
                                            stringResource(UiR.string.storage_space_files_count, sub.stats.fileCount.toString()),
                                        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                                    )
                                }
                                Text(
                                    text = sub.path,
                                    style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.5f)),
                                    maxLines = 1,
                                )
                            }
                            if (i != category.subcategories.lastIndex) DividerRow()
                        }
                    }
                }
            }
            if (categoryKey == StorageCategoryKey.IMAGES || categoryKey == StorageCategoryKey.FILES) {
                item {
                    UploadManagerSection(
                        images = categoryKey == StorageCategoryKey.IMAGES,
                        onDeleteRequested = { paths ->
                            confirm = ConfirmSpec(
                                target = uploadsName,
                                message = String.format(deleteUploadsTemplate, paths.size),
                                action = {
                                    val n = StorageUsage.deleteUploadFiles(context, paths, images = categoryKey == StorageCategoryKey.IMAGES)
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = String.format(deletedTemplate, n),
                                            type = NotificationType.SUCCESS,
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    confirm?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(UiR.string.storage_space_clear_confirm_title)) },
            text = {
                Text(
                    spec.message ?: stringResource(UiR.string.storage_space_clear_confirm_message, spec.target),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = spec.action
                        confirm = null
                        clearing = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { action() } }
                                .onSuccess {
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = String.format(doneTemplate, spec.target),
                                            type = NotificationType.SUCCESS,
                                        ),
                                    )
                                }
                                .onFailure { e ->
                                    SnackbarManager.show(
                                        AppNotification(
                                            message = String.format(failTemplate, e.message ?: e.toString()),
                                            type = NotificationType.ERROR,
                                        ),
                                    )
                                }
                            refresh()
                            clearing = false
                        }
                    },
                ) {
                    Text(stringResource(UiR.string.storage_space_clear_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(UiR.string.home_page_cancel))
                }
            },
        )
    }
}

private class ConfirmSpec(
    val target: String,
    val message: String? = null,
    val action: () -> Unit,
)

/** _UploadManager: list + select-all + sort + delete (compact port). */
@Composable
private fun UploadManagerSection(
    images: Boolean,
    onDeleteRequested: (List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    var entries by remember { mutableStateOf<List<StorageFileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sort by remember { mutableStateOf("newest") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sort) {
        loading = true
        entries = withContext(Dispatchers.IO) { StorageUsage.listUploadEntries(context, images) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(UiR.string.storage_space_uploads_count, entries.size.toString()),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                sort = when (sort) {
                    "newest" -> "oldest"
                    "oldest" -> "largest"
                    "largest" -> "smallest"
                    else -> "newest"
                }
            }) {
                Text(
                    text = stringResource(
                        when (sort) {
                            "oldest" -> UiR.string.storage_space_sort_oldest
                            "largest" -> UiR.string.storage_space_sort_largest
                            "smallest" -> UiR.string.storage_space_sort_smallest
                            else -> UiR.string.storage_space_sort_newest
                        },
                    ),
                    style = TextStyle(fontSize = 13.sp),
                )
            }
            TextButton(onClick = {
                selected = if (selected.size == entries.size) emptySet() else entries.map { it.path }.toSet()
            }) {
                Text(
                    text = stringResource(UiR.string.storage_space_select_all),
                    style = TextStyle(fontSize = 13.sp),
                )
            }
        }
        if (selected.isNotEmpty()) {
            IosTileButton(
                label = stringResource(UiR.string.storage_space_selected_count, selected.size.toString()),
                icon = Lucide.Trash2,
                onClick = { onDeleteRequested(selected.toList()) },
            )
        }
        if (loading) {
            Text(
                text = stringResource(UiR.string.settings_page_calculating),
                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else if (entries.isEmpty()) {
            Text(
                text = stringResource(UiR.string.storage_space_no_uploads),
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            SectionCard {
                val sorted = when (sort) {
                    "oldest" -> entries.sortedBy { it.modifiedAt }
                    "largest" -> entries.sortedByDescending { it.bytes }
                    "smallest" -> entries.sortedBy { it.bytes }
                    else -> entries
                }
                sorted.forEachIndexed { i, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (entry.path in selected) {
                                    selected - entry.path
                                } else {
                                    selected + entry.path
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        IosCheckbox(value = entry.path in selected, onValueChanged = {
                            selected = if (it) selected + entry.path else selected - entry.path
                        })
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                                maxLines = 1,
                            )
                            Text(
                                text = StorageUsage.fmtBytes(entry.bytes),
                                style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        }
                    }
                    if (i != sorted.lastIndex) DividerRow()
                }
            }
        }
    }
}
