package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessagesSquare
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 1:1 port of lib/features/settings/pages/debug_page.dart +
 * lib/features/settings/services/debug_conversation_factory.dart. Creates
 * synthetic debug conversations (oversized / many messages / long reasoning /
 * daily mixed markdown) through ConversationDao + MessageDao, mirroring
 * ChatService.restoreConversation (insert conversation then all messages).
 */
private enum class DebugAction { OVERSIZED, MANY_MESSAGES, DAILY_MIXED_MARKDOWN, LONG_REASONING }

@Composable
fun DebugScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var runningAction by remember { mutableStateOf<DebugAction?>(null) }
    val isBusy = runningAction != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.debug_page_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                // _DebugSectionCard L226-269.
                val lum = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
                val isDark = lum < 0.5f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceContainerHigh, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .border(
                            0.5.dp,
                            if (isDark) cs.onSurface.copy(alpha = 0.06f) else cs.outlineVariant.copy(alpha = 0.12f),
                            RoundedCornerShape(MemoRadius.INNER_DP.dp),
                        )
                        .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Text(
                        text = stringResource(UiR.string.debug_page_conversation_tools_title),
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 12.dp),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                    fun runAction(action: DebugAction, seed: () -> Pair<Conversation, List<ChatMessage>>) {
                        if (isBusy) return
                        // _runAction L103-151.
                        val assistantId = currentAssistantId(container)
                        if (assistantId == null) {
                            SnackbarManager.show(
                                AppNotification(
                                    message = context.getString(UiR.string.debug_page_no_current_assistant),
                                    type = NotificationType.ERROR,
                                ),
                            )
                            return
                        }
                        runningAction = action
                        SnackbarManager.show(
                            AppNotification(
                                message = context.getString(
                                    when (action) {
                                        DebugAction.OVERSIZED -> UiR.string.debug_page_creating_oversized_conversation
                                        DebugAction.MANY_MESSAGES -> UiR.string.debug_page_creating_many_messages_conversation
                                        DebugAction.DAILY_MIXED_MARKDOWN -> UiR.string.debug_page_creating_daily_mixed_markdown_conversation
                                        DebugAction.LONG_REASONING -> UiR.string.debug_page_creating_long_reasoning_conversation
                                    },
                                ),
                            ),
                        )
                        scope.launch {
                            try {
                                val (conversation, messages) = withContext(Dispatchers.Default) { seed() }
                                withContext(Dispatchers.IO) {
                                    container.conversationDao.insert(conversation)
                                    container.messageDao.insertAllInTransaction(messages)
                                }
                                SnackbarManager.show(
                                    AppNotification(
                                        message = context.getString(
                                            UiR.string.debug_page_conversation_created,
                                            messages.size.toString(),
                                        ),
                                        type = NotificationType.SUCCESS,
                                    ),
                                )
                            } catch (error: Exception) {
                                SnackbarManager.show(
                                    AppNotification(
                                        message = context.getString(
                                            UiR.string.debug_page_create_conversation_failed,
                                            error.toString(),
                                        ),
                                        type = NotificationType.ERROR,
                                    ),
                                )
                            } finally {
                                runningAction = null
                            }
                        }
                    }
                    // L173-215 — the four create buttons.
                    IosTileButton(
                        label = stringResource(
                            if (runningAction == DebugAction.OVERSIZED) {
                                UiR.string.debug_page_creating_button
                            } else {
                                UiR.string.debug_page_create_oversized_conversation_button
                            },
                        ),
                        icon = Lucide.Database,
                        backgroundColor = cs.primary,
                        enabled = !isBusy,
                        onClick = {
                            runAction(DebugAction.OVERSIZED) {
                                DebugConversationFactory.createOversizedConversation(
                                    title = context.getString(UiR.string.debug_page_oversized_conversation_title, "30"),
                                    assistantId = currentAssistantId(container),
                                    chunkText = context.getString(UiR.string.debug_page_oversized_conversation_seed_text),
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(
                            if (runningAction == DebugAction.MANY_MESSAGES) {
                                UiR.string.debug_page_creating_button
                            } else {
                                UiR.string.debug_page_create_many_messages_conversation_button
                            },
                        ),
                        icon = Lucide.MessagesSquare,
                        backgroundColor = cs.primary,
                        enabled = !isBusy,
                        onClick = {
                            runAction(DebugAction.MANY_MESSAGES) {
                                DebugConversationFactory.createManyMessagesConversation(
                                    title = context.getString(
                                        UiR.string.debug_page_many_messages_conversation_title,
                                        DebugConversationFactory.MANY_MESSAGES_COUNT.toString(),
                                    ),
                                    assistantId = currentAssistantId(container),
                                    messageCount = DebugConversationFactory.MANY_MESSAGES_COUNT,
                                    contentBuilder = { index, role ->
                                        context.getString(
                                            UiR.string.debug_page_many_messages_seed_text,
                                            role,
                                            (index + 1).toString(),
                                        )
                                    },
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(
                            if (runningAction == DebugAction.DAILY_MIXED_MARKDOWN) {
                                UiR.string.debug_page_creating_button
                            } else {
                                UiR.string.debug_page_create_daily_mixed_markdown_conversation_button
                            },
                        ),
                        icon = Lucide.FileText,
                        backgroundColor = cs.primary,
                        enabled = !isBusy,
                        onClick = {
                            runAction(DebugAction.DAILY_MIXED_MARKDOWN) {
                                DebugConversationFactory.createDailyMixedMarkdownConversation(
                                    title = context.getString(
                                        UiR.string.debug_page_daily_mixed_markdown_conversation_title,
                                        DebugConversationFactory.DAILY_MIXED_MARKDOWN_MESSAGES_COUNT.toString(),
                                    ),
                                    assistantId = currentAssistantId(container),
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    IosTileButton(
                        label = stringResource(
                            if (runningAction == DebugAction.LONG_REASONING) {
                                UiR.string.debug_page_creating_button
                            } else {
                                UiR.string.debug_page_create_long_reasoning_conversation_button
                            },
                        ),
                        icon = Lucide.Brain,
                        backgroundColor = cs.primary,
                        enabled = !isBusy,
                        onClick = {
                            runAction(DebugAction.LONG_REASONING) {
                                DebugConversationFactory.createLongReasoningConversation(
                                    title = context.getString(
                                        UiR.string.debug_page_long_reasoning_conversation_title,
                                        DebugConversationFactory.LONG_REASONING_MESSAGES_COUNT.toString(),
                                    ),
                                    assistantId = currentAssistantId(container),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** assistant_provider currentAssistant — current_assistant_id_v1 + assistant_rows. */
internal fun currentAssistantId(container: AppContainerImpl): String? {
    val storedId = container.preferenceRepository.readJson("current_assistant_id_v1")
        ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        ?: return null
    return com.psyche.memo.data.db.PayloadEntityDao(
        container.database.readableDatabase,
        "assistant_rows",
        primaryKey = "id",
    ).get(storedId)?.let { row ->
        runCatching {
            com.psyche.memo.data.model.Assistant.fromJsonString(
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                row.payload,
            ).id
        }.getOrNull()
    } ?: storedId
}

/** 1:1 port of debug_conversation_factory.dart. */
private object DebugConversationFactory {
    const val OVERSIZED_CONVERSATION_BYTES: Int = 30 * 1024 * 1024
    const val MANY_MESSAGES_COUNT = 1024
    const val DAILY_MIXED_MARKDOWN_MESSAGES_COUNT = 3000
    const val LONG_REASONING_MESSAGES_COUNT = 128

    fun createOversizedConversation(
        title: String,
        assistantId: String?,
        chunkText: String,
        targetBytes: Int = OVERSIZED_CONVERSATION_BYTES,
    ): Pair<Conversation, List<ChatMessage>> {
        require(targetBytes > 0)
        require(chunkText.isNotEmpty())
        val conversation = Conversation.create(title = title, assistantId = assistantId)
        val messages = mutableListOf<ChatMessage>()
        var totalBytes = 0L
        var index = 0
        while (totalBytes < targetBytes) {
            val role = if (index % 2 == 0) "user" else "assistant"
            val messageId = ChatMessage.newId()
            val content = buildString {
                append("debug-message-index: ").append(index).append('\n')
                append("debug-message-role: ").append(role).append('\n')
                for (block in 0 until 128) {
                    append(chunkText).append(" index=").append(index)
                        .append(" block=").append(block).append('\n')
                }
            }
            totalBytes += content.toByteArray(Charsets.UTF_8).size
            messages.add(message(conversation.id, messageId, role, content, index))
            index++
        }
        return conversation to messages
    }

    fun createManyMessagesConversation(
        title: String,
        assistantId: String?,
        messageCount: Int,
        contentBuilder: (index: Int, role: String) -> String,
    ): Pair<Conversation, List<ChatMessage>> {
        require(messageCount > 0)
        val conversation = Conversation.create(title = title, assistantId = assistantId)
        val messages = mutableListOf<ChatMessage>()
        for (index in 0 until messageCount) {
            val role = if (index % 2 == 0) "user" else "assistant"
            val messageId = ChatMessage.newId()
            val content = contentBuilder(index, role)
            messages.add(message(conversation.id, messageId, role, content, index))
        }
        return conversation to messages
    }

    fun createLongReasoningConversation(
        title: String,
        assistantId: String?,
        messageCount: Int = LONG_REASONING_MESSAGES_COUNT,
    ): Pair<Conversation, List<ChatMessage>> {
        require(messageCount > 0)
        val conversation = Conversation.create(title = title, assistantId = assistantId)
        val messages = mutableListOf<ChatMessage>()
        val baseTime = System.currentTimeMillis()
        for (index in 0 until messageCount) {
            val role = if (index % 2 == 0) "user" else "assistant"
            val messageId = ChatMessage.newId()
            val timestamp = baseTime + index * 1000L
            val content = if (role == "user") buildReasoningUserContent(index) else buildReasoningAssistantContent(index)
            val reasoningText = if (role == "assistant") buildReasoningText(index) else null
            val reasoningStartAt = reasoningText?.let { timestamp - 18_000L }
            val reasoningFinishedAt = reasoningText?.let { timestamp - 2_000L }
            val reasoningSegmentsJson = reasoningText?.let {
                buildReasoningSegmentsJson(
                    reasoningText = it,
                    startAtIso = java.time.Instant.ofEpochMilli(reasoningStartAt!!).toString(),
                    finishedAtIso = java.time.Instant.ofEpochMilli(reasoningFinishedAt!!).toString(),
                    expanded = index < messageCount - 16,
                )
            }
            val parts = buildList {
                if (reasoningText != null) add(ReasoningPart(reasoningText))
                add(TextPart(content))
            }
            messages.add(
                ChatMessage(
                    id = messageId,
                    role = role,
                    parts = parts,
                    timestamp = timestamp,
                    conversationId = conversation.id,
                    groupId = messageId,
                    reasoningStartAt = reasoningStartAt,
                    reasoningFinishedAt = reasoningFinishedAt,
                    reasoningSegmentsJson = reasoningSegmentsJson,
                    messageOrder = index,
                ),
            )
        }
        return conversation to messages
    }

    fun createDailyMixedMarkdownConversation(
        title: String,
        assistantId: String?,
        messageCount: Int = DAILY_MIXED_MARKDOWN_MESSAGES_COUNT,
    ): Pair<Conversation, List<ChatMessage>> = createManyMessagesConversation(
        title = title,
        assistantId = assistantId,
        messageCount = messageCount,
        contentBuilder = { index, role ->
            if (role == "user") buildDailyUserMarkdownContent(index) else buildDailyAssistantMarkdownContent(index)
        },
    )

    private fun message(
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        order: Int,
    ): ChatMessage = ChatMessage(
        id = messageId,
        role = role,
        parts = listOf(TextPart(content)),
        timestamp = System.currentTimeMillis(),
        conversationId = conversationId,
        groupId = messageId,
        messageOrder = order,
    )

    private fun buildReasoningUserContent(index: Int): String {
        val turn = index / 2 + 1
        return listOf(
            "Debug long reasoning prompt #$turn.",
            "Please answer with a visible final answer after extended thinking.",
            "Keep enough detail to exercise long conversation history replay.",
        ).joinToString("\n")
    }

    private fun buildReasoningAssistantContent(index: Int): String {
        val turn = index / 2 + 1
        return listOf(
            "Debug answer #$turn.",
            "",
            "Summary:",
            "- The requested scenario was analyzed against earlier turns.",
            "- The final answer stays short while the reasoning payload is stored separately.",
            "- This message intentionally keeps structured reasoning metadata.",
        ).joinToString("\n")
    }

    private fun buildReasoningText(index: Int): String {
        val turn = index / 2 + 1
        return buildString {
            append("Debug reasoning chain for assistant turn #$turn.\n")
            append("1. Inspect the recent user request and retained context.\n")
            append("2. Compare it with previous constraints and generated state.\n")
            append("3. Decide whether the final answer needs a concise response.\n")
            for (step in 0 until 12) {
                append(
                    "Reasoning detail $step for turn $turn: repeated diagnostic content " +
                        "keeps this block large enough to reproduce long-chat rendering and " +
                        "persistence behavior without calling a real provider.\n",
                )
            }
        }.trimEnd()
    }

    private fun buildReasoningSegmentsJson(
        reasoningText: String,
        startAtIso: String,
        finishedAtIso: String,
        expanded: Boolean,
    ): String = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        kotlinx.serialization.json.buildJsonObject {
            put("v", kotlinx.serialization.json.JsonPrimitive(2))
            put("segments", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("text", kotlinx.serialization.json.JsonPrimitive(reasoningText))
                    put("startAt", kotlinx.serialization.json.JsonPrimitive(startAtIso))
                    put("finishedAt", kotlinx.serialization.json.JsonPrimitive(finishedAtIso))
                    put("expanded", kotlinx.serialization.json.JsonPrimitive(expanded))
                    put("toolStartIndex", kotlinx.serialization.json.JsonPrimitive(0))
                })
            })
            put("contentSplits", kotlinx.serialization.json.buildJsonObject {
                put("offsets", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(0))
                })
                put("reasoningCounts", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(1))
                })
                put("toolCounts", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(0))
                })
            })
        },
    )

    private fun buildDailyUserMarkdownContent(index: Int): String {
        val turn = index / 2 + 1
        return when (turn % 6) {
            0 -> listOf(
                "第 $turn 轮：帮我整理今天的待办，优先处理工作和生活事项。",
                "",
                "- [ ] 回复产品评审意见",
                "- [ ] 晚上 8 点前确认旅行预算",
                "- [ ] 把会议纪要压缩成 3 个结论",
            ).joinToString("\n")
            1 -> listOf(
                "Can you review this Markdown note from my daily work?",
                "",
                "## Context $turn",
                "",
                "| Item | Status | Owner |",
                "| --- | --- | --- |",
                "| API retry | blocked | me |",
                "| UI copy | ready | design |",
            ).joinToString("\n")
            2 -> listOf(
                "请解释这段代码为什么偶尔会重复提交：",
                "",
                "```dart",
                "if (isSending) return;",
                "isSending = true;",
                "await submitMessage(input);",
                "isSending = false;",
                "```",
            ).joinToString("\n")
            3 -> listOf(
                "Summarize this shopping comparison in Chinese:",
                "",
                "1. Keyboard: quiet switches, compact layout.",
                "2. Monitor light: needs USB-C power.",
                "3. SSD enclosure: check heat during long copies.",
                "",
                "> I want a practical answer, not a long review.",
            ).joinToString("\n")
            4 -> listOf(
                "今天的健身记录：",
                "",
                "- 跑步 32 分钟",
                "- 深蹲 4 组",
                "- 睡眠只有 6 小时",
                "",
                "请给一个**不过度激进**的明日计划。",
            ).joinToString("\n")
            else -> listOf(
                "Draft a short reply for this message:",
                "",
                "```text",
                "Thanks for the update. Could we move the sync to Thursday?",
                "I need one more day to finish the migration checks.",
                "```",
            ).joinToString("\n")
        }
    }

    private fun buildDailyAssistantMarkdownContent(index: Int): String {
        val turn = index / 2 + 1
        return when (turn % 6) {
            0 -> listOf(
                "可以，建议按影响面排序：",
                "",
                "1. 先处理会阻塞他人的产品评审意见。",
                "2. 旅行预算只需要定上限，避免展开成完整攻略。",
                "3. 会议纪要保留结论、负责人和截止时间。",
                "",
                "- [x] 给出优先级",
                "- [ ] 等你补充具体时间",
            ).joinToString("\n")
            1 -> listOf(
                "Here is the cleaned version:",
                "",
                "## Daily Status",
                "",
                "| Area | Next step | Risk |",
                "| --- | --- | --- |",
                "| API retry | Confirm idempotency key | duplicate writes |",
                "| UI copy | Ship current draft | low |",
            ).joinToString("\n")
            2 -> listOf(
                "问题通常出在异常路径：如果 `submitMessage` 抛错，`isSending` 不会恢复。",
                "",
                "```dart",
                "if (isSending) return;",
                "isSending = true;",
                "try {",
                "  await submitMessage(input);",
                "} finally {",
                "  isSending = false;",
                "}",
                "```",
            ).joinToString("\n")
            3 -> listOf(
                "建议这样决策：",
                "",
                "- **键盘**：如果每天打字超过 4 小时，优先买。",
                "- **屏幕灯**：确认供电和桌面空间后再买。",
                "- **硬盘盒**：只有频繁大文件拷贝才值得升级。",
                "",
                "> 结论：先买键盘，其他两个延后。",
            ).joinToString("\n")
            4 -> listOf(
                "明天计划应该保守一点：",
                "",
                "- 轻松跑 20 分钟或快走 35 分钟",
                "- 下肢力量减到 2 组",
                "- 目标睡眠 7.5 小时",
                "",
                "重点是恢复，不是继续加量。",
            ).joinToString("\n")
            else -> listOf(
                "You could reply:",
                "",
                "```text",
                "Thursday works for me. I will use the extra day to finish the",
                "migration checks and send a concise status before the sync.",
                "```",
                "",
                "This keeps the tone direct and accountable.",
            ).joinToString("\n")
        }
    }
}
