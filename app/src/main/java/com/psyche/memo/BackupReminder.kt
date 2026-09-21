package com.psyche.memo

import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 1:1 port of `backup_reminder_provider.dart` — five preference keys, the due
 * evaluation (`(lastBackupAt ?: enabledAt) + intervalDays` at the reminder time
 * of day) and the session-scoped snooze, driven by a one-minute ticker instead
 * of Flutter's `Timer.periodic`.
 *
 * The keys land in `preference_rows` (upstream keeps them in the same business
 * preferences blob the backups carry), so a restore brings the schedule along.
 */
class BackupReminder(private val prefs: PreferenceRepository) {

    companion object {
        val PRESET_INTERVALS = listOf(1, 3, 7, 14, 30)

        private const val ENABLED_KEY = "backup_reminder_enabled_v1"
        private const val INTERVAL_DAYS_KEY = "backup_reminder_interval_days_v1"
        private const val MINUTES_OF_DAY_KEY = "backup_reminder_minutes_of_day_v1"
        private const val ENABLED_AT_KEY = "backup_reminder_enabled_at_v1"
        private const val LAST_BACKUP_AT_KEY = "backup_reminder_last_backup_at_v1"

        private const val MIN_INTERVAL_DAYS = 1
        private const val MAX_INTERVAL_DAYS = 365

        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }

    private val _enabled = MutableStateFlow(false)
    private val _intervalDays = MutableStateFlow(7)
    private val _reminderMinutesOfDay = MutableStateFlow<Int?>(null)
    private val _lastBackupAt = MutableStateFlow<LocalDateTime?>(null)
    private val _shouldShowReminder = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(false)

    val enabledFlow: StateFlow<Boolean> = _enabled
    val intervalDaysFlow: StateFlow<Int> = _intervalDays
    val reminderMinutesOfDayFlow: StateFlow<Int?> = _reminderMinutesOfDay
    val lastBackupAtFlow: StateFlow<LocalDateTime?> = _lastBackupAt
    val shouldShowReminderFlow: StateFlow<Boolean> = _shouldShowReminder
    val loadedFlow: StateFlow<Boolean> = _loaded

    private var enabledAt: LocalDateTime? = null
    private var snoozedForSession = false

    init {
        // The provider autoLoads on construction upstream (autoLoad: true).
        load()
    }

    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerStarted = false

    /** Next due moment, null when the reminder is off or has no time yet. */
    fun nextReminderAt(): LocalDateTime? {
        val minutes = _reminderMinutesOfDay.value ?: return null
        if (!_enabled.value) return null
        val anchor = _lastBackupAt.value ?: enabledAt ?: return null
        val date = anchor.toLocalDate().plusDays(_intervalDays.value.toLong())
        return LocalDateTime.of(date, LocalTime.of(minutes / 60, minutes % 60))
    }

    fun initialize() {
        load()
        if (tickerStarted) return
        tickerStarted = true
        tickerScope.launch {
            while (true) {
                delay(60_000L)
                evaluateDue(LocalDateTime.now())
            }
        }
    }

    fun load() {
        _enabled.value = readBool(ENABLED_KEY) ?: false
        _intervalDays.value = (readInt(INTERVAL_DAYS_KEY) ?: 7).coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
        _reminderMinutesOfDay.value = readInt(MINUTES_OF_DAY_KEY)?.takeIf { it in 0 until 24 * 60 }
        enabledAt = readDate(ENABLED_AT_KEY)
        _lastBackupAt.value = readDate(LAST_BACKUP_AT_KEY)
        snoozedForSession = false
        _loaded.value = true
        evaluateDue(LocalDateTime.now())
    }

    /**
     * `saveSchedule` — enabling without a reminder time throws upstream
     * (`StateError`); the UI never does that, and neither does this.
     */
    fun saveSchedule(
        enabled: Boolean,
        intervalDays: Int,
        reminderMinutesOfDay: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        require(intervalDays in MIN_INTERVAL_DAYS..MAX_INTERVAL_DAYS) { "intervalDays" }
        require(reminderMinutesOfDay in 0 until 24 * 60) { "reminderMinutesOfDay" }
        val wasEnabled = _enabled.value
        _enabled.value = enabled
        _intervalDays.value = intervalDays
        _reminderMinutesOfDay.value = reminderMinutesOfDay
        if (enabled && (!wasEnabled || enabledAt == null)) enabledAt = now
        if (!enabled) {
            snoozedForSession = false
            _shouldShowReminder.value = false
        }
        persist()
        evaluateDue(now)
    }

    /** `setEnabled(false)` — the on-path goes through [saveSchedule] with a time. */
    fun disable() {
        _enabled.value = false
        snoozedForSession = false
        _shouldShowReminder.value = false
        persist()
    }

    fun recordBackupCompleted(now: LocalDateTime = LocalDateTime.now()) {
        _lastBackupAt.value = now
        snoozedForSession = false
        persist()
        evaluateDue(now)
    }

    fun snoozeForSession() {
        if (!_shouldShowReminder.value && snoozedForSession) return
        snoozedForSession = true
        _shouldShowReminder.value = false
    }

    /** `!now.isBefore(next)` — the reminder is "due at", not "due after". */
    fun evaluateDue(now: LocalDateTime = LocalDateTime.now()) {
        val next = nextReminderAt()
        val shouldShow = _enabled.value && !snoozedForSession && next != null && !now.isBefore(next)
        if (_shouldShowReminder.value == shouldShow) return
        _shouldShowReminder.value = shouldShow
    }

    private fun persist() {
        writeBool(ENABLED_KEY, _enabled.value)
        writeInt(INTERVAL_DAYS_KEY, _intervalDays.value)
        val minutes = _reminderMinutesOfDay.value
        if (minutes == null) prefs.writeJson(MINUTES_OF_DAY_KEY, "null") else writeInt(MINUTES_OF_DAY_KEY, minutes)
        writeDate(ENABLED_AT_KEY, enabledAt)
        writeDate(LAST_BACKUP_AT_KEY, _lastBackupAt.value)
    }

    /** Reads "1"/"0"/"true"/"false" the way [com.psyche.memo.logging.LogBootstrap] does. */
    private fun readBool(key: String): Boolean? = when (readJson(key)?.trim()?.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    private fun writeBool(key: String, value: Boolean) {
        prefs.writeJson(key, if (value) "1" else "0")
    }

    private fun readInt(key: String): Int? = readJson(key)?.trim()?.toIntOrNull()

    private fun writeInt(key: String, value: Int) {
        prefs.writeJson(key, value.toString())
    }

    private fun readDate(key: String): LocalDateTime? = readJson(key)?.let { raw ->
        runCatching { LocalDateTime.parse(raw, ISO) }.getOrNull()
    }

    private fun writeDate(key: String, value: LocalDateTime?) {
        if (value == null) prefs.writeJson(key, "null") else prefs.writeJson(key, value.format(ISO))
    }

    private fun readJson(key: String): String? {
        val raw = prefs.readJson(key) ?: return null
        return if (raw == "null") null else raw
    }
}
