package com.psyche.memo.common.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Date

/**
 * 1:1 port of lib/core/services/logging/flutter_logger.dart (writer side).
 *
 * Generic app-level logger that writes timestamped lines into
 * `<logsDir>/flutter_logs.txt`. All writes go through [LogRedactor] first
 * so an accidental `print(apiKey)` doesn't leak secrets.
 *
 * Multi-line messages are split and each line gets its own timestamp prefix,
 * so a 5-line error block becomes 5 separate log lines.
 *
 * The uncaught-exception handler (analog of Flutter's
 * `installGlobalHandlers`) writes the throwable + stack to the same file
 * with the `Uncaught` tag.
 */
object FlutterLogger {

    const val ACTIVE_FILE_NAME = "flutter_logs.txt"
    private const val ROTATED_PREFIX = "flutter_logs_"

    @Volatile private var enabled: Boolean = false
    val isEnabled: Boolean get() = enabled

    @Volatile private var logsDir: File? = null
    private var sink: BufferedWriter? = null
    private var sinkDate: Date? = null
    private val sinkMutex = Mutex()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var writeErrorReported = false

    @Volatile private var installed: Boolean = false
    private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null

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

    /**
     * Installs a default uncaught-exception handler that logs the throwable
     * and its stack to `flutter_logs.txt` with the `Uncaught` tag, then
     * delegates to the original handler. Idempotent.
     */
    fun installGlobalHandlers() {
        if (installed) return
        installed = true
        originalUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log(formatThrowable(throwable), tag = "Uncaught")
            } catch (_: Exception) {
                // never let the logger break crash reporting
            }
            originalUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Writes [message] to `flutter_logs.txt`. Multi-line messages are split
     * with the timestamp prefix repeated on each line. [message] is run
     * through [LogRedactor.redactText] before being written.
     */
    fun log(message: String, tag: String? = null) {
        if (!enabled) return
        val redacted = LogRedactor.redactText(message)
        val now = Date()
        val prefix = "[${formatTs(now)}]" + (tag?.let { " [$it] " } ?: " ")
        val normalized = redacted.replace("\r\n", "\n").replace('\r', '\n')
        val text = buildString {
            for (line in normalized.split('\n')) {
                append(prefix)
                appendLine(line)
            }
        }
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
                        System.err.println("[FlutterLogger] write failed; further write errors will be suppressed.")
                    }
                }
            }
        }
    }

    /** Convenience for routed `print()` output. Equivalent to `log(line, tag = "print")`. */
    fun logPrint(line: String) = log(line, tag = "print")

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

    private fun formatThrowable(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }
}
