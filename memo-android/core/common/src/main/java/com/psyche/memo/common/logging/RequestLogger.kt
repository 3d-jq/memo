package com.psyche.memo.common.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1:1 port of lib/core/services/network/request_logger.dart (writer side).
 *
 * Writes timestamped lines in the format the existing parser in
 * `com.psyche.memo.ui.LogData` understands:
 *   [2026-09-09 12:34:56.789] [REQ 1] POST https://...
 *   [2026-09-09 12:34:56.789] [REQ 1] headers={"..."}
 *   [2026-09-09 12:34:56.789] [REQ 1] body=...
 *   [2026-09-09 12:34:56.789] [RES 1] status=200
 *   [2026-09-09 12:34:56.789] [RES 1] chunk=...
 *   [2026-09-09 12:34:56.789] [RES 1] done
 *
 * Daily rotation: the previous day's `logs.txt` is renamed to `logs_YYYY-MM-DD.txt`
 * (or `logs_YYYY-MM-DD_n.txt` if that already exists). The active file is
 * `logs.txt` inside the configured logs directory.
 *
 * The OkHttp interceptor (in `core:llm`) and direct call sites both funnel
 * through [logLine]. Writes run on a single-threaded FIFO writer (guarded
 * additionally by a Mutex) so lines land in call order on one file
 * descriptor per day.
 */
object RequestLogger {

    /** Name of the active log file. Matches the Flutter side + LogData parser. */
    const val ACTIVE_FILE_NAME = "logs.txt"
    private const val ROTATED_PREFIX = "logs_"
    private const val MAX_LINE_CHARS = 1024 * 1024

    /**
     * Files currently held open by RequestLogger / FlutterLogger / ContextLogger.
     * Deleting them unlinks the inode on Unix (writes vanish) or fails on
     * Windows; cleanup passes skip these.
     */
    val ACTIVE_LOG_FILE_NAMES: Set<String> = setOf(
        "logs.txt",
        "flutter_logs.txt",
        "context_logs.txt",
    )

    @Volatile private var enabled: Boolean = false
    val isEnabled: Boolean get() = enabled

    /** When true, the OkHttp interceptor writes full request/response bodies
     *  without elision. Matches Flutter's `RequestLogger.saveOutput`. */
    @Volatile var saveOutput: Boolean = false

    /** When true, large base64 payloads are elided before writing. */
    @Volatile var elideLargePayloads: Boolean = true

    private val nextId = AtomicInteger(0)
    fun nextRequestId(): Int = nextId.incrementAndGet()

    @Volatile private var logsDir: File? = null
    private var sink: BufferedWriter? = null
    private var sinkDate: Date? = null
    private val sinkMutex = Mutex()
    // Single-threaded FIFO writer: log lines must land in call order. A
    // multi-threaded Dispatchers.IO pool only serializes on the Mutex — it
    // does not preserve submission order (workers race for the lock), which
    // reordered log lines. One daemon writer thread gives FIFO by design;
    // the Mutex stays as the file-handle guard for setEnabled's flush/close.
    private val writeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "memo-request-log-writer").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)
    @Volatile private var writeErrorReported = false

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Sets the directory for log files. Call before [setEnabled]. */
    fun initialize(dir: File) {
        logsDir = dir
    }

    fun setEnabled(v: Boolean) {
        if (enabled == v) return
        enabled = v
        if (!v) {
            writeScope.launch {
                sinkMutex.withLock {
                    try { sink?.flush() } catch (_: Exception) {}
                    try { sink?.close() } catch (_: Exception) {}
                    sink = null
                    sinkDate = null
                }
            }
        } else {
            writeErrorReported = false
        }
    }

    /** Wraps [LogPayloadElider] so the writer behaves like the Flutter side. */
    fun elidePayloads(text: String): String {
        if (!elideLargePayloads) return text
        return LogPayloadElider.elide(text)
    }

    /**
     * Backstop for anything the elider misses; a single log line never grows
     * past this, so the viewer can always open the file.
     */
    fun capLine(line: String): String {
        if (line.length <= MAX_LINE_CHARS) return line
        val dropped = line.length - MAX_LINE_CHARS
        return "${line.substring(0, MAX_LINE_CHARS)}…<truncated $dropped chars>"
    }

    /**
     * Escapes \, \r, \n, \t so a log line stays single-line. Reversed by
     * `RequestLogParser.unescape` in `com.psyche.memo.ui.LogData`.
     */
    fun escape(input: String): String = buildString(input.length + 8) {
        for (c in input) {
            when (c) {
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /**
     * Writes one [line] to the active log file, prefixed with a timestamp.
     * No-op when disabled. Body elision is the caller's responsibility.
     */
    fun logLine(line: String) {
        if (!enabled) return
        val now = Date()
        val text = "[${formatTs(now)}] ${capLine(line)}\n"
        writeScope.launch {
            sinkMutex.withLock {
                if (!enabled) return@withLock
                try {
                    ensureSinkLocked(now)
                    sink?.write(text)
                    sink?.flush()
                } catch (_: Exception) {
                    try { sink?.flush() } catch (_: Exception) {}
                    try { sink?.close() } catch (_: Exception) {}
                    sink = null
                    sinkDate = null
                    if (!writeErrorReported) {
                        writeErrorReported = true
                        System.err.println("[RequestLogger] write failed; further write errors will be suppressed.")
                    }
                }
            }
        }
    }

    /** Pretty-prints [obj] as JSON (2-space indent). Falls back to toString. */
    fun encodeObject(obj: JsonElement): String =
        runCatching { prettyJson.encodeToString(JsonElement.serializer(), obj) }
            .getOrElse { obj.toString() }

    /** UTF-8 decode that replaces malformed bytes (instead of throwing). */
    fun safeDecodeUtf8(bytes: ByteArray): String = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        ""
    }

    /**
     * Deletes rotated log files older than [autoDeleteDays] (0 disables), then
     * enforces a [maxSizeMB] total cap (0 disables) by deleting oldest first.
     * The active file is never touched.
     */
    suspend fun cleanupLogs(autoDeleteDays: Int, maxSizeMB: Int) {
        val dir = logsDir ?: return
        runCatching {
            if (!dir.exists()) return@runCatching
            val files = dir.listFiles { f ->
                val name = f.name.lowercase()
                f.isFile && name.endsWith(".txt") && name !in ACTIVE_LOG_FILE_NAMES
            } ?: return@runCatching
            val mutable = files.toMutableList()
            if (autoDeleteDays > 0) {
                val cutoff = System.currentTimeMillis() - autoDeleteDays * 86_400_000L
                val it = mutable.iterator()
                while (it.hasNext()) {
                    val f = it.next()
                    if (f.lastModified() < cutoff) {
                        runCatching { f.delete() }
                        it.remove()
                    }
                }
            }
            if (maxSizeMB > 0 && mutable.isNotEmpty()) {
                val maxBytes = maxSizeMB.toLong() * 1024L * 1024L
                val withSize = mutable.mapNotNull { f -> runCatching { f to f.length() }.getOrNull() }
                var total = withSize.sumOf { it.second }
                if (total > maxBytes) {
                    val sorted = withSize.sortedBy { it.first.lastModified() }
                    for ((f, size) in sorted) {
                        if (total <= maxBytes) break
                        runCatching { f.delete() }
                        total -= size
                    }
                }
            }
        }
    }

    // — internals —

    private suspend fun ensureSinkLocked(now: Date) {
        val today = dayOf(now)
        if (sink != null && sinkDate?.let { dayOf(it) == today } == true) return

        try { sink?.flush() } catch (_: Exception) {}
        try { sink?.close() } catch (_: Exception) {}
        sink = null
        sinkDate = null

        val dir = logsDir ?: return
        if (!dir.exists()) dir.mkdirs()

        val active = File(dir, ACTIVE_FILE_NAME)
        if (active.exists()) {
            val fileDay = dayOf(Date(active.lastModified()))
            if (fileDay != today) {
                val suffix = formatDate(Date(active.lastModified()))
                var rotated = File(dir, "$ROTATED_PREFIX$suffix.txt")
                if (rotated.exists()) {
                    var i = 1
                    while (File(dir, "${ROTATED_PREFIX}${suffix}_$i.txt").exists()) i++
                    rotated = File(dir, "${ROTATED_PREFIX}${suffix}_$i.txt")
                }
                runCatching { active.renameTo(rotated) }
            }
        }

        sink = BufferedWriter(
            OutputStreamWriter(FileOutputStream(active, true), StandardCharsets.UTF_8)
        )
        sinkDate = today
    }

    private fun dayOf(dt: Date): Date {
        val cal = Calendar.getInstance().apply {
            time = dt
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.time
    }

    private fun two(v: Int): String = v.toString().padStart(2, '0')
    private fun three(v: Int): String = v.toString().padStart(3, '0')

    private fun formatDate(dt: Date): String {
        val cal = Calendar.getInstance().apply { time = dt }
        return "${cal.get(Calendar.YEAR)}-${two(cal.get(Calendar.MONTH) + 1)}-${two(cal.get(Calendar.DAY_OF_MONTH))}"
    }

    private fun formatTs(dt: Date): String {
        val cal = Calendar.getInstance().apply { time = dt }
        return "${formatDate(dt)} ${two(cal.get(Calendar.HOUR_OF_DAY))}:${two(cal.get(Calendar.MINUTE))}:${two(cal.get(Calendar.SECOND))}.${three(cal.get(Calendar.MILLISECOND))}"
    }
}
