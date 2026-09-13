package com.psyche.memo.ui.chat

import com.psyche.memo.ChatViewModel
import com.psyche.memo.common.SessionCompaction
import com.psyche.memo.data.model.CompactionPart
import com.psyche.memo.data.model.FilePart
import com.psyche.memo.data.model.ImagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart

/**
 * 上下文压缩（opencode 阈值机制）在 UI 侧用到的纯映射：
 * - [checkpointPart]：这条消息是不是压缩检查点
 * - [compactionWindow]：opencode `history.load` 的 latestCompaction —— 最后一条
 *   检查点 + 它 `boundaryOrder` 之后的消息（检查点排在最前，其余消息原顺序）
 * - [compactionEntry]：UiMessage → opencode `serialize` 的 [SessionCompaction.Entry]
 *
 * 检查点消息本身不参与序列化（opencode 过滤 `type !== "compaction"`），它整条被
 * `<conversation-checkpoint>` user 轮次替换。
 */
internal fun ChatViewModel.UiMessage.checkpointPart(): CompactionPart? =
    parts.filterIsInstance<CompactionPart>().firstOrNull()

internal fun compactionWindow(
    messages: List<ChatViewModel.UiMessage>,
): List<ChatViewModel.UiMessage> {
    if (messages.isEmpty()) return messages
    val entries = messages.map { message ->
        val part = message.checkpointPart()
        SessionCompaction.WindowEntry(
            id = message.id,
            order = message.messageOrder,
            checkpoint = part?.let {
                SessionCompaction.Checkpoint(it.summary, it.recent, it.boundaryOrder)
            },
        )
    }
    val byId = messages.associateBy { it.id }
    return SessionCompaction.windowIds(entries).mapNotNull { byId[it] }
}

/** null = 不参与压缩序列化（检查点消息、或没有任何可读内容的空消息）。 */
internal fun compactionEntry(message: ChatViewModel.UiMessage): SessionCompaction.Entry? {
    if (message.checkpointPart() != null) return null
    val parts = message.parts.mapNotNull { part ->
        when (part) {
            is TextPart -> SessionCompaction.Part.Text(part.text)
            is ReasoningPart -> SessionCompaction.Part.Reasoning(part.text)
            is ToolCallPart -> toolCallEntry(part)
            else -> null
        }
    }
    val attachments = message.parts.mapNotNull { part ->
        when (part) {
            is ImagePart -> "${part.mime ?: "image/*"}: ${part.uri}"
            is FilePart -> "${part.mime ?: "application/octet-stream"}: ${part.name}"
            else -> null
        }
    }
    if (parts.isEmpty() && attachments.isEmpty()) return null
    return SessionCompaction.Entry(role = message.role, parts = parts, attachments = attachments)
}

private fun toolCallEntry(part: ToolCallPart): SessionCompaction.Part.ToolCall? {
    val payload = ToolCallPart.decode(part.payloadJson) ?: return null
    return SessionCompaction.Part.ToolCall(
        name = payload.name,
        input = payload.arguments,
        result = payload.content,
    )
}
