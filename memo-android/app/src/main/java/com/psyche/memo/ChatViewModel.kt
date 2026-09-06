package com.psyche.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.stream.StreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat state holder for one conversation. Consumes [StreamChunk] into the
 * mutable assistant message so the timeline updates incrementally.
 */
class ChatViewModel(
    private val container: AppContainerImpl,
    private val conversationId: String,
) : ViewModel() {

    private val isTemporary: Boolean = conversationId == com.psyche.memo.data.model.Conversation.TEMPORARY_ID

    /** provider + model for this conversation (selected in the UI). Empty
     * until the first configured provider is resolved — no hardcoded defaults. */
    val selectedProviderId = MutableStateFlow("")
    val selectedModelId = MutableStateFlow("")

    val input = MutableStateFlow("")

    data class UiMessage(
        val id: String,
        val role: String,
        val parts: List<MessagePart>,
        val isStreaming: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val model: String = "",
        val providerId: String = "",
        val failed: Boolean = false,
        val groupId: String = id,
        val version: Int = 0,
        val messageOrder: Int = 0,
        val totalTokens: Int? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val cachedTokens: Int? = null,
        val durationMs: Long? = null,
        /** Translated body (chat_message_widget.dart message.translation 显示层)。 */
        val translation: String? = null,
    ) {
        val content: String
            get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    }

    /** Conversation title shown in the top bar; "" for a conversation whose
     * title is empty or for a temporary chat. HomeScreen resolves the final
     * label (temporary title / stored title / localized "New Chat"). */
    val title = MutableStateFlow("")

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    /**
     * Available versions per message group (sorted ASC) — drives the branch
     * selector. The displayed version per group is [versionSelections] when
     * set, otherwise the newest version.
     */
    private val _versionInfo = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    val versionInfo: StateFlow<Map<String, List<Int>>> = _versionInfo
    private val versionSelections = mutableMapOf<String, Int>()

    private val _sendEnabled = MutableStateFlow(false)
    val sendEnabled: StateFlow<Boolean> = _sendEnabled

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private var generationJob: Job? = null

    init {
        // Start with the first configured provider and its default model;
        // leave both empty when nothing is configured (the top bar then shows
        // no model subtitle, like kelivo before a model is picked).
        // First enabled provider wins (providers_page order = DB order);
        // the previous hardcoded openai/anthropic lookup missed user-added
        // providers like LongCat.
        val firstProvider = container.firstEnabledProviderConfig()
        if (firstProvider != null) {
            selectedProviderId.value = firstProvider.id
            selectedModelId.value = firstProvider.models.firstOrNull()
                ?: firstProvider.modelOverrides.keys.firstOrNull()
                ?: ""
        }
        if (!isTemporary) {
            // Home page shows the conversation's stored title; matches the
            // "New Chat" default of home_page_controller._createNewConversation.
            viewModelScope.launch {
                // Room/SQLite access must stay off the main thread.
                val stored = withContext(Dispatchers.IO) {
                    container.conversationDao.get(conversationId)
                }
                title.value = stored?.title?.trim() ?: ""
            }
        }
        refreshTail()
    }

    fun selectProvider(providerId: String, modelId: String) {
        selectedProviderId.value = providerId
        selectedModelId.value = modelId
    }

    fun refreshTail() {
        if (isTemporary) {
            _sendEnabled.value = true
            return
        }
        viewModelScope.launch { reloadTail() }
    }

    private suspend fun reloadTail() {
        val loaded = withContext(Dispatchers.IO) {
            container.messageDao.getTail(conversationId)
        }
        val versions = withContext(Dispatchers.IO) {
            container.messageDao.groupVersions(conversationId)
        }
        _versionInfo.value = versions
        _messages.value = loaded.collapseVersions()
        _sendEnabled.value = true
    }

    /**
     * Version collapse (mirrors the original timeline grouping): several rows
     * can share one group_id (edit/regenerate versions). Only the selected
     * version stays visible, positioned at the group's first occurrence in
     * wall order. Selected = [versionSelections], else the newest version.
     */
    private fun List<ChatMessage>.collapseVersions(): List<UiMessage> {
        val out = ArrayList<UiMessage>(size)
        val indexByGroup = HashMap<String, Int>()
        for (row in this) {
            val gid = row.groupId.ifEmpty { row.id }
            val existingIdx = indexByGroup[gid]
            if (existingIdx == null) {
                indexByGroup[gid] = out.size
                out.add(row.toUi())
                continue
            }
            val selected = versionSelections[gid]
            val existing = out[existingIdx]
            val existingWins = when (selected) {
                null -> existing.version >= row.version
                else -> existing.version == selected || row.version != selected
            }
            if (!existingWins) {
                out[existingIdx] = row.toUi()
            }
        }
        return out
    }

    /** Branch selector: switch the visible version of a group. */
    fun switchVersion(groupId: String, version: Int) {
        versionSelections[groupId] = version
        refreshTail()
    }

    fun updateInput(text: String) {
        input.value = text
    }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty() || _streaming.value) return
        input.value = ""
        viewModelScope.launch {
            // nextOrder queries SQLite synchronously, so both the build and
            // the insert run on Dispatchers.IO; StateFlow updates (append)
            // stay outside the IO blocks, in the original order.
            val userMessage = withContext(Dispatchers.IO) { buildUserMessage(text) }
            append(userMessage)
            // Persist user message best-effort (DAO errors surface in logs).
            if (!isTemporary) {
                withContext(Dispatchers.IO) {
                    container.messageDao.insert(userMessage)
                }
            }
            startGeneration(userMessage)
        }
    }

    fun stop() {
        generationJob?.cancel()
        container.cancellations.cancel(conversationId)
        _streaming.value = false
        val msgs = _messages.value
        if (msgs.isNotEmpty()) {
            val last = msgs.last()
            _messages.value = msgs.dropLast(1) + last.copy(isStreaming = false)
        }
    }

    /**
     * Regenerate from a user message (chat_message_widget onResend +
     * _confirmRegeneration): the confirm dialog lives in the UI layer; here
     * the messages below the anchor are deleted and the anchor's text is
     * sent again. The anchor row itself is kept.
     */
    fun regenerate(userMessageId: String) {
        if (_streaming.value) return
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == userMessageId }
        if (idx < 0) return
        val anchor = msgs[idx]
        viewModelScope.launch {
            if (!isTemporary) {
                withContext(Dispatchers.IO) {
                    container.messageDao.deleteAfterOrder(conversationId, anchor.messageOrder)
                }
            }
            _messages.value = msgs.take(idx + 1)
            startGeneration(anchor.toChatMessage())
        }
    }

    /**
     * Edit a message (message_edit_sheet semantics): the trimmed text is
     * saved as the next version of the message's group; [shouldSend] sends
     * it again after saving (Save & Send — user messages only in this port).
     * Temporary chats rewrite the in-memory message in place (no DB).
     * Returns false when the message has no editable text content
     * (user_message_edit_unsupported_snackbar in the original).
     */
    suspend fun editMessage(messageId: String, newContent: String, shouldSend: Boolean): Boolean {
        if (_streaming.value) return false
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return false
        val target = msgs[idx]
        if (target.parts.none { it is TextPart }) return false
        if (isTemporary) {
            val newParts = ChatMessage.partsWithReplacedText(target.parts, newContent)
            val edited = target.copy(parts = newParts)
            _messages.value = msgs.take(idx) + edited
            if (shouldSend && target.role == "user") {
                startGeneration(edited.toChatMessage())
            }
            return true
        }
        val newVersionRow = withContext(Dispatchers.IO) {
            val orig = container.messageDao.get(messageId) ?: return@withContext null
            val groupId = orig.groupId.ifEmpty { orig.id }
            val version = container.messageDao.maxVersionForGroup(conversationId, groupId) + 1
            val row = ChatMessage(
                id = ChatMessage.newId(),
                role = orig.role,
                parts = ChatMessage.partsWithReplacedText(orig.parts, newContent),
                timestamp = System.currentTimeMillis(),
                modelId = orig.modelId,
                providerId = orig.providerId,
                conversationId = conversationId,
                groupId = groupId,
                version = version,
                messageOrder = container.messageDao.nextOrder(conversationId),
            )
            container.messageDao.insert(row)
            row
        } ?: return false
        versionSelections[newVersionRow.groupId] = newVersionRow.version
        reloadTail()
        if (shouldSend && target.role == "user") {
            // The collapsed list now shows the edited version in the group's
            // original position; generate against it.
            val visible = _messages.value.firstOrNull {
                it.groupId == newVersionRow.groupId && it.version == newVersionRow.version
            }
            if (visible != null) startGeneration(visible.toChatMessage())
        }
        return true
    }

    /** Delete the visible version of a message (more sheet / context menu). */
    fun deleteVersion(messageId: String) {
        if (_streaming.value) return
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val target = msgs[idx]
        if (isTemporary) {
            _messages.value = msgs.filterNot { it.id == messageId }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.messageDao.delete(messageId)
            }
            versionSelections.remove(target.groupId)
            refreshTail()
        }
    }

    /** Delete every stored version of a message group. */
    fun deleteAllVersions(messageId: String) {
        if (_streaming.value) return
        val target = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (isTemporary) {
            _messages.value = _messages.value.filterNot { it.groupId == target.groupId }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.messageDao.deleteByGroup(conversationId, target.groupId)
            }
            versionSelections.remove(target.groupId)
            refreshTail()
        }
    }

    private fun UiMessage.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        parts = parts,
        timestamp = timestamp,
        modelId = model.ifEmpty { null },
        providerId = providerId.ifEmpty { null },
        conversationId = conversationId,
        groupId = groupId,
        version = version,
        messageOrder = messageOrder,
    )

    private fun startGeneration(userMessage: ChatMessage) {
        generationJob?.cancel()
        _streaming.value = true
        val generationStartMs = System.currentTimeMillis()
        generationJob = viewModelScope.launch {
            // Publish streaming state so the drawer can show its loading dot.
            container.streamingConversationIds.value =
                container.streamingConversationIds.value + conversationId
            // Assistant skeleton: streaming message grows as chunks arrive.
            val assistantId = ChatMessage.newId()
            append(
                UiMessage(
                    id = assistantId,
                    role = "assistant",
                    parts = emptyList(),
                    isStreaming = true,
                ),
            )
            // Folding handler lives outside the try: the stop path (catch)
            // reads the accumulated partial parts from it.
            val handler = com.psyche.memo.llm.stream.StreamChunkHandler()
            // Terminal usage reported by the provider (Finish chunk), if any.
            var finishUsage: UsageStats? = null
            try {
                // Build request from current UI messages (exclude skeleton).
                val history = _messages.value
                    .dropLast(1)
                    .mapNotNull { msg ->
                        val content = msg.parts.filterIsInstance<TextPart>()
                            .joinToString("") { it.text }
                        if (content.isEmpty()) null
                        else LlmMessage(role = msg.role, content = content)
                    }
                val providerId = selectedProviderId.value
                val modelId = selectedModelId.value
                val request = LlmRequest(
                    providerId = providerId,
                    modelId = modelId,
                    messages = history,
                    apiKey = container.apiKeyFor(providerId) ?: "",
                    baseUrl = container.baseUrlFor(providerId),
                    chatPath = container.providerConfig(providerId)?.chatPath,
                )
                val client = container.clientFor(providerId)
                // First terminal wins (mirrors the original finishHandled /
                // terminalPersisted flags): a stream that ends with either a
                // Finish or an Error must not also run the other path.
                var terminalHandled = false
                var persisted = false
                fun persistOnce(parts: List<MessagePart>) {
                    if (persisted) return
                    persisted = true
                    persistAssistant(
                        assistantId,
                        parts,
                        usage = finishUsage,
                        durationMs = System.currentTimeMillis() - generationStartMs,
                    )
                }
                client.streamChat(request).collect { chunk ->
                    handler.handle(chunk)
                    when (chunk) {
                        is StreamChunk.TextDelta,
                        is StreamChunk.ReasoningDelta,
                        is StreamChunk.ToolCallDelta,
                        -> updateAssistantStreaming(assistantId, handler.parts())
                        is StreamChunk.Finish -> if (!terminalHandled) {
                            terminalHandled = true
                            finishUsage = parseUsage(chunk.usage)
                            updateAssistantStreaming(assistantId, handler.parts())
                            finishAssistant(assistantId, handler.parts())
                            // Persist the finished reply (chat_service terminal
                            // checkpoint in the original).
                            persistOnce(handler.parts())
                        }
                        is StreamChunk.Error -> if (!terminalHandled && !handler.finished) {
                            // Mirror the original stream-error path
                            // (chat_actions._handleStreamError +
                            // assistantPartsForStreamError): keep any partial
                            // content, surface the error text when nothing was
                            // generated, and mark the message failed.
                            terminalHandled = true
                            val finalParts =
                                markFailed(assistantId, chunk.message, handler.parts())
                            persistOnce(finalParts)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user stop: the partial reply is kept and persisted, exactly
                // like the original stop path.
                persistAssistant(assistantId, handler.parts())
            } catch (e: Exception) {
                val finalParts = markFailed(assistantId, e.toString(), handler.parts())
                persistAssistant(assistantId, finalParts)
            } finally {
                _streaming.value = false
                container.streamingConversationIds.value =
                    container.streamingConversationIds.value - conversationId
            }
        }
    }

    /**
     * Token usage reported by the provider on the Finish chunk. Field
     * fallbacks mirror chat_completions_decoder._mergeUsage in the original:
     * prompt_tokens|input_tokens, completion_tokens|output_tokens,
     * prompt_tokens_details.cached_tokens|input_tokens_details.cached_tokens
     * (plus Claude cache_read_input_tokens).
     */
    private data class UsageStats(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val cachedTokens: Int?,
        val totalTokens: Int?,
    )

    private fun parseUsage(usage: kotlinx.serialization.json.JsonObject?): UsageStats? {
        if (usage == null) return null
        fun intOf(vararg keys: String): Int? {
            for (key in keys) {
                (usage[key] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toIntOrNull()?.let { return it }
            }
            return null
        }
        val details = (usage["prompt_tokens_details"]
            ?: usage["input_tokens_details"]) as? kotlinx.serialization.json.JsonObject
        val cached = details?.get("cached_tokens")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            ?: intOf("cache_read_input_tokens")
        val prompt = intOf("prompt_tokens", "input_tokens")
        val completion = intOf("completion_tokens", "output_tokens")
        val total = intOf("total_tokens")
            ?: listOfNotNull(prompt, completion).sum().takeIf { it > 0 }
        if (prompt == null && completion == null && total == null) return null
        return UsageStats(prompt, completion, cached, total)
    }

    private fun buildUserMessage(text: String): ChatMessage {
        val id = ChatMessage.newId()
        return ChatMessage(
            id = id,
            role = "user",
            parts = listOf(TextPart(text)),
            timestamp = System.currentTimeMillis(),
            conversationId = conversationId,
            groupId = id,
            version = 0,
            messageOrder = container.messageDao.nextOrder(conversationId),
        )
    }

    private fun append(message: ChatMessage) {
        _messages.value = _messages.value + message.toUi()
    }

    private fun append(ui: UiMessage) {
        _messages.value = _messages.value + ui
    }

    private fun updateAssistantStreaming(
        assistantId: String,
        parts: List<MessagePart>,
    ) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        _messages.value = msgs.toMutableList().apply {
            set(index, msgs[index].copy(parts = parts, isStreaming = true))
        }
    }

    private fun finishAssistant(
        assistantId: String,
        parts: List<MessagePart>,
    ) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        val finalUi = msgs[index].copy(parts = parts, isStreaming = false)
        _messages.value = msgs.toMutableList().apply { set(index, finalUi) }
    }

    /**
     * Marks the assistant message failed and returns the parts to persist.
     * Mirrors the original assistantPartsForStreamError semantics: when no
     * text was generated the error text becomes the message content; any
     * partial content is kept as-is.
     */
    private fun markFailed(
        assistantId: String,
        errorText: String,
        parts: List<MessagePart>,
    ): List<MessagePart> {
        val hasText = parts.any { it is TextPart && it.text.isNotEmpty() }
        val finalParts = if (hasText) parts else parts + TextPart(errorText)
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index >= 0) {
            _messages.value = msgs.toMutableList().apply {
                set(index, msgs[index].copy(parts = finalParts, isStreaming = false, failed = true))
            }
        }
        return finalParts
    }

    /**
     * Persists the assistant reply (chat_service terminal checkpoint in the
     * original). Temporary chats stay purely in memory. Nothing generated yet
     * → nothing to persist. Idempotent: a Finish plus a late user stop can
     * both reach here, and a second insert would violate UNIQUE(id).
     */
    private fun persistAssistant(
        assistantId: String,
        parts: List<MessagePart>,
        usage: UsageStats? = null,
        durationMs: Long = 0L,
    ) {
        if (isTemporary || parts.isEmpty()) return
        val providerId = selectedProviderId.value
        val modelId = selectedModelId.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (container.messageDao.get(assistantId) != null) return@withContext
                container.messageDao.insert(
                    ChatMessage(
                        id = assistantId,
                        role = "assistant",
                        parts = parts,
                        timestamp = System.currentTimeMillis(),
                        modelId = modelId,
                        providerId = providerId,
                        totalTokens = usage?.totalTokens,
                        conversationId = conversationId,
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        cachedTokens = usage?.cachedTokens,
                        durationMs = durationMs.takeIf { it > 0 },
                        groupId = assistantId,
                        version = 0,
                        messageOrder = container.messageDao.nextOrder(conversationId),
                    ),
                )
            }
        }
    }

    private fun ChatMessage.toUi(): UiMessage = UiMessage(
        id = id,
        role = role,
        parts = parts,
        isStreaming = isStreaming,
        timestamp = timestamp,
        model = modelId ?: "",
        providerId = providerId ?: "",
        groupId = groupId.ifEmpty { id },
        version = version,
        messageOrder = messageOrder,
        totalTokens = totalTokens,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        durationMs = durationMs,
        translation = translation,
    )

    companion object {
        fun factory(container: AppContainerImpl, conversationId: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(container, conversationId) as T
            }

        /**
         * Fork ("create branch") copy: the message row duplicated into a new
         * conversation (drawer duplicateConversation semantics, truncated at
         * the anchor). New row id, streaming cleared.
         */
        fun forkCopy(m: ChatMessage, newConversationId: String): ChatMessage = ChatMessage(
            id = ChatMessage.newId(),
            role = m.role,
            parts = m.parts,
            timestamp = m.timestamp,
            modelId = m.modelId,
            providerId = m.providerId,
            totalTokens = m.totalTokens,
            conversationId = newConversationId,
            reasoningSegmentsJson = m.reasoningSegmentsJson,
            translation = m.translation,
            reasoningStartAt = m.reasoningStartAt,
            reasoningFinishedAt = m.reasoningFinishedAt,
            groupId = m.groupId,
            version = m.version,
            promptTokens = m.promptTokens,
            completionTokens = m.completionTokens,
            cachedTokens = m.cachedTokens,
            durationMs = m.durationMs,
            updatedAt = m.updatedAt,
            messageOrder = m.messageOrder,
        )
    }
}
