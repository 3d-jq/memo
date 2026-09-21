package com.psyche.memo.llm.prompt

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.llm.client.LlmMessage

/**
 * 助手「限制上下文条数」的裁剪 —— 1:1 移植
 * `message_builder_service.applyContextLimit`（L2139-2166）。
 *
 * 关着（[enabled] = false）或 [size] ≤ 0 时不动；打开时**保留系统消息 + 最近 N 条**
 * （N 夹在 `Assistant.Min/MaxContextMessageSize` 之间），再从开头丢掉因裁剪而悬空的
 * `tool` 消息（裁点可能落在 assistant tool_calls + tool 结果三元组中间，留下孤立的
 * tool 结果会让部分厂商直接 400）。
 *
 * 原版的调用点是「世界书注入之后、OCR/文档抽取之前」（message_generation_service
 * L194-197），所以世界书插进来的尾部消息也在被保留的 N 条之内。
 */
internal fun applyContextLimit(
    messages: MutableList<LlmMessage>,
    enabled: Boolean,
    size: Int,
) {
    if (!enabled || size <= 0) return
    val keep = size.coerceIn(Assistant.MinContextMessageSize, Assistant.MaxContextMessageSize)
    val startIdx = if (messages.isNotEmpty() && messages.first().role == "system") 1 else 0
    val tail = messages.subList(startIdx, messages.size)
    if (tail.size > keep) {
        val trimmed = tail.subList(tail.size - keep, tail.size).toList()
        messages.subList(startIdx, messages.size).clear()
        messages.addAll(trimmed)
    }
    // 裁剪可能切在工具三元组中间：别把开头的悬空 tool 消息发出去。
    while (messages.size > startIdx && messages[startIdx].role == "tool") {
        messages.removeAt(startIdx)
    }
}
