package com.psyche.memo.provider.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一条参数违规：哪个参数、没满足什么约束、该怎么改。
 *
 * 学 deepseek-harness 的 B8：参数校验失败时，模型拿到的不该是一句
 * `kotlin.IllegalStateException: path must be an absolute path`，而是一份**能照着改**
 * 的清单。一句散文它只能猜；一条 `param/constraint/fix` 它一次就改对。
 * 更关键的是这条纪律的前提：**校验失败时那颗工具根本没执行**，所以错误里必须说清楚
 * 「什么都没发生」，否则模型会以为文件已经写了。
 */
data class ArgViolation(val param: String, val constraint: String, val fix: String) {
    fun toJson(): JsonElement = JsonObject(
        linkedMapOf(
            "param" to JsonPrimitive(param),
            "constraint" to JsonPrimitive(constraint),
            "fix" to JsonPrimitive(fix),
        ),
    )
}

/**
 * 收集式参数校验器：所有访问器**不抛异常**，缺什么就往 [violations] 里记一条，
 * 让一次调用能报全所有问题（模型修一处撞一处的体验最差）。
 */
class ToolArgs(private val args: JsonObject) {

    val violations = mutableListOf<ArgViolation>()

    /** 字符串参数。空串/纯空白算缺；`required = false` 时缺就不算违规（可选参数）。 */
    fun string(name: String, required: Boolean = true, maxChars: Int? = null): String? {
        val raw = (args[name] as? JsonPrimitive)?.contentOrNull
        if (raw.isNullOrBlank()) {
            if (!required) return null
            violations += ArgViolation(
                param = name,
                constraint = "required, non-empty text",
                fix = "call again with \"$name\" filled in",
            )
            return null
        }
        if (maxChars != null && raw.length > maxChars) {
            violations += ArgViolation(
                param = name,
                constraint = "at most $maxChars characters (got ${raw.length})",
                fix = "shorten \"$name\" (split it up, or write the file in parts)",
            )
            return null
        }
        return raw
    }

    /** rootfs 内的绝对路径（与 [com.psyche.memo.provider.workspace.WorkspaceTools] 同一口径）。 */
    fun absolutePath(name: String, required: Boolean = true): String? {
        val raw = (args[name] as? JsonPrimitive)?.contentOrNull?.replace('\\', '/')?.trim()
        if (raw.isNullOrBlank()) {
            if (required) {
                violations += ArgViolation(
                    param = name,
                    constraint = "required, an absolute path inside the rootfs",
                    fix = "pass \"$name\" like \"/workspace/notes.md\" (paths are always absolute)",
                )
            }
            return null
        }
        if (!raw.startsWith("/")) {
            violations += ArgViolation(
                param = name,
                constraint = "must start with \"/\" (got \"$raw\")",
                fix = "prefix it with the mount point, e.g. \"/workspace/$raw\"",
            )
            return null
        }
        if (raw.contains('\u0000')) {
            violations += ArgViolation(
                param = name,
                constraint = "must not contain a NUL character",
                fix = "re-type \"$name\" without control characters",
            )
            return null
        }
        return raw
    }

    fun boolean(name: String, default: Boolean): Boolean {
        val element = args[name] ?: return default
        val primitive = element as? JsonPrimitive
        return primitive?.booleanOrNull
            ?: primitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: run {
                violations += ArgViolation(
                    param = name,
                    constraint = "a JSON true/false (got ${element.toString().take(24)})",
                    fix = "pass \"$name\" as a bare true or false, not a quoted string",
                )
                default
            }
    }

    fun int(name: String, default: Int, minimum: Int, maximum: Int): Int {
        val element = args[name] ?: return default
        val value = (element as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (value == null) {
            violations += ArgViolation(
                param = name,
                constraint = "an integer between $minimum and $maximum (got ${element.toString().take(24)})",
                fix = "pass \"$name\" as a number, not text",
            )
            return default
        }
        if (value < minimum || value > maximum) {
            violations += ArgViolation(
                param = name,
                constraint = "between $minimum and $maximum (got $value)",
                fix = "clamp \"$name\" into that range",
            )
            return default
        }
        return value
    }

    val isValid: Boolean get() = violations.isEmpty()
}
