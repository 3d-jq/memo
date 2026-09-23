package com.psyche.memo.logging

import com.psyche.memo.common.logging.LogRedactor
import com.psyche.memo.common.logging.ContextLogSnapshot
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
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Date

/**
 * 1:1 port of `lib/core/services/logging/context_logger.dart` (writer side).
 *
 * Writes one JSONL line per turn's assembled context into
 * `<logsDir>/context_logs.txt`. The reader is
 * `com.psyche.memo.common.logging.ContextLogTailReader`, which parses with
 * `ContextLogSnapshot.fromJson`; the assembler that turns the tagged request
 * messages into a snapshot is [ContextLogAssembler].
 */
object ContextLogger {

    const val ACTIVE_FILE_NAME = "context_logs.txt"
    private const val ROTATED_PREFIX = "context_logs_"

    @Volatile private var enabled: Boolean = false
    val isEnabled: Boolean get() = enabled

    @Volatile private var logsDir: File? = null
    private var sink: BufferedWriter? = null
    private var sinkDate: Date? = null
    private val sinkMutex = Mutex()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var writeErrorReported = false

    fun initialize(dir: File) {
        logsDir = dir
    }

    fun setEnabled(v: Boolean) {
        if (enabled == v) return
        if (!v) {
            // drain pending writes first
            writeScope.launch {
                sinkMutex.withLock {
                    try { sink?.flush() } catch (_: Exception) { /* flush 失败只能丢日志，不能影响调用方（sink 由持有者在写入流程末尾关闭） */ }
                    try { sink?.close() } catch (_: Exception) { /* close 失败同理：此处是 sink 的最后一次使用，调用方已不再持有它 */ }
                    sink = null
                    sinkDate = null
                }
            }
        } else {
            writeErrorReported = false
        }
        enabled = v
    }

    /**
     * Writes [snapshot] as a single JSON line, redacted through [LogRedactor]
     * so any accidentally-logged secrets in `text` segments are masked.
     */
    fun logSnapshot(snapshot: ContextLogSnapshot) {
        if (!enabled) return
        val raw = snapshot.toJson().toString()
        val redacted = LogRedactor.redactText(raw)
        val line = "$redacted\n"
        writeScope.launch {
            sinkMutex.withLock {
                if (!enabled) return@withLock
                try {
                    ensureSinkLocked(Date())
                    sink?.write(line)
                    sink?.flush()
                } catch (_: Exception) {
                    try { sink?.flush() } catch (_: Exception) { /* flush 失败只能丢日志，不能影响调用方（sink 由持有者在写入流程末尾关闭） */ }
                    try { sink?.close() } catch (_: Exception) { /* close 失败同理：此处是 sink 的最后一次使用，调用方已不再持有它 */ }
                    sink = null
                    sinkDate = null
                    if (!writeErrorReported) {
                        writeErrorReported = true
                        System.err.println("[ContextLogger] write failed; further write errors will be suppressed.")
                    }
                }
            }
        }
    }

    // — internals —

    private suspend fun ensureSinkLocked(now: Date) {
        val today = dayOf(now)
        if (sink != null && sinkDate?.let { dayOf(it) == today } == true) return

        try { sink?.flush() } catch (_: Exception) { /* flush 失败只能丢日志，不能影响调用方（sink 由持有者在写入流程末尾关闭） */ }
        try { sink?.close() } catch (_: Exception) { /* close 失败同理：此处是 sink 的最后一次使用，调用方已不再持有它 */ }
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
    private fun formatDate(dt: Date): String {
        val cal = Calendar.getInstance().apply { time = dt }
        return "${cal.get(Calendar.YEAR)}-${two(cal.get(Calendar.MONTH) + 1)}-${two(cal.get(Calendar.DAY_OF_MONTH))}"
    }
}
