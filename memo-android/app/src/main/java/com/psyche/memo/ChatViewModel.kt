package com.psyche.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.ReasoningSegment
import com.psyche.memo.data.model.ReasoningSegmentCodec
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import com.psyche.memo.data.model.ToolCallPayload
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmToolCall
import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.llm.stream.StreamChunk
import com.psyche.memo.llm.stream.StreamChunkHandler
import com.psyche.memo.ui.chat.ToolHandler
import com.psyche.memo.ui.chat.ToolUiPart
import com.psyche.memo.ui.chat.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        /** Reasoning segment timings (`reasoning_segments_json`), zipped with
         * the message's ReasoningParts; the thinking card shows (X.Xs) from it. */
        val reasoningSegmentsJson: String? = null,
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

    /** 在途翻译请求（messageId → Job），新请求顶掉旧的（TS _runs 语义）。 */
    private val translationJobs = mutableMapOf<String, Job>()

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
                // resolveChatModel（model_select_sheet.dart:283-303）：会话级
                // chat_model_* 优先于全局默认——重启后仍保留用户上次的选择。
                val provider = stored?.chatModelProvider
                val model = stored?.chatModelId
                if (!provider.isNullOrEmpty() && !model.isNullOrEmpty()) {
                    selectedProviderId.value = provider
                    selectedModelId.value = model
                }
            }
        }
        refreshTail()
    }

    /**
     * 选择模型（model_select_sheet.dart:283-303 →
     * `controller.setConversationModel`）：先更新内存，再持久化到当前会话，
     * 这样重启后仍是上次选的模型。临时会话不落库（同 regenerate/编辑的
     * isTemporary 语义）。
     */
    fun selectProvider(providerId: String, modelId: String) {
        selectedProviderId.value = providerId
        selectedModelId.value = modelId
        if (isTemporary) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.conversationDao.setChatModel(conversationId, providerId, modelId)
            }
        }
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
        // chat_actions.dart _cancelStreamingByIdOnce 1924-1941 — cancel pending
        // tool approvals / askUser requests for this conversation so the
        // handler's await resolves instead of deadlocking the round loop.
        container.toolApprovalService.cancelForConversation(conversationId)
        container.askUserInteractionService.cancelForConversation(conversationId)
        _streaming.value = false
        val msgs = _messages.value
        if (msgs.isNotEmpty()) {
            val last = msgs.last()
            _messages.value = msgs.dropLast(1) + last.copy(isStreaming = false)
        }
    }

    // ------------------------------------------------------------------
    // Translation (translation_service.dart 1:1)
    // ------------------------------------------------------------------

    /**
     * 翻译模型解析（TS:121-133 回退链）：翻译专用模型 → 当前会话模型。
     * `translate_model_v1` 存 "provider::model"（PREFERENCE 键，preference_rows
     * JSON 文本）；为空回退当前选中模型；两者皆无 → null（UI 提示请先设置）。
     */
    private fun resolveTranslationModel(): Pair<String, String>? {
        val stored = runCatching {
            container.preferenceRepository.readJson("translate_model_v1")
        }.getOrNull()
            ?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.content
                }.getOrDefault(raw)
            }
            ?.takeIf { it.isNotBlank() }
        if (!stored.isNullOrEmpty()) {
            val parts = stored.split("::")
            if (parts.size >= 2) return parts[0] to parts.subList(1, parts.size).joinToString("::")
        }
        val p = selectedProviderId.value
        val m = selectedModelId.value
        return if (p.isNotEmpty() && m.isNotEmpty()) p to m else null
    }

    /** 翻译 prompt 模板（settings_provider.dart:3693 defaultTranslatePrompt）。 */
    private val defaultTranslatePrompt =
        "You are a translation expert, skilled in translating various languages, and maintaining accuracy, faithfulness, and elegance in translation.\n" +
            "Next, I will send you text. Please translate it into {target_lang}, and return the translation result directly, without adding any explanations or other content.\n\n" +
            "Please translate the <source_text> section:\n<source_text>\n{source_text}\n</source_text>"

    /**
     * 翻译消息（TS translateMessage 1:1）。[targetLang] null = 用户取消；
     * [TranslateLanguage.CLEAR_TRANSLATION] = 清除翻译（顶掉在途请求 +
     * 内存/DB 置空）；否则流式翻译并实时刷内存，完成后存库。
     * 新请求顶掉同消息的旧请求（supersedeTranslationRun：旧 Job 取消且
     * 不再写 UI/DB）。
     */
    fun translateMessage(
        messageId: String,
        targetLang: String?,
        translatingLabel: String,
        onResult: (String) -> Unit,
    ) {
        translationJobs.remove(messageId)?.cancel()
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (targetLang == null) return // cancelled
        if (targetLang == TranslateLanguage.CLEAR_TRANSLATION) {
            updateTranslationInPlace(messageId, "")
            viewModelScope.launch {
                if (!isTemporary) withContext(Dispatchers.IO) {
                    container.messageDao.updateTranslation(messageId, "")
                }
            }
            onResult("cleared")
            return
        }
        val model = resolveTranslationModel()
        if (model == null) {
            onResult("no_model")
            return
        }
        // onTranslationStarted：先写"翻译中"占位（home_page_controller 语义）。
        updateTranslationInPlace(messageId, translatingLabel)
        val promptTemplate = runCatching {
            container.preferenceRepository.readJson("translate_prompt_v1")
        }.getOrNull()
            ?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.content
                }.getOrDefault(raw)
            }
            ?.takeIf { it.isNotBlank() }
            ?: defaultTranslatePrompt
        val prompt = promptTemplate
            .replace("{source_text}", message.content)
            .replace("{target_lang}", targetLang)
        val job = viewModelScope.launch {
            try {
                val request = LlmRequest(
                    providerId = model.first,
                    modelId = model.second,
                    messages = listOf(LlmMessage(role = "user", content = prompt)),
                    apiKey = container.apiKeyFor(model.first) ?: "",
                    baseUrl = container.baseUrlFor(model.first),
                    chatPath = container.providerConfig(model.first)?.chatPath,
                )
                val client = container.clientFor(model.first)
                val buffer = StringBuilder()
                client.streamChat(request).collect { chunk ->
                    if (chunk is StreamChunk.TextDelta && chunk.text.isNotEmpty()) {
                        buffer.append(chunk.text)
                        updateTranslationInPlace(messageId, buffer.toString())
                    }
                }
                if (!isTemporary) withContext(Dispatchers.IO) {
                    container.messageDao.updateTranslation(messageId, buffer.toString())
                }
                onResult("success")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 出错清除（TS:196-198 shouldApplyTranslationFailure 语义）。
                updateTranslationInPlace(messageId, "")
                if (!isTemporary) withContext(Dispatchers.IO) {
                    container.messageDao.updateTranslation(messageId, "")
                }
                onResult("error: ${e.message}")
            }
        }
        translationJobs[messageId] = job
    }

    private fun updateTranslationInPlace(messageId: String, translation: String) {
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        _messages.value = msgs.map {
            if (it.id == messageId) it.copy(translation = translation) else it
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
            // Parts accumulated across rounds; each round folds into its own
            // handler, then its parts/segments merge here (the original's single
            // UI handler folds every round into one parts list).
            val allParts = mutableListOf<MessagePart>()
            val allSegments = mutableListOf<ReasoningSegment>()
            var persisted = false
            fun persistOnce(parts: List<MessagePart>, usage: UsageStats?) {
                if (persisted) return
                persisted = true
                persistAssistant(
                    assistantId,
                    parts,
                    segments = allSegments,
                    usage = usage,
                    durationMs = System.currentTimeMillis() - generationStartMs,
                )
            }
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
                    .toMutableList()
                // System prompt injection (message_builder_service.dart): the
                // assistant prompt plus the search citation block when web
                // search is on. Appended to an existing system message or
                // prepended as a new one.
                val assistant = container.currentAssistant()
                val systemPrompt = buildSystemPrompt(assistant)
                if (systemPrompt.isNotEmpty()) {
                    val existing = history.indexOfFirst { it.role == "system" }
                    if (existing >= 0) {
                        val merged = listOf(history[existing].content, systemPrompt)
                            .filterNotNull().filter { it.isNotEmpty() }.joinToString("\n\n")
                        history[existing] = history[existing].copy(content = merged)
                    } else {
                        history.add(0, LlmMessage(role = "system", content = systemPrompt))
                    }
                }
                // Only tools with a native dispatch path are offered:
                // get_time_info has an executor, ask_user_input_v0 routes to the
                // interaction service, calendar_create exercises the approval
                // gate (its executor is unported → honest execution_error after
                // approval), search_web runs through the ported search engine.
                // MCP/memory executors are unported, so their tools are not
                // offered. Deviation from the original's full
                // LocalToolsService.buildToolDefinitions set.
                val tools = offeredTools()
                runGenerationLoop(
                    assistantId = assistantId,
                    history = history,
                    tools = tools,
                    allParts = allParts,
                    allSegments = allSegments,
                    updateStreaming = { parts, segments ->
                        updateAssistantStreaming(assistantId, parts, segments)
                    },
                    onPersist = { parts, usage -> persistOnce(parts, usage) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user stop: the partial reply is kept and persisted, exactly
                // like the original stop path.
                persistAssistant(assistantId, allParts, segments = allSegments)
            } catch (e: Exception) {
                val finalParts = markFailed(
                    assistantId,
                    e.toString(),
                    allParts,
                    encodeSegments(allSegments),
                )
                persistAssistant(assistantId, finalParts, segments = allSegments)
            } finally {
                _streaming.value = false
                container.streamingConversationIds.value =
                    container.streamingConversationIds.value - conversationId
            }
        }
    }

    /**
     * 工具轮询循环主体（tool_loop_runner.dart runClientToolFollowUps）——
     * [startGeneration] 与 [resumeAfterToolAnswer] 共用同一执行体。每个 HTTP 流用
     * 一个 StreamChunkHandler（Dart 的 handler 也是一响应一实例），把结果折回 part、
     * 追加 assistant tool_calls + tool 记录进 [history]，直到模型不再宣告工具。
     *
     * [updateStreaming] 收到「已累积 part + 当前 round 的 part」的合并结果与编码后的
     * 段，用于刷新流式 UI；[onPersist] 在流程终止/出错时落库（调用方自带去重守卫）。
     */
    private suspend fun runGenerationLoop(
        assistantId: String,
        history: MutableList<LlmMessage>,
        tools: List<LlmToolSpec>,
        allParts: MutableList<MessagePart>,
        allSegments: MutableList<ReasoningSegment>,
        updateStreaming: (List<MessagePart>, String?) -> Unit,
        onPersist: (List<MessagePart>, UsageStats?) -> Unit,
    ) {
        var finishUsage: UsageStats? = null
        val toolHandler = ToolHandler(
            approvalService = container.toolApprovalService,
            askUserService = container.askUserInteractionService,
            conversationId = conversationId,
            assistant = container.currentAssistant(),
            searchEngine = container.searchEngine,
            searchService = container.searchSettingsRepository.selectedService(),
            searchCommonOptions = container.searchSettingsRepository.commonOptions(),
        )
        val providerId = selectedProviderId.value
        val modelId = selectedModelId.value
        while (true) {
            val request = LlmRequest(
                providerId = providerId,
                modelId = modelId,
                messages = history,
                tools = tools,
                apiKey = container.apiKeyFor(providerId) ?: "",
                baseUrl = container.baseUrlFor(providerId),
                chatPath = container.providerConfig(providerId)?.chatPath,
            )
            val client = container.clientFor(providerId)
            // Fresh handler per round: each HTTP stream ends with its own
            // Finish, which would otherwise block later chunks (the Dart
            // handler is likewise one instance per response).
            val roundHandler = StreamChunkHandler()
            var failed = false
            fun roundUpdate() {
                updateStreaming(
                    allParts + roundHandler.parts,
                    encodeSegments(allSegments + roundHandler.reasoningSegments),
                )
            }
            client.streamChat(request).collect { chunk ->
                roundHandler.handle(chunk)
                when (chunk) {
                    is StreamChunk.TextDelta,
                    is StreamChunk.ReasoningDelta,
                    is StreamChunk.ToolCallDelta,
                    -> roundUpdate()
                    is StreamChunk.Finish -> {
                        finishUsage = accumulateUsage(finishUsage, parseUsage(chunk.usage))
                        roundUpdate()
                    }
                    is StreamChunk.Error -> if (!failed && !roundHandler.finished) {
                        // Mirror the original stream-error path
                        // (chat_actions._handleStreamError +
                        // assistantPartsForStreamError): keep any partial
                        // content, surface the error text when nothing was
                        // generated, and mark the message failed.
                        failed = true
                        val finalParts = markFailed(
                            assistantId,
                            chunk.message,
                            allParts + roundHandler.parts,
                            encodeSegments(allSegments + roundHandler.reasoningSegments),
                        )
                        onPersist(finalParts, finishUsage)
                    }
                }
            }
            if (failed) break
            val calls = takeCallsAfterRound(roundHandler)
            if (calls.isEmpty()) {
                // The model is done: finalize the reply. A clean stream end
                // without a Finish chunk still finalizes (the round loop's
                // `finish()`).
                val finalParts = allParts + roundHandler.parts
                updateStreaming(
                    finalParts,
                    encodeSegments(allSegments + roundHandler.reasoningSegments),
                )
                finishAssistant(
                    assistantId,
                    finalParts,
                    encodeSegments(allSegments + roundHandler.reasoningSegments),
                )
                onPersist(finalParts, finishUsage)
                break
            }
            // Execute each announced tool and fold its result into the
            // part (stream_chunk_handler.dart ToolCallResult path).
            val results = calls.map { call ->
                val result = toolHandler.handle(
                    call.name,
                    parseToolArguments(call.arguments),
                    call.id,
                )
                roundHandler.foldToolResult(call.id, JsonPrimitive(result))
                result
            }
            allParts += roundHandler.parts
            allSegments += roundHandler.reasoningSegments
            updateStreaming(allParts, encodeSegments(allSegments)) // folded results now visible
            // Append the assistant tool_calls + tool result transcript
            // (chat_completions_api.dart _buildAssistantToolCallMessage;
            // empty assistant text normalizes to "\n\n").
            history.add(
                LlmMessage(
                    role = "assistant",
                    content = allParts.filterIsInstance<TextPart>()
                        .joinToString("") { it.text }
                        .ifEmpty { "\n\n" },
                    toolCalls = calls.map { LlmToolCall(it.id, it.name, it.arguments) },
                ),
            )
            for ((index, call) in calls.withIndex()) {
                history.add(
                    LlmMessage(
                        role = "tool",
                        toolCallId = call.id,
                        toolName = call.name,
                        content = results[index],
                    ),
                )
            }
        }
    }

    /**
     * 恢复已持久化的 ask-user 工具回答（home_page_controller.submitRecoveredAskUserAnswer
     * 1017-1066 + chat_actions.continueAssistantMessageAfterToolAnswer 1766+）：把答案折回
     * 目标消息的工具 part（upsertToolEvent 等价）并置为流式，再跑 [runGenerationLoop] 续答。
     * 在途发送时忽略（Dart isSendInFlight 守卫）。目标是继续同一消息，不新建骨架。
     */
    fun resumeAfterToolAnswer(messageId: String, part: ToolUiPart, resultJson: String) {
        if (_streaming.value) return
        val targetUi = _messages.value.firstOrNull { it.id == messageId } ?: return
        val updatedParts = targetUi.parts.map { p ->
            if (p is ToolCallPart) {
                val payload = ToolCallPart.decode(p.payloadJson)
                if (payload != null &&
                    (payload.id == part.id || (payload.id.isEmpty() && payload.name == part.toolName))
                ) {
                    ToolCallPart.encode(
                        id = payload.id,
                        name = payload.name,
                        arguments = runCatching { Json.parseToJsonElement(payload.arguments) }
                            .getOrElse { JsonNull },
                        content = JsonPrimitive(resultJson),
                        server = payload.server,
                        metadata = payload.metadata,
                    )
                } else p
            } else p
        }
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(parts = updatedParts, isStreaming = true) else it
        }
        generationJob?.cancel()
        _streaming.value = true
        generationJob = viewModelScope.launch {
            container.streamingConversationIds.value =
                container.streamingConversationIds.value + conversationId
            val allParts = updatedParts.toMutableList()
            val allSegments = ReasoningSegmentCodec.decode(targetUi.reasoningSegmentsJson).toMutableList()
            var persisted = false
            fun persistFinal(parts: List<MessagePart>) {
                if (persisted) return
                persisted = true
                if (isTemporary) return
                viewModelScope.launch {
                    val dbMsg = withContext(Dispatchers.IO) { container.messageDao.get(messageId) }
                        ?: return@launch
                    withContext(Dispatchers.IO) {
                        container.messageDao.replaceParts(
                            dbMsg.withParts(
                                parts = parts,
                                reasoningSegmentsJson = encodeSegments(allSegments),
                                isStreaming = false,
                            ),
                            streaming = false,
                        )
                    }
                }
            }
            // 先把已答内容写库（replaceParts 与 upsertToolEvent 等价），再进入流式。
            if (!isTemporary) {
                val initial = container.messageDao.get(messageId)
                if (initial != null) {
                    withContext(Dispatchers.IO) {
                        container.messageDao.replaceParts(
                            initial.withParts(parts = updatedParts, isStreaming = true),
                            streaming = true,
                        )
                    }
                }
            }
            try {
                // 历史 = 目标之前的消息正文 + assistant tool_calls 记录 + 工具回答
                // （chat_completions_api.dart _buildAssistantToolCallMessage 形状）。
                val history = _messages.value
                    .takeWhile { it.id != messageId }
                    .mapNotNull { msg ->
                        val content = msg.parts.filterIsInstance<TextPart>()
                            .joinToString("") { it.text }
                        if (content.isEmpty()) null
                        else LlmMessage(role = msg.role, content = content)
                    }
                    .toMutableList()
                history.add(
                    LlmMessage(
                        role = "assistant",
                        content = targetUi.content.ifEmpty { "\n\n" },
                        toolCalls = listOf(LlmToolCall(part.id, part.toolName, part.arguments.toString())),
                    ),
                )
                history.add(
                    LlmMessage(
                        role = "tool",
                        toolCallId = part.id,
                        toolName = part.toolName,
                        content = resultJson,
                    ),
                )
                runGenerationLoop(
                    assistantId = messageId,
                    history = history,
                    tools = offeredTools(),
                    allParts = allParts,
                    allSegments = allSegments,
                    updateStreaming = { parts, segments ->
                        updateAssistantStreaming(messageId, parts, segments)
                    },
                    onPersist = { parts, _ -> persistFinal(parts) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                persistFinal(allParts)
            } catch (e: Exception) {
                persistFinal(markFailed(messageId, e.toString(), allParts, encodeSegments(allSegments)))
            } finally {
                _streaming.value = false
                container.streamingConversationIds.value =
                    container.streamingConversationIds.value - conversationId
            }
        }
    }

    /** chat_message 无 data class copy —— 手工重建一条保留其余字段的消息。 */
    private fun ChatMessage.withParts(
        parts: List<MessagePart>,
        reasoningSegmentsJson: String? = this.reasoningSegmentsJson,
        isStreaming: Boolean = this.isStreaming,
        updatedAt: Long? = System.currentTimeMillis(),
    ): ChatMessage = ChatMessage(
        id = id,
        role = role,
        parts = parts,
        timestamp = timestamp,
        modelId = modelId,
        providerId = providerId,
        totalTokens = totalTokens,
        conversationId = conversationId,
        isStreaming = isStreaming,
        reasoningStartAt = reasoningStartAt,
        reasoningFinishedAt = reasoningFinishedAt,
        translation = translation,
        reasoningSegmentsJson = reasoningSegmentsJson,
        groupId = groupId,
        version = version,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        durationMs = durationMs,
        updatedAt = updatedAt,
        messageOrder = messageOrder,
    )

    /**
     * Local tools offered to the model (see startGeneration for the subset
     * rationale). Mirrors LocalToolsService.buildToolDefinitions 419-449:
     * only names in the assistant's localToolIds that are available on this
     * platform, definitions from the catalog.
     */
    private fun offeredTools(): List<LlmToolSpec> {
        val assistant = container.currentAssistant() ?: return emptyList()
        val names = com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames
        val offered = setOf(
            names.TIME_INFO,
            names.ASK_USER,
            names.CALENDAR_CREATE,
        )
        val out = mutableListOf<LlmToolSpec>()
        // Web search tool (tool_handler_service.dart L241-245): offered
        // whenever the assistant's search switch is on.
        if (assistant.searchEnabled) {
            out.add(
                LlmToolSpec(
                    name = com.psyche.memo.provider.search.SearchToolService.TOOL_NAME,
                    description = com.psyche.memo.provider.search.SearchToolService.TOOL_DESCRIPTION,
                    inputSchemaJson = com.psyche.memo.provider.search.SearchToolService.parametersJson(),
                ),
            )
        }
        for (name in assistant.localToolIds) {
            if (name !in offered) continue
            if (!com.psyche.memo.ui.BuiltInToolCatalog.isAvailableOnThisPlatform(name)) continue
            val definition = com.psyche.memo.ui.BuiltInToolCatalog.localDefinition(name)
            val fn = definition["function"] as? JsonObject ?: continue
            val specName = (fn["name"] as? JsonPrimitive)?.content ?: name
            val description = (fn["description"] as? JsonPrimitive)?.content ?: ""
            val parameters = fn["parameters"] as? JsonObject
            out.add(LlmToolSpec(specName, description, parameters?.toString() ?: "{}"))
        }
        return out
    }

    /**
     * message_builder_service.dart: the assistant system prompt plus the
     * search citation block when web search is enabled
     * (injectSearchPrompt L1734-1750).
     */
    private fun buildSystemPrompt(assistant: com.psyche.memo.data.model.Assistant?): String {
        val parts = mutableListOf<String>()
        assistant?.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (assistant?.searchEnabled == true) {
            parts.add(com.psyche.memo.provider.search.SearchToolService.SYSTEM_PROMPT)
        }
        return parts.joinToString("\n\n")
    }

    /** takeCallsAfterRound 的 Native 等价 —— 本轮新增（未执行）的工具调用。 */
    private fun takeCallsAfterRound(roundHandler: StreamChunkHandler): List<ToolCallPayload> =
        roundHandler.parts
            .filterIsInstance<ToolCallPart>()
            .mapNotNull { ToolCallPart.decode(it.payloadJson) }
            .filter { it.content == null }

    /** 工具调用参数解析：非法 JSON 落到空对象（handler 收不到对象参数时）。 */
    private fun parseToolArguments(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    /** accumulate 各轮 Finish 的 usage（openai_provider 对 roundUsage 累加）。 */
    private fun accumulateUsage(a: UsageStats?, b: UsageStats?): UsageStats? {
        if (b == null) return a
        if (a == null) return b
        fun sum(x: Int?, y: Int?): Int? = when {
            x == null && y == null -> null
            x == null -> y
            y == null -> x
            else -> x + y
        }
        return UsageStats(
            promptTokens = sum(a.promptTokens, b.promptTokens),
            completionTokens = sum(a.completionTokens, b.completionTokens),
            cachedTokens = sum(a.cachedTokens, b.cachedTokens),
            totalTokens = sum(a.totalTokens, b.totalTokens),
        )
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
        segmentsJson: String? = null,
    ) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        _messages.value = msgs.toMutableList().apply {
            set(
                index,
                msgs[index].copy(parts = parts, isStreaming = true, reasoningSegmentsJson = segmentsJson),
            )
        }
    }

    private fun finishAssistant(
        assistantId: String,
        parts: List<MessagePart>,
        segmentsJson: String? = null,
    ) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        val finalUi = msgs[index].copy(
            parts = parts,
            isStreaming = false,
            reasoningSegmentsJson = segmentsJson,
        )
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
        segmentsJson: String? = null,
    ): List<MessagePart> {
        val hasText = parts.any { it is TextPart && it.text.isNotEmpty() }
        val finalParts = if (hasText) parts else parts + TextPart(errorText)
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index >= 0) {
            _messages.value = msgs.toMutableList().apply {
                set(
                    index,
                    msgs[index].copy(
                        parts = finalParts,
                        isStreaming = false,
                        failed = true,
                        reasoningSegmentsJson = segmentsJson,
                    ),
                )
            }
        }
        return finalParts
    }

    /**
     * stream_controller.dart 771 — a segment starts expanded only when
     * "auto-collapse thinking" is off; the user's own toggle wins afterwards.
     */
    private fun encodeSegments(segments: List<ReasoningSegment>): String? {
        val autoCollapse = readBool("display_auto_collapse_thinking_v1", true)
        return ReasoningSegmentCodec.encode(
            segments.map { it.copy(expanded = !autoCollapse) },
        )
    }

    private fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readLocal(key)?.let { it == "1" } ?: default

    /**
     * Expand/collapse one reasoning segment
     * (home_page_controller.toggleReasoningSegment 2268-2287): flip the stored
     * flag in memory and rewrite `reasoning_segments_json`.
     */
    fun toggleReasoningSegment(messageId: String, segmentIndex: Int) {
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val segments = ReasoningSegmentCodec.decode(msgs[idx].reasoningSegmentsJson).toMutableList()
        val segment = segments.getOrNull(segmentIndex) ?: return
        segments[segmentIndex] = segment.copy(expanded = !segment.expanded)
        val json = ReasoningSegmentCodec.encode(segments)
        _messages.value = msgs.map { if (it.id == messageId) it.copy(reasoningSegmentsJson = json) else it }
        if (isTemporary) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.messageDao.updateReasoningSegments(messageId, json) }
        }
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
        segments: List<com.psyche.memo.data.model.ReasoningSegment> = emptyList(),
        usage: UsageStats? = null,
        durationMs: Long = 0L,
    ) {
        if (isTemporary || parts.isEmpty()) return
        val providerId = selectedProviderId.value
        val modelId = selectedModelId.value
        // stream_controller.dart 1444 — a segment left open by an interrupted
        // stream reports start == end so the restored timer never runs forever.
        val closed = segments.map {
            it.copy(finishedAt = it.finishedAt ?: it.startAt)
        }
        val startAt = closed.firstOrNull()?.startAt
        val finishedAt = closed.lastOrNull()?.finishedAt
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
                        reasoningStartAt = startAt,
                        reasoningFinishedAt = finishedAt,
                        reasoningSegmentsJson = ReasoningSegmentCodec.encode(closed),
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
        reasoningSegmentsJson = reasoningSegmentsJson,
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
