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

    /** 系统提示词里的 `{name}` 占位符；`{{...}}`（消息模板）与 JSON 花括号都不算。 */
    private val SINGLE_BRACE_PLACEHOLDER = Regex("""(?<!\{)\{(\w+)\}(?!\})""")

    /**
     * 提示词里出现、但**不认识**的占位符。
     *
     * dsh 的严格渲染是「未知变量直接抛」；我们这套是 `{...}` 写法（与 JSON 花括号同形），
     * 抛会误伤合法提示词，所以退一步做到 **不静默**：调用方拿到清单后记一条告警 ——
     * 否则 `{cur_dtae}` 这种手滑会**原样发给模型**（上游行为）而没人知道
     *（`docs/ENGINEERING_HARNESS.md` §2 fail loud 的柔性版）。
     */
    fun unknownPlaceholders(text: String, known: Set<String>): List<String> {
        if (text.isEmpty()) return emptyList()
        val found = SINGLE_BRACE_PLACEHOLDER.findAll(text).map { it.value }.toSet()
        return (found - known).sorted()
    }

    /**
     * 支持的占位符键（**唯一来源**：就是 [buildPlaceholders] 的 map 键）。
     *
     * 助手编辑页的「可用变量」清单必须与它一致 —— 两边各写一份必然漂移
     *（dsh「每个事实只有一个所有者」），由 `PromptVariableCatalogTest` 钉住。
     */
    fun supportedKeys(): Set<String> = buildPlaceholders(
        assistantName = "",
        userNickname = "",
        modelId = null,
        modelName = null,
        locale = "",
        timezone = "",
        systemVersion = "",
        deviceInfo = "",
    ).keys

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
