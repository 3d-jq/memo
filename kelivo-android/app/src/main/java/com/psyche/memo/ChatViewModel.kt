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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    fun newConversation() = Unit

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
        val firstProvider = container.providerConfig("openai") ?: container.providerConfig("anthropic")
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
                title.value = container.conversationDao.get(conversationId)?.title?.trim() ?: ""
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
            val loaded = container.messageDao.getTail(conversationId)
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
        val userMessage = buildUserMessage(text)
        append(userMessage)
        input.value = ""
        // Persist user message best-effort (DAO errors surface in logs).
        if (!isTemporary) {
            viewModelScope.launch { container.messageDao.insert(userMessage) }
        }
        startGeneration(userMessage)
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
                val handler = com.psyche.memo.llm.stream.StreamChunkHandler()
                client.streamChat(request).collect { chunk ->
                    handler.handle(chunk)
                    when (chunk) {
                        is StreamChunk.TextDelta,
                        is StreamChunk.ReasoningDelta,
                        is StreamChunk.ToolCallDelta,
                        -> updateAssistantStreaming(assistantId, handler.parts())
                        is StreamChunk.Finish -> {
                            updateAssistantStreaming(assistantId, handler.parts())
                            finishAssistant(assistantId, handler.parts())
                        }
                        is StreamChunk.Error -> Unit // surfaced as string; P3
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user stop
            } catch (e: Exception) {
                markFailed(assistantId)
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

    private fun markFailed(assistantId: String) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        _messages.value = msgs.toMutableList().apply {
            set(index, msgs[index].copy(isStreaming = false, failed = true))
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
