package com.psyche.memo.llm.prompt

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Port of PromptTransformer in lib/core/services/chat/prompt_transformer.dart.
 *
 * 两条替换通道（原版的两种括号，别混）：
 * - **消息模板**用双花括号 `{{ role }}` / `{{ message }}` / `{{ time }}` /
 *   `{{ date }}` → [applyMessageTemplate]；
 * - **系统提示词**用单花括号 `{cur_date}` … `{assistant_name}`（12 个，助手编辑页
 *   「可用变量」列的就是它们）→ [buildPlaceholders] + [replacePlaceholders]。
 *   平台相关的那几个值由 app 侧填（core:llm 不认识 Context/电量）。
 */
object PromptTransformer {

    private val VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun applyMessageTemplate(
        template: String,
        role: String,
        message: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String {
        val vars = mapOf(
            "role" to role,
            "message" to message,
            "time" to now.format(TIME_FORMAT),
            "date" to now.format(DATE_FORMAT),
        )
        return VARIABLE.replace(template) { match ->
            vars[match.groupValues[1]] ?: match.value
        }
    }

    /**
     * `PromptTransformer.buildPlaceholders`（prompt_transformer.dart L6-41）——
     * 系统提示词的 12 个变量。`{model_name}` 与原版一致地回退到 modelId（调用方也
     * 传同一个值）；`{battery_level}` / `{device_info}` 原版是写死的 `unknown` /
     * 只有 OS 名，Android 这边给了真值（见 PORTING §5.11「平台差异」）。
     */
    fun buildPlaceholders(
        assistantName: String,
        userNickname: String,
        modelId: String?,
        modelName: String?,
        locale: String,
        timezone: String,
        systemVersion: String,
        deviceInfo: String,
        batteryLevel: String = "unknown",
        now: LocalDateTime = LocalDateTime.now(),
    ): Map<String, String> {
        val date = now.format(DATE_FORMAT)
        val time = now.format(TIME_FORMAT)
        return mapOf(
            "{cur_date}" to date,
            "{cur_time}" to time,
            "{cur_datetime}" to "$date $time",
            "{model_id}" to (modelId ?: ""),
            "{model_name}" to (modelName ?: modelId ?: ""),
            "{locale}" to locale,
            "{timezone}" to timezone,
            "{system_version}" to systemVersion,
            "{device_info}" to deviceInfo,
            "{battery_level}" to batteryLevel,
            "{nickname}" to userNickname,
            "{assistant_name}" to assistantName,
        )
    }

    /**
     * `PromptTransformer.replacePlaceholders` L43-49：逐个 key 顺序 `replace`
     * （与 Dart 的 `replaceAll` 同语义，不做正则解释），未知变量原样留着。
     */
    fun replacePlaceholders(text: String, vars: Map<String, String>): String {
        var out = text
        for ((key, value) in vars) out = out.replace(key, value)
        return out
    }
}
