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
        val failed: Boolean = false,
    )

    /** Conversation title shown in the top bar; "" for a conversation whose
     * title is empty or for a temporary chat. HomeScreen resolves the final
     * label (temporary title / stored title / localized "New Chat"). */
    val title = MutableStateFlow("")

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

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
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                container.messageDao.getTail(conversationId)
            }
            _messages.value = loaded.map { it.toUi() }
            _sendEnabled.value = true
        }
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

    private fun startGeneration(userMessage: ChatMessage) {
        generationJob?.cancel()
        _streaming.value = true
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
                    persistAssistant(assistantId, parts)
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
    private fun persistAssistant(assistantId: String, parts: List<MessagePart>) {
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
                        conversationId = conversationId,
                        groupId = assistantId,
                        version = 0,
                        messageOrder = container.messageDao.nextOrder(conversationId),
                    ),
                )
            }
        }
    }

    private fun ChatMessage.toUi(): UiMessage =
        UiMessage(id = id, role = role, parts = parts, isStreaming = isStreaming)

    companion object {
        fun factory(container: AppContainerImpl, conversationId: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(container, conversationId) as T
            }
    }
}
