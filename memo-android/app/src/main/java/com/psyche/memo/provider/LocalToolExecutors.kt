package com.psyche.memo.provider

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.psyche.memo.ui.chat.TtsPlayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.objecthunter.exp4j.ExpressionBuilder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Executors for the Android local tools — the Flutter side implements these in
 * local_tools_service.dart (clipboard / calculate / text_to_speech) and in the
 * host's DeviceLocalToolsHandler.kt (screen time). Unported tools keep
 * returning the honest execution_error from ToolHandler.
 */
object LocalToolExecutors {

    const val TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TEXT_TO_SPEECH = "text_to_speech"
    const val CALCULATE = "calculate"
    const val SCREEN_TIME = "get_screen_time"

    /** Names this object can execute; the rest fall through. */
    val EXECUTABLE = setOf(CLIPBOARD, TEXT_TO_SPEECH, CALCULATE, SCREEN_TIME)

    fun execute(context: Context, name: String, args: JsonObject): String? = when (name) {
        CLIPBOARD -> clipboard(context, args)
        TEXT_TO_SPEECH -> textToSpeech(context, args)
        CALCULATE -> calculate(args)
        SCREEN_TIME -> screenTime(context, args)
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

    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

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
            put("start", startTime.withNano(0).format(iso))
            put("end", endTime.withNano(0).format(iso))
            put("total_ms", totalMs)
            put("total_minutes", totalMs / 60000)
            put("apps", JsonArray(sorted.take(top).map { entry ->
                buildJsonObject {
                    put("package", entry.key)
                    put("app_name", resolveAppName(pm, entry.key))
                    put("total_ms", entry.value)
                    put("total_minutes", entry.value / 60000)
                }
            }))
        }.toString()
    }

    private fun parseTime(raw: String, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME.withZone(zone))

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

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
