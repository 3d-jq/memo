package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.theme.AppFontWeights
import com.psyche.memo.ui.R as UiR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parsed local-tool results — 1:1 port of weather_tool_ui.dart /
 * screen_time_tool_ui.dart (rendering layer only; tool execution lives in a
 * later batch).
 */

/** Parsed `get_weather` result (weather_tool_ui.dart WeatherToolResult). */
data class WeatherToolResult(
    val condition: String?,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val precipitationChance: Double?,
    val placeLabel: String?,
    val error: String?,
) {
    val isError: Boolean get() = !error.isNullOrEmpty()

    /** weather_tool_ui.dart:29 —— 有温度或有天气描述才算有当前天气。 */
    val hasCurrent: Boolean get() = temperatureC != null || !condition.isNullOrEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun tryParse(content: String?): WeatherToolResult? {
            if (content.isNullOrBlank()) return null
            val obj = try { json.parseToJsonElement(content).jsonObject } catch (e: Exception) { return null }
            // 源码 weather_tool_ui.dart:38 —— error 字段非空时解析失败返回 null。
            val error = obj.str("error")
            if (!error.isNullOrEmpty()) return null
            val current = obj["current"] as? JsonObject ?: return null
            return WeatherToolResult(
                condition = current.str("condition"),
                temperatureC = current.dbl("temperature_c"),
                apparentTemperatureC = current.dbl("apparent_temperature_c"),
                precipitationChance = current.dbl("precipitation_chance"),
                placeLabel = placeLabel(obj),
                error = error,
            )
        }

        private fun placeLabel(obj: JsonObject): String? {
            val lat = obj.dbl("latitude") ?: return null
            val lon = obj.dbl("longitude") ?: return null
            return "%.2f, %.2f".format(lat, lon)
        }
    }
}

/** Compact one-line weather summary (weather_tool_ui.dart _currentLine). */
fun weatherCurrentLine(result: WeatherToolResult): String {
    val parts = ArrayList<String>(5)
    if (!result.placeLabel.isNullOrEmpty()) parts.add(result.placeLabel)
    if (!result.condition.isNullOrEmpty()) parts.add(result.condition)
    result.temperatureC?.let { parts.add("${formatTemp(it)}°C") }
    result.apparentTemperatureC?.let { parts.add("feels ${formatTemp(it)}°C") }
    result.precipitationChance?.let {
        parts.add("${kotlin.math.round(it * 100).toInt()}% precip")
    }
    return parts.joinToString(" · ")
}

private fun formatTemp(value: Double): String =
    if (value == kotlin.math.round(value)) "%.0f".format(value) else "%.1f".format(value)

/**
 * 时间线工具卡里的天气摘要行（weather_tool_ui.dart WeatherToolSummary）。
 * WeatherKit 归属标签属工具结果图片/归属批次，本批不渲染。
 */
@Composable
fun WeatherToolSummary(
    result: WeatherToolResult,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!result.hasCurrent) return
    Text(
        text = weatherCurrentLine(result),
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 15.6.sp,
            fontWeight = AppFontWeights.medium,
            color = textColor,
        ),
        modifier = modifier,
    )
}

/** Parsed `get_screen_time` result (screen_time_tool_ui.dart ScreenTimeResult). */
data class ScreenTimeResult(
    val totalMinutes: Int,
    val apps: List<ScreenTimeAppUsage>,
    val start: String?,
    val end: String?,
    val error: String?,
) {
    val isNoPermission: Boolean get() = error == "NO_PERMISSION"
    val hasApps: Boolean get() = apps.isNotEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun tryParse(content: String?): ScreenTimeResult? {
            if (content.isNullOrBlank()) return null
            val obj = try { json.parseToJsonElement(content).jsonObject } catch (e: Exception) { return null }
            val apps = ArrayList<ScreenTimeAppUsage>()
            val appsRaw = obj["apps"] as? kotlinx.serialization.json.JsonArray
            appsRaw?.forEach { item ->
                val app = item as? JsonObject ?: return@forEach
                val name = (app.str("app_name") ?: app.str("package"))?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                val totalMs = app.int("total_ms")
                val totalMinutes = app.int("total_minutes")
                    ?: (totalMs?.let { it / 60000 } ?: 0)
                apps.add(
                    ScreenTimeAppUsage(
                        name = name,
                        totalMs = totalMs?.toLong() ?: totalMinutes.toLong() * 60000L,
                        totalMinutes = totalMinutes,
                    ),
                )
            }
            return ScreenTimeResult(
                totalMinutes = obj.int("total_minutes") ?: 0,
                apps = apps,
                start = obj.str("start"),
                end = obj.str("end"),
                error = obj.str("error"),
            )
        }
    }
}

data class ScreenTimeAppUsage(val name: String, val totalMs: Long, val totalMinutes: Int)

/** screen_time_tool_ui.dart formatScreenTimeMinutes — "2h 5m" / "2h" / "5m". */
fun formatScreenTimeMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    if (h > 0 && m > 0) return "${h}h ${m}m"
    if (h > 0) return "${h}h"
    return "${m}m"
}

/** screen_time_tool_ui.dart formatScreenTimeRange — MM-dd HH:mm, raw fallback. */
fun formatScreenTimeRange(iso: String): String {
    // Dart 的 DateTime.parse 总是得到一个本地时区下的瞬时值，所以带偏移的
    // 输入要先换算到系统时区；无偏移的裸时间才按 LocalDateTime 直接用。
    val parsed: LocalDateTime = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    } catch (e: Exception) {
        try { LocalDateTime.parse(iso) } catch (e2: Exception) { return iso }
    }
    return parsed.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

/**
 * Compact screen-time summary for the tool card
 * (screen_time_tool_ui.dart ScreenTimeToolSummary).
 */
@Composable
fun ScreenTimeToolSummary(
    result: ScreenTimeResult,
    textColor: Color,
    modifier: Modifier = Modifier,
    maxApps: Int = 3,
    secondaryColor: Color = textColor.copy(alpha = 0.8f),
    errorColor: Color = MaterialTheme.colorScheme.error,
) {
    if (result.isNoPermission) {
        Text(
            text = androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_screen_time_permission_required),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.2.sp,
                color = errorColor,
            ),
            modifier = modifier,
        )
        return
    }
    if (!result.hasApps) return
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_screen_time_total),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.6.sp, color = secondaryColor),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatScreenTimeMinutes(result.totalMinutes),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.6.sp,
                    fontWeight = AppFontWeights.medium,
                    color = textColor,
                ),
            )
        }
        for (app in result.apps.take(maxApps)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = app.name,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.6.sp, color = textColor),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    text = formatScreenTimeMinutes(app.totalMinutes),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.6.sp, color = secondaryColor),
                )
            }
        }
    }
}

/**
 * Full screen-time detail body for the tool result sheet
 * (screen_time_tool_ui.dart ScreenTimeToolDetailBody).
 */
@Composable
fun ScreenTimeToolDetailBody(result: ScreenTimeResult) {
    val cs = MaterialTheme.colorScheme
    val maxAppMs = result.apps.maxOfOrNull { it.totalMs }?.coerceAtLeast(1L) ?: 1L

    val rangeLabel = if (result.start != null && result.end != null) {
        "${formatScreenTimeRange(result.start)} → ${formatScreenTimeRange(result.end)}"
    } else null

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_screen_time_total),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = AppFontWeights.semibold,
                    color = cs.onSurface,
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatScreenTimeMinutes(result.totalMinutes),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = AppFontWeights.semibold,
                    color = cs.primary,
                ),
            )
        }
        if (rangeLabel != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = rangeLabel,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.6f)),
            )
        }
        Spacer(Modifier.height(16.dp))
        for (app in result.apps) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.name,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = cs.onSurface),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        text = formatScreenTimeMinutes(app.totalMinutes),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (app.totalMs.toFloat() / maxAppMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                    color = cs.primary,
                    trackColor = cs.onSurface.copy(alpha = 0.08f),
                )
            }
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.int(key: String): Int? =
    str(key)?.toIntOrNull() ?: str(key)?.toDoubleOrNull()?.toInt()

private fun JsonObject.dbl(key: String): Double? =
    str(key)?.toDoubleOrNull()
