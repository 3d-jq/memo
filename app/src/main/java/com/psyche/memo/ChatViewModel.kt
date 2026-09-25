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
import com.psyche.memo.llm.client.LlmImage
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.client.LlmToolCall
import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.llm.stream.StreamChunk
import com.psyche.memo.llm.stream.StreamChunkHandler
import com.psyche.memo.ui.chat.ToolHandler
import com.psyche.memo.ui.chat.ToolUiPart
import com.psyche.memo.ui.chat.TranslateLanguage
import com.psyche.memo.ui.chat.checkpointPart
import com.psyche.memo.ui.chat.compactionEntry
import com.psyche.memo.ui.chat.compactionWindow
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    /**
     * 新会话标记（HomeScreen 的 `pendingPresetInject`）：为真时 init 里把助手的
     * 预设对话落成真实消息。**注意别在类体里再声明一个同名字段** —— 那会屏蔽这个
     * 构造参数，注入静默失效（2026-09-13 修的真 bug，见 ChatPresetInjectionTest）。
     */
    private val injectPresets: Boolean = false,
) : ViewModel() {

    private val isTemporary: Boolean = conversationId == com.psyche.memo.data.model.Conversation.TEMPORARY_ID

    /**
     * `home_view_model.dart:185/423/495` `onHapticFeedback` —— **生成开始时**的触觉
     * 回调，由页面注入（触觉必须有 View 才能发，ViewModel 拿不到）。
     *
     * 挂在 ViewModel 而不是发送按钮的 onSend 上：上游是在 `sendMessage` /
     * `regenerateAtMessage` 真正开跑前触发，所以回车发送、重新生成、建议气泡等
     * 所有入口都覆盖；只挂按钮就会漏（用户 2026-09-15「消息生成触觉反馈好像没有
     * 做到呀」）。
     */
    var onHapticFeedback: (() -> Unit)? = null

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
        /**
         * 气泡内自动重试倒计时（Dart streaming_content_notifier.dart:34 RetryStatus
         * 等价物）：attempt/maxRetries 显示 "(2/3)"，retryAtMs 是退避结束的绝对时刻
         *（倒计时用它算剩余秒数，而不是 now+delay —— 消费延迟后仍准确）。
         * null = 不在重试等待中。仅流式期间短暂存在，不落库。
         */
        val retryStatus: RetryStatus? = null,
    ) {
        val content: String
            get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

        /**
         * 把 provider 报的 usage 落到 UI 消息上（token_display_widget 的数据源）。
         * 流结束时必须即时反映 —— 只写库不更新内存态的话，token 数字要等冷启动
         * 重读会话才出现（用户 2026-09-14 实测「输出结束后没马上显示」）。
         * null 表示该字段本轮没有上报，保留原值。
         */
        fun withTokenStats(
            totalTokens: Int?,
            promptTokens: Int?,
            completionTokens: Int?,
            cachedTokens: Int?,
            durationMs: Long?,
        ): UiMessage = copy(
            totalTokens = totalTokens ?: this.totalTokens,
            promptTokens = promptTokens ?: this.promptTokens,
            completionTokens = completionTokens ?: this.completionTokens,
            cachedTokens = cachedTokens ?: this.cachedTokens,
            durationMs = durationMs ?: this.durationMs,
        )

        /** In-bubble countdown while auto-retry waits for the next attempt
         *（Dart streaming_content_notifier.dart:34 RetryStatus 等价物）。 */
        data class RetryStatus(
            val attempt: Int,
            val maxRetries: Int,
            /** Absolute epoch-ms deadline when backoff ends. */
            val retryAtMs: Long,
        )
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

    /**
     * 首屏窗口是否已经读过一次（[reloadTail] 走完）。`messages` 为空**且**这个为真才是
     * 「这是个空会话」—— 否则刚打开会话的那一帧会把有消息的会话当成空的（顶栏图标会闪）。
     * 顶栏 `+` 的三态判据 `newActionToggleable` 用它，替掉原先组合期的
     * `messageDao.count(conversationId)`（整表 COUNT 打在跑组合的那一帧上，
     * 用户 2026-09-15「对话点击加载还是卡」，见 PORTING §5.14）。
     */
    private val _tailLoaded = MutableStateFlow(false)
    val tailLoaded: StateFlow<Boolean> = _tailLoaded

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private var generationJob: Job? = null

    /** 在途翻译请求（messageId → Job），新请求顶掉旧的（TS _runs 语义）。 */
    private val translationJobs = mutableMapOf<String, Job>()

    init {
        // resolveChatModel（model_display_helper.dart L44-58）—— 「这条会话用哪个
        // 模型」的唯一链：会话覆盖 → 助手默认 → 全局默认（selected_model_v1）。
        // 新建会话此时还没有会话行（draft 不落库），助手/全局就是它的模型来源；
        // 只认全局会把助手配好的模型漏掉、也让"新建对话后模型要重新选"变成常态。
        val assistant = container.currentAssistant()
        val assistantSelection = assistant?.chatModelProvider
            ?.takeIf { it.isNotEmpty() }
            ?.let { provider ->
                assistant.chatModelId?.takeIf { it.isNotEmpty() }?.let { provider to it }
            }
        val fallbackSelection = com.psyche.memo.DefaultModelPrefs.resolveChatModel(
            // 会话覆盖优先级最高，在下面异步读库后再应用（此处不重复读）。
            conversation = null,
            assistant = assistantSelection,
            // 偏好值是 JSON 文本（带引号的 `"Zhipu AI::glm-5.3-flash"`）；必须解包
            // 再 parse，否则引号会混进 provider/model，模型选择器显示"未选中"、
            // 请求也拿不到 key（init 原先就是漏了这一步）。
            global = com.psyche.memo.DefaultModelPrefs.parseStoredModelSelection(
                container.preferenceRepository.readJson("selected_model_v1"),
            ),
        )
        if (fallbackSelection != null) {
            selectedProviderId.value = fallbackSelection.first
            selectedModelId.value = fallbackSelection.second
        }
        // 三层都解析不出模型时**保持为空** —— 与原版一致：不替用户猜 provider，
        // 发送/重生成时按 `no_model` 提示「请先选择模型」（hasModelOrWarn）。
        if (!isTemporary) {
            // Home page shows the conversation's stored title; matches the
            // "New Chat" default of home_page_controller._createNewConversation.
            viewModelScope.launch {
                // 预设对话注入（home_view_model.dart L993-1023）：新会话把助手的
                // presetMessages 作为真实消息落库（先于 refreshTail 读库，时序确定）。
                if (injectPresets) withContext(Dispatchers.IO) { injectPresetsIfNeeded() }
                // Room/SQLite access must stay off the main thread.
                val stored = withContext(Dispatchers.IO) {
                    container.conversationDao.get(conversationId)
                }
                title.value = stored?.title?.trim() ?: ""
                // resolveChatModel（model_select_sheet.dart:283-303）：会话级
                // chat_model_* 优先于助手默认/全局默认——重启后仍保留上次的选择。
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
        val changed = selectedProviderId.value != providerId || selectedModelId.value != modelId
        selectedProviderId.value = providerId
        selectedModelId.value = modelId
        // 阈值基准（上下文窗口）跟着模型走 —— 换了模型就重算占用条。
        if (changed) viewModelScope.launch { refreshContextUsage() }
        if (isTemporary) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.conversationDao.setChatModel(conversationId, providerId, modelId)
            }
        }
    }

    /**
     * 强制重算上下文占用 —— 上下文管理 sheet 打开时调用：模型编辑页改了
     * 「上下文长度」之后，占用卡的分母/阈值要立刻跟上（用户 2026-09-14）。
     */
    fun refreshContextUsageNow() {
        viewModelScope.launch { refreshContextUsage() }
    }

    /**
     * 这条会话页的 VM 是否还在干活（在途生成 / 流式）。**回收判据**：容器的
     * [com.psyche.memo.AppContainerImpl.reapIdleChatViewModels] 绝不回收还在生成的 VM
     * —— 「切走会话继续后台生成」是既有功能（`ChatBackgroundController`），不能被回收打断。
     */
    val isBusy: Boolean
        get() = generationJob?.isActive == true || _streaming.value

    /** 回收判据用：是否还握着消息窗口/版本表/建议这些重状态。 */
    val hasLoadedContent: Boolean
        get() = _messages.value.isNotEmpty() ||
            _suggestions.value.isNotEmpty() ||
            _versionInfo.value.isNotEmpty()

    /** 首屏窗口正在读（[ensureLoaded] 用它避免和 init 的 refreshTail 打架）。 */
    private var tailLoadInFlight = false

    /**
     * 把这条会话的**重状态**释放成空壳，等下次切回来再读（[ensureLoaded]）。
     *
     * 为什么需要：`ChatContent` 用 `viewModel(key = conversationId)` 取 VM，而
     * `ViewModelStore` **不会**因为 key 变化移除旧 VM —— 每点开一条会话就留下一个活的
     * VM：40 条消息 + 每条的 parts、一个 `viewModelScope`、可能还有在途生成。真机上就是
     * 「点击对话多还是会卡 / 从侧边栏到主页会很卡」（用户 2026-09-15）；同一时刻
     * `dumpsys meminfo` 对比：我们 RSS 259MB，原版 Flutter 版 120MB。
     *
     * **刻意不动 `viewModelScope`**：scope 一旦 cancel 就不能再启动协程，切回来这个 VM
     * 就废了；这里只取消我们自己记着的长任务（生成 / 翻译），重状态清空即可让 GC 收走。
     */
    fun releaseForReuse() {
        generationJob?.cancel()
        generationJob = null
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
        _streaming.value = false
        // 这里也取消生成 ⇒ 同样是「等待方消失」的一条路径：挂着审批/问询的表项要还回去。
        releaseInterruptions()
        _messages.value = emptyList()
        _versionInfo.value = emptyMap()
        _suggestions.value = emptyList()
        _hasMoreBefore.value = false
        _tailLoaded.value = false
        versionSelections.clear()
    }

    /** 首次进入本会话、或被 [releaseForReuse] 回收过之后切回来：补读首屏窗口。 */
    fun ensureLoaded() {
        if (isTemporary) {
            _tailLoaded.value = true
            return
        }
        // 已经在读就不要重复发起（首次进入时 init 的 refreshTail 可能还没回来）。
        if (!_tailLoaded.value && !tailLoadInFlight) refreshTail()
    }

    fun refreshTail() {
        if (isTemporary) {
            _sendEnabled.value = true
            _tailLoaded.value = true
            // 临时会话不落库，但占用条一样要算（发给模型的还是同一套消息）。
            viewModelScope.launch { refreshContextUsage() }
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
                    // 翻进来的老消息里若有「重新生成/编辑」过多版本的消息，也要有版本表
                    // 才能出分支选择器 —— 补一次**只针对这一页**的 IN 查询（之前是全表
                    // 一把查完，所以翻页天然覆盖；改成按需查之后必须在这里补）。
                    val pageGroups = rows.asSequence()
                        .filter { it.version > 0 }
                        .map { it.groupId }
                        .toCollection(LinkedHashSet())
                    if (pageGroups.isNotEmpty()) {
                        val extra = withContext(Dispatchers.IO) {
                            container.messageDao.groupVersions(conversationId, pageGroups)
                        }
                        _versionInfo.value = _versionInfo.value + extra
                    }
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
        tailLoadInFlight = true
        try {
            reloadTailBody()
        } finally {
            tailLoadInFlight = false
        }
    }

    /**
     * 首屏窗口里**马上要显示的那几条**先在 Default 线程把 Markdown 解析缓存预热掉。
     *
     * 为什么：`MarkdownText` 的首帧是同步解析（照 RikkaHub `Markdown.kt:240`，避免空白
     * 闪烁），而打开一条**内容多**的历史会话时，可见的几条消息全是冷缓存 —— 那些
     * CommonMark 解析 + 纯文本收集全落在跑组合的那一帧上（用户 2026-09-15「内容多的就会
     * 很卡」）。原版 `markdown_with_highlight.dart` 靠 `ByteLruCache` +
     * `IncrementalMarkdownDocument` 避免这件事；我们这边等价的办法就是**在发布窗口数据
     * 之前先把这几条解析好**（有界：条数 + 字符数双上限）。
     *
     * 注意与 2026-09-15 删掉的那段「预热最近 60 条」的区别：那段是无界的、与首帧抢 CPU；
     * 这里只预热**即将可见的一屏**、并且**等它完成才发布**（延迟一点点换首帧不再解析）。
     */
    private suspend fun prewarmWindowMarkdown(rows: List<ChatMessage>) {
        val tail = rows.takeLast(MARKDOWN_PREWARM_MESSAGES)
        runCatching {
            withContext(Dispatchers.Default) {
                val marks = ArrayList<Pair<String, Boolean>>(tail.size)
                var budget = MARKDOWN_PREWARM_CHARS
                for (row in tail.asReversed()) {
                    val text = row.content
                    if (text.isEmpty()) continue
                    if (text.length > budget) break
                    budget -= text.length
                    // `withCitations=true`：渲染侧两处 `MarkdownText` 都传了非空的
                    // `onCitationTap`（HomeScreen:2521/2824/2906），所以缓存键就是 true。
                    marks += text to true
                }
                if (marks.isNotEmpty()) {
                    com.psyche.memo.ui.markdown.preloadMarkdown(marks)
                }
            }
        }
    }

    private suspend fun reloadTailBody() {
        val loaded = withContext(Dispatchers.IO) {
            container.messageDao.getTail(conversationId)
        }
        _suggestions.value = withContext(Dispatchers.IO) {
            if (isTemporary) emptyList()
            else container.conversationDao.get(conversationId)?.chatSuggestions ?: emptyList()
        }
        // 内容多的会话：可见的那几条先在 Default 线程解析好，别把 CommonMark 落在首帧上
        // （发布 `_messages` 之前完成 —— 见 prewarmWindowMarkdown 的注释）。
        prewarmWindowMarkdown(loaded)
        // 版本表**按需查**：只有窗口里确实出现过多版本的组才需要分支选择器。原版同一
        // 判据（`chat_controller.dart:180-198`：只收 `versionCount > 1 || version > 0
        // || 已在 versionSelections 里` 的组才预载），而且那批组一次查完。之前这里是
        // 无条件 `groupVersions(conversationId)`：把整会话的 `group_id, version` 全表扫
        // 一遍、每次打开会话都跑，会话越长越慢（用户 2026-09-15「历史对话打开卡到爆」）。
        val candidateGroups = loaded.asSequence()
            .filter { it.version > 0 }
            .map { it.groupId }
            .toCollection(LinkedHashSet())
        val versions = withContext(Dispatchers.IO) {
            if (candidateGroups.isEmpty()) emptyMap()
            else container.messageDao.groupVersions(conversationId, candidateGroups)
        }
        _versionInfo.value = versions
        _messages.value = loaded.collapseVersions()
        // 窗口之外还有没有更早的：**窗口装满就说明有**（原版
        // `LoadedTimelinePage.hasMoreBefore = start > 0`，不查库）。之前这里又多做了一次
        // 全表 `SELECT COUNT(*)`，白扫一遍 message_rows —— 和 [loadOlderMessages] 的
        // `rows.size >= HISTORY_PAGE_SIZE` 同一个判据。
        _hasMoreBefore.value = loaded.size >= TAIL_WINDOW
        _sendEnabled.value = true
        _tailLoaded.value = true
        // 占用**不在这里算**：实测这一步要 ~100ms，而占用只在「上下文管理」sheet 里
        // 展示（输入栏上方的常显细条已撤掉）。打开 sheet 时
        // `refreshContextUsageNow()` 会重算，冷启动/切会话没必要为它买单。
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

    /**
     * 原版 `ChatActionResult.noModel()`（chat_actions.dart L1193 send / L1578
     * regenerate / L1816 continue-after-tool-answer）：会话、助手、全局三层都解析
     * 不出模型时**不发请求、也不吞草稿**，只提示 `homePagePleaseSelectModel`
     * （"Please select a model first"）。调用点必须放在各自「改状态」之前
     * （send 清输入框、regenerate 删后续消息之前）。
     */
    private fun hasModelOrWarn(): Boolean {
        if (selectedProviderId.value.isNotEmpty() && selectedModelId.value.isNotEmpty()) return true
        val message = MemoApplication.instance
            ?.getString(com.psyche.memo.ui.R.string.home_page_please_select_model)
            .orEmpty()
        SnackbarManager.show(
            AppNotification(
                message = message,
                type = NotificationType.WARNING,
            ),
        )
        return false
    }

    fun send() {
        val text = input.value.trim()
        val pending = _attachments.value
        if ((text.isEmpty() && pending.isEmpty()) || _streaming.value) return
        if (!hasModelOrWarn()) return
        input.value = ""
        _attachments.value = emptyList()
        clearSuggestions()
        // home_view_model.dart:423 —— 生成开始前给一次触觉（开关在页面侧）。
        onHapticFeedback?.invoke()
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
    /**
     * 预设对话注入（home_view_model.dart L993-1023）：新会话创建时把助手的
     * presetMessages 作为**真实消息**落库（user/assistant 交替、空内容跳过），
     * 与原版 addMessage 一样会持久化会话本体。仅对空会话生效。
     */
    private suspend fun injectPresetsIfNeeded() {
        if (container.messageDao.count(conversationId) > 0) return
        val rawPresets = container.currentAssistant()?.presetMessages.orEmpty()
        val presets = com.psyche.memo.data.model.PresetMessage.decodeList(rawPresets)
        if (presets.isEmpty()) return
        ensureConversationRow()
        var order = 0
        for (preset in presets) {
            val role = if (preset.role == "assistant") "assistant" else "user"
            val content = preset.content.trim()
            if (content.isEmpty()) continue
            container.messageDao.insert(
                com.psyche.memo.data.model.ChatMessage(
                    id = com.psyche.memo.data.model.ChatMessage.newId(),
                    role = role,
                    parts = listOf(com.psyche.memo.data.model.TextPart(content)),
                    timestamp = System.currentTimeMillis(),
                    conversationId = conversationId,
                    groupId = com.psyche.memo.data.model.ChatMessage.newId(),
                    messageOrder = order++,
                ),
            )
        }
    }

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

    /**
     * 释放本会话**正挂着**的审批与问询请求（chat_actions.dart
     * `_cancelStreamingByIdOnce` 1924-1941 的等价物）。
     *
     * 这两张表是容器级的、按会话建键，而生成的等待方是本会话的协程：协程没了（用户
     * 停止、离开聊天页把 ViewModel 清掉、前台服务超时裸 `cancel()`）表项却还留着，
     * 于是那条会话的输入栏被一张永远没人应答的面板顶掉 —— 同会话后续所有工具都不
     * 执行，换新对话立刻正常（用户 2026-09-25「工作区工具传了参数，工具就全部问题」）。
     * 所以**每一条终止路径**都要调它，不只是「停止」按钮。
     */
    private fun releaseInterruptions() {
        container.toolApprovalService.cancelForConversation(conversationId)
        container.askUserInteractionService.cancelForConversation(conversationId)
    }

    override fun onCleared() {
        // ViewModel 被清（离开聊天页 / Activity 重建）会连带取消 viewModelScope，
        // 挂在审批/问询上的协程就此消失 —— 表项必须还回去（用户 2026-09-25 的
        // 「工作区工具传了参数，工具就全部问题，换新对话才好」）。
        releaseInterruptions()
        super.onCleared()
    }

    fun stop() {
        generationJob?.cancel()
        container.cancellations.cancel(conversationId)
        // chat_actions.dart _cancelStreamingByIdOnce 1924-1941 — 见 [releaseInterruptions]。
        releaseInterruptions()
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
        viewModelScope.launch { refreshContextUsage() }
    }

    /** compactWindow 的返回值：成功时 [message] 是新建的检查点，否则 [errorKey]。 */
    private data class CompactionResult(val message: UiMessage?, val errorKey: String?)

    /**
     * 上下文占用（输入栏上方那条细进度条的数据源，用户 2026-09-13 定：分母按自动压缩
     * 阈值算，100% = 该压缩了）。
     */
    data class ContextUsage(
        val usedTokens: Int,
        val thresholdTokens: Int,
        val windowTokens: Int,
        val auto: Boolean,
    )

    private val _contextUsage = kotlinx.coroutines.flow.MutableStateFlow<ContextUsage?>(null)
    val contextUsage: kotlinx.coroutines.flow.StateFlow<ContextUsage?> = _contextUsage

    /** 压缩进行中 → 消息流末尾那条「上下文压缩中」分隔线（扫光）。 */
    private val _compacting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val compacting: kotlinx.coroutines.flow.StateFlow<Boolean> = _compacting

    /**
     * 重算上下文占用 —— 与发送链路的估算同源：`estimate(系统提示词 + 窗口消息 + 工具
     * 定义)`，分母是自动压缩阈值（窗口 − max(输出预算, buffer)）。消息走「消息自身正文」
     * （不重算 OCR/文档/记忆注入，那些要真的发请求才有意义），因此是近似值。
     */
    private suspend fun refreshContextUsage() {
        runCatching {
            val startOrder = contextStartOrder()
            val settings = ContextCompactionPrefs.read(
                container,
                selectedProviderId.value,
                selectedModelId.value,
            )
            val assistant = container.currentAssistant()
            val base = _messages.value
                .filter { !it.isStreaming }
                .filter { startOrder == null || it.messageOrder >= startOrder }
            val window = compactionWindow(base).mapNotNull { msg ->
                val checkpoint = msg.checkpointPart()
                if (checkpoint != null) {
                    "user" to com.psyche.memo.common.SessionCompaction
                        .checkpointText(checkpoint.summary, checkpoint.recent)
                } else {
                    compactionEntry(msg)
                        ?.let { it.role to com.psyche.memo.common.SessionCompaction.serialize(it) }
                        ?.takeIf { (_, text) -> text.isNotEmpty() }
                }
            }
            val tools = offeredTools()
            val systemParts = buildSystemPromptParts(
                assistant,
                offeredToolNames = tools.map { it.name },
            )
            val used = com.psyche.memo.common.SessionCompaction.estimateRequest(
                system = com.psyche.memo.provider.prompt.assembleSystemPrompt(systemParts),
                messages = window,
                toolsJson = toolsJsonForEstimate(tools),
            )
            _contextUsage.value = ContextUsage(
                usedTokens = used,
                thresholdTokens = com.psyche.memo.common.SessionCompaction.thresholdTokens(
                    settings.contextWindow,
                    assistant?.maxTokens ?: 0,
                    settings.buffer,
                ),
                windowTokens = settings.contextWindow,
                auto = settings.auto,
            )
        }.onFailure { e ->
            com.psyche.memo.common.logging.FlutterLogger.log(
                "[CompressContext] usage estimate failed: $e",
                tag = "HomePage",
            )
        }
    }

    /**
     * 手动「立即压缩」（opencode `SessionCompaction.create` + `process`，不看阈值）：
     * 把窗口里较早的部分归纳成锚定摘要，落成一条检查点消息。
     * [onResult] 回传错误 key（成功为 null）。
     */
    fun compactContextNow(onResult: (errorKey: String?) -> Unit) {
        if (_streaming.value) {
            onResult("busy")
            return
        }
        viewModelScope.launch {
            _compacting.value = true
            try {
                val window = compactionWindow(_messages.value)
                if (window.isEmpty()) {
                    onResult("no_messages")
                    return@launch
                }
                val settings = ContextCompactionPrefs.read(
                    container,
                    selectedProviderId.value,
                    selectedModelId.value,
                )
                val result = compactWindow(
                    window = window,
                    anchorOrder = Int.MAX_VALUE,
                    settings = settings,
                    entryOf = { message, _ -> compactionEntry(message) },
                    beforeSkeletonId = null,
                )
                onResult(result.errorKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // home_page.dart L1843-1848 —— 压缩失败也写应用日志（页面上仍有 toast）。
                com.psyche.memo.common.logging.FlutterLogger.log(
                    "[CompressContext] failed: $e\n${e.stackTraceToString()}",
                    tag = "HomePage",
                )
                onResult("failed")
            } finally {
                _compacting.value = false
                refreshContextUsage()
            }
        }
    }

    /**
     * opencode `compactAfterOverflow` —— 把 [window] 里 `messageOrder < anchorOrder`
     * 的部分折叠成一条压缩检查点（摘要 + 原样保留的最近上下文）并落库。
     *
     * [entryOf] 决定每条消息怎么序列化（发送链路上是「带注入内容的正文」，手动压缩
     * 是消息自身正文）；[beforeSkeletonId] 非空时把新检查点插到 UI 列表里那条消息
     * 之前（流式骨架）。
     */
    private suspend fun compactWindow(
        window: List<UiMessage>,
        anchorOrder: Int,
        settings: com.psyche.memo.common.SessionCompaction.Settings,
        entryOf: (UiMessage, Int) -> com.psyche.memo.common.SessionCompaction.Entry?,
        beforeSkeletonId: String?,
    ): CompactionResult {
        val previousPart = window.lastOrNull { it.checkpointPart() != null }?.checkpointPart()
        val summarizable = window.filter { it.checkpointPart() == null && it.messageOrder < anchorOrder }
        if (summarizable.isEmpty() && previousPart == null) return CompactionResult(null, "no_messages")
        val serialized = summarizable.mapIndexedNotNull { index, message ->
            entryOf(message, index)
                ?.let(com.psyche.memo.common.SessionCompaction::serialize)
                ?.takeIf { it.isNotEmpty() }
        }
        val selection = com.psyche.memo.common.SessionCompaction
            .select(serialized, settings.keepTokens)
            ?: return CompactionResult(null, "no_messages")
        if (selection.head.isEmpty() && previousPart == null) return CompactionResult(null, "no_messages")

        val assistant = container.currentAssistant()
        val model = com.psyche.memo.common.CompressModel.resolve(
            readModelSelection("compress_model_v1"),
            readModelSelection("summary_model_v1"),
            readModelSelection("title_model_v1"),
            assistant?.chatModelProvider?.let { p -> assistant.chatModelId?.let { m -> p to m } },
            selectedProviderId.value.takeIf { it.isNotEmpty() }
                ?.let { p -> selectedModelId.value.takeIf { it.isNotEmpty() }?.let { m -> p to m } },
        ) ?: return CompactionResult(null, "no_model")

        val locale = java.util.Locale.getDefault().toLanguageTag()
        val contexts = buildList {
            previousPart?.recent?.takeIf { it.isNotEmpty() }?.let(::add)
            selection.head.takeIf { it.isNotEmpty() }?.let(::add)
        }
        val custom = readPrefString("compress_prompt_v1")
        val prompt = if (custom == null || custom == com.psyche.memo.common.SessionCompaction.SUMMARY_TEMPLATE) {
            // 默认走 opencode 的锚定摘要模板（含 previous-summary 更新指令）。
            com.psyche.memo.common.SessionCompaction.buildPrompt(previousPart?.summary, contexts, locale)
        } else {
            custom.replace("{content}", contexts.joinToString("\n\n")).replace("{locale}", locale)
        }
        val summaryOutput = minOf(
            assistant?.maxTokens ?: com.psyche.memo.common.SessionCompaction.SUMMARY_OUTPUT_TOKENS,
            com.psyche.memo.common.SessionCompaction.SUMMARY_OUTPUT_TOKENS,
        )
        // opencode：摘要提示词本身就超出窗口（留出摘要输出）时放弃压缩。
        if (com.psyche.memo.common.SessionCompaction.estimate(prompt) > settings.contextWindow - summaryOutput) {
            com.psyche.memo.common.logging.FlutterLogger.log(
                "[CompressContext] prompt does not fit the context window; skipped",
                tag = "HomePage",
            )
            return CompactionResult(null, "failed")
        }
        val summary = withContext(Dispatchers.IO) { runCompactionSummary(model, prompt, summaryOutput) }
        if (summary.isBlank()) return CompactionResult(null, "empty_summary")

        val boundary = summarizable.lastOrNull()?.messageOrder ?: previousPart?.boundaryOrder ?: -1
        val parts: List<MessagePart> = listOf(
            TextPart(summary),
            com.psyche.memo.data.model.CompactionPart(summary, selection.recent, boundary),
        )
        val order = if (isTemporary) {
            (_messages.value.maxOfOrNull { it.messageOrder } ?: -1) + 1
        } else {
            withContext(Dispatchers.IO) { container.messageDao.nextOrder(conversationId) }
        }
        val message = UiMessage(
            id = ChatMessage.newId(),
            role = "user",
            parts = parts,
            isStreaming = false,
            timestamp = System.currentTimeMillis(),
            groupId = ChatMessage.newId(),
            messageOrder = order,
        )
        if (!isTemporary) {
            withContext(Dispatchers.IO) {
                container.messageDao.insert(message.toChatMessage())
            }
        }
        insertUiMessage(message, beforeSkeletonId)
        com.psyche.memo.common.logging.FlutterLogger.log(
            "[CompressContext] compacted: summarized=${selection.head.length} chars, " +
                "kept=${selection.recent.length} chars, boundary=$boundary",
            tag = "HomePage",
        )
        return CompactionResult(message, null)
    }

    /** 压缩用的总结请求（compress → summary → title → 助手 → 当前模型链）。 */
    private suspend fun runCompactionSummary(
        model: Pair<String, String>,
        prompt: String,
        maxTokens: Int,
    ): String {
        val request = LlmRequest(
            providerId = model.first,
            modelId = model.second,
            messages = listOf(LlmMessage(role = "user", content = prompt)),
            apiKey = container.apiKeyFor(model.first) ?: "",
            baseUrl = container.baseUrlFor(model.first),
            chatPath = container.providerConfig(model.first)?.chatPath,
            useResponseApi = container.usesResponseApi(model.first),
            thinkingBudget = if (readBoolPref("compress_generation_thinking_enabled_v1")) -1 else 0,
            maxTokens = maxTokens,
        )
        return container.clientFor(model.first).complete(request).parts.joinToString("").trim()
    }

    /** 把新落库的消息并入 UI 列表（[beforeId] 非空时插到它之前）。 */
    private fun insertUiMessage(message: UiMessage, beforeId: String?) {
        val list = _messages.value
        if (beforeId == null) {
            _messages.value = list + message
            return
        }
        val index = list.indexOfFirst { it.id == beforeId }
        _messages.value = if (index < 0) {
            list + message
        } else {
            list.toMutableList().apply { add(index, message) }
        }
    }

    /** 偏好值 → 纯文本（值可能是带引号的 JSON 串，也可能是裸串）。 */
    private fun readPrefString(key: String): String? =
        com.psyche.memo.DefaultModelPrefs.decodeStoredString(
            container.preferenceRepository.readJson(key),
        )

    private fun readBoolPref(key: String): Boolean {
        val raw = container.preferenceRepository.readJson(key) ?: return false
        // 两种存储形态并存：裸 boolean JSON（旧键）与 "1"/"0"（行为启动页写入，
        // 与 LogBootstrap 约定一致）——只认一种就会读成恒 false。
        when (raw.trim()) {
            "1", "true" -> return true
            "0", "false" -> return false
        }
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.booleanOrNull
        }.getOrNull() ?: false
    }

    private fun readModelSelection(key: String): Pair<String, String>? =
        com.psyche.memo.DefaultModelPrefs.parseModelSelection(readPrefString(key))

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
     * _confirmRegeneration): the confirm dialog lives in the UI layer.
     *
     * `display_regenerate_delete_trailing_messages_v1`（默认**关**，
     * chat_actions.dart:1523 `truncateFuture`）：
     * - 开 → 锚点分组之后出现的分组整组删掉（含它们的全部版本），再重新生成；
     * - 关 → 不删任何行；新回复作为「锚点之后第一条助手消息」所属分组的**新版本**
     *   追加（chat_database_repository.dart:4532-4622），旧回复仍可在版本选择器里翻回。
     */
    fun regenerate(userMessageId: String) {
        if (_streaming.value) return
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == userMessageId }
        if (idx < 0) return
        if (!hasModelOrWarn()) return
        val anchor = msgs[idx]
        clearSuggestions()
        // home_view_model.dart:495 —— 重新生成同样属于「生成开始」，一样给触觉。
        onHapticFeedback?.invoke()
        val deleteTrailing = container.preferenceRepository
            .readJson("display_regenerate_delete_trailing_messages_v1") == "1"
        val anchorGroupId = anchor.groupId.ifEmpty { anchor.id }
        // 关掉开关时的版本追加目标（原版 targetGroupId = 下一条助手行的 group）。
        val targetGroupId = if (deleteTrailing) {
            null
        } else {
            msgs.drop(idx + 1).firstOrNull { it.role == "assistant" }?.groupId
        }
        viewModelScope.launch {
            val nextVersion = if (targetGroupId != null && !isTemporary) {
                withContext(Dispatchers.IO) {
                    container.messageDao.maxVersionForGroup(conversationId, targetGroupId) + 1
                }
            } else {
                0
            }
            if (!isTemporary) {
                withContext(Dispatchers.IO) {
                    if (deleteTrailing) {
                        container.messageDao.deleteTrailingGroups(conversationId, anchorGroupId)
                    }
                }
            }
            if (deleteTrailing) {
                _messages.value = msgs.take(idx + 1)
            }
            if (targetGroupId != null) versionSelections[targetGroupId] = nextVersion
            startGeneration(
                anchor.toChatMessage(),
                assistantGroupId = targetGroupId,
                assistantVersion = nextVersion,
                replaceVisibleGroupId = targetGroupId,
            )
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
        // chat_edit_assistant_keep_thinking_tool_cards_v1（默认关）：
        // home_page_controller.dart:1497-1518 —— 关掉时新版本的 parts 里不留思考段与
        // 工具调用，思考元数据也一起清空；打开时**整份 parts 与思考元数据都继承**。
        val keepThinkingAndToolCards = container.preferenceRepository
            .readJson("chat_edit_assistant_keep_thinking_tool_cards_v1") == "1"
        val isAssistant = target.role == "assistant"
        fun editedParts(original: List<MessagePart>): List<MessagePart> {
            val base = if (isAssistant && !keepThinkingAndToolCards) {
                ChatMessage.partsWithoutThinkingAndToolCards(original)
            } else {
                original
            }
            return ChatMessage.partsWithReplacedText(base, newContent)
        }
        if (isTemporary) {
            val edited = target.copy(
                parts = editedParts(target.parts),
                reasoningSegmentsJson = if (isAssistant && !keepThinkingAndToolCards) {
                    null
                } else {
                    target.reasoningSegmentsJson
                },
            )
            _messages.value = msgs.take(idx) + edited
            if (shouldSend) {
                if (hasModelOrWarn()) startGeneration(edited.toChatMessage())
            }
            return true
        }
        val newVersionRow = withContext(Dispatchers.IO) {
            val orig = container.messageDao.get(messageId) ?: return@withContext null
            val groupId = orig.groupId.ifEmpty { orig.id }
            val version = container.messageDao.maxVersionForGroup(conversationId, groupId) + 1
            val dropReasoning = orig.role == "assistant" && !keepThinkingAndToolCards
            val row = ChatMessage(
                id = ChatMessage.newId(),
                role = orig.role,
                parts = editedParts(orig.parts),
                timestamp = System.currentTimeMillis(),
                modelId = orig.modelId,
                providerId = orig.providerId,
                conversationId = conversationId,
                groupId = groupId,
                version = version,
                messageOrder = container.messageDao.nextOrder(conversationId),
                // home_page_controller.dart:1517-1518 + repository `preserveReasoning`
                // （chat_database_repository.dart:4834）：保留开关打开时思考元数据
                // 随 parts 一起继承。
                reasoningSegmentsJson = if (dropReasoning) null else orig.reasoningSegmentsJson,
                reasoningStartAt = if (dropReasoning) null else orig.reasoningStartAt,
                reasoningFinishedAt = if (dropReasoning) null else orig.reasoningFinishedAt,
            )
            container.messageDao.insert(row)
            row
        } ?: return false
        versionSelections[newVersionRow.groupId] = newVersionRow.version
        reloadTail()
        // Save & Send（home_page_controller.dart:1533-1536）：助手消息保存后同样
        // 立刻重新生成——`assistantAsNewReply`，锚点就是刚写入的新版本。
        if (shouldSend) {
            // The collapsed list now shows the edited version in the group's
            // original position; generate against it.
            val visible = _messages.value.firstOrNull {
                it.groupId == newVersionRow.groupId && it.version == newVersionRow.version
            }
            if (visible != null && hasModelOrWarn()) startGeneration(visible.toChatMessage())
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

    /** [backgroundGenerationId] 那一刻的后台模式档位（release 与完成通知都按它判）。 */
    private var backgroundGenerationMode:
        com.psyche.memo.service.ChatBackgroundController.AndroidBackgroundChatMode? = null

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
        // android_background_chat_mode_v1 是 PREFERENCE 键（写进
        // preference_rows），必须走 readJson——readLocal 读 SharedPreferences
        // 永远拿不到，后台模式因此恒为 OFF（读写路由镜像 bug）。
        com.psyche.memo.service.ChatBackgroundController.modeOf(container.preferenceRepository::readJson)

    private fun beginBackgroundGeneration() {
        val id = java.util.UUID.randomUUID().toString()
        backgroundGenerationId = id
        // 结束时要按**开始时**那一档来 release/通知：中途去设置里改档位，
        // 不能把已经 acquire 的前台服务漏掉（漏掉就只能等系统超时停）。
        backgroundGenerationMode = backgroundMode()
        backgroundSenderName = resolveNotificationSenderName()
        com.psyche.memo.service.ChatBackgroundController.onGenerationStart(
            conversationId = conversationId,
            mode = backgroundGenerationMode
                ?: com.psyche.memo.service.ChatBackgroundController.AndroidBackgroundChatMode.OFF,
            generationId = id,
            // 必须走 stop() 而不是裸 generationJob?.cancel()：超时这一路也要释放
            // 本会话挂着审批/问询（见 [releaseInterruptions]），否则前台服务一超时
            // （dataSync 类型系统只给几分钟），那条会话就被孤儿面板锁死。
            stopGeneration = { stop() },
        )
    }

    private fun endBackgroundGeneration() {
        val id = backgroundGenerationId ?: return
        backgroundGenerationId = null
        val mode = backgroundGenerationMode
            ?: com.psyche.memo.service.ChatBackgroundController.AndroidBackgroundChatMode.OFF
        backgroundGenerationMode = null
        com.psyche.memo.service.ChatBackgroundController.onGenerationEnd(
            conversationId = conversationId,
            mode = mode,
            generationId = id,
            isCurrentConversation = true,
        )
        // 生成结束：取消 Live Update 进度通知（ChatGenerationEnded 等价）。
        com.psyche.memo.service.ChatNotificationManager.onGenerationEnded(conversationId)
    }

    private fun startGeneration(
        userMessage: ChatMessage,
        /**
         * 重新生成且「删除后续消息」关闭时，新回复并入这个已有的助手分组
         * （`chat_database_repository.dart:4532-4622` 的 targetGroupId 语义）。
         */
        assistantGroupId: String? = null,
        assistantVersion: Int = 0,
        /** 非空时把该分组当前可见的那条换成流式骨架（原地重生成，不追加到末尾）。 */
        replaceVisibleGroupId: String? = null,
    ) {
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
            val skeleton = UiMessage(
                id = assistantId,
                role = "assistant",
                parts = emptyList(),
                isStreaming = true,
                groupId = assistantGroupId ?: assistantId,
                version = assistantVersion,
            )
            val replaceIndex = replaceVisibleGroupId?.let { gid ->
                _messages.value.indexOfFirst { it.groupId == gid }
            } ?: -1
            if (replaceIndex >= 0) {
                _messages.value = _messages.value.toMutableList().apply { this[replaceIndex] = skeleton }
            } else {
                append(skeleton)
            }
            // Parts accumulated across rounds; each round folds into its own
            // handler, then its parts/segments merge here (the original's single
            // UI handler folds every round into one parts list).
            val allParts = mutableListOf<MessagePart>()
            val allSegments = mutableListOf<ReasoningSegment>()
            // 新一轮生成：重置 segment 权威展开态（见 encodeSegments）。
            resetReasoningExpandedState(emptyList())
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
                    groupId = assistantGroupId,
                    version = assistantVersion,
                )
            }
            try {
                // Build request from current UI messages (exclude skeleton).
                // message_builder_service.dart L215-221 —— truncateIndex 之后
                // 的消息才进入请求（"清空上下文"）。
                val startOrder = contextStartOrder()
                var rawMessages = _messages.value.dropLast(1)
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
                var lastUserMessageId = rawMessages.lastOrNull { it.role == "user" }?.id
                val assistant = container.currentAssistant()
                // 助手正则（user scope, send 目标）与消息模板/时间后缀的来源。
                val sendRegexRules = com.psyche.memo.data.model.AssistantRegexApplier.decodeRules(
                    container.currentAssistant()?.regexRules.orEmpty(),
                )
                val messageTemplate = assistant?.messageTemplate?.takeIf { it.isNotBlank() } ?: "{{ message }}"

                /**
                 * message_builder_service.dart L1106-1116 —— 用户消息过消息模板 +
                 * 可选时间后缀（模板变量 {{message}}/{{role}}/{{time}}/{{date}}），
                 * 再按助手正则（user scope + send 目标）改写；[carriesMemory] 的那条
                 * 前置记忆快照前缀。压缩（阈值估算 + 摘要序列化）与请求组装共用这一份。
                 */
                fun assembledBody(msg: UiMessage, carriesMemory: Boolean): String {
                    val rawText = (ocrBlocks[msg.id] ?: "") + (fileBlocks[msg.id] ?: "") +
                        msg.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                    val body = if (msg.role == "user") {
                        val templated = com.psyche.memo.llm.prompt.PromptTransformer
                            .applyMessageTemplate(messageTemplate, "user", rawText)
                        if (assistant?.appendCurrentTimeToUserMessage == true) {
                            templated + "\n\n" +
                                com.psyche.memo.ui.MemoryPrompts.formatCurrentTimeTag(msg.timestamp)
                        } else {
                            templated
                        }
                    } else {
                        rawText
                    }
                    // regex user scope + send 目标（message_generation_service L148-154）。
                    val content = if (msg.role == "user") {
                        com.psyche.memo.data.model.AssistantRegexApplier.applyAll(
                            body,
                            sendRegexRules,
                            com.psyche.memo.data.model.AssistantRegexScope.USER,
                            com.psyche.memo.data.model.AssistantRegexApplier.Target.SEND,
                        )
                    } else {
                        body
                    }
                    return (if (carriesMemory) memoryPrefix else "") + content
                }

                val tools = offeredTools()
                val systemParts = buildSystemPromptParts(
                    assistant,
                    offeredToolNames = tools.map { it.name },
                )

                // ---- 上下文压缩（opencode packages/core/src/session/compaction.ts）----
                // 阈值：estimate(system + messages + tools) > 窗口 − max(输出预算, buffer)
                // 时先把较早的上下文折叠成一条检查点（锚定摘要 + 原样保留的最近上下文），
                // 然后按 opencode 的窗口语义组装请求：检查点 + 它 boundaryOrder 之后的消息，
                // 检查点之前的消息不再发送（opencode history.load 的 latestCompaction）。
                val compactionSettings = ContextCompactionPrefs.read(
                    container,
                    selectedProviderId.value,
                    selectedModelId.value,
                )
                if (compactionSettings.auto) {
                    val estimates = com.psyche.memo.common.SessionCompaction.estimateRequest(
                        system = com.psyche.memo.provider.prompt.assembleSystemPrompt(systemParts),
                        messages = compactionWindow(rawMessages).mapNotNull { msg ->
                            if (msg.checkpointPart() != null) return@mapNotNull null
                            val body = assembledBody(msg, msg.id == lastUserMessageId && memoryPrefix.isNotEmpty())
                            if (body.isEmpty()) null else msg.role to body
                        },
                        toolsJson = toolsJsonForEstimate(tools),
                    )
                    if (
                        com.psyche.memo.common.SessionCompaction.shouldCompact(
                            estimates,
                            compactionSettings.contextWindow,
                            assistant?.maxTokens ?: 0,
                            compactionSettings.buffer,
                        )
                    ) {
                        _compacting.value = true
                        val result = try {
                            compactWindow(
                                window = compactionWindow(rawMessages),
                                anchorOrder = userMessage.messageOrder,
                                settings = compactionSettings,
                                entryOf = { msg, _ ->
                                    val entry = compactionEntry(msg)
                                    if (entry == null || msg.role != "user") {
                                        entry
                                    } else {
                                        entry.copy(
                                            parts = listOf(
                                                com.psyche.memo.common.SessionCompaction.Part.Text(
                                                    assembledBody(msg, msg.id == lastUserMessageId && memoryPrefix.isNotEmpty()),
                                                ),
                                            ),
                                        )
                                    }
                                },
                                beforeSkeletonId = assistantId,
                            )
                        } finally {
                            _compacting.value = false
                        }
                        val checkpoint = result.message
                        if (checkpoint != null) {
                            val boundary = checkpoint.checkpointPart()?.boundaryOrder ?: -1
                            // opencode：压缩后历史 = 检查点 + boundary 之后的消息。
                            rawMessages = listOf(checkpoint) + rawMessages.filter { it.messageOrder > boundary }
                            lastUserMessageId = rawMessages.lastOrNull { it.role == "user" }?.id
                        } else {
                            com.psyche.memo.common.logging.FlutterLogger.log(
                                "[CompressContext] auto compact skipped: ${result.errorKey}",
                                tag = "HomePage",
                            )
                        }
                    }
                }
                // 上下文日志：组装期给承载注入内容的轮次打来源标签
                // （context_log_models.dart 的 `_kelivo_ctx_segments`），请求前由
                // ContextLogAssembler 切片写盘。历史轮次不带标签，读取时按 role 推断。
                val tagContextLog = com.psyche.memo.logging.ContextLogger.isEnabled
                val history = rawMessages
                    .mapIndexedNotNull { index, msg ->
                        val carriesMemory = msg.id == lastUserMessageId && memoryPrefix.isNotEmpty()
                        // 压缩检查点整条替换成 opencode 的 <conversation-checkpoint> user 轮次
                        // （to-llm-message.ts 的 compaction 分支）。
                        val checkpoint = msg.checkpointPart()
                        val finalBody = if (checkpoint != null) {
                            com.psyche.memo.common.SessionCompaction
                                .checkpointText(checkpoint.summary, checkpoint.recent)
                        } else {
                            assembledBody(msg, carriesMemory)
                        }
                        // Images ride along as part payloads; only user turns may
                        // carry them (assistant media is stashed by the original
                        // OpenAI builder instead of replayed).
                        val attachments = if (msg.role == "user" && checkpoint == null) {
                            msg.parts.filterIsInstance<com.psyche.memo.data.model.ImagePart>()
                                .map { it.encodePayload() }
                        } else {
                            emptyList()
                        }
                        if (finalBody.isEmpty() && attachments.isEmpty()) null
                        else LlmMessage(
                            role = msg.role,
                            content = finalBody.ifEmpty { null },
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
                                        finalBody.length - memoryPrefix.length,
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
                // （systemParts 在压缩阈值估算前就已组装，见上。）
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
                // 助手「限制上下文条数」（message_builder_service.applyContextLimit
                // L2139-2166）：世界书注入之后、真正发请求之前裁剪，保留系统消息 +
                // 最近 N 条，并丢掉裁点留下的悬空 tool 消息。
                com.psyche.memo.llm.prompt.applyContextLimit(
                    messages = history,
                    enabled = assistant?.limitContextMessages == true,
                    size = assistant?.contextMessageSize ?: 0,
                )
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
                // 提供给模型的工具集（offeredTools）：本地工具里 Android 有执行器的
                // 那一组（时间/剪贴板/TTS/计算/屏幕时间/日历读写/定位 + 自研 render_visual、
                // render_mermaid）、ask_user 走交互服务、search_web 走已移植搜索引擎，
                // 再加记忆 / 技能 / 工作区 / 生成图片·视频 / 已连接 MCP 服务器的工具。
                // 与上游的差集只有平台性的：iOS-only 本地工具（天气/健康/提醒）按
                // isAvailableOnThisPlatform 剔除（定位是本工程自写的安卓执行器，已开闸），
                // STDIO MCP 是桌面专属。名单见 BuiltInToolCatalog.offeredLocalToolNames。
                // （tools 在压缩阈值估算前就已组装，见上。）
                runGenerationLoop(
                    assistantId = assistantId,
                    history = history,
                    tools = tools,
                    allParts = allParts,
                    allSegments = allSegments,
                    startedAtMs = generationStartMs,
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
                    // 「朗读取哪部分文本」只作用在助手消息上（home_page_controller.dart:1818）。
                    // `val text = allParts...` 必须留在一行里 —— ToolTranscriptContentTest
                    // 按行白名单放行这一处累计全文（工具 transcript 不许取 allParts，TTS 要整条）。
                    val text = allParts.filterIsInstance<TextPart>().joinToString("") { it.text }
                        .let { round ->
                            com.psyche.memo.ui.chat.assistantReplyForTts(
                                modeValue = container.ttsServicesStore.textSelectionMode(),
                                content = round,
                            )
                        }
                    if (text.isNotBlank()) {
                        // 走 `speak(appContext, ...)` 拿 Context，让 TtsPlayer 的
                        // `controller(context)` 懒初始化兜底能命中——之前 `TtsPlayer.init`
                        // 已挪到 IO，冷启+长时间空载→首次自动播放这条路径不再 no-op。
                        com.psyche.memo.ui.chat.TtsPlayer.speak(
                            context = container.appContext,
                            text = text,
                            ownerId = assistantId,
                        )
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
                val finalParts = markFailed(
                    assistantId,
                    com.psyche.memo.ui.chat.generationErrorDisplayText(container.appContext, e),
                    allParts,
                    segmentsJson,
                )
                persistAssistant(
                    assistantId,
                    finalParts,
                    segments = allSegments,
                    segmentsJson = segmentsJson,
                )
            } finally {
                // 终止路径（正常结束 / 用户停止 / 真失败）都要把倒计时清掉，
                // 否则「N 秒后重试」会停在一个已经结束的消息上。
                updateAssistantRetry(assistantId, null)
                _streaming.value = false
                // 协程要结束了：没人等的审批/问询必须还回去，否则这条会话被面板锁死。
                releaseInterruptions()
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
                // 顶栏下方那条占用进度条：一轮结束后重算（也覆盖本轮刚发生的自动压缩）。
                refreshContextUsage()
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
            .filter { it.checkpointPart() == null }
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
                    useResponseApi = container.usesResponseApi(providerId),
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
        /** 本轮生成的起始时刻 —— 收尾写 UI 的 durationMs 与落库同口径。 */
        startedAtMs: Long,
        updateStreaming: (List<MessagePart>, String?) -> Unit,
        onPersist: (List<MessagePart>, UsageStats?, String?) -> Unit,
    ) {
        var finishUsage: UsageStats? = null
        // 采样参数/自定义请求层的助手侧来源（chat_actions.dart 用 ctx.assistant）。
        val assistant = container.currentAssistant()
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
        // 连续重复调用检测（照 deepseek-harness 的 guard/repeat-tool-reminder）：
        // **每次生成一个新实例** ⇒ 用户真发了新消息就归零，跨发言的重复不算死循环。
        val repeatGuard = com.psyche.memo.provider.tool.RepeatCallGuard()
        val providerId = selectedProviderId.value
        val modelId = selectedModelId.value
        // chat_api_helpers.dart:137-153 effectiveModelInfo —— 名称推断**叠加**
        // 模型 override；只看名称会让第三方模型（推断不出任何能力）永远不通思考，
        // 也让「编辑页把 abilities 打开」形同虚设。
        val effectiveModel = container.providerConfig(providerId)?.let { cfg ->
            com.psyche.memo.ModelOverrideResolver.forModel(cfg, modelId)
        }
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
                // 工具结果里的图片只发给支持图片输入的模型（上游
                // supportInputModalities 等价物）。
                imageInput = effectiveModel?.visionInput == true,
                thinkingBudget = thinkingBudget,
                reasoning = effectiveModel?.reasoning
                    ?: com.psyche.memo.ModelRegistry.infer(modelId).reasoning,
                // 采样参数（chat_actions.dart:2113-2115 assistant.temperature/topP/maxTokens）。
                temperature = assistant?.temperature,
                topP = assistant?.topP,
                maxTokens = assistant?.maxTokens,
                // 自定义请求三层（custom_request_merger.dart）：助手 + provider +
                // 模型 override 的 headers/body。
                extraHeaders = run {
                    fun headerRows(rows: List<Map<String, String>>): Map<String, String> =
                        rows.mapNotNull { row ->
                            row["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it to row["value"].orEmpty() }
                        }.toMap()
                    fun modelHeaderRows(ov: JsonObject?): List<Map<String, String>> =
                        (ov?.get("headers") as? JsonArray)?.mapNotNull { entry ->
                            (entry as? JsonObject)?.let { row ->
                                val name = (row["name"] as? JsonPrimitive)?.contentOrNull
                                val value = (row["value"] as? JsonPrimitive)?.contentOrNull
                                if (name == null) null else buildMap<String, String> {
                                    put("name", name)
                                    put("value", value.orEmpty())
                                }
                            }
                        }.orEmpty()
                    com.psyche.memo.llm.client.CustomRequestMerger.mergeHeaders(
                        assistant = assistant?.customHeaders?.let(::headerRows),
                        provider = container.providerConfig(providerId)?.customHeaders?.let(::headerRows)
                            ?: emptyMap(),
                        model = modelHeaderRows(
                            container.providerConfig(providerId)?.modelOverrides?.get(modelId) as? JsonObject,
                        ).fold(emptyMap()) { acc, row -> acc + row },
                    )
                },
                extraBodyJson = run {
                    fun bodyRows(rows: List<Map<String, String>>): Map<String, String> =
                        rows.mapNotNull { row ->
                            row["key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { it to row["value"].orEmpty() }
                        }.toMap()
                    fun modelBodyRows(ov: JsonObject?): List<Map<String, String>> =
                        (ov?.get("body") as? JsonArray)?.mapNotNull { entry ->
                            (entry as? JsonObject)?.let { row ->
                                val key = (row["key"] as? JsonPrimitive)?.contentOrNull
                                val value = (row["value"] as? JsonPrimitive)?.contentOrNull
                                if (key == null) null else buildMap<String, String> {
                                    put("key", key)
                                    put("value", value.orEmpty())
                                }
                            }
                        }.orEmpty()
                    com.psyche.memo.llm.client.CustomRequestMerger.mergeBody(
                        assistant = assistant?.customBody?.let(::bodyRows),
                        providerRows = container.providerConfig(providerId)?.customBody
                            ?: emptyList(),
                        model = modelBodyRows(
                            container.providerConfig(providerId)?.modelOverrides?.get(modelId) as? JsonObject,
                        ).fold(emptyMap()) { acc, row -> acc + row },
                    ).toString()
                },
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
            // 助手「流式输出」关闭 → 走一次性请求（`chat_actions.dart:2109` 的非流式
            // 分支）：同一个解码器产出的 chunk 序列，下面这段循环一行都不用改。
            val chunks = if (assistant?.streamOutput == false) {
                client.completeAsChunks(request)
            } else {
                client.streamChat(request)
            }
            chunks.collect { chunk ->
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
                    // 自动重试等待（Dart chat_api_service.dart RetryPending）：
                    // 只驱动气泡内「N 秒后重试 (2/3)」倒计时，不进 parts、不落库、
                    // 不 markFailed —— 之前裸 Error 文案被当成失败写进消息就是根因。
                    is StreamChunk.RetryPending -> {
                        updateAssistantRetry(
                            assistantId,
                            UiMessage.RetryStatus(
                                attempt = chunk.attempt,
                                maxRetries = chunk.maxRetries,
                                retryAtMs = chunk.retryAtMs,
                            ),
                        )
                    }
                    // 退避结束、下一次尝试开始：清掉倒计时。
                    is StreamChunk.RetryAttemptStart -> updateAssistantRetry(assistantId, null)
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
                finishAssistant(
                    assistantId,
                    finalParts,
                    finalSegmentsJson,
                    usage = finishUsage,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                )
                onPersist(finalParts, finishUsage, finalSegmentsJson)
                break
            }
            // Execute each announced tool and fold its result into the
            // part (stream_chunk_handler.dart ToolCallResult path).
            // 生成类工具的产物（图片 / 视频）先攒着，等工具卡 fold 完再紧跟其后并进
            // **同一条**助手消息（用户 2026-09-17「生成结果再开一个输出…好割裂呀」）。
            val generatedParts = mutableListOf<com.psyche.memo.data.model.MessagePart>()
            val nudges = mutableListOf<String>()
            // 本轮开始前的累计 parts（每颗工具跑完都要用它 + 本轮已 fold 的结果重拼一次）。
            val carriedParts = allParts.toList()
            val results = calls.map { call ->
                val images = mutableListOf<com.psyche.memo.data.model.ToolImage>()
                val result = toolHandler.handle(
                    call.name,
                    parseToolArguments(call.arguments),
                    call.id,
                ) { images += it }
                // 执行**之后**才计数（上游在 post-execute 观察）：被拒绝、被中断的调用
                // 同样在原地打转，那正是要打断的循环。
                repeatGuard.observe(call.name, call.arguments)?.let { nudges += it }
                if (call.name in com.psyche.memo.provider.generation.MEDIA_TOOL_NAMES) {
                    generatedParts += com.psyche.memo.provider.generation.generatedMediaParts(result)
                }
                roundHandler.foldToolResult(call.id, JsonPrimitive(result), images)
                // **每颗跑完就并一次**：用户在这之后点停止，已经执行过的工具仍留在 parts 里
                //（界面上看得到那张卡与结果，落库也看得到）。原先是整个 map 跑完才并，
                // 中途取消就把「真的做过」的证据整批丢掉 —— 「说了做了其实没做」的另一半根因。
                allParts.clear()
                allParts += carriedParts
                allParts += roundHandler.parts
                result to images
            }
            // 产物紧跟工具卡（本轮 fold 出来的 part 之后），不再单开一条消息。
            allParts += generatedParts
            allSegments += roundHandler.reasoningSegments
            updateStreaming(allParts, encodeSegments(allSegments)) // folded results now visible
            // Append the assistant tool_calls + tool result transcript
            // (chat_completions_api.dart `_buildAssistantToolCallMessage`).
            //
            // **正文只取本轮响应的文本**（Dart 传的是 `msg['content']` —— 当前这次响应的
            // message.content），不能传 `allParts`：那是**跨轮累计**的全文，会让模型在
            // 第 2 轮往后的 assistant 轮里又看到一遍自己第 1 轮的正文，于是经常把旧正文
            // 复述出来（用户 2026-09-16「为什么我看他经常输出重复正文呀 是结果反馈没有
            // 做好吗」）。空正文按原版归一成 "\n\n"（`:132-136`）。
            history.add(
                LlmMessage(
                    role = "assistant",
                    content = assistantToolCallTranscriptContent(roundHandler.parts),
                    toolCalls = calls.map { LlmToolCall(it.id, it.name, it.arguments) },
                ),
            )
            for ((index, call) in calls.withIndex()) {
                history.add(
                    LlmMessage(
                        role = "tool",
                        toolCallId = call.id,
                        toolName = call.name,
                        content = results[index].first,
                        // 工具结果附带的图片（工作区读图片）—— 照上游作为工具结果的一部分
                        // 回传；模型不支持图片输入时由客户端换成文本占位。
                        toolImages = results[index].second.map {
                            LlmImage(uri = it.uri, mime = it.mime)
                        },
                    ),
                )
            }
            // 重复调用提醒：工具结果**之后**补一轮带框上下文（上游把它做成
            // `source={kind:'plugin',form:'notice'}` 的 user 消息）。只提醒不否决 ——
            // 模型照样看到那次调用的真实结果，只是多一句「你在原地打转」。
            if (nudges.isNotEmpty()) {
                history.add(
                    LlmMessage(
                        role = "user",
                        content = "<system-reminder>\n" +
                            nudges.joinToString("\n\n") + "\n</system-reminder>",
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
        if (!hasModelOrWarn()) return
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
            // 本轮（续写）起始时刻 —— 收尾写 UI 的 durationMs 用。
            val continueStartMs = System.currentTimeMillis()
            val allParts = updatedParts.toMutableList()
            val allSegments = ReasoningSegmentCodec.decode(targetUi.reasoningSegmentsJson).toMutableList()
            // 续写：已有 segment 视作「已存在」，用库里的展开/折叠态初始化权威态。
            resetReasoningExpandedState(allSegments)
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
                // 压缩检查点按发送链路同样的语义展开（检查点置前、boundary 之前的
                // 消息不再发送），避免把摘要正文当成一条普通 user 发言。
                val history = compactionWindow(_messages.value.takeWhile { it.id != messageId })
                    .mapNotNull { msg ->
                        val checkpoint = msg.checkpointPart()
                        val content = if (checkpoint != null) {
                            com.psyche.memo.common.SessionCompaction
                                .checkpointText(checkpoint.summary, checkpoint.recent)
                        } else {
                            msg.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                        }
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
                    startedAtMs = continueStartMs,
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
                persistFinal(
                    markFailed(
                        messageId,
                        com.psyche.memo.ui.chat.generationErrorDisplayText(container.appContext, e),
                        allParts,
                        encodeSegments(allSegments),
                    ),
                )
            } finally {
                updateAssistantRetry(messageId, null)
                _streaming.value = false
                // 同 [startGeneration] 的收尾：协程结束前释放本会话没人等的审批/问询。
                releaseInterruptions()
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
        // 从执行器清单推导，不再手抄第二份名单（漏一次就静默失效，见 offeredLocalToolNames 注释）。
        val offered = com.psyche.memo.ui.BuiltInToolCatalog.offeredLocalToolNames()
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
        // Agent Skills（SkillsTools）：助手启用、且磁盘上确实存在的技能才暴露
        // `use_skill`；一个都没有就整颗不提供（照上游 createSkillTools）。
        out.addAll(
            com.psyche.memo.provider.SkillTools.buildDefinitions(
                enabledSkills = assistant.enabledSkills,
                allSkills = container.skillStore.listSkills(),
            ),
        )
        // 沙箱工作区（WorkspaceTools）：助手绑定了工作区才提供整颗工具面
        // （读/写/改 + list/glob/grep 三个只读搜索 + shell）。
        out.addAll(
            com.psyche.memo.provider.workspace.WorkspaceTools.buildDefinitions(
                workspaceId = assistant.workspaceId,
                cwd = assistant.workspaceCwd,
            ),
        )
        // 生成图片 / 生成视频（GenerationTools，自研功能）：助手在生成 tab 里选了
        // 服务才提供对应工具（没选就不出现在请求里）。
        out.addAll(
            com.psyche.memo.provider.generation.GenerationTools.buildDefinitions(
                repo = container.generationServices,
                assistant = assistant,
            ),
        )
        // MCP 工具（mcp_tool_service）：助手绑定且已连接的服务器，仅启用的工具；
        // 与内置工具同名的条目按原版保留名规则剔除。
        val reserved = com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.all.toSet() + setOf(
            com.psyche.memo.provider.search.SearchToolService.TOOL_NAME,
        ) + com.psyche.memo.provider.MemoryTools.ALL_TOOL_NAMES +
            com.psyche.memo.provider.SkillTools.ALL_TOOL_NAMES +
            com.psyche.memo.provider.workspace.WorkspaceTools.ALL_TOOL_NAMES +
            com.psyche.memo.provider.generation.GenerationTools.ALL_TOOL_NAMES
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
            val specName = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: name
            val description = (fn["description"] as? JsonPrimitive)?.contentOrNull ?: ""
            // 会画图的工具：颜色是「主题」拥有的事实，在请求时注入进描述 —— 照
            // deepseek-harness 的「每个事实只有一个所有者」（docs/ENGINEERING_HARNESS.md §1）。
            val themedDescription = when (specName) {
                com.psyche.memo.provider.chart.VisualTools.TOOL_NAME,
                com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME,
                -> com.psyche.memo.provider.chart.VisualTools.withThemeNote(description, container)
                else -> description
            }
            val parameters = fn["parameters"] as? JsonObject
            out.add(LlmToolSpec(specName, themedDescription, parameters?.toString() ?: "{}"))
        }
        // 「设置 → 工具描述」里改过的描述套上去（tool_handler_service.dart:284-286 的
        // `ToolSchemaOverrides.apply`）。**只作用于内置工具名** —— MCP 工具是动态的，
        // 名字不在目录里，原样保留。此前这个键只有写没有读，改了完全不起作用。
        val schemaLang = com.psyche.memo.ui.MemorySettingsState(container).resolvedPromptLang()
        return com.psyche.memo.provider.ToolSchemaOverrides.apply(
            definitions = out,
            overrides = com.psyche.memo.ui.readOverrides(container),
            builtInNames = com.psyche.memo.ui.BuiltInToolCatalog.allBuiltInNames(schemaLang),
        )
    }

    /**
     * 阈值估算用的工具定义 JSON —— opencode `estimate({… , tools})` 的 `request.tools`
     * （名称 + 描述 + 参数 schema）。
     */
    private fun toolsJsonForEstimate(tools: List<LlmToolSpec>): String = JsonArray(
        tools.map { tool ->
            JsonObject(
                linkedMapOf(
                    "name" to JsonPrimitive(tool.name),
                    "description" to JsonPrimitive(tool.description),
                    "parameters" to runCatching { Json.parseToJsonElement(tool.inputSchemaJson) }
                        .getOrElse { JsonPrimitive(tool.inputSchemaJson) },
                ),
            )
        },
    ).toString()

    /**
     * message_builder_service.dart 的系统消息组装顺序（L167-189）：
     * 助手系统提示词 → 记忆/回忆规则 → 搜索引用提示词 → 指令注入。
     * 每一段带自己的 [ContextSource]，上下文日志按它切片（见
     * [com.psyche.memo.logging.ContextLogAssembler]）。
     */
    internal suspend fun buildSystemPromptParts(
        assistant: com.psyche.memo.data.model.Assistant?,
        /**
         * 这一轮**实际递给模型的工具名**（`offeredTools().map { it.name }`）。
         * 空 = 不注入工具纪律；里面的路由句也**只写这里有的名字** —— 告诉模型一颗
         * 列表里没有的工具，比不告诉更糟（见 [com.psyche.memo.provider.ToolRules]）。
         */
        offeredToolNames: Collection<String> = emptyList(),
        hasTools: Boolean = false,
    ): List<Pair<ContextSource, String>> {        val parts = mutableListOf<Pair<ContextSource, String>>()
        fun add(source: ContextSource, text: String?) {
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(source to it) }
        }
        // injectSystemPrompt（message_builder_service L1569-1597）：系统提示词里的
        // `{cur_date}` / `{nickname}` / `{assistant_name}` … 12 个变量在这里替换
        // （助手编辑页「可用变量」列的就是它们）。
        add(ContextSource.systemPrompt, resolveSystemPromptVariables(assistant))
        // 工具纪律 + 按本轮工具生成的路由句（本工程新增，照 deepseek-harness 的提示词工程思路）：
        // 「只有工具成功返回才算做了」——用户 2026-09-23「出现大模型说做了，他根本没有做的问题」。
        add(ContextSource.toolRules, com.psyche.memo.provider.ToolRules.blockFor(offeredToolNames))
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
        // Agent Skills：可用技能清单（上游 `Tool.systemPrompt` 的等价物，见 SkillTools）。
        // 清单空但**这段对话以前给过目录**（模型自己调用过 use_skill）时也要注入 ——
        // 那时递的是「墓碑」，否则模型会照着历史里那次成功的名字继续猜。
        val catalogWasUsed = skillCatalogWasUsed()
        if (assistant != null && (assistant.enabledSkills.isNotEmpty() || catalogWasUsed)) {
            add(
                ContextSource.skillPrompt,
                com.psyche.memo.provider.SkillTools.systemPromptBlock(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = container.skillStore.listSkills(),
                    catalogWasUsed = catalogWasUsed,
                ),
            )
        }
        // 沙箱工作区：助手绑了工作区**且 shell 就绪**才注入（上游
        // WorkspaceReminderTransformer 的触发条件 —— 没装好就说有沙箱会骗模型）。
        val workspaceId = assistant?.workspaceId
        if (!workspaceId.isNullOrBlank()) {
            val workspace = container.workspaceRepository.get(workspaceId)
            if (workspace != null &&
                workspace.shellStatus == com.psyche.memo.workspace.WorkspaceShellStatus.READY.name
            ) {
                add(
                    ContextSource.workspace,
                    com.psyche.memo.provider.workspace.WorkspaceTools.buildSystemPromptBlock(
                        workspaceName = workspace.name,
                        cwd = assistant.workspaceCwd,
                    ),
                )
            }
        }
        // injectInstructionPrompts L1748-1772 —— 助手启用中的注入项按顺序合并。
        add(ContextSource.instructionInjection, activeInstructionPrompts(assistant?.id))
        return parts
    }

    /**
     * 这段对话里模型是否**已经调用过** `use_skill`（只看已加载消息的工具 part，纯内存）。
     *
     * 这是「技能目录曾经存在过」的唯一可靠信号：Memo 的目录在系统提示词里、每轮重建，
     * 用户中途删技能后历史里什么都不会留下 —— 只剩模型自己那次成功的调用。
     */
    private fun skillCatalogWasUsed(): Boolean = _messages.value.any { message ->
        message.parts.any { part ->
            part is com.psyche.memo.data.model.ToolCallPart &&
                com.psyche.memo.provider.SkillTools.USE_SKILL ==
                com.psyche.memo.data.model.ToolCallPart.decode(part.payloadJson)?.name
        }
    }

    /**
     * `PromptTransformer.buildPlaceholders` 的 app 侧取值 —— 平台相关的几个值
     * （时区/系统版本/设备/电量/昵称）在这里读，core:llm 只负责拼表与替换。
     *
     * 与原版的两处差异（原版这里是显式占位，见 PORTING §5.11 平台差异）：
     * `{device_info}` 给「android 厂商 型号」（原版只有 OS 名）、`{battery_level}`
     * 给真实电量百分比（原版写死 "unknown"）。
     */
    private fun resolveSystemPromptVariables(
        assistant: com.psyche.memo.data.model.Assistant?,
    ): String? {
        val prompt = assistant?.systemPrompt ?: return null
        if (!prompt.contains('{')) return prompt
        // 手滑的变量名不许静默：原样保留（与上游一致），但记一条告警让它能被发现。
        val unknownVariables = com.psyche.memo.llm.prompt.PromptTransformer.unknownPlaceholders(
            prompt,
            com.psyche.memo.llm.prompt.PromptTransformer.supportedKeys(),
        )
        if (unknownVariables.isNotEmpty()) {
            android.util.Log.w(
                "MemoPrompt",
                "系统提示词里有不认识的变量（会原样发给模型）：$unknownVariables",
            )
        }
        val context = container.appContext
        val nickname = DefaultModelPrefs.decodeStoredString(
            container.preferenceRepository.readJson("user_name"),
        ).orEmpty()
        val vars = com.psyche.memo.llm.prompt.PromptTransformer.buildPlaceholders(
            assistantName = assistant.name,
            userNickname = nickname,
            modelId = selectedModelId.value.takeIf { it.isNotEmpty() },
            modelName = selectedModelId.value.takeIf { it.isNotEmpty() },
            locale = java.util.Locale.getDefault().toLanguageTag(),
            timezone = java.util.TimeZone.getDefault()
                .getDisplayName(false, java.util.TimeZone.SHORT)
                .orEmpty(),
            systemVersion = "android ${android.os.Build.VERSION.RELEASE}",
            deviceInfo = listOf(
                "android",
                android.os.Build.MANUFACTURER,
                android.os.Build.MODEL,
            ).filter { it.isNotBlank() }.joinToString(" "),
            batteryLevel = batteryLevelLabel(context),
        )
        return com.psyche.memo.llm.prompt.PromptTransformer.replacePlaceholders(prompt, vars)
    }

    /** `{battery_level}` —— 读系统电量（读不到时沿用原版的 "unknown"）。 */
    private fun batteryLevelLabel(context: android.content.Context): String = runCatching {
        val manager = context.getSystemService(android.content.Context.BATTERY_SERVICE)
            as? android.os.BatteryManager ?: return@runCatching "unknown"
        val level = manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) "$level%" else "unknown"
    }.getOrDefault("unknown")

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

    /**
     * 只更新助手消息的自动重试倒计时（[UiMessage.retryStatus]），不碰
     * parts/segments —— RetryPending 事件与内容更新并行到达，独立通道互不覆盖
     *（copy() 保留其余字段，靠它自然合并）。null = 清除倒计时（RetryAttemptStart）。
     */
    private fun updateAssistantRetry(assistantId: String, retry: UiMessage.RetryStatus?) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        _messages.value = msgs.toMutableList().apply {
            set(index, msgs[index].copy(retryStatus = retry))
        }
    }

    private fun finishAssistant(
        assistantId: String,
        parts: List<MessagePart>,
        segmentsJson: String? = null,
        usage: UsageStats? = null,
        durationMs: Long? = null,
    ) {
        val msgs = _messages.value
        val index = msgs.indexOfLast { it.id == assistantId }
        if (index < 0) return
        val finalUi = msgs[index].copy(
            parts = parts,
            isStreaming = false,
            reasoningSegmentsJson = segmentsJson,
            retryStatus = null, // 正常结束：倒计时随之消失。
        ).withTokenStats(
            totalTokens = usage?.totalTokens,
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            cachedTokens = usage?.cachedTokens,
            durationMs = durationMs,
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
        // 失败提示（原版 home_page_controller.dart:508-515：SnackBar 走
        // `generationInterrupted: <错误>`）。有半成品时气泡里不写错误行，这条就是唯一告知。
        com.psyche.memo.ui.snackbar.SnackbarManager.show(
            com.psyche.memo.ui.snackbar.AppNotification(
                message = container.appContext.getString(
                    com.psyche.memo.ui.R.string.generation_interrupted,
                ) + ": " + errorText.lineSequence().first(),
                type = com.psyche.memo.ui.snackbar.NotificationType.ERROR,
            ),
        )
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
                        retryStatus = null, // 真失败：倒计时随之消失。
                    ),
                )
            }
        }
        return finalParts
    }

    /**
     * 逐下标的**权威展开态** —— 等价于 Dart 里被就地改写的
     * `ReasoningSegmentData.expanded`（`stream_controller.dart` 94-97 的
     * `_reasoningSegments` 映射）。流式期间的展开/折叠以它为准，语义见
     * [ReasoningSegmentCodec.resolveExpanded]。每次开新会话生成 / 续写时重置。
     */
    private val segmentExpanded = HashMap<Int, Boolean>()

    /** 上一次编码出的 segment 列表（交给 [ReasoningSegmentCodec.resolveExpanded] 判新段/结束转变）。 */
    private var lastEncodedSegments: List<ReasoningSegment> = emptyList()

    /**
     * 新一轮生成 / 续写开始：用当时的 segment 重置权威展开态（续写时库里读出的
     * 展开/折叠态原样保留）。
     */
    private fun resetReasoningExpandedState(segments: List<ReasoningSegment>) {
        segmentExpanded.clear()
        segments.forEachIndexed { index, segment -> segmentExpanded[index] = segment.expanded }
        lastEncodedSegments = segments
    }

    /**
     * stream_controller.dart 771/776 —— 编码前的展开态以 [segmentExpanded] 为准：
     * 只有**新段**与**结束转变**采用流式 handler 传来的值，其余（含用户点击）沿用
     * 权威态。此前直接用 handler 重建出的 `expanded`，导致用户在思考中点击展开被
     * 下一个增量打回（「点卡片展开会打架」）。
     */
    private fun encodeSegments(segments: List<ReasoningSegment>): String? {
        val initialExpanded = !readBool(AUTO_COLLAPSE_THINKING_KEY, true)
        val resolved = ReasoningSegmentCodec.resolveExpanded(
            segments,
            lastEncodedSegments,
            segmentExpanded,
            initialExpanded,
        )
        lastEncodedSegments = resolved
        return ReasoningSegmentCodec.encode(resolved)
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
        // 唯一入口（旧键是裸布尔、新键是 "1"/"0"，只认一种会读成恒 false）。
        com.psyche.memo.ui.DisplayPrefs.readBool(container, key, default)

    /**
     * Expand/collapse one reasoning segment
     * (home_page_controller.toggleReasoningSegment 2268-2287): flip the stored
     * flag in memory and rewrite `reasoning_segments_json`.
     */
    fun toggleReasoningSegment(messageId: String, segmentIndex: Int) {
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val displayed = ReasoningSegmentCodec.decode(msgs[idx].reasoningSegmentsJson)
            .getOrNull(segmentIndex)?.expanded ?: true
        val json = ReasoningSegmentCodec.toggleExpandedAt(msgs[idx].reasoningSegmentsJson, segmentIndex)
            ?: msgs[idx].reasoningSegmentsJson
        // 正在流式的这条消息：把翻转结果写进权威展开态，否则下一个增量编码会按
        // handler 重建的 expanded 覆盖掉（Dart 里点的是同一个对象，天然保留）。
        if (msgs[idx].isStreaming) segmentExpanded[segmentIndex] = !displayed
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
        /** 并入已有助手分组（重新生成 + 不删后续）时传入；null = 自成一组。 */
        groupId: String? = null,
        version: Int = 0,
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
                        groupId = groupId ?: assistantId,
                        version = version,
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
        /**
         * assistant 工具调用轮次的正文（`chat_completions_api.dart:125-151`
         * `_buildAssistantToolCallMessage` 的 `normalizedContent`）：
         *
         * - 只取**本轮响应**的 TextPart 拼接（调用点传 `roundHandler.parts`，
         *   **绝不能传跨轮累计的 allParts** —— 那会让模型又看到自己上一轮的正文并复述出来）；
         * - 空正文归一成 `"\n\n"`（Dart `:135`，内容为空时才用，非空原样）。
         */
        internal fun assistantToolCallTranscriptContent(parts: List<MessagePart>): String {
            val text = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            return text.ifEmpty { "\n\n" }
        }

        /** `display_auto_collapse_thinking_v1` — "auto-collapse thinking" setting. */
        private const val AUTO_COLLAPSE_THINKING_KEY = "display_auto_collapse_thinking_v1"

        /**
         * 往前翻页的每页条数 —— chat_service.dart `defaultHistoryPageSize = 20`
         * （首屏窗口是 `defaultTimelineInitialSlots = 40`，见 MessageDao.getTail）。
         */
        private const val HISTORY_PAGE_SIZE = 20

        /**
         * 首屏窗口条数 —— `chat_service.dart:77 defaultTimelineInitialSlots = 40`，
         * 也是 `MessageDao.getTail` 的默认 limit。窗口装满即认为还有更早的，
         * 不必再查一次总数。
         */
        private const val TAIL_WINDOW = 40

        /** 首屏 Markdown 预热条数：一屏可见量 + 余量（不是原来那套无界的 60 条）。 */
        private const val MARKDOWN_PREWARM_MESSAGES = 8

        /** 预热的总字符预算（原版流式侧 `_streamingHighlightMaxChars = 12000` 同量级）。 */
        private const val MARKDOWN_PREWARM_CHARS = 40_000

        fun factory(
            container: AppContainerImpl,
            conversationId: String,
            injectPresets: Boolean = false,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(container, conversationId, injectPresets) as T
        }

        /**
         * Fork ("create branch") copy: the message row duplicated into a new
         * conversation (drawer duplicateConversation semantics, truncated at
         * the anchor). New row id, streaming cleared.
         */
        fun forkCopy(
            m: ChatMessage,
            newConversationId: String,
            /** 保留版本模式：源分组 → 新分组（版本号原样保留，分组内仍是多版本）。 */
            groupId: String? = null,
            /** 拍平模式：每个分组只留一条，统一 version = 0。 */
            version: Int? = null,
            /** 拍平模式按顺序重排 message_order。 */
            messageOrder: Int? = null,
        ): ChatMessage = ChatMessage(
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
            groupId = groupId ?: m.groupId,
            version = version ?: m.version,
            promptTokens = m.promptTokens,
            completionTokens = m.completionTokens,
            cachedTokens = m.cachedTokens,
            durationMs = m.durationMs,
            updatedAt = m.updatedAt,
            messageOrder = messageOrder ?: m.messageOrder,
        )
    }
}
