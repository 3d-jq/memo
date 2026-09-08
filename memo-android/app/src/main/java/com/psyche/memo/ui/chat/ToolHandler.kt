package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.BuiltInToolCatalog
import com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * tool_handler_service.dart buildToolCallHandler 的 Native 分派。覆盖有执行路径
 * 的工具子集（get_time_info / ask_user_input_v0 / calendar_create 审批门 /
 * search_web 搜索引擎）；MCP / memory 执行器未移植，未提供的工具不会出现在请求里。
 * 未覆盖的工具用 execution_error 如实上报，模型可据此重试。
 */
class ToolHandler(
    private val approvalService: ToolApprovalService?,
    private val askUserService: AskUserInteractionService?,
    private val conversationId: String?,
    private val assistant: Assistant?,
    private val searchEngine: com.psyche.memo.provider.search.SearchEngine? = null,
    private val searchService: com.psyche.memo.data.model.SearchServiceOptions? = null,
    private val searchCommonOptions: com.psyche.memo.data.model.SearchCommonOptions =
        com.psyche.memo.data.model.SearchCommonOptions(),
    private val container: com.psyche.memo.AppContainerImpl? = null,
    private val isTemporary: Boolean = false,
) {

    /** 处理一个工具调用，返回写回模型的内容（tool_error 为 JSON 字符串）。 */
    suspend fun handle(name: String, args: JsonObject, toolCallId: String?): String {
        return try {
            // Search tool (tool_handler_service.dart L435-439).
            if (name == com.psyche.memo.provider.search.SearchToolService.TOOL_NAME &&
                assistant?.searchEnabled == true
            ) {
                val query = (args["query"] as? JsonPrimitive)?.content ?: ""
                val engine = searchEngine
                    ?: return toolError(
                        error = "search_unavailable",
                        message = "Search engine is unavailable.",
                        tool = name,
                    )
                return com.psyche.memo.provider.search.SearchToolService.executeSearch(
                    query = query,
                    engine = engine,
                    service = searchService,
                    common = searchCommonOptions,
                )
            }
            // Creating calendar events or changing reminders modifies user data,
            // so those tools always require explicit user approval first.
            if (LocalToolNames.requiresUserApproval.contains(name) &&
                assistant != null &&
                assistant.localToolIds.contains(name) &&
                approvalService != null
            ) {
                val approval = approvalService.requestApproval(
                    toolCallId = approvalIdFor(name, toolCallId),
                    toolName = name,
                    arguments = args,
                    conversationId = conversationId,
                ).await()
                if (!approval.approved) {
                    return toolError(
                        error = "approval_denied",
                        message = approval.denyReason ?: "User denied the tool call",
                        tool = name,
                    )
                }
            }

            // Memory tools (memory_tools.dart handle)：enableMemory 才生效。
            container?.let { c ->
                com.psyche.memo.provider.MemoryTools.handle(
                    container = c,
                    assistant = assistant,
                    conversationId = conversationId,
                    isTemporary = isTemporary,
                    name = name,
                    args = args,
                )?.let { return it }
            }

            // Local tools (local_tools_service.dart tryHandleToolCall 451-529):
            // get_time_info is the only one with a native executor so far.
            if (name == LocalToolNames.TIME_INFO &&
                assistant != null &&
                assistant.localToolIds.contains(name)
            ) {
                return timeInfoJson()
            }

            if (name == AskUserToolNames.ASK_USER &&
                assistant != null &&
                assistant.localToolIds.contains(AskUserToolNames.ASK_USER)
            ) {
                if (askUserService == null) {
                    return toolError(
                        error = "ask_user_unavailable",
                        message = "Ask user interaction service is unavailable.",
                        tool = name,
                    )
                }
                return try {
                    askUserService.requestAnswer(
                        toolCallId = toolCallId?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "${name}_${System.currentTimeMillis() * 1000}",
                        arguments = args,
                        conversationId = conversationId,
                    ).await().jsonString
                } catch (e: AskUserInvalidRequestException) {
                    toolError(
                        error = "invalid_ask_user_request",
                        message = e.message ?: "",
                        tool = name,
                    )
                }
            }

            // Dart falls through to the MCP call here; the native executor set
            // is unported, so an offered-but-unexecutable tool reports honestly.
            toolError(
                error = "execution_error",
                message = "Tool '$name' has no executor on this platform.",
                tool = name,
                instruction = "The tool execution failed unexpectedly. You may try again with different parameters or inform the user about the issue.",
            )
        } catch (e: Exception) {
            toolError(
                error = "execution_error",
                message = e.toString(),
                tool = name,
                instruction = "The tool execution failed unexpectedly. You may try again with different parameters or inform the user about the issue.",
            )
        }
    }

    /** tool_handler_service.dart approvalIdFor 382-386 — 空 toolCallId 落到 name_µs。 */
    fun approvalIdFor(name: String, toolCallId: String?): String {
        val trimmed = toolCallId?.trim()
        if (!trimmed.isNullOrEmpty()) return trimmed
        return "${name}_${System.currentTimeMillis() * 1000}"
    }

    /** tool_handler_service.dart _toolError 179-192。 */
    private fun toolError(
        error: String,
        message: String,
        tool: String,
        instruction: String? = null,
    ): String = buildJsonObject {
        put("type", "tool_error")
        put("error", error)
        put("message", message)
        put("tool", tool)
        instruction?.let { put("instruction", it) }
    }.toString()

    /** local_tools_service.dart 460-461 — get_time_info 返回 `jsonEncode(_buildTimeInfoPayload(...))`。 */
    private fun timeInfoJson(): String =
        buildTimeInfoPayload(ZonedDateTime.now()).toString()

    /** local_tools_service.dart `_buildTimeInfoPayload` 1048-1077 的 java.time 移植。 */
    internal fun buildTimeInfoPayload(now: ZonedDateTime): JsonObject {
        val totalSeconds = now.offset.totalSeconds
        val sign = if (totalSeconds < 0) "-" else "+"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val offsetHours = (absSeconds / 3600).toString().padStart(2, '0')
        val offsetMinutes = ((absSeconds % 3600) / 60).toString().padStart(2, '0')

        val year = now.year.toString().padStart(4, '0')
        val month = now.monthValue.toString().padStart(2, '0')
        val day = now.dayOfMonth.toString().padStart(2, '0')
        val hour = now.hour.toString().padStart(2, '0')
        val minute = now.minute.toString().padStart(2, '0')
        val second = now.second.toString().padStart(2, '0')
        val weekdayEn = englishWeekdayName(now.dayOfWeek)

        return buildJsonObject {
            put("year", now.year)
            put("month", now.monthValue)
            put("day", now.dayOfMonth)
            put("weekday", weekdayEn)
            put("weekday_en", weekdayEn)
            put("weekday_index", now.dayOfWeek.value)
            put("date", "$year-$month-$day")
            put("time", "$hour:$minute:$second")
            // DateTime.toIso8601String() — local, always 6-digit microseconds.
            put("datetime", isoLocalDateTime(now))
            // Dart timeZoneName (local name); native exposes the zone id instead.
            put("timezone", now.zone.id)
            put("utc_offset", "$sign$offsetHours:$offsetMinutes")
            put("timestamp_ms", now.toInstant().toEpochMilli())
        }
    }

    private fun isoLocalDateTime(now: ZonedDateTime): String {
        val dt = now.toLocalDateTime()
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02d.%06d",
            dt.year, dt.monthValue, dt.dayOfMonth,
            dt.hour, dt.minute, dt.second, dt.nano / 1000,
        )
    }

    private fun englishWeekdayName(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }
}
