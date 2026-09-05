package com.psyche.memo.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Calculator
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.CalendarPlus
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.ClipboardCheck
import com.composables.icons.lucide.ClipboardPen
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CloudSun
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wrench
import com.psyche.memo.ui.R as UiR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Tool call card rendering — 1:1 port of the chat_message_widget.dart tool
 * surfaces (_ToolCallItem inline card / _TimelineStepRow summary, _showToolDetail
 * sheet, thinking_sheen.dart breathing sheen). Tool *execution* is a later
 * batch; this file is the rendering layer only.
 */

/** UI data for a tool call — mirrors chat_message_widget.dart ToolUIPart. */
data class ToolUiPart(
    val id: String,
    val toolName: String,
    val arguments: JsonObject,
    val content: String?,
    val metadata: JsonObject?,
) {
    /** content 为 null/空表示仍在执行（chat_message_widget.dart loading 语义）。 */
    val loading: Boolean get() = content.isNullOrEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** chat_message_widget.dart toolUiFromPayload — 解析失败返回 null。 */
        fun fromPayload(payloadJson: String, fallbackOrdinal: Int = 0): ToolUiPart? {
            val obj = try { json.parseToJsonElement(payloadJson).jsonObject } catch (e: Exception) { return null }
            var id = obj.str("id").orEmpty()
            val name = obj.str("name").orEmpty()
            if (id.isEmpty()) id = "${if (name.isEmpty()) "tool" else name}-$fallbackOrdinal"
            return ToolUiPart(
                id = id,
                toolName = name,
                arguments = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                content = obj.str("content"),
                metadata = obj["metadata"] as? JsonObject,
            )
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is JsonNull }?.content

// ---------------------------------------------------------------------------
// Icon / title mapping (chat_message_widget.dart _toolIconFor / _toolTitleFor)
// ---------------------------------------------------------------------------

fun toolIconFor(name: String, args: JsonObject? = null): ImageVector = when (name) {
    "ask_user" -> Lucide.MessageCircleQuestion
    "time_info" -> Lucide.Clock
    "clipboard" -> when (args?.str("action")) {
        "read" -> Lucide.ClipboardCheck
        "write" -> Lucide.ClipboardPen
        else -> Lucide.Clipboard
    }
    "text_to_speech" -> Lucide.Volume2
    "calculate" -> Lucide.Calculator
    "screen_time" -> Lucide.Smartphone
    "calendar_query" -> Lucide.Calendar
    "calendar_create" -> Lucide.CalendarPlus
    "current_location" -> Lucide.MapPin
    "weather" -> Lucide.CloudSun
    "health_summary" -> Lucide.HeartPulse
    "reminders_query" -> Lucide.ListTodo
    "reminders_create" -> Lucide.CalendarPlus
    "reminders_complete" -> Lucide.ListTodo
    // memory_* 家族（chat_message_widget.dart:427-437）
    "memory_read", "memory_update", "memory_search_profile", "memory_edit",
    "edit_memory", "create_memory", "update_user_profile",
    -> Lucide.Search
    "memory_delete", "delete_memory" -> Lucide.Wrench
    "chat_search", "builtin_search" -> Lucide.Search
    "search_web" -> Lucide.Earth
    // Provider 内置服务端工具（chat_message_widget.dart:446-455）
    "web_fetch" -> Lucide.Earth
    "code_execution", "code_interpreter", "text_editor_code_execution" -> Lucide.Code
    "bash_code_execution" -> Lucide.Terminal
    else -> Lucide.Wrench
}

/** chat_message_widget.dart _toolTitleFor（本批覆盖的键；缺失键用通用标题）。 */
@Composable
fun toolTitleFor(name: String, args: JsonObject?, isResult: Boolean): String {
    if (name == "ask_user") {
        return androidx.compose.ui.res.stringResource(UiR.string.assistant_edit_local_tool_ask_user_title)
    }
    val local = when (name) {
        "time_info" -> UiR.string.assistant_edit_local_tool_time_info_title
        "clipboard" -> when (args?.str("action")) {
            "read" -> UiR.string.chat_message_widget_read_clipboard
            "write" -> UiR.string.chat_message_widget_write_clipboard
            else -> UiR.string.assistant_edit_local_tool_clipboard_title
        }
        "text_to_speech" -> UiR.string.chat_message_widget_speaking_title
        "calculate" -> UiR.string.assistant_edit_local_tool_calculate_title
        "screen_time" -> UiR.string.assistant_edit_local_tool_screen_time_title
        "calendar_query" -> UiR.string.assistant_edit_local_tool_calendar_query_title
        "calendar_create" -> UiR.string.assistant_edit_local_tool_calendar_create_title
        "current_location" -> UiR.string.assistant_edit_local_tool_location_title
        "weather" -> UiR.string.assistant_edit_local_tool_weather_title
        "health_summary" -> UiR.string.assistant_edit_local_tool_health_title
        "reminders_query" -> UiR.string.assistant_edit_local_tool_reminders_query_title
        "reminders_create" -> UiR.string.assistant_edit_local_tool_reminders_create_title
        "reminders_complete" -> UiR.string.assistant_edit_local_tool_reminders_complete_title
        else -> null
    }
    if (local != null) return androidx.compose.ui.res.stringResource(local)
    return when (name) {
        "memory_read" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_memory_read)
        "memory_update" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_memory_update)
        "memory_search_profile" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_memory_search_profile)
        "memory_edit", "edit_memory" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_memory_edit)
        "memory_delete", "delete_memory" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_memory_delete)
        "update_user_profile" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_update_user_profile)
        "chat_search" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_chat_search)
        "create_memory" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_create_memory)
        "search_web" -> androidx.compose.ui.res.stringResource(
            UiR.string.chat_message_widget_web_search,
            args?.str("query").orEmpty(),
        )
        "builtin_search" -> androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_builtin_search)
        else -> androidx.compose.ui.res.stringResource(
            if (isResult) UiR.string.chat_message_widget_tool_result
            else UiR.string.chat_message_widget_tool_call,
            if (name.isEmpty()) "tool" else name,
        )
    }
}

// ---------------------------------------------------------------------------
// ThinkingSheen（thinking_sheen.dart 呼吸高光 — srcIn 渐变扫过）
// ---------------------------------------------------------------------------

/**
 * 呼吸高光修饰符：base→peak 的斜向渐变随 progress 从左向右扫过内容
 * （thinking_sheen.dart ShaderMask srcIn + _SlideGradientTransform）。
 * speed 1.05 / spread 0.52 / intensity 0.68 与 thinkingSheenDefaults 一致。
 */
@Composable
fun Modifier.thinkingSheen(color: Color, isDark: Boolean, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "thinking-sheen")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2286, easing = LinearEasing), // 2400/1.05
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheen-progress",
    )
    return this.then(
        Modifier.drawWithCache {
            val base = color.copy(alpha = 1f)
            val highlight = lerp(base, Color.White, if (isDark) 0.82f else 0.78f)
            val peak = lerp(base, highlight, 0.42f + 0.68f * 0.58f)
            val mid = lerp(base, peak, 0.5f)
            val outer = (0.52f.coerceIn(0.2f, 0.9f)) / 2 // 0.26
            val inner = outer * 0.34f
            val stops = floatArrayOf(
                0f,
                (0.5f - outer).coerceIn(0.02f, 0.42f),
                (0.5f - inner).coerceIn(0.16f, 0.48f),
                0.5f,
                (0.5f + inner).coerceIn(0.52f, 0.84f),
                (0.5f + outer).coerceIn(0.58f, 0.98f),
                1f,
            )
            val colors = listOf(base, base, mid, peak, mid, base, base)
            // 源码 _SlideGradientTransform：x 平移 width*(t*2-1)。
            val tx = size.width * (progress * 2f - 1f)
            val brush = Brush.linearGradient(
                *stops.mapIndexed { i, stop -> stop to colors[i] }.toTypedArray(),
                start = Offset(tx, -0.18f * size.height),
                end = Offset(tx + size.width, 0.18f * size.height),
            )
            onDrawWithContent {
                drawContent()
                drawRect(brush, blendMode = BlendMode.SrcIn)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Inline tool call card (chat_message_widget.dart _ToolCallItem, mobile)
// ---------------------------------------------------------------------------

/**
 * 消息时间线里的工具卡：18dp 状态位（loading spinner / 完成图标）+ 标题
 * （loading 时呼吸高光）+ 可选摘要（weather / screen_time 专属 UI）。
 * 点击打开详情弹层。审批等待态属工具执行器批次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCallCard(part: ToolUiPart) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    var showDetail by remember { mutableStateOf(false) }

    val isResult = !part.loading
    val title = toolTitleFor(part.toolName, part.arguments, isResult)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                cs.primaryContainer.copy(alpha = if (isDark) 0.25f else 0.30f),
                RoundedCornerShape(16.dp),
            )
            .clickable { showDetail = true }
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态位 — loading spinner / 工具图标
                // (chat_message_widget.dart:5722-5751)。
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (part.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary,
                        )
                    } else {
                        Icon(
                            toolIconFor(part.toolName, part.arguments),
                            contentDescription = null,
                            tint = cs.onSurface.copy(alpha = 0.88f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface.copy(alpha = 0.88f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .thinkingSheen(
                            cs.onSurface.copy(alpha = 0.88f),
                            isDark,
                            enabled = part.loading,
                        ),
                )
            }
            // TTS 工具卡：播放行在 loading 与完成时都显示
            // （chat_message_widget.dart:5793-5801）。
            if (part.toolName == "text_to_speech") {
                val ttsText = textToSpeechToolText(part.arguments)
                if (ttsText.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextToSpeechReplayRow(
                        text = ttsText,
                        textColor = cs.onSurface.copy(alpha = 0.72f),
                        buttonColor = cs.primary,
                    )
                }
            } else if (!part.loading) {
                // 完成后的专属摘要（chat_message_widget.dart:5802-5846）。
                val summary = toolSummaryOrNull(part)
                if (summary != null) {
                    Spacer(Modifier.height(8.dp))
                    summary()
                }
            }
        }
    }

    if (showDetail) {
        ToolDetailSheet(part = part, onDismiss = { showDetail = false })
    }
}

/** weather / screen_time / tts 专属摘要，无专属内容时返回 null。 */
@Composable
fun toolSummaryOrNull(part: ToolUiPart): (@Composable () -> Unit)? {
    val summary: (@Composable () -> Unit)? = when (part.toolName) {
        "weather" -> {
            val weather = WeatherToolResult.tryParse(part.content)
            if (weather != null && !weather.isError) {
                { -> WeatherSummaryLine(weather) }
            } else null
        }
        "screen_time" -> {
            val screenTime = ScreenTimeResult.tryParse(part.content)
            if (screenTime != null && (screenTime.isNoPermission || screenTime.hasApps)) {
                { -> ScreenTimeToolSummary(screenTime) }
            } else null
        }
        // TTS 工具卡的播放行（chat_message_widget.dart:5793-5801）。
        "text_to_speech" -> {
            val ttsText = textToSpeechToolText(part.arguments)
            if (ttsText.isNotEmpty()) {
                val cs = MaterialTheme.colorScheme
                { -> TextToSpeechReplayRow(text = ttsText, textColor = cs.onSurface.copy(alpha = 0.72f), buttonColor = cs.primary) }
            } else null
        }
        else -> null
    }
    return summary
}

@Composable
private fun WeatherSummaryLine(result: WeatherToolResult) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = weatherCurrentLine(result),
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
        ),
    )
}

// ---------------------------------------------------------------------------
// Detail sheet (chat_message_widget.dart _showToolDetail + tool_detail_text_section)
// ---------------------------------------------------------------------------

private const val LAZY_LINE_THRESHOLD = 120
private const val LAZY_CHAR_THRESHOLD = 8000
private const val CHUNK_LINES = 40

/** tool_detail_text_section.dart shouldChunk — 大文本分块懒加载阈值。 */
internal fun shouldChunkText(text: String): Boolean {
    if (text.length > LAZY_CHAR_THRESHOLD) return true
    var lines = 1
    for (ch in text) {
        if (ch == '\n') {
            lines++
            if (lines > LAZY_LINE_THRESHOLD) return true
        }
    }
    return false
}

internal fun chunkText(text: String): List<String> {
    val lines = text.split('\n')
    val chunks = ArrayList<String>()
    var start = 0
    while (start < lines.size) {
        val end = (start + CHUNK_LINES).coerceAtMost(lines.size)
        chunks.add(lines.subList(start, end).joinToString("\n"))
        start = end
    }
    return chunks.ifEmpty { listOf(text) }
}

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

/** chat_message_widget.dart _prettyToolJson — 失败时原样返回。 */
private fun prettyToolJson(raw: String): String = try {
    prettyJson.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        prettyJson.parseToJsonElement(raw),
    )
} catch (e: Exception) {
    raw
}

/**
 * 工具详情弹层（mobile bottom sheet 路径）：标题 + Arguments / Result
 * 两个分块文本区（surfaceFill 10dp 圆角），screen_time 有 apps 时换成
 * 专属详情体。工具结果图片段属工具执行器批次（依赖其 metadata 约定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(part: ToolUiPart, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title = toolTitleFor(part.toolName, part.arguments, isResult = !part.loading)
    val argsLabel = androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_arguments)
    val resultLabel = androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_result)
    val argsPretty = prettyJson.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        part.arguments,
    )
    val resultText = if (!part.content.isNullOrEmpty()) prettyToolJson(part.content)
    else androidx.compose.ui.res.stringResource(UiR.string.chat_message_widget_no_result_yet)

    val screenTime = if (part.toolName == "screen_time") ScreenTimeResult.tryParse(part.content) else null
    val useScreenTimeDetail = screenTime != null && screenTime.hasApps

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = cs.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            if (useScreenTimeDetail && screenTime != null) {
                item {
                    ScreenTimeToolDetailBody(result = screenTime)
                    Spacer(Modifier.height(24.dp))
                }
            } else {
                toolDetailTextSection(
                    label = argsLabel,
                    text = argsPretty,
                )
                item { Spacer(Modifier.height(12.dp)) }
                toolDetailTextSection(
                    label = resultLabel,
                    text = resultText,
                )
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * tool_detail_text_section.dart ToolDetailTextSection：12sp 标签 + 10dp
 * 内边距文本块；超阈值文本按 40 行分块挂到外层 LazyColumn 懒加载。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.toolDetailTextSection(label: String, text: String) {
    // 分块决策在 lazy scope 上做（tool_detail_text_section.dart 的
    // DecoratedSliver + SliverList 等价物）：小文本单个 item，大文本按
    // 40 行 chunk 逐 item 懒布局。
    val chunked = shouldChunkText(text)
    if (!chunked) {
        item {
            val cs = MaterialTheme.colorScheme
            Column {
                SectionLabel(label, cs.onSurface)
                TextBlockContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                }
            }
        }
    } else {
        val chunks = chunkText(text)
        item { SectionLabel(label, MaterialTheme.colorScheme.onSurface) }
        items(chunks) { chunk ->
            val cs = MaterialTheme.colorScheme
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        cs.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(if (chunk == chunks.first() || chunk == chunks.last()) 10.dp else 0.dp),
                    ),
            ) {
                Text(
                    text = chunk,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, cs: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp,
            color = cs.copy(alpha = 0.6f),
        ),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun TextBlockContainer(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                cs.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) {
        content()
    }
}

// TTS replay row lives in TtsPlayer.kt (TextToSpeechReplayRow).
