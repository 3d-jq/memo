package com.psyche.memo.llm.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * 2xx 流里夹带的**带内错误帧**（`chat_api_helpers.dart:857-919`
 * `throwIfInBandStreamError`）。
 *
 * 为什么必须有：不少网关出错时 HTTP 状态码仍是 200，失败信息藏在 SSE 的某一帧里
 * （`{"error":{...}}`、Anthropic 的 `event: error`、Responses API 的
 * `response.failed` / `response.incomplete`）。不认它，流就"正常结束"了 —— 半成品
 * 回答被当成功落库，用户只看到话说了一半，连报错都没有（比裸英文更难查）。
 *
 * 健康的 chunk 要么没有 `error` 键，要么是 null/空占位，一律放过；判定顺序与上游
 * 逐条一致。
 */
private val inBandJson = Json { ignoreUnknownKeys = true }

/** 上游的廉价前置闸门：整帧里这三个字串都没有就直接返回。 */
fun throwIfInBandStreamError(data: String) {
    if (!data.contains("\"error\"") &&
        !data.contains("response.failed") &&
        !data.contains("response.incomplete")
    ) {
        return
    }
    val obj = runCatching { inBandJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
    when (obj["type"].textValue().trim()) {
        "error" -> {
            // Anthropic 把载荷套在 `error` 里；Responses API 把 code/message 平铺在帧上。
            val nested = obj["error"] as? JsonObject
            if (nested != null && nested.isNotEmpty()) throwInBand(nested)
            throwInBand(obj)
        }

        "response.failed", "response.incomplete" -> {
            // Responses API 的终止失败事件把错误套在 `response` 里。
            val response = obj["response"] as? JsonObject
            if (response != null) {
                throwInBand(response["error"])
                val details = response["incomplete_details"] as? JsonObject
                if (details != null && details.isNotEmpty()) {
                    val reason = details["reason"].textValue().trim()
                    throw IOException(
                        if (reason.isEmpty()) {
                            "Provider error: response incomplete"
                        } else {
                            "Provider error: response incomplete ($reason)"
                        },
                    )
                }
            }
            // 载荷解不出来的失败事件也不能被当成正常收尾。
            // 用 textValue() 而不是裸插值：JsonPrimitive 的 toString() 会连引号一起带出来。
            throw IOException("Provider error: ${obj["type"].textValue()}")
        }
    }
    throwInBand(obj["error"])
}

/** `_throwOnInBandStreamError`：null / 空对象 / 数字布尔都放行，其余一律抛。 */
private fun throwInBand(error: Any?) {
    when (error) {
        is JsonObject -> if (error.isNotEmpty()) {
            val message = error["message"].textValue().trim()
            val code = error["code"].textValue().ifEmpty { error["type"].textValue() }.trim()
            // 上游是 jsonEncode(error)；JsonObject 的 toString() 是 map 形状（`{a=1}`），
            // 所以这里显式编码回 JSON。
            val detail = message.ifEmpty { inBandJson.encodeToString(JsonElement.serializer(), error) }
            throw IOException(
                if (code.isEmpty()) "Provider error: $detail" else "Provider error ($code): $detail",
            )
        }

        // Dart 侧 `error is String` 只对 JSON 字符串生效。
        is JsonPrimitive -> if (error.isString) {
            val text = error.content.trim()
            if (text.isNotEmpty()) throw IOException("Provider error: $text")
        }
    }
}

private fun Any?.textValue(): String = when (this) {
    is JsonPrimitive -> if (isString) contentOrNull.orEmpty() else content
    else -> ""
}
