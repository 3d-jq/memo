package com.psyche.memo.data.model

/**
 * Chat message entity — mirrors Flutter `ChatMessage`.
 *
 * Text body is derived from [TextPart]s in order (same as Dart's `content`).
 * Versioning: groupId identifies a message thread; version starts at 0 and
 * increments for regenerations.
 */
class ChatMessage(
    val id: String,
    val role: String,
    var parts: List<MessagePart>,
    val timestamp: Long,
    val modelId: String? = null,
    val providerId: String? = null,
    val totalTokens: Int? = null,
    val conversationId: String,
    var isStreaming: Boolean = false,
    val reasoningStartAt: Long? = null,
    val reasoningFinishedAt: Long? = null,
    val translation: String? = null,
    val reasoningSegmentsJson: String? = null,
    val groupId: String,
    var version: Int = 0,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val durationMs: Long? = null,
    /**
     * 本轮**正文实际在流**的累计毫秒数 —— 生成速度的分母。
     *
     * 上游口径是 `completion ÷ 总耗时`，把排队、prefill 和工具轮次全算成「模型在写字」，
     * 一次带搜索的回答会显示成 2 tok/s；但也不能用「总耗时 − 首 token」：非流式回合里
     * 那个差值接近 0，会算出 600 tok/s（用户 2026-09-25 抓到）。所以记真实流式窗口，
     * 落在 `message_rows.extras_json`（schema 是 drift 生成的，不许加列）。
     */
    val textStreamMs: Long? = null,
    val updatedAt: Long? = null,
    val messageOrder: Int,
) {
    /** Concatenation of every TextPart in order. */
    val content: String
        get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

    /**
     * 上下文压缩检查点（parts 里带 [CompactionPart]）。**不是**用户/助手的真实发言：
     * 界面按分隔线渲染、导出/标题/总结/记忆抽取一律跳过；只有聊天请求组装会用到它
     * （整条替换成 `<conversation-checkpoint>` 轮次）。
     */
    val isCompaction: Boolean
        get() = parts.any { it is CompactionPart }

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()

        /**
         * Content-only rewrite preserving part ordinals: first TextPart gets
         * [newContent], later TextParts are skipped; non-text parts keep place.
         * No TextPart → prepends one. Mirrors Dart partsWithReplacedText.
         */
        fun partsWithReplacedText(original: List<MessagePart>, newContent: String): List<MessagePart> {
            var replaced = false
            val out = ArrayList<MessagePart>(original.size + 1)
            for (part in original) {
                if (part is TextPart) {
                    if (!replaced) {
                        out.add(TextPart(newContent))
                        replaced = true
                    }
                    // skip further TextParts
                } else {
                    out.add(part)
                }
            }
            if (!replaced) out.add(0, TextPart(newContent))
            return out
        }

        /**
         * chat_message.dart:237-245 `partsWithoutThinkingAndToolCards` —— 编辑助手
         * 消息且「保留思考/工具卡」关闭时，思考段与工具调用整个丢掉，图片/文件/
         * 未知 part 保留。
         */
        fun partsWithoutThinkingAndToolCards(original: List<MessagePart>): List<MessagePart> =
            original.filterNot { it is ReasoningPart || it is ToolCallPart }
    }
}
