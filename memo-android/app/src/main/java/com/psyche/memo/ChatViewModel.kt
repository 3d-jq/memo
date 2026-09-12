package com.psyche.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.psyche.memo.common.logging.ContextSource
import com.psyche.memo.common.logging.ContextTag
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
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

    /** 待发送附件（图片/文件），发送后并入用户消息的 parts 并清空。 */
    data class PendingAttachment(
        val uri: String,
        val mime: String?,
        val name: String,
        val isImage: Boolean,
    )

    private val _attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingAttachment>> = _attachments

    fun addAttachments(items: List<PendingAttachment>) {
        if (items.isEmpty()) return
        _attachments.value = _attachments.value + items
    }

    fun removeAttachment(index: Int) {
        val list = _attachments.value
        if (index !in list.indices) return
        _attachments.value = list.filterIndexed { i, _ -> i != index }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

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

    /** chat_suggestions_json —— 助手回复后生成的 3 条建议气泡。 */
    private val _suggestions = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val suggestions: kotlinx.coroutines.flow.StateFlow<List<String>> = _suggestions

    /**
     * 是否还有更早的历史（chat_service.dart `LoadedTimelinePage.hasMoreBefore`：
     * 首页窗口的起始逻辑索引 > 0）。列表滚到顶部附近时用 [loadOlderMessages]
     * 往前翻页。
     */
    private val _hasMoreBefore = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasMoreBefore: kotlinx.coroutines.flow.StateFlow<Boolean> = _hasMoreBefore

    /** 翻页互斥：一次只加载一页，避免滚动回调里并发触发。 */
    private var loadingOlder = false

    /**
     * 往前加载一页历史 —— chat_controller.dart:384 `loadMoreBefore` →
     * `loadTimelinePage(beforeRevisionId: 首页第一条)`，每页 [HISTORY_PAGE_SIZE]
     * （chat_service.dart `defaultHistoryPageSize = 20`）。
     *
     * 返回新插入的条数：调用方拿它把视口锚回原来的内容 —— Compose 的
     * LazyColumn 在头部插入后是按 index 保持位置的，不补偿会直接跳到新加载的顶部。
     */
    suspend fun loadOlderMessages(): Int {
        if (isTemporary) return 0
        if (loadingOlder || !_hasMoreBefore.value) return 0
        val current = _messages.value
        if (current.isEmpty()) return 0
        loadingOlder = true
        return try {
            // 已经加载过的消息/版本组不再插入（同 group_id 的其它版本由
            // collapseVersions 的语义保持唯一）。
            val existingIds = current.mapTo(HashSet()) { it.id }
            val existingGroups = current.mapTo(HashSet()) { it.groupId }
            var beforeId = current.first().id
            var guard = 0
            while (guard++ < 5) {
                val rows = withContext(Dispatchers.IO) {
                    container.messageDao.getBefore(conversationId, beforeId, HISTORY_PAGE_SIZE)
                }
                if (rows.isEmpty()) {
                    _hasMoreBefore.value = false
                    return 0
                }
                val older = rows
                    .filter { it.id !in existingIds && it.groupId !in existingGroups }
                    .map { it.toUi() }
                if (older.isNotEmpty()) {
                    _messages.value = older + _messages.value
                    // 取满一页就认为可能还有更多（原版 hasMoreBefore 同义）；
                    // 下一页取空时上面会把标记清掉。
                    _hasMoreBefore.value = rows.size >= HISTORY_PAGE_SIZE
                    return older.size
                }
                // 这一页全是被折叠掉的版本行 → 继续往前翻。
                beforeId = rows.first().id
            }
            _hasMoreBefore.value = false
            0
        } finally {
            loadingOlder = false
        }
    }

    private suspend fun reloadTail() {
        val loaded = withContext(Dispatchers.IO) {
            container.messageDao.getTail(conversationId)
        }
        _suggestions.value = withContext(Dispatchers.IO) {
            if (isTemporary) emptyList()
            else container.conversationDao.get(conversationId)?.chatSuggestions ?: emptyList()
        }
        val versions = withContext(Dispatchers.IO) {
            container.messageDao.groupVersions(conversationId)
        }
        _versionInfo.value = versions
        _messages.value = loaded.collapseVersions()
        // 首页窗口（40 条）之后还有没有更早的：总数比窗口大就说明有
        // （原版 LoadedTimelinePage.hasMoreBefore = start > 0 的等价判断）。
        _hasMoreBefore.value = withContext(Dispatchers.IO) {
            container.messageDao.count(conversationId) > loaded.size
        }
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

    /**
     * `home_view_model._clearSuggestionsFor` —— 发送 / 重新生成 / 工具续答时会先
     * 清掉上一轮的建议气泡（内存 + 落库），避免旧建议跨轮残留。
     */
    private fun clearSuggestions() {
        if (_suggestions.value.isEmpty()) return
        _suggestions.value = emptyList()
        writeSuggestions(emptyList())
    }

    fun send() {
        val text = input.value.trim()
        val pending = _attachments.value
        if ((text.isEmpty() && pending.isEmpty()) || _streaming.value) return
        input.value = ""
        _attachments.value = emptyList()
        clearSuggestions()
        viewModelScope.launch {
            // nextOrder queries SQLite synchronously, so both the build and
            // the insert run on Dispatchers.IO; StateFlow updates (append)
            // stay outside the IO blocks, in the original order.
            val userMessage = withContext(Dispatchers.IO) { buildUserMessage(text, pending) }
            append(userMessage)
            // Persist user message best-effort (DAO errors surface in logs).
            if (!isTemporary) {
                withContext(Dispatchers.IO) {
                    // draft 会话（新建后一条消息都没发）到这一刻才写进历史 —— 原版
                    // `createDraftConversation` 只在内存里挂着，`_saveConversation`
                    // 在首条消息落库时才持久化。
                    ensureConversationRow()
                    container.messageDao.insert(userMessage)
                }
            }
            startGeneration(userMessage)
        }
    }

    /**
     * draft → 持久化：draft 会话只在内存（没有 conversation_rows 行），首条消息落库
     * 前把它补上，否则这个会话永远不会出现在抽屉的历史列表里。标题留空，等标题生成
     * 或手动重命名再填。
     */
    private suspend fun ensureConversationRow() {
        if (container.conversationDao.get(conversationId) != null) return
        container.conversationDao.insert(
            com.psyche.memo.data.model.Conversation.create(
                id = conversationId,
                title = "",
                assistantId = container.currentAssistantId.value,
            ),
        )
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
            // stream_controller.dart 1339 / finishReasoningIfNeeded — a stop
            // ends the reasoning phase too: close the open segment and fold it
            // (when auto-collapse is on) so the card is not left spinning.
            val closedJson = closeOpenReasoningSegment(last)
            _messages.value = msgs.dropLast(1) +
                last.copy(isStreaming = false, reasoningSegmentsJson = closedJson ?: last.reasoningSegmentsJson)
            persistClosedSegments(last.id, closedJson)
        }
    }

    /**
     * Closes the message's still-open last reasoning segment (stamp
     * `finishedAt`, fold when auto-collapse is on). Returns the rewritten
     * `reasoning_segments_json`, or null when nothing changed.
     * Mirrors `finishReasoningIfNeeded` (stream_controller.dart 1326-1362).
     */
    private fun closeOpenReasoningSegment(target: UiMessage): String? {
        val segments = ReasoningSegmentCodec.decode(target.reasoningSegmentsJson)
        if (segments.isEmpty()) return null
        val (closed, changed) = ReasoningSegmentCodec.finishLastOpenSegment(
            segments,
            now = System.currentTimeMillis(),
            autoCollapse = readBool(AUTO_COLLAPSE_THINKING_KEY, true),
        )
        if (!changed) return null
        return encodeSegments(closed)
    }

    /** Persists a rewritten segment payload after an out-of-band close. */
    private fun persistClosedSegments(messageId: String, json: String?) {
        if (json == null || isTemporary) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.messageDao.updateReasoningSegments(messageId, json) }
        }
    }

    // ------------------------------------------------------------------
    // Context management (home_view_model.dart clearContext L1099-1113)
    // ------------------------------------------------------------------

    /** Truncation point as a message order, or null when everything is sent. */
    private fun contextStartOrder(): Int? {
        if (isTemporary) return null
        val conv = container.conversationDao.get(conversationId) ?: return null
        val t = conv.truncateIndex
        if (t < 0) return null
        val ids = container.messageDao.getMessageIds(conversationId)
        val cutId = ids.getOrNull(t) ?: return null
        return container.messageDao.get(cutId)?.messageOrder
    }

    /**
     * Toggles the truncation point: cut everything up to now, or restore the
     * full history. Mirrors ChatService.toggleTruncateAtTail.
     */
    fun clearContext() {
        if (isTemporary) return
        val conv = container.conversationDao.get(conversationId) ?: return
        val count = container.messageDao.count(conversationId)
        val next = if (conv.truncateIndex == count) -1 else count
        container.conversationDao.setTruncateIndex(conversationId, next)
        _contextVersion.value = _contextVersion.value + 1
    }

    /**
     * home_view_model.compressContext —— 把折叠后的会话文本按模式截取/分块，交给
     * compress 模型总结（必要时分块 + 多轮合并），然后新建会话把摘要作为第一条
     * 用户消息（keepRecent 模式则保留最近若干轮用户消息）。
     * [onResult] 回传新会话 id 与错误 key（成功时 error 为 null）。
     */
    fun compressContext(
        mode: com.psyche.memo.common.CompressText.Mode,
        maxChars: Int?,
        keepUserMessages: Int?,
        onResult: (newConversationId: String?, errorKey: String?) -> Unit,
    ) {
        if (_streaming.value) {
            onResult(null, "busy")
            return
        }
        viewModelScope.launch {
            try {
                val pairs = _messages.value.map { it.role to it.content }
                if (pairs.isEmpty()) {
                    onResult(null, "no_messages")
                    return@launch
                }
                val contents = com.psyche.memo.common.CompressText.buildCompressRequestContents(
                    pairs,
                    mode,
                    maxChars,
                )
                if (contents.isEmpty()) {
                    onResult(null, "no_messages")
                    return@launch
                }
                val assistant = container.currentAssistant()
                val model = com.psyche.memo.common.CompressText.resolveCompressModel(
                    readModelSelection("compress_model_v1"),
                    readModelSelection("summary_model_v1"),
                    readModelSelection("title_model_v1"),
                    assistant?.chatModelProvider?.let { p -> assistant.chatModelId?.let { m -> p to m } },
                    selectedProviderId.value.takeIf { it.isNotEmpty() }
                        ?.let { p -> selectedModelId.value.takeIf { it.isNotEmpty() }?.let { m -> p to m } },
                ) ?: run {
                    onResult(null, "no_model")
                    return@launch
                }
                val template = readPrefString("compress_prompt_v1")
                    ?: com.psyche.memo.DefaultModelPrefs.DEFAULT_COMPRESS_PROMPT
                val locale = java.util.Locale.getDefault().toLanguageTag()
                val thinking = readBoolPref("compress_generation_thinking_enabled_v1")
                val budgetChars = com.psyche.memo.common.CompressText.compressRequestCharBudget(
                    readContextWindowTokens(model.first, model.second),
                )
                suspend fun summarize(text: String): String {
                    val prompt = template.replace("{content}", text).replace("{locale}", locale)
                    val request = LlmRequest(
                        providerId = model.first,
                        modelId = model.second,
                        messages = listOf(LlmMessage(role = "user", content = prompt)),
                        apiKey = container.apiKeyFor(model.first) ?: "",
                        baseUrl = container.baseUrlFor(model.first),
                        chatPath = container.providerConfig(model.first)?.chatPath,
                        thinkingBudget = if (thinking) -1 else 0,
                    )
                    return container.clientFor(model.first).complete(request)
                        .parts.joinToString("").trim()
                }

                var partials = contents.map { withContext(Dispatchers.IO) { summarize(it) } }
                if (partials.any { it.isEmpty() }) {
                    onResult(null, "empty_summary")
                    return@launch
                }
                var mergeRound = 0
                while (partials.size > 1 && mergeRound < 8) {
                    mergeRound++
                    val packed = com.psyche.memo.common.CompressText.chunkPlainTexts(partials, budgetChars)
                    partials = packed.map { withContext(Dispatchers.IO) { summarize(it) } }
                    if (partials.any { it.isEmpty() }) {
                        onResult(null, "empty_summary")
                        return@launch
                    }
                }
                val summary = if (partials.size == 1) {
                    partials.single()
                } else {
                    withContext(Dispatchers.IO) {
                        summarize(com.psyche.memo.common.Utf16SafeCut.truncateHead(partials.joinToString("\n\n"), budgetChars))
                    }
                }
                if (summary.isEmpty()) {
                    onResult(null, "empty_summary")
                    return@launch
                }

                val kept = if (mode == com.psyche.memo.common.CompressText.Mode.KEEP_RECENT) {
                    com.psyche.memo.common.CompressText.selectKeepRecentMessages(pairs, keepUserMessages ?: 0)
                } else {
                    null
                }
                val source = container.conversationDao.get(conversationId)
                val newConversation = com.psyche.memo.data.model.Conversation.create(
                    title = source?.title ?: "",
                    assistantId = source?.assistantId ?: container.currentAssistantId.value,
                )
                withContext(Dispatchers.IO) {
                    container.conversationDao.insert(newConversation)
                    var order = 0
                    container.messageDao.insert(
                        com.psyche.memo.data.model.ChatMessage(
                            id = com.psyche.memo.data.model.ChatMessage.newId(),
                            role = "user",
                            parts = listOf(com.psyche.memo.data.model.TextPart(summary)),
                            timestamp = System.currentTimeMillis(),
                            conversationId = newConversation.id,
                            groupId = com.psyche.memo.data.model.ChatMessage.newId(),
                            messageOrder = order++,
                        ),
                    )
                    kept?.forEach { (role, content) ->
                        container.messageDao.insert(
                            com.psyche.memo.data.model.ChatMessage(
                                id = com.psyche.memo.data.model.ChatMessage.newId(),
                                role = role,
                                parts = listOf(com.psyche.memo.data.model.TextPart(content)),
                                timestamp = System.currentTimeMillis(),
                                conversationId = newConversation.id,
                                groupId = com.psyche.memo.data.model.ChatMessage.newId(),
                                messageOrder = order++,
                            ),
                        )
                    }
                }
                onResult(newConversation.id, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // home_page.dart L1843-1848 —— 压缩失败也写应用日志（页面上仍有 toast）。
                com.psyche.memo.common.logging.FlutterLogger.log(
                    "[CompressContext] dialog failed: $e\n${e.stackTraceToString()}",
                    tag = "HomePage",
                )
                onResult(null, e.message ?: "error")
            }
        }
    }

    private fun readPrefString(key: String): String? =
        container.preferenceRepository.readJson(key)
            ?.let { raw -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw) }
            ?.takeIf { it.isNotBlank() }

    private fun readBoolPref(key: String): Boolean {
        val raw = container.preferenceRepository.readJson(key) ?: return false
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.booleanOrNull
        }.getOrNull() ?: false
    }

    private fun readModelSelection(key: String): Pair<String, String>? =
        com.psyche.memo.DefaultModelPrefs.parseModelSelection(readPrefString(key))

    /** readModelContextWindowTokens —— 模型 override 里的上下文字段。 */
    private fun readContextWindowTokens(providerId: String, modelId: String): Int? {
        val override = container.providerConfig(providerId)?.modelOverrides?.get(modelId)
            as? kotlinx.serialization.json.JsonObject ?: return null
        for (key in listOf(
            "contextWindow", "context_window", "maxContextTokens",
            "max_context_tokens", "contextLength", "context_length",
        )) {
            val value = (override[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toIntOrNull()
            if (value != null && value > 0) return value
        }
        return null
    }

    /** 供 UI 观察清空后刷新标签。 */
    private val _contextVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val contextVersion: kotlinx.coroutines.flow.StateFlow<Int> = _contextVersion

    /** clearContextLabel —— "Clear Context (actual/configured)". */
    fun clearContextLabel(): String {
        val assistant = container.currentAssistant()
        val configured = if (assistant?.limitContextMessages == true) assistant.contextMessageSize else 0
        val total = if (isTemporary) _messages.value.size else container.messageDao.count(conversationId)
        val t = if (isTemporary) -1 else (container.conversationDao.get(conversationId)?.truncateIndex ?: -1)
        val safe = if (t < 0 || t > total) 0 else t
        val remaining = total - safe
        return if (configured > 0) {
            val actual = if (remaining > configured) configured else remaining
            container.appContext.getString(com.psyche.memo.ui.R.string.home_page_clear_context_with_count, actual.toString(), configured.toString())
        } else {
            container.appContext.getString(com.psyche.memo.ui.R.string.home_page_clear_context)
        }
    }

    /** thinking_budget_v1 —— null/-1 auto、0 off、>0 具体预算。 */
    private fun readThinkingBudgetSetting(): Int? {
        val raw = runCatching {
            container.preferenceRepository.readJson("thinking_budget_v1")
        }.getOrNull() ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.intOrNull
        }.getOrNull()
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
        clearSuggestions()
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

    /** 后台聊天生成（ChatBackgroundController，RikkaHub FGS 移植）的当前代 id。 */
    private var backgroundGenerationId: String? = null

    /**
     * Live Update 进度通知的 sender（RikkaHub ChatService senderName L538-543：
     * useAssistantAvatar 时助手名、空回退默认助手名；否则模型显示名）。Memo
     * 无模型显示名运行时解析链路，用当前 modelId 代替。
     */
    private var backgroundSenderName: String = ""

    private fun resolveNotificationSenderName(): String {
        val assistant = container.currentAssistant()
        return if (assistant?.useAssistantAvatar == true) {
            assistant.name.ifEmpty {
                MemoApplication.instance?.getString(
                    com.psyche.memo.ui.R.string.assistant_provider_default_assistant_name,
                ).orEmpty()
            }
        } else {
            selectedModelId.value
        }
    }

    private fun backgroundMode(): com.psyche.memo.service.ChatBackgroundController.AndroidBackgroundChatMode =
        com.psyche.memo.service.ChatBackgroundController.modeOf(container.preferenceRepository::readLocal)

    private fun beginBackgroundGeneration() {
        val id = java.util.UUID.randomUUID().toString()
        backgroundGenerationId = id
        backgroundSenderName = resolveNotificationSenderName()
        com.psyche.memo.service.ChatBackgroundController.onGenerationStart(
            conversationId = conversationId,
            mode = backgroundMode(),
            generationId = id,
            stopGeneration = { generationJob?.cancel() },
        )
    }

    private fun endBackgroundGeneration() {
        val id = backgroundGenerationId ?: return
        backgroundGenerationId = null
        com.psyche.memo.service.ChatBackgroundController.onGenerationEnd(
            conversationId = conversationId,
            mode = backgroundMode(),
            generationId = id,
            isCurrentConversation = true,
        )
        // 生成结束：取消 Live Update 进度通知（ChatGenerationEnded 等价）。
        com.psyche.memo.service.ChatNotificationManager.onGenerationEnded(conversationId)
    }

    private fun startGeneration(userMessage: ChatMessage) {
        generationJob?.cancel()
        _streaming.value = true
        beginBackgroundGeneration()
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
            // 新一轮生成：清空 segment 初始态跟踪（见 encodeSegments）。
            seenSegmentIndices.clear()
            var persisted = false
            fun persistOnce(parts: List<MessagePart>, usage: UsageStats?, segmentsJson: String? = null) {
                if (persisted) return
                persisted = true
                // 落库走与 UI 相同的编码路径（含新增段初始展开态与用户的展开/折叠
                // 点击），否则库里存的是未修正的默认 expanded，冷启动即回退。
                persistAssistant(
                    assistantId,
                    parts,
                    segments = allSegments,
                    usage = usage,
                    durationMs = System.currentTimeMillis() - generationStartMs,
                    segmentsJson = segmentsJson ?: encodeSegments(allSegments),
                )
            }
            try {
                // Build request from current UI messages (exclude skeleton).
                // message_builder_service.dart L215-221 —— truncateIndex 之后
                // 的消息才进入请求（"清空上下文"）。
                val startOrder = contextStartOrder()
                val rawMessages = _messages.value.dropLast(1)
                    .let { all -> if (startOrder == null) all else all.filter { it.messageOrder >= startOrder } }
                // ocr_service.dart：开启 OCR 时先把图片识别成文本块前置进用户轮次
                // （模型没有视觉能力也能读图）；结果按图片内容哈希缓存。
                val ocrSettings = com.psyche.memo.provider.OcrService.settingsOf(container.preferenceRepository)
                val ocrBlocks: Map<String, String> = if (ocrSettings.usable) {
                    withContext(Dispatchers.IO) {
                        rawMessages.filter { it.role == "user" }.mapNotNull { msg ->
                            val images = msg.parts
                                .filterIsInstance<com.psyche.memo.data.model.ImagePart>()
                                .filter { it.unavailable != true && it.uri.isNotBlank() }
                            if (images.isEmpty()) return@mapNotNull null
                            val hashes = images.mapNotNull { com.psyche.memo.provider.OcrService.contentHash(it.uri) }
                            val cachedText = hashes.mapNotNull { com.psyche.memo.provider.OcrService.cached(it) }
                            val text = if (cachedText.size == hashes.size && hashes.isNotEmpty()) {
                                cachedText.joinToString("\n\n")
                            } else {
                                com.psyche.memo.provider.OcrService.runOcr(
                                    container,
                                    ocrSettings,
                                    images.map { it.uri },
                                )?.also { result ->
                                    hashes.forEach { com.psyche.memo.provider.OcrService.cacheText(it, result) }
                                }
                            } ?: return@mapNotNull null
                            msg.id to com.psyche.memo.provider.OcrService.wrapBlock(text)
                        }.toMap()
                    }
                } else {
                    emptyMap()
                }
                // message_builder_service.readDocument: file attachments become
                // a text block prepended to the user turn, cached by path+stat.
                val fileBlocks = withContext(Dispatchers.IO) {
                    rawMessages.filter { it.role == "user" }.associate { msg ->
                        val block = StringBuilder()
                        for (part in msg.parts.filterIsInstance<com.psyche.memo.data.model.FilePart>()) {
                            if (part.unavailable == true) continue
                            val mime = part.mime?.takeIf { it.isNotBlank() }
                                ?: com.psyche.memo.provider.DocumentTextExtractor.mimeForName(part.name)
                            val text = com.psyche.memo.provider.DocumentTextExtractor
                                .extractCached(part.uri, mime)
                            if (text.isNullOrBlank()) continue
                            block.append("## user sent a file: ").append(part.name).append('\n')
                            block.append("<content>\n```\n")
                            block.append(text)
                            block.append("\n```\n</content>\n\n")
                        }
                        msg.id to block.toString()
                    }.filterValues { it.isNotEmpty() }
                }
                // 记忆摘要注入（memory_block_builder.buildFullSnapshotPrefix）：
                // 只加到本轮最后一条用户消息前，让模型无需主动调工具就能看到记忆。
                val memoryPrefix = container.currentAssistant()?.let { current ->
                    withContext(Dispatchers.IO) {
                        com.psyche.memo.provider.MemoryBlockBuilder.buildPrefix(container, current)
                    }
                }.orEmpty()
                val lastUserIndex = rawMessages.indexOfLast { it.role == "user" }
                // 上下文日志：组装期给承载注入内容的轮次打来源标签
                // （context_log_models.dart 的 `_kelivo_ctx_segments`），请求前由
                // ContextLogAssembler 切片写盘。历史轮次不带标签，读取时按 role 推断。
                val tagContextLog = com.psyche.memo.logging.ContextLogger.isEnabled
                val history = rawMessages
                    .mapIndexedNotNull { index, msg ->
                        val carriesMemory = index == lastUserIndex && memoryPrefix.isNotEmpty()
                        val content = (if (index == lastUserIndex) memoryPrefix else "") +
                            (ocrBlocks[msg.id] ?: "") + (fileBlocks[msg.id] ?: "") +
                            msg.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                        // Images ride along as part payloads; only user turns may
                        // carry them (assistant media is stashed by the original
                        // OpenAI builder instead of replayed).
                        val attachments = if (msg.role == "user") {
                            msg.parts.filterIsInstance<com.psyche.memo.data.model.ImagePart>()
                                .map { it.encodePayload() }
                        } else {
                            emptyList()
                        }
                        if (content.isEmpty() && attachments.isEmpty()) null
                        else LlmMessage(
                            role = msg.role,
                            content = content.ifEmpty { null },
                            parts = attachments,
                            // 冻结轮次语义（_tagFrozenUserPrompt L479-510）：记忆快照
                            // 前缀算 memorySnapshot，其余归 chatHistory。
                            contextTags = if (tagContextLog && carriesMemory) {
                                listOf(
                                    ContextTag(
                                        ContextSource.memorySnapshot,
                                        memoryPrefix.length,
                                        mapOf("kind" to "full"),
                                    ),
                                    ContextTag(
                                        ContextSource.chatHistory,
                                        content.length - memoryPrefix.length,
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        )
                    }
                    .toMutableList()
                // System prompt injection (message_builder_service.dart L167-189):
                // 助手提示词 + 记忆规则 + 搜索引用块 + 指令注入，按序拼进系统消息；
                // 世界书随后 wrap 在它外面。每段带来源标签供上下文日志使用。
                val assistant = container.currentAssistant()
                val systemParts = buildSystemPromptParts(assistant)
                if (systemParts.isNotEmpty()) {
                    val assembler = com.psyche.memo.logging.ContextLogAssembler
                    val existing = history.indexOfFirst { it.role == "system" }
                    if (existing >= 0) {
                        val previous = history[existing]
                        history[existing] = previous.copy(
                            content = assembler.joinedAppending(previous.content, systemParts),
                            contextTags = if (!tagContextLog) {
                                previous.contextTags
                            } else {
                                assembler.appendedSystemMessageTags(
                                    previous.contextTags,
                                    previous.content,
                                    systemParts,
                                )
                            },
                        )
                    } else {
                        history.add(
                            0,
                            LlmMessage(
                                role = "system",
                                content = assembler.joinSystemParts(systemParts),
                                contextTags = if (tagContextLog) {
                                    assembler.systemMessageTags(systemParts)
                                } else {
                                    emptyList()
                                },
                            ),
                        )
                    }
                }
                // World book (lorebook) injection. Mirrors
                // `MessageBuilderService.injectWorldBookPrompts`
                // (message_builder_service.dart L1776-2106): keyword/regex
                // triggers, then splice entries at BEFORE_SYSTEM_PROMPT,
                // AFTER_SYSTEM_PROMPT, TOP_OF_CHAT, BOTTOM_OF_CHAT, or
                // AT_DEPTH. The repo is the same one the WorldBook settings
                // page writes to, so a toggle there takes effect on the very
                // next chat request.
                run {
                    val assistantId = container.currentAssistant()?.id
                    val activeIds = container.worldBookRepository.activeIds(assistantId)
                    if (activeIds.isNotEmpty()) {
                        val books = container.worldBookRepository.books()
                        val injected = com.psyche.memo.worldbook.WorldBookInjector.inject(
                            history, books, activeIds,
                            tagContextLog = tagContextLog,
                        )
                        if (injected !== history) {
                            history.clear()
                            history.addAll(injected)
                        }
                    }
                }
                // context_logger.logPrepared（message_generation_service L253-263）——
                // 打标签 → stripInternalRevisionIds 之间写一条上下文快照。
                // Android 的 strip 步骤不存在（标签不在 wire 载荷里）。
                com.psyche.memo.logging.ContextLogAssembler.logPrepared(
                    messages = history,
                    conversationId = conversationId,
                    assistantName = assistant?.name.orEmpty(),
                    provider = container.providerConfig(selectedProviderId.value)?.name
                        ?.takeIf { it.isNotBlank() } ?: selectedProviderId.value,
                    model = selectedModelId.value,
                )
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
                    onPersist = { parts, usage, segmentsJson ->
                        persistOnce(parts, usage, segmentsJson)
                    },
                )
                // home_page_controller.dart L1763-1765 —— 「自动播放助手回复」：
                // 正常跑完一轮就朗读整条回复（取消/报错不播）。
                if (readBoolPref("tts_auto_play_assistant_replies_v1")) {
                    val text = allParts.filterIsInstance<TextPart>().joinToString("") { it.text }
                    if (text.isNotBlank()) {
                        com.psyche.memo.ui.chat.TtsPlayer.speak(text, ownerId = assistantId)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user stop: the partial reply is kept and persisted, exactly
                // like the original stop path.
                persistAssistant(
                    assistantId,
                    allParts,
                    segments = allSegments,
                    segmentsJson = encodeSegments(allSegments),
                )
            } catch (e: Exception) {
                val segmentsJson = encodeSegments(allSegments)
                val finalParts = markFailed(assistantId, e.toString(), allParts, segmentsJson)
                persistAssistant(
                    assistantId,
                    finalParts,
                    segments = allSegments,
                    segmentsJson = segmentsJson,
                )
            } finally {
                _streaming.value = false
                container.streamingConversationIds.value =
                    container.streamingConversationIds.value - conversationId
                endBackgroundGeneration()
                // home_view_model L1755+ —— 回复完成后生成建议气泡。
                maybeGenerateSuggestions()
                // 默认模型「标题总结」槽位通电：首条回复完成后自动生成标题
                // （标题仍是默认占位时）；「对话总结」槽位在满足助手开关与
                // 消息阈值后生成摘要。二者均复用 TitleSummaryGenerator。
                maybeGenerateTitle()
                maybeGenerateSummary()
                // §12.1 —— 回复完成后按助手的自动整理开关/轮数阈值排队后台整理。
                maybeOrganizeMemory()
            }
        }
    }

    /**
     * chat_suggestion_service.generate —— 用 suggestion 模型对最近 8 轮生成
     * 最多 3 条建议，写回 conversation.chatSuggestions。
     */
    private fun maybeGenerateSuggestions() {
        if (isTemporary) return
        if (!readBoolPref("suggestion_generation_enabled_v1")) return
        val stored = readModelSelection("suggestion_model_v1")
        val providerId = stored?.first ?: selectedProviderId.value
        val modelId = stored?.second ?: selectedModelId.value
        if (providerId.isEmpty() || modelId.isEmpty()) return
        val startOrder = contextStartOrder()
        val pairs = _messages.value
            .filter { startOrder == null || it.messageOrder >= startOrder }
            .map { it.role to it.content }
        val content = com.psyche.memo.common.SuggestionText.buildContent(pairs)
        if (content.isBlank()) return
        val template = readPrefString("suggestion_prompt_v1")
            ?: com.psyche.memo.DefaultModelPrefs.DEFAULT_SUGGESTION_PROMPT
        val locale = java.util.Locale.getDefault().toLanguageTag()
        val thinking = readBoolPref("suggestion_generation_thinking_enabled_v1")
        viewModelScope.launch {
            try {
                // chat_service.clearConversationSuggestions —— 先清空旧建议。
                _suggestions.value = emptyList()
                writeSuggestions(emptyList())
                val prompt = template.replace("{content}", content).replace("{locale}", locale)
                val request = LlmRequest(
                    providerId = providerId,
                    modelId = modelId,
                    messages = listOf(LlmMessage(role = "user", content = prompt)),
                    apiKey = container.apiKeyFor(providerId) ?: "",
                    baseUrl = container.baseUrlFor(providerId),
                    chatPath = container.providerConfig(providerId)?.chatPath,
                    thinkingBudget = if (thinking) -1 else 0,
                )
                val raw = withContext(Dispatchers.IO) {
                    container.clientFor(providerId).complete(request).parts.joinToString("")
                }
                val parsed = com.psyche.memo.common.SuggestionText.parseSuggestions(raw)
                if (parsed.isEmpty()) return@launch
                _suggestions.value = parsed
                writeSuggestions(parsed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 建议生成失败静默（原版只记录日志）—— home_view_model
                // _runBackgroundTask(suggestions) 的日志落点。
                logBackgroundTaskFailure("suggestions", e)
            }
        }
    }

    private fun writeSuggestions(list: List<String>) {
        if (isTemporary) return
        val jsonText = kotlinx.serialization.json.JsonArray(
            list.map { kotlinx.serialization.json.JsonPrimitive(it) },
        ).toString()
        runCatching {
            container.conversationDao.updateJsonColumn(conversationId, "chat_suggestions_json", jsonText)
        }
    }

    /**
     * 默认模型「标题总结」槽位通电 —— 复用 TitleSummaryGenerator 生成会话标题。
     * 内部会自行判定：标题仍是默认占位 + title_generation_enabled_v1 开启 +
     * 已配置模型（title_model_v1，缺省回退聊天模型）。
     */
    private fun maybeGenerateTitle() {
        if (isTemporary) return
        viewModelScope.launch {
            runCatching {
                com.psyche.memo.TitleSummaryGenerator.generateTitle(container, conversationId, force = false)
            }.onFailure { e ->
                logBackgroundTaskFailure("title", e)
            }.getOrNull()?.let { generated ->
                // home_view_model.dart L1531-1536：写库后若会话就是当前会话，
                // 立即 updateCurrentConversation + notifyListeners，让顶栏刷新。
                title.value = generated
            }
        }
    }

    /**
     * home_view_model `_runBackgroundTask` L296-306 —— 后台任务（标题/摘要/
     * 记忆整理/建议）失败时写一行应用日志，聊天本身不受影响。
     */
    private fun logBackgroundTaskFailure(task: String, error: Throwable) {
        com.psyche.memo.common.logging.FlutterLogger.log(
            "[BackgroundTask:$task] failed: ${error.message ?: error}\n${error.stackTraceToString()}",
            tag = "HomeViewModel",
        )
    }

    /**
     * 重新从库里读取会话标题并刷新顶栏。
     *
     * Flutter 端 chat_service 持有一份共享的 `_conversationsCache`，抽屉/顶栏
     * 都读它，任意一处写标题（手动重命名、LLM 生成、从别的页面改）后
     * `notifyListeners()` 双方自动同步。Android 端没有这层共享缓存：顶栏读
     * [title]（进入会话时的一次性快照），抽屉读自己的会话列表。故抽屉侧写完
     * 标题后，通过本方法把变化同步回顶栏。
     */
    fun refreshTitle() {
        if (isTemporary) return
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                container.conversationDao.get(conversationId)
            }
            title.value = stored?.title?.trim() ?: ""
        }
    }

    /**
     * §12.1 —— 一轮结束后交给记忆 pipeline 排队（autoOrganizeMemory 与
     * smartAdd 的判定都在 pipeline 里，这里只负责触发；失败不影响聊天）。
     */
    private fun maybeOrganizeMemory() {
        if (isTemporary) return
        viewModelScope.launch {
            // The turn's assistant is the conversation's owner; the globally
            // selected assistant is only a fallback for an unbound conversation.
            val assistantId = withContext(Dispatchers.IO) {
                container.conversationDao.get(conversationId)?.assistantId
            } ?: container.currentAssistantId.value ?: return@launch
            runCatching {
                container.memoryPipeline.scheduleIfNeeded(conversationId, assistantId)
            }.onFailure { e ->
                // home_view_model.dart L339-344 —— MemoryPipeline schedule failed。
                logBackgroundTaskFailure("memory", e)
            }
        }
    }

    /**
     * 默认模型「对话总结」槽位通电 —— 复用 TitleSummaryGenerator 生成会话摘要。
     * 内部会自行判定：助手 allowPastConversationRecall + generateConversationSummary
     * 均开启 + 消息数越过 recentChatsSummaryMessageCount 阈值 + 已配置模型。
     */
    private fun maybeGenerateSummary() {
        if (isTemporary) return
        viewModelScope.launch {
            runCatching {
                com.psyche.memo.TitleSummaryGenerator.generateSummary(container, conversationId)
            }.onFailure { e ->
                logBackgroundTaskFailure("summary", e)
            }
        }
    }

    /** home_page_controller.sendSuggestion —— 插入输入框或直接发送。 */
    fun sendSuggestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        updateInput(trimmed)
        if (!readBoolPref("suggestion_insert_on_tap_only_v1")) send()
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
        onPersist: (List<MessagePart>, UsageStats?, String?) -> Unit,
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
            container = container,
            isTemporary = isTemporary,
        )
        val providerId = selectedProviderId.value
        val modelId = selectedModelId.value
        while (true) {
            // chat_actions.dart L2116-2117 —— 助手覆盖优先，其次全局 thinking_budget_v1。
            val thinkingBudget = container.currentAssistant()?.thinkingBudget
                ?: readThinkingBudgetSetting()
            val request = LlmRequest(
                providerId = providerId,
                modelId = modelId,
                messages = history,
                tools = tools,
                apiKey = container.apiKeyFor(providerId) ?: "",
                baseUrl = container.baseUrlFor(providerId),
                chatPath = container.providerConfig(providerId)?.chatPath,
                thinkingBudget = thinkingBudget,
                reasoning = com.psyche.memo.ModelRegistry.infer(modelId).reasoning,
            )
            val client = container.clientFor(providerId)
            var failed = false
            // Declared before the handler so `onSegmentClosed` can push the
            // mid-stream fold straight to the UI (Kotlin resolves the local
            // function at call time, not at lambda-creation time).
            lateinit var roundHandler: StreamChunkHandler
            fun roundUpdate() {
                updateStreaming(
                    allParts + roundHandler.parts,
                    encodeSegments(allSegments + roundHandler.reasoningSegments),
                )
            }
            // Fresh handler per round: each HTTP stream ends with its own
            // Finish, which would otherwise block later chunks (the Dart
            // handler is likewise one instance per response).
            //
            // The handler owns the "reasoning phase is over" move itself
            // (stamp finishedAt + fold when auto-collapse is on) so a thought
            // card folds as soon as a tool call starts or the answer begins
            // arriving — not only when the whole reply finishes
            // (stream_controller.dart L853 / L1232). `autoCollapse` is read
            // fresh each time, matching Dart's per-call settings read.
            roundHandler = StreamChunkHandler(
                autoCollapse = { readBool(AUTO_COLLAPSE_THINKING_KEY, true) },
                onSegmentClosed = { roundUpdate() },
            )
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
                        val errorSegmentsJson =
                            encodeSegments(allSegments + roundHandler.reasoningSegments)
                        val finalParts = markFailed(
                            assistantId,
                            chunk.message,
                            allParts + roundHandler.parts,
                            errorSegmentsJson,
                        )
                        onPersist(finalParts, finishUsage, errorSegmentsJson)
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
                // stream_controller.dart 1246-1255 —— 流正常结束：先给仍未结束的最后
                // 一段补上 finishedAt（计时器停住），再按「自动折叠思考」决定是否折起。
                val now = System.currentTimeMillis()
                val finalSegments = (allSegments + roundHandler.reasoningSegments).map {
                    if (it.finishedAt == null) it.copy(finishedAt = now) else it
                }
                val finalSegmentsJson = encodeSegments(collapseFinishedSegments(finalSegments))
                updateStreaming(finalParts, finalSegmentsJson)
                finishAssistant(assistantId, finalParts, finalSegmentsJson)
                onPersist(finalParts, finishUsage, finalSegmentsJson)
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
        beginBackgroundGeneration()
        generationJob = viewModelScope.launch {
            container.streamingConversationIds.value =
                container.streamingConversationIds.value + conversationId
            val allParts = updatedParts.toMutableList()
            val allSegments = ReasoningSegmentCodec.decode(targetUi.reasoningSegmentsJson).toMutableList()
            // 续写：已有 segment 视作「已存在」，保留其展开/折叠态（见 encodeSegments）。
            seenSegmentIndices.clear()
            seenSegmentIndices.addAll(allSegments.indices)
            var persisted = false
            fun persistFinal(parts: List<MessagePart>, segmentsJson: String? = null) {
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
                                reasoningSegmentsJson = segmentsJson ?: encodeSegments(allSegments),
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
                    onPersist = { parts, _, segmentsJson ->
                        persistFinal(parts, segmentsJson)
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                persistFinal(allParts)
            } catch (e: Exception) {
                persistFinal(markFailed(messageId, e.toString(), allParts, encodeSegments(allSegments)))
            } finally {
                _streaming.value = false
                container.streamingConversationIds.value =
                    container.streamingConversationIds.value - conversationId
                endBackgroundGeneration()
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
            names.CALENDAR_QUERY,
            names.CLIPBOARD,
            names.TEXT_TO_SPEECH,
            names.CALCULATE,
            names.SCREEN_TIME,
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
        // 记忆工具（memory_tools.dart buildDefinitions）：enableMemory 才提供，
        // 临时会话只读不写。
        out.addAll(
            com.psyche.memo.provider.MemoryTools.buildDefinitions(
                assistant = assistant,
                lang = com.psyche.memo.ui.MemorySettingsState(container).resolvedPromptLang(),
                allowMemoryWrites = !isTemporary,
            ),
        )
        // MCP 工具（mcp_tool_service）：助手绑定且已连接的服务器，仅启用的工具；
        // 与内置工具同名的条目按原版保留名规则剔除。
        val reserved = com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.all.toSet() + setOf(
            com.psyche.memo.provider.search.SearchToolService.TOOL_NAME,
        ) + com.psyche.memo.provider.MemoryTools.ALL_TOOL_NAMES
        for (serverId in assistant.mcpServerIds) {
            if (!container.mcpConnections.isConnected(serverId)) continue
            val config = container.mcpRepository.server(serverId) ?: continue
            val enabledNames = config.tools.filter { it.enabled }.map { it.name }.toSet()
            for (tool in container.mcpConnections.toolsFor(serverId)) {
                if (tool.name in reserved) continue
                if (enabledNames.isNotEmpty() && tool.name !in enabledNames) continue
                out.add(
                    LlmToolSpec(
                        name = tool.name,
                        description = tool.description ?: "",
                        inputSchemaJson = tool.inputSchema?.toString() ?: "{}",
                    ),
                )
            }
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
     * message_builder_service.dart 的系统消息组装顺序（L167-189）：
     * 助手系统提示词 → 记忆/回忆规则 → 搜索引用提示词 → 指令注入。
     * 每一段带自己的 [ContextSource]，上下文日志按它切片（见
     * [com.psyche.memo.logging.ContextLogAssembler]）。
     */
    private suspend fun buildSystemPromptParts(
        assistant: com.psyche.memo.data.model.Assistant?,
    ): List<Pair<ContextSource, String>> {
        val parts = mutableListOf<Pair<ContextSource, String>>()
        fun add(source: ContextSource, text: String?) {
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(source to it) }
        }
        add(ContextSource.systemPrompt, assistant?.systemPrompt)
        // 记忆规则（message_builder.injectMemoryAndRecentChats L1610-1643）：
        // 长期记忆规则与过往回忆规则各自独立门控。
        if (assistant != null && (assistant.enableMemory || assistant.allowPastConversationRecall)) {
            val lang = com.psyche.memo.ui.MemorySettingsState(container).resolvedPromptLang()
            val zh = lang == com.psyche.memo.ui.MemoryPromptLang.zh
            if (assistant.enableMemory) {
                add(
                    ContextSource.memoryRules,
                    com.psyche.memo.ui.MemorySettingsState(container)
                        .prompt(com.psyche.memo.ui.MemoryPromptKind.RULES, zh),
                )
            }
            if (assistant.allowPastConversationRecall) {
                add(
                    ContextSource.memoryRules,
                    com.psyche.memo.ui.MemoryPrompts.rulesPastConversationRecallFor(lang),
                )
            }
        }
        // injectSearchPrompt L1732-1746 —— 内置搜索不移植，故 searchEnabled 即注入。
        if (assistant?.searchEnabled == true) {
            add(
                ContextSource.searchPrompt,
                com.psyche.memo.provider.search.SearchToolService.SYSTEM_PROMPT,
            )
        }
        // injectInstructionPrompts L1748-1772 —— 助手启用中的注入项按顺序合并。
        add(ContextSource.instructionInjection, activeInstructionPrompts(assistant?.id))
        return parts
    }

    /**
     * `InstructionInjectionProvider.activesFor(assistantId)` —— 取该助手（或全局
     * 分组）启用中的注入项，按列表顺序用空行连接；空提示词忽略。
     */
    private suspend fun activeInstructionPrompts(assistantId: String?): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val repo = com.psyche.memo.data.repo.InstructionInjectionRepository(
                    container.database.writableDatabase,
                    container.preferenceRepository,
                )
                val active = repo.activeIds(assistantId).toSet()
                repo.items()
                    .filter { it.id in active }
                    .map { it.prompt.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
                    .takeIf { it.isNotEmpty() }
            }.getOrNull()
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

    private fun buildUserMessage(text: String, attachments: List<PendingAttachment> = emptyList()): ChatMessage {
        val id = ChatMessage.newId()
        val parts = buildList {
            for (a in attachments) {
                if (a.isImage) {
                    add(com.psyche.memo.data.model.ImagePart(uri = a.uri, mime = a.mime))
                } else {
                    add(com.psyche.memo.data.model.FilePart(uri = a.uri, name = a.name, mime = a.mime))
                }
            }
            if (text.isNotEmpty()) add(TextPart(text))
        }
        return ChatMessage(
            id = id,
            role = "user",
            parts = parts,
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
        // Live Update 进度通知（RikkaHub ChatGenerationUpdate 事件等价；
        // manager 内部做前台 / 开关 / 1s 节流 gate）。
        com.psyche.memo.service.ChatNotificationManager.onGenerationUpdate(
            conversationId = conversationId,
            senderName = backgroundSenderName,
            parts = parts,
            segmentsJson = segmentsJson,
        )
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
     * 已在本轮生成中出现过的 segment 下标。新增 segment 才给初始展开态，已存在的
     * 保留其当前 `expanded`（用户的展开/折叠点击）——对应 Flutter
     * `stream_controller.dart:776`「Do not reset r.expanded here - preserve user's
     * toggle state during streaming」。每次开新会话生成时清空。
     */
    private val seenSegmentIndices = HashSet<Int>()

    /**
     * stream_controller.dart 771/776 —— 新 segment 的初始展开态 =
     * `!autoCollapsePrompt`；已存在的 segment **不重置** expanded，从而保留用户在
     * 流式过程中手动展开/折叠的点击。此前每次编码都把所有 segment 重算成
     * `!autoCollapse`，导致手动折叠的思考卡在下次增量/落库时被改回展开（冷启动
     * 后即为展开态）。
     */
    private fun encodeSegments(segments: List<ReasoningSegment>): String? {
        val initialExpanded = !readBool(AUTO_COLLAPSE_THINKING_KEY, true)
        return ReasoningSegmentCodec.encode(
            ReasoningSegmentCodec.applyInitialExpanded(
                segments,
                seenSegmentIndices,
                initialExpanded,
            ),
        )
    }

    /**
     * stream_controller.dart 1231-1255 —— 流结束且「自动折叠思考」开启时，把**已结束**
     * 的 segment 折起来（`finishedAt != null`）。未结束的最后一段保持原态。开关关闭
     * 时原样返回（用户的手动展开得以保留）。
     */
    private fun collapseFinishedSegments(segments: List<ReasoningSegment>): List<ReasoningSegment> =
        ReasoningSegmentCodec.collapseFinishedSegments(
            segments,
            autoCollapse = readBool(AUTO_COLLAPSE_THINKING_KEY, true),
        )

    private fun readBool(key: String, default: Boolean): Boolean =
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default

    /**
     * Expand/collapse one reasoning segment
     * (home_page_controller.toggleReasoningSegment 2268-2287): flip the stored
     * flag in memory and rewrite `reasoning_segments_json`.
     */
    fun toggleReasoningSegment(messageId: String, segmentIndex: Int) {
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val json = ReasoningSegmentCodec.toggleExpandedAt(msgs[idx].reasoningSegmentsJson, segmentIndex)
            ?: msgs[idx].reasoningSegmentsJson
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
        /** 已算好的 `reasoning_segments_json`（含展开态）；null 时由 [segments] 兜底编码。 */
        segmentsJson: String? = null,
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
                        reasoningSegmentsJson = segmentsJson ?: ReasoningSegmentCodec.encode(closed),
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
        /** `display_auto_collapse_thinking_v1` — "auto-collapse thinking" setting. */
        private const val AUTO_COLLAPSE_THINKING_KEY = "display_auto_collapse_thinking_v1"

        /**
         * 往前翻页的每页条数 —— chat_service.dart `defaultHistoryPageSize = 20`
         * （首屏窗口是 `defaultTimelineInitialSlots = 40`，见 MessageDao.getTail）。
         */
        private const val HISTORY_PAGE_SIZE = 20

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
