package com.psyche.memo.logging

import android.content.Context
import com.psyche.memo.common.logging.FlutterLogger
import com.psyche.memo.common.logging.RequestLogger
import com.psyche.memo.data.settings.KeyDisposition
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.data.settings.classifyBusinessKey
import java.io.File

/**
 * App-start wiring for the three log writers.
 *
 * 1. Resolves `<filesDir>/logs` and points every writer at it.
 * 2. Installs the uncaught-exception handler (so a crash leaves a stack in
 *    `flutter_logs.txt` with the `Uncaught` tag, mirroring Flutter's
 *    `FlutterLogger.installGlobalHandlers`).
 * 3. Reads the existing log preference keys and flips each writer on/off.
 * 4. Exposes setters used by the About screen toggles; the setters write
 *    the preference (to the right backing store — SharedPreferences for
 *    LOCAL_ONLY, the preference_rows table for PREFERENCE / UNKNOWN) AND
 *    flip the writer, so the change is immediate.
 *
 * Mirrors the Flutter `settings_provider.dart` enable sequence plus
 * `FlutterLogger.setEnabled`.
 */
object LogBootstrap {

    private const val PREF_FLUTTER_LOG = "flutter_log_enabled_v1"
    private const val PREF_REQUEST_LOG = "request_log_enabled_v1"
    private const val PREF_CONTEXT_LOG = "context_log_enabled_v1"
    private const val PREF_SAVE_OUTPUT = "log_save_output_v1"
    private const val PREF_ELIDE = "log_elide_large_payloads_v1"
    private const val PREF_AUTO_DELETE = "log_auto_delete_days_v1"
    private const val PREF_MAX_SIZE = "log_max_size_mb_v1"

    fun init(context: Context, prefs: PreferenceRepository) {
        val logsDir = File(context.filesDir, "logs")
        RequestLogger.initialize(logsDir)
        FlutterLogger.initialize(logsDir)
        ContextLogger.initialize(logsDir)

        FlutterLogger.installGlobalHandlers()

        RequestLogger.saveOutput = readBool(prefs, PREF_SAVE_OUTPUT, default = false)
        RequestLogger.elideLargePayloads = readBool(prefs, PREF_ELIDE, default = true)
        RequestLogger.setEnabled(readBool(prefs, PREF_REQUEST_LOG, default = true))
        FlutterLogger.setEnabled(readBool(prefs, PREF_FLUTTER_LOG, default = false))
        ContextLogger.setEnabled(readBool(prefs, PREF_CONTEXT_LOG, default = true))
    }

    fun setRequestLogEnabled(prefs: PreferenceRepository, enabled: Boolean) {
        writeBool(prefs, PREF_REQUEST_LOG, enabled)
        RequestLogger.setEnabled(enabled)
    }

    fun setFlutterLogEnabled(prefs: PreferenceRepository, enabled: Boolean) {
        writeBool(prefs, PREF_FLUTTER_LOG, enabled)
        FlutterLogger.setEnabled(enabled)
    }

    fun setContextLogEnabled(prefs: PreferenceRepository, enabled: Boolean) {
        writeBool(prefs, PREF_CONTEXT_LOG, enabled)
        ContextLogger.setEnabled(enabled)
    }

    fun setSaveOutput(prefs: PreferenceRepository, enabled: Boolean) {
        writeBool(prefs, PREF_SAVE_OUTPUT, enabled)
        RequestLogger.saveOutput = enabled
    }

    fun setElideLargePayloads(prefs: PreferenceRepository, enabled: Boolean) {
        writeBool(prefs, PREF_ELIDE, enabled)
        RequestLogger.elideLargePayloads = enabled
    }

    fun isRequestLogEnabled(prefs: PreferenceRepository, default: Boolean = true): Boolean =
        readBool(prefs, PREF_REQUEST_LOG, default)

    fun isFlutterLogEnabled(prefs: PreferenceRepository, default: Boolean = false): Boolean =
        readBool(prefs, PREF_FLUTTER_LOG, default)

    fun isContextLogEnabled(prefs: PreferenceRepository, default: Boolean = true): Boolean =
        readBool(prefs, PREF_CONTEXT_LOG, default)

    fun isSaveOutput(prefs: PreferenceRepository, default: Boolean = false): Boolean =
        readBool(prefs, PREF_SAVE_OUTPUT, default)

    fun isElideLargePayloads(prefs: PreferenceRepository, default: Boolean = true): Boolean =
        readBool(prefs, PREF_ELIDE, default)

    fun autoDeleteDays(prefs: PreferenceRepository, default: Int = 0): Int =
        readInt(prefs, PREF_AUTO_DELETE, default)

    fun maxSizeMB(prefs: PreferenceRepository, default: Int = 50): Int =
        readInt(prefs, PREF_MAX_SIZE, default)

    fun setAutoDeleteDays(prefs: PreferenceRepository, days: Int) {
        writeInt(prefs, PREF_AUTO_DELETE, days)
    }

    fun setMaxSizeMB(prefs: PreferenceRepository, mb: Int) {
        writeInt(prefs, PREF_MAX_SIZE, mb)
    }

    /**
     * Deletes rotated log files older than the configured retention window
     * and trims to the configured size cap. Safe to call concurrently with
     * a live writer (it only touches files outside the active set).
     */
    suspend fun cleanup(prefs: PreferenceRepository) {
        val autoDelete = readInt(prefs, PREF_AUTO_DELETE, default = 0)
        val maxSize = readInt(prefs, PREF_MAX_SIZE, default = 50)
        RequestLogger.cleanupLogs(autoDelete, maxSize)
    }

    // — preference read/write helpers that route to the right backing store —

    /**
     * Reads [key] as a boolean. Accepts "1"/"0", "true"/"false" (case
     * insensitive) so old values written before the format was unified still
     * decode correctly. Writes are normalized to "1"/"0".
     */
    private fun readBool(prefs: PreferenceRepository, key: String, default: Boolean): Boolean {
        val raw = readRaw(prefs, key)?.trim()?.lowercase() ?: return default
        return when (raw) {
            "1", "true" -> true
            "0", "false" -> false
            else -> default
        }
    }

    private fun writeBool(prefs: PreferenceRepository, key: String, value: Boolean) {
        val encoded = if (value) "1" else "0"
        when (classifyBusinessKey(key)) {
            KeyDisposition.LOCAL_ONLY -> prefs.writeLocal(key, encoded)
            else -> prefs.writeJson(key, encoded)
        }
    }

    private fun writeInt(prefs: PreferenceRepository, key: String, value: Int) {
        val encoded = value.toString()
        when (classifyBusinessKey(key)) {
            KeyDisposition.LOCAL_ONLY -> prefs.writeLocal(key, encoded)
            else -> prefs.writeJson(key, encoded)
        }
    }

    private fun readInt(prefs: PreferenceRepository, key: String, default: Int): Int =
        readRaw(prefs, key)?.toIntOrNull() ?: default

    private fun readRaw(prefs: PreferenceRepository, key: String): String? =
        if (classifyBusinessKey(key) == KeyDisposition.LOCAL_ONLY) {
            prefs.readLocal(key)
        } else {
            prefs.readJson(key)
        }
}
