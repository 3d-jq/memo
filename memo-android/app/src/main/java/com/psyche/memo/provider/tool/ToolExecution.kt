package com.psyche.memo.provider.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 工具执行的统一骨架 —— 照 deepseek-harness 的 execute() 契约
 * （`docs/cookbook/adding-a-tool.md`，见 `docs/ENGINEERING_HARNESS.md` §8）。
 *
 * 三条它明确要求、而我们此前缺的：
 *  1. **统一 deadline**：工具卡住不许挂死整轮对话，到点回 error（dsh 的 `tools/execute` 环绕）；
 *  2. **抛错 ⇒ isError（不吞）**：任何异常都归一成 `tool_error`，别让模型收到半截东西；
 *  3. **结果有上限**：工具结果整段进上下文，超长必须截断并**标明截断**（dsh 的 output cap）。
 *
 * 策略（deadline 表、上限）集中在本文件，**不写进各工具**——这是 dsh 的「策略不写进工具」。
 */
object ToolRunner {

    /** 默认 deadline：本地工具（剪贴板/计算/日历/图表）秒级就够。 */
    const val DEFAULT_TIMEOUT_MS = 20_000L

    /**
     * 逐工具的 deadline 覆盖（**策略集中在这里**）。
     *
     * `render_mermaid` 自己内部就有 25s 超时（离屏 WebView 渲染），外面必须给得更宽，
     * 否则会出现「外面先超时、里面还在跑」的双重超时。
     */
    private val TIMEOUT_OVERRIDES = mapOf(
        com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME to 35_000L,
    )

    /** 工具结果的字符上限（约 6k tokens 量级）：超了截断并附 `truncated` 说明。 */
    const val MAX_RESULT_CHARS = 24_000

    fun timeoutFor(tool: String): Long = TIMEOUT_OVERRIDES[tool] ?: DEFAULT_TIMEOUT_MS

    /**
     * 跑一次工具执行。
     *
     * 返回 `null` = **这个执行器不负责该工具**（调用方继续往下派发）；
     * 返回字符串 = 给模型的最终结果（已归一、已截断）。
     */
    suspend fun run(
        tool: String,
        timeoutMs: Long = timeoutFor(tool),
        body: suspend () -> String?,
    ): String? {
        var declined = false
        val value = try {
            withTimeoutOrNull(timeoutMs) {
                body().also { declined = it == null }
            }
        } catch (e: CancellationException) {
            // 外层取消（用户点停止）必须透传，别当成工具失败。
            throw e
        } catch (e: Throwable) {
            return cap(
                ToolResults.error(
                    code = "tool_crashed",
                    message = e.message ?: (e::class.simpleName ?: "unknown error"),
                    tool = tool,
                ),
            )
        }
        return when {
            value == null && declined -> null
            value == null -> cap(
                ToolResults.error(
                    code = "tool_timeout",
                    message = "工具在 ${timeoutMs / 1000}s 内没有返回结果；请简化参数后重试。",
                    tool = tool,
                ),
            )
            else -> cap(value)
        }
    }

    /** 结果上限：截断并**标明**截断（不许把截断后的结果当成完整结果）。 */
    fun cap(result: String): String {
        if (result.length <= MAX_RESULT_CHARS) return result
        val kept = result.take(MAX_RESULT_CHARS)
        return buildJsonObject {
            put("type", JsonPrimitive("tool_result_truncated"))
            put("status", JsonPrimitive("ok_truncated"))
            put("truncated", JsonPrimitive(true))
            put("originalChars", JsonPrimitive(result.length))
            put(
                "message",
                JsonPrimitive(
                    "工具结果超过 $MAX_RESULT_CHARS 字符，已截断 —— 请缩小范围（更少的条目/更短的查询）后重试。",
                ),
            )
            put("content", JsonPrimitive(kept))
        }.toString()
    }
}

/**
 * 工具结果的**规范形状**（照 dsh：一个 canonical value + isError 语义）。
 *
 * 现状与取舍：错误侧已有统一形状（`type=tool_error` + `error` + `message` + `tool`），
 * 成功侧过去是各工具手搓（`{"type":"chart_result","paths":[…]}`…）。这里**不重命名**各工具
 * 自己的 `type`（视图层与产物提取都在读它），而是加一层稳定外壳：
 *
 * 成功 = `{"type": <工具自己的> | "tool_result", "status": "ok", "tool": <名>, …工具字段}`
 * 失败 = `{"type": "tool_error", "status": "error", "error": <码>, "message": …, "tool": <名>}`
 *
 * 于是模型拿到的是**形状稳定**的值：看 `status` 就知道成败，不必再从散文里猜
 *（上一轮「工具明明成功、模型却说没成功」就是缺这一层）。
 */
object ToolResults {

    fun ok(
        tool: String,
        type: String? = null,
        fields: JsonObject = buildJsonObject { },
    ): String {
        val out = buildJsonObject {
            put("type", JsonPrimitive(type ?: "tool_result"))
            put("status", JsonPrimitive("ok"))
            put("tool", JsonPrimitive(tool))
            fields.forEach { (key, value) -> put(key, value) }
        }
        return out.toString()
    }

    fun error(
        code: String,
        message: String,
        tool: String,
        instruction: String? = null,
        type: String = "tool_error",
    ): String = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("status", JsonPrimitive("error"))
        put("error", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
        put("tool", JsonPrimitive(tool))
        instruction?.let { put("instruction", JsonPrimitive(it)) }
    }.toString()
}
