package com.psyche.memo.provider

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.psyche.memo.ui.chat.TtsPlayer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.objecthunter.exp4j.ExpressionBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Android 本地工具的执行器。上游分布在 `local_tools_service.dart`（剪贴板 / 计算 /
 * 语音播放 / 日历 / 屏幕时间）与自研的 `render_visual`、`render_mermaid` 两件套。
 * `get_current_location` 是**超出上游的安卓侧加法**（上游 `locationSupported` 是
 * iOS-only），本体在 [LocationTool]。本机确实没有执行器的（天气/健康/提醒）由
 * ToolHandler 兜底成 execution_error，不会伪装成功。
 */
object LocalToolExecutors {

    const val TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TEXT_TO_SPEECH = "text_to_speech"
    const val CALCULATE = "calculate"
    const val SCREEN_TIME = "get_screen_time"
    const val CALENDAR_QUERY = "calendar_query"
    const val CALENDAR_CREATE = "calendar_create"
    const val CURRENT_LOCATION = LocationTool.TOOL_NAME

    /** 可视化绘图（自研，一个工具 10 种图 + 手写 SVG）：名字以工具本体为准。 */
    const val RENDER_VISUAL = com.psyche.memo.provider.chart.VisualTools.TOOL_NAME

    /** Mermaid 图（自研）。 */
    const val RENDER_MERMAID = com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME

    /** Names this object can execute; the rest fall through. */
    val EXECUTABLE = setOf(
        CLIPBOARD, TEXT_TO_SPEECH, CALCULATE, SCREEN_TIME, CALENDAR_QUERY, CALENDAR_CREATE,
        CURRENT_LOCATION, RENDER_VISUAL, RENDER_MERMAID,
    )

    /**
     * @param chartPalette 图表工具的配色（外壳跟主题）；调用方拿不到当前主题时给 null，
     *   工具会退回浅色主题的配色。
     * @param locationPermission 定位工具要运行时权限时用：只有界面手里有
     *   ActivityResultRegistry，所以由调用方注入「挂起等系统弹窗结果」的那根通道。
     *   给 null 等于问不到人 ⇒ 工具按「未授权」返回错误。
     */
    suspend fun execute(
        context: Context,
        name: String,
        args: JsonObject,
        chartPalette: com.psyche.memo.provider.chart.ChartPalette? = null,
        locationPermission: (suspend () -> Boolean)? = null,
    ): String? = when (name) {
        CLIPBOARD -> clipboard(context, args)
        TEXT_TO_SPEECH -> textToSpeech(context, args)
        CALCULATE -> calculate(args)
        SCREEN_TIME -> screenTime(context, args)
        CALENDAR_QUERY -> queryCalendar(context, args)
        CALENDAR_CREATE -> createCalendarEvent(context, args)
        CURRENT_LOCATION -> LocationTool.execute(context, locationPermission ?: { false })
        RENDER_VISUAL -> com.psyche.memo.provider.chart.VisualTools.execute(
            context = context,
            args = args,
            palette = chartPalette ?: com.psyche.memo.provider.chart.ChartPalette.LIGHT,
        )
        RENDER_MERMAID -> com.psyche.memo.provider.chart.MermaidTools.execute(
            context = context,
            args = args,
            palette = chartPalette ?: com.psyche.memo.provider.chart.ChartPalette.LIGHT,
        )
        else -> null
    }

    // ------------------------------------------------------------------ clipboard

    private fun clipboard(context: Context, args: JsonObject): String {
        val action = args.string("action") ?: ""
        return when (action) {
            "read" -> {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = manager.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    ?: ""
                buildJsonObject { put("text", text) }.toString()
            }
            "write" -> {
                val text = args.string("text")
                    ?: throw IllegalArgumentException("text is required for clipboard write")
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("memo", text))
                buildJsonObject {
                    put("success", true)
                    put("text", text)
                }.toString()
            }
            else -> throw IllegalArgumentException("unknown clipboard action: $action")
        }
    }

    // ------------------------------------------------------------- text to speech

    private fun textToSpeech(context: Context, args: JsonObject): String {
        val text = args.string("text")?.trim()
        if (text.isNullOrEmpty()) {
            throw IllegalArgumentException("text is required for text_to_speech")
        }
        TtsPlayer.speak(context, text)
        return buildJsonObject { put("success", true) }.toString()
    }

    // ------------------------------------------------------------------ calculate

    /** _handleCalculateTool: exp4j parse + finite check, upstream error payloads. */
    fun calculate(args: JsonObject): String {
        val expression = args.string("expression")?.trim() ?: ""
        if (expression.isEmpty()) {
            return buildJsonObject {
                put("error", "empty_expression")
                put(
                    "message",
                    "Expression is empty. Please provide a mathematical expression in standard notation, e.g. \"(15 + 3) * 2\".",
                )
            }.toString()
        }
        return try {
            val result = ExpressionBuilder(expression).build().evaluate()
            if (!result.isFinite()) {
                buildJsonObject {
                    put("error", "math_error")
                    put(
                        "message",
                        "The result is not a finite number. Please check your expression (e.g. division by zero).",
                    )
                }.toString()
            } else {
                buildJsonObject {
                    put("expression", expression)
                    put("result", formatNumber(result))
                }.toString()
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "parse_error")
                put("message", "Could not parse the expression. Use standard notation, e.g. \"(15 + 3) * 2\".")
                put("detail", e.toString())
            }.toString()
        }
    }

    /** Dart double.toString: integral values lose the ".0". */
    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    // ---------------------------------------------------------------- screen time

    /** handleScreenTime / computeScreenTime of DeviceLocalToolsHandler.kt. */
    fun screenTime(context: Context, args: JsonObject): String {
        if (!DeviceLocalTools.hasUsageStatsPermission(context)) {
            DeviceLocalTools.openUsageAccessSettings(context)
            return buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Usage access permission is not granted. The system settings page has been opened; " +
                        "please ask the user to enable 'Usage access' for this app and try again.",
                )
            }.toString()
        }
        val top = (args.string("top")?.toIntOrNull() ?: args.int("top") ?: 10).coerceIn(1, 50)
        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = args.string("begin")?.takeIf { it.isNotBlank() }
        val endRaw = args.string("end")?.takeIf { it.isNotBlank() }
        val rangePreset = args.string("range")?.takeIf { it.isNotBlank() } ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            endTime = endRaw?.let { parseTime(it, zone) } ?: now
            startTime = if (beginRaw != null) {
                parseTime(beginRaw, zone)
            } else {
                when (rangePreset) {
                    "week" -> now.minusDays(7)
                    else -> now.toLocalDate().atStartOfDay(zone)
                }
            }
        } catch (e: Exception) {
            return buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }.toString()
        }
        if (!startTime.isBefore(endTime)) {
            return buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }.toString()
        }

        val isCustom = beginRaw != null || endRaw != null
        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val launcherPackages = resolveLauncherPackages(pm)
        val foregroundMs = computeForegroundFromUsage(usageStats, startMs, endMs, launcherPackages)

        val sorted = foregroundMs.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        val totalMs = sorted.sumOf { it.value }
        return buildJsonObject {
            put("range", if (isCustom) "custom" else rangePreset)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("total_ms", totalMs)
            put("total_minutes", totalMs / 60000)
            put("apps", buildJsonArray {
                sorted.take(top).forEach { entry ->
                    add(buildJsonObject {
                        put("package", entry.key)
                        put("app_name", resolveAppName(pm, entry.key))
                        put("total_ms", entry.value)
                        put("total_minutes", entry.value / 60000)
                    })
                }
            })
        }.toString()
    }

    @Suppress("DEPRECATION")
    private fun computeForegroundFromUsage(
        usageStats: UsageStatsManager,
        startMs: Long,
        endMs: Long,
        excludedPackages: Set<String>,
    ): Map<String, Long> {
        val events = ArrayList<DeviceLocalTools.UsageEvent>()
        val cursor = usageStats.queryEvents(startMs - 12L * 60 * 60 * 1000, endMs)
        val event = UsageEvents.Event()
        while (cursor.hasNextEvent()) {
            cursor.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    events.add(DeviceLocalTools.UsageEvent(event.timeStamp, event.packageName ?: "", true))
                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    events.add(DeviceLocalTools.UsageEvent(event.timeStamp, event.packageName ?: "", false))
            }
        }
        return DeviceLocalTools.computeForegroundTime(events, startMs, endMs, excludedPackages)
    }

    private fun resolveLauncherPackages(pm: android.content.pm.PackageManager): Set<String> =
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())

    private fun resolveAppName(pm: android.content.pm.PackageManager, packageName: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)

    // ------------------------------------------------------------------- calendar

    /** queryCalendar of DeviceLocalToolsHandler.kt. */
    fun queryCalendar(context: Context, args: JsonObject): String {
        val limit = (args.string("limit")?.toIntOrNull() ?: args.int("limit") ?: 20).coerceIn(1, 100)
        val query = args.string("query")?.takeIf { it.isNotBlank() }

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = args.string("begin")?.takeIf { it.isNotBlank() }
        val endRaw = args.string("end")?.takeIf { it.isNotBlank() }
        val rangePreset = args.string("range")?.takeIf { it.isNotBlank() } ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseTime(beginRaw, zone)
            } else {
                when (rangePreset) {
                    "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                    "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                    else -> now.toLocalDate().atStartOfDay(zone)
                }
            }
            endTime = if (endRaw != null) {
                parseTime(endRaw, zone)
            } else if (beginRaw != null) {
                // Custom interval: 'range' is ignored per the tool contract, and
                // the end defaults to now (matches iOS).
                now
            } else {
                when (rangePreset) {
                    "week" -> startTime.plusDays(7)
                    "month" -> startTime.plusMonths(1)
                    else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
                }
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format for begin/end.")
        }
        if (!startTime.isBefore(endTime)) {
            return errorPayload("INVALID_RANGE", "begin must be earlier than end.")
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        // Escape LIKE wildcards so the keyword matches literally as a substring.
        val selection = if (query != null) "${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'" else null
        val selectionArgs = if (query != null) {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            arrayOf("%$escaped%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = mutableListOf<JsonObject>()
        context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && events.size < limit) {
                val dtStart = cursor.getLong(4)
                val dtEnd = cursor.getLong(5)
                val allDay = cursor.getInt(6) == 1
                events.add(buildJsonObject {
                    put("id", cursor.getLong(0))
                    put("title", cursor.getString(1) ?: "")
                    put("description", cursor.getString(2) ?: "")
                    put("location", cursor.getString(3) ?: "")
                    if (allDay) {
                        put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        put(
                            "end",
                            if (dtEnd > 0) {
                                Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            } else "",
                        )
                    } else {
                        put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                        put("end", if (dtEnd > 0) Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString() else "")
                    }
                    put("all_day", allDay)
                    put("calendar", cursor.getString(7) ?: "")
                })
            }
        }

        return buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("count", events.size)
            put("events", JsonArray(events))
        }.toString()
    }

    /** createCalendarEvent of DeviceLocalToolsHandler.kt. */
    fun createCalendarEvent(context: Context, args: JsonObject): String {
        val title = args.string("title")?.takeIf { it.isNotBlank() }
        val startRaw = args.string("start")?.takeIf { it.isNotBlank() }
        val endRaw = args.string("end")?.takeIf { it.isNotBlank() }
        val allDay = args.bool("all_day") ?: false

        if (title == null || startRaw == null) {
            return errorPayload("MISSING_REQUIRED", "Both 'title' and 'start' are required.")
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (e: Exception) {
            return errorPayload("INVALID_TIME", e.message ?: "Invalid time format.")
        }
        if (!startTime.isBefore(endTime)) {
            return errorPayload("INVALID_RANGE", "end must be later than start.")
        }

        val description = args.string("description") ?: ""
        val location = args.string("location") ?: ""
        val reminderMinutes = parseReminderMinutes(args["reminders"])

        val eventStartMillis: Long
        val eventEndMillis: Long
        val eventTimeZone: String
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                return errorPayload("INVALID_RANGE", "all-day event end date must be later than start date.")
            }
            eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventTimeZone = "UTC"
        } else {
            eventStartMillis = startTime.toInstant().toEpochMilli()
            eventEndMillis = endTime.toInstant().toEpochMilli()
            eventTimeZone = zone.id
        }

        val calendarId = getDefaultCalendarId(context)
            ?: return errorPayload(
                "NO_CALENDAR",
                "No calendar account found on this device. Please add a calendar account first.",
            )

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, eventStartMillis)
            put(CalendarContract.Events.DTEND, eventEndMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return errorPayload("INSERT_FAILED", "Failed to insert calendar event.")

        val eventId = ContentUris.parseId(uri)
        val savedReminders = insertReminders(context, eventId, reminderMinutes)
        if (savedReminders.isNotEmpty()) {
            // Only claim an alarm when a reminder row really landed.
            runCatching {
                context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, 1) },
                    null,
                    null,
                )
            }
        }

        return buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            put("title", title)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("all_day", allDay)
            put("location", location)
            put("reminders", buildJsonArray { savedReminders.forEach { add(JsonPrimitive(it)) } })
            if (savedReminders.size < reminderMinutes.size) {
                // The event exists but some reminders were rejected; the model
                // must see this instead of telling the user they were set.
                put("reminders_requested", buildJsonArray { reminderMinutes.forEach { add(JsonPrimitive(it)) } })
                put(
                    "warning",
                    "The event was created, but the calendar account rejected some reminders. " +
                        "Tell the user which reminders were actually saved.",
                )
            }
        }.toString()
    }

    /**
     * Reminder offsets (minutes before the event). Accepts an array or a single
     * number/string; negatives use their absolute value, deduped, max 5, capped
     * at 4 weeks.
     */
    fun parseReminderMinutes(raw: kotlinx.serialization.json.JsonElement?): List<Int> {
        if (raw == null || raw is kotlinx.serialization.json.JsonNull) return emptyList()
        val items: List<kotlinx.serialization.json.JsonElement> = when (raw) {
            is JsonArray -> raw.toList()
            else -> listOf(raw)
        }
        val minutes = LinkedHashSet<Int>()
        for (item in items) {
            val primitive = item as? JsonPrimitive ?: continue
            val value = primitive.content.trim().toDoubleOrNull() ?: continue
            if (value.isNaN() || value.isInfinite()) continue
            // Double intermediate: abs(Int.MIN_VALUE) is still negative.
            minutes.add(Math.abs(value).coerceAtMost(40320.0).toInt())
            if (minutes.size == 5) break
        }
        return minutes.toList()
    }

    private fun insertReminders(context: Context, eventId: Long, minutes: List<Int>): List<Int> {
        if (minutes.isEmpty()) return emptyList()
        val saved = mutableListOf<Int>()
        for (minute in minutes) {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minute)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            val inserted = runCatching {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            }.getOrNull()
            if (inserted != null) saved.add(minute)
        }
        return saved
    }

    private fun getDefaultCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val writableSelection =
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
            writableArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            writableSelection,
            writableArgs,
            "${CalendarContract.Calendars.VISIBLE} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    // --------------------------------------------------------------- time parsing

    /**
     * Tries, in order: epoch millis, offset date-time, instant, local date-time,
     * local date (midnight).
     */
    private fun parseTime(raw: String, zone: ZoneId): ZonedDateTime {
        val text = raw.trim()
        text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
        runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
        runCatching { return Instant.parse(text).atZone(zone) }
        runCatching { return LocalDateTime.parse(text).atZone(zone) }
        runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
        error("Invalid time format: '$text'. Use ISO-8601 date/date-time or epoch milliseconds.")
    }

    private fun errorPayload(error: String, message: String): String =
        buildJsonObject {
            put("error", error)
            put("message", message)
        }.toString()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
