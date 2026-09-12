package com.psyche.memo

import android.content.Context
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.db.ConversationDao
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.MessageDao
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.settings.AppLocaleStore
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.llm.client.LlmClient
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.provider.ClaudeClient
import com.psyche.memo.llm.provider.GeminiClient
import com.psyche.memo.llm.provider.OpenAiChatCompletionsClient
import com.psyche.memo.llm.retry.AutoRetryOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Production container. Wiring holder for the whole app; kept manual so tests
 * can substitute fakes.
 */
class AppContainerImpl(context: Context) : com.psyche.memo.common.AppContainer {

    /** App context for tool executors (clipboard / TTS / usage stats). */
    val appContext: Context = context.applicationContext

    override val appName: String = "Memo"
    override val platform: com.psyche.memo.common.Platform = com.psyche.memo.common.Platform.ANDROID

    val database: MemoDatabase by lazy { MemoDatabase(appContext) }
    val preferenceRepository: PreferenceRepository by lazy {
        PreferenceRepository(
            database,
            appContext.getSharedPreferences("memo_preferences", Context.MODE_PRIVATE),
        )
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // long SSE reads
        .writeTimeout(30, TimeUnit.SECONDS)
        // 全局网络代理（network_proxy_page 的 global_proxy_*_v1 键）：selector
        // 每次连接都读当前配置 ⇒ 设置改动立即生效，无需重建客户端。绕过规则
        // （localhost/127.0.0.1/网段）在这里生效——上游 dio 没消费 bypass，但
        // 本地模型服务器挂代理时必须直连。
        .proxySelector(GlobalProxy.selector(preferenceRepository))
        .proxyAuthenticator { _, response ->
            val credentials = GlobalProxy.credentialsFor(preferenceRepository)
            if (credentials == null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", credentials)
                    .build()
            }
        }
        // RequestLogInterceptor is a no-op when com.psyche.memo.common.logging.RequestLogger
        // is disabled; safe to keep installed regardless of the toggle.
        .addInterceptor(com.psyche.memo.llm.logging.RequestLogInterceptor())
        .build()

    val appLocaleStore: AppLocaleStore by lazy { AppLocaleStore(preferenceRepository) }

    /** Conversations with an active LLM stream — drives the drawer loading dot. */
    val streamingConversationIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    val conversationDao: ConversationDao by lazy { ConversationDao(database.readableDatabase) }
    val messageDao: MessageDao by lazy { MessageDao(database.readableDatabase) }

    /** Provider data layer (provider_rows + ordering + groups). */
    val providerRepository: com.psyche.memo.data.repo.ProviderRepository by lazy {
        com.psyche.memo.data.repo.ProviderRepository(database.writableDatabase, preferenceRepository)
    }

    val assistantStore: AssistantStore by lazy { AssistantStore(database.writableDatabase) }

    /** 长期记忆数据层（memory_entry_rows 表 + payload 投影，见 MemoryEntryRowDao）。 */
    val memoryProviderV2: com.psyche.memo.ui.MemoryProviderV2 by lazy {
        com.psyche.memo.ui.MemoryProviderV2(database.writableDatabase)
    }

    /** 用户资料（user_provider.dart：user_name / avatar_type / avatar_value）。 */
    val userProfileStore: com.psyche.memo.ui.UserProfileStore by lazy {
        com.psyche.memo.ui.UserProfileStore(preferenceRepository)
    }

    /**
     * 助手编辑页 tab 布局（mobile_assistant_edit_tab_order_v1 /
     * mobile_assistant_edit_tab_hidden_v1 / mobile_assistant_detail_outline_enabled_v1）。
     * 容器级共享，编辑页与布局页读同一份可变状态。
     */
    val assistantTabLayout: com.psyche.memo.ui.AssistantTabLayoutState by lazy {
        com.psyche.memo.ui.AssistantTabLayoutState(preferenceRepository)
    }

    /** Search service settings (search_service_rows + preference keys). */
    val searchSettingsRepository: com.psyche.memo.data.repo.SearchSettingsRepository by lazy {
        com.psyche.memo.data.repo.SearchSettingsRepository(database.writableDatabase, preferenceRepository)
    }

    /** MCP server storage + runtime connections. */
    val mcpRepository: com.psyche.memo.data.repo.McpRepository by lazy {
        com.psyche.memo.data.repo.McpRepository(database.writableDatabase)
    }

    /** World book (lorebook) data layer. */
    val worldBookRepository: com.psyche.memo.data.repo.WorldBookRepository by lazy {
        com.psyche.memo.data.repo.WorldBookRepository(database.writableDatabase, preferenceRepository)
    }

    /**
     * Voice service stores (TTS + ASR). Container-scoped so the list page
     * and the add/edit pages share the same instance; the editor's
     * `upsert` / `add` / `remove` bump `version` here, and the list page
     * re-renders the section without any nav-result plumbing.
     */
    val ttsServicesStore: com.psyche.memo.ui.TtsServicesStore by lazy {
        com.psyche.memo.ui.TtsServicesStore(database.writableDatabase, preferenceRepository)
    }
    val asrServicesStore: com.psyche.memo.ui.AsrServicesStore by lazy {
        com.psyche.memo.ui.AsrServicesStore(preferenceRepository)
    }
    val mcpConnections: com.psyche.memo.provider.mcp.McpConnectionManager by lazy {
        com.psyche.memo.provider.mcp.McpConnectionManager(mcpRepository, httpClient)
    }

    /** HTTP search dispatch (ported provider subset). */
    val searchEngine: com.psyche.memo.provider.search.SearchEngine by lazy {
        com.psyche.memo.provider.search.HttpSearchEngine(httpClient)
    }

    /**
     * Backup / restore orchestration (archive build, local-file restore).
     * appVersion is read from the package manager so the manifest records the
     * real build instead of a hardcoded string.
     */
    val backupService: com.psyche.memo.data.backup.MemoBackupService by lazy {
        com.psyche.memo.data.backup.MemoBackupService(
            context = appContext,
            database = database,
            preferenceRepository = preferenceRepository,
            appVersion = appVersionString(),
            httpClient = httpClient,
        )
    }

    /**
     * 本机副本（backup 子块 3）：`<filesDir>/snapshots/` 下的归档 + 保留策略。
     * 自动/手动都走这里，`runIfDue` 由启动与回前台触发。
     */
    val localSnapshots: com.psyche.memo.data.backup.LocalSnapshotService by lazy {
        com.psyche.memo.data.backup.LocalSnapshotService(
            // Copies live beside the app's files; the change fingerprint reads
            // the real database path (`/data/data/<pkg>/databases/memo.db`),
            // which is *not* under filesDir on Android.
            appDataDirectory = appContext.filesDir,
            databaseFile = appContext.getDatabasePath(com.psyche.memo.data.db.MemoSchema.DB_NAME),
            backupService = backupService,
            preferences = com.psyche.memo.data.backup.LocalSnapshotPreferences(preferenceRepository),
        )
    }

    /**
     * 备份提醒（backup 子块 4）：五键调度 + 到期判定 + 会话内 snooze，
     * 容器级单例（横幅与备份页读同一份状态），`initialize()` 启动分钟计时。
     */
    val backupReminder: BackupReminder by lazy { BackupReminder(preferenceRepository) }

    /**
     * Launch + resume hook: takes a copy when the schedule says one is due.
     * Cheap to call — the counters and the change fingerprint decide — and it
     * never blocks the caller.
     */
    fun maybeRunLocalSnapshot() {
        appScope.launch { runCatching { localSnapshots.runIfDue() } }
    }

    /** `"1.2.5+2073"` — versionName + versionCode, matching Flutter's appVersion. */
    private fun appVersionString(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val versionName = info.versionName ?: "0"
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "$versionName+$versionCode"
    }.getOrDefault("0+0")

    /** tool_approval_service.dart / ask_user_interaction_service.dart 服务对。 */
    val toolApprovalService: com.psyche.memo.ui.chat.ToolApprovalService by lazy {
        com.psyche.memo.ui.chat.ToolApprovalService()
    }
    val askUserInteractionService: com.psyche.memo.ui.chat.AskUserInteractionService by lazy {
        com.psyche.memo.ui.chat.AskUserInteractionService()
    }

    /** App-wide IO scope for one-shot persistence (assistant selection writes). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 记忆流程追踪（memory_trace.dart）：内存环形缓冲（24 条，不落盘），
     * 后台整理与记忆工具调用都会写一份，供「流程追踪」页查看。
     */
    val memoryTraceRecorder: com.psyche.memo.provider.MemoryTraceRecorder
        get() = com.psyche.memo.provider.MemoryTraceRecorder

    /** `memory_trace_enabled_v1` → recorder（默认开）。 */
    fun syncMemoryTraceEnabled() {
        val raw = preferenceRepository.readJson("memory_trace_enabled_v1")
        val enabled = raw == null || raw == "true"
        memoryTraceRecorder.enabled = enabled
    }

    /**
     * 后台记忆整理（memory_pipeline.dart）：Gatekeeper → Extract → Smart Add →
     * Profile Distiller，单并发队列。跟着进程活，聊天侧只负责 schedule。
     */
    val memoryPipeline: com.psyche.memo.provider.MemoryPipelineService by lazy {
        com.psyche.memo.provider.MemoryPipelineService(this, appScope)
    }

    /**
     * assistant_provider.dart currentAssistantId — the globally selected
     * assistant that the drawer scopes conversations to. Exposed as a
     * StateFlow so the drawer card + conversation list recompose on switch.
     */
    private val _currentAssistantId = MutableStateFlow<String?>(null)
    val currentAssistantId: StateFlow<String?> = _currentAssistantId

    fun currentAssistant(): Assistant? = _currentAssistantId.value?.let { assistantStore.get(it) }

    /**
     * assistant_provider.load() 94-100: restore the persisted id only while it
     * still exists in assistant_rows. Reads prefs + the store — call on IO.
     */
    fun refreshCurrentAssistant() {
        val savedId = preferenceRepository.readJson(currentAssistantKey())
            ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        _currentAssistantId.value = resolveCurrentAssistantId(
            savedId = savedId,
            existingIds = assistantStore.getAll().map { it.id },
        )
    }

    /**
     * The conversation the chat screen is showing (`ChatService.currentConversationId`).
     * The manual "organize memory" action needs it: it extracts from the chat
     * the user is actually looking at.
     */
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId

    fun setCurrentConversation(id: String?) {
        if (_currentConversationId.value == id) return
        _currentConversationId.value = id
    }

    /** assistant_provider.dart setCurrentAssistant 278-284 — no-op when unchanged. */
    fun setCurrentAssistant(id: String) {
        if (_currentAssistantId.value == id) return
        _currentAssistantId.value = id
        appScope.launch { preferenceRepository.writeJson(currentAssistantKey(), "\"$id\"") }
    }

    /** assistant_provider.dart setSearchEnabledForCurrentAssistant 460-464. */
    fun setAssistantSearchEnabled(enabled: Boolean) {
        val current = currentAssistant() ?: return
        if (current.searchEnabled == enabled) return
        assistantStore.update(current.copy(searchEnabled = enabled))
    }

    private fun currentAssistantKey(): String = "current_assistant_id_v1"

    val cancellations: CancellationRegistry = CancellationRegistry()

    /**
     * auto_retry_options（自动重试设置页）——按请求实时读取，设置页改动
     * 立即作用于下一次生成（此前是硬编码默认值，整页设置无效）。
     */
    private fun currentRetryOptions(): AutoRetryOptions {
        val raw = preferenceRepository.readJson("auto_retry_options") ?: return AutoRetryOptions()
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return AutoRetryOptions()
        fun boolOf(k: String, d: Boolean) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: d
        fun intOf(k: String, d: Int) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: d
        fun longOf(k: String, d: Long) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: d
        fun dblOf(k: String, d: Double) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: d
        fun strList(k: String) = (obj[k] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()
        fun intSet(k: String) = (obj[k] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            ?.toSet()
            ?: AutoRetryOptions().retryStatusCodes
        return AutoRetryOptions(
            enabled = boolOf("enabled", true),
            maxRetries = intOf("maxRetries", AutoRetryOptions.DEFAULT_MAX_RETRIES),
            initialDelayMs = longOf("initialDelayMs", AutoRetryOptions.DEFAULT_INITIAL_DELAY_MS),
            maxDelayMs = longOf("maxDelayMs", AutoRetryOptions.DEFAULT_MAX_DELAY_MS),
            multiplier = AutoRetryOptions.clampMultiplier(dblOf("multiplier", AutoRetryOptions.DEFAULT_MULTIPLIER)),
            jitter = boolOf("jitter", true),
            retryOnNetworkError = boolOf("retryOnNetworkError", true),
            retryStatusCodes = intSet("retryStatusCodes"),
            retryKeywords = strList("retryKeywords"),
            stopKeywords = strList("stopKeywords"),
        )
    }

    val llmClients: List<LlmClient> = listOf(
        OpenAiChatCompletionsClient(httpClient, { currentRetryOptions() }, cancellations),
        ClaudeClient(httpClient, { currentRetryOptions() }, cancellations),
        GeminiClient(httpClient, { currentRetryOptions() }, cancellations),
    )

    fun clientFor(providerId: String): LlmClient =
        llmClients.firstOrNull { it.supports(providerId) } ?: llmClients.first()

    /**
     * Provider API key from the provider_rows payload (multi-key aware).
     *
     * The key is folded onto the canonical built-in spelling first: a stored
     * model selection can still carry an older spelling (`"zhipu ai"`) while the
     * seeded row is `"Zhipu AI"`, and an exact lookup would then read as "no
     * such provider" — which is how a memory-model call ends up unable to reach
     * the model at all.
     */
    fun providerConfig(providerId: String): com.psyche.memo.data.model.ProviderConfig? {
        val dao = PayloadEntityDao(database.readableDatabase, "provider_rows", primaryKey = "provider_key")
        val row = dao.get(providerId)
            ?: dao.get(com.psyche.memo.data.repo.ProviderRepository.canonicalizeKey(providerId))
            ?: return null
        return com.psyche.memo.data.model.ProviderConfig.fromJsonString(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            row.payload,
        )
    }

    fun apiKeyFor(providerId: String): String? =
        providerConfig(providerId)?.effectiveApiKey()
            ?: appContext.getSharedPreferences("memo_providers", Context.MODE_PRIVATE)
                .let { prefs ->
                    prefs.getString("api_key_$providerId", null)
                        ?: prefs.getString(
                            "api_key_" + com.psyche.memo.data.repo.ProviderRepository.canonicalizeKey(providerId),
                            null,
                        )
                }

    fun baseUrlFor(providerId: String): String {
        providerConfig(providerId)?.baseUrl?.takeIf { it.isNotEmpty() }?.let { return it }
        val canonical = com.psyche.memo.data.repo.ProviderRepository.canonicalizeKey(providerId)
        val prefs = appContext.getSharedPreferences("memo_providers", Context.MODE_PRIVATE)
        return prefs.getString("base_url_$providerId", null)
            ?: prefs.getString("base_url_$canonical", null)
            ?: com.psyche.memo.llm.client.LlmDefaults.baseUrlFor(canonical)
    }

    /**
     * Drops every reference to the given models of [providerKey].
     *
     * Port of `_clearAssistantSelectionsForModels` (provider_detail_page.dart
     * L3083-3118) plus the deletion path's own cleanup (L1651-1662). Deleting a
     * model from a provider must not leave an assistant, a conversation or the
     * pinned list pointing at a model that no longer exists — otherwise the
     * chat silently falls back or errors on send.
     *
     * Three stores are swept:
     * 1. `assistant_rows` — an assistant whose `chatModelProvider`/`chatModelId`
     *    match loses its chat-model binding (back to "follow default").
     * 2. `conversation_rows` — a conversation pinning the model is reset to null.
     * 3. `pinned_models_v1` — the "providerKey::modelId" favourites list.
     */
    fun clearModelReferences(providerKey: String, modelIds: List<String>) {
        if (modelIds.isEmpty()) return
        val wanted = modelIds.toSet()

        runCatching {
            val store = assistantStore
            store.getAll()
                .filter { it.chatModelProvider == providerKey && it.chatModelId in wanted }
                .forEach { store.update(it.copy(chatModelProvider = null, chatModelId = null)) }
        }

        runCatching {
            conversationDao.getAll()
                .filter { it.chatModelProvider == providerKey && it.chatModelId in wanted }
                .forEach { conversationDao.setChatModel(it.id, null, null) }
        }

        runCatching {
            val pinned = com.psyche.memo.ui.readPinnedModels(this)
            val kept = pinned.filterNot { entry ->
                val separator = entry.indexOf("::")
                separator > 0 &&
                    entry.substring(0, separator) == providerKey &&
                    entry.substring(separator + 2) in wanted
            }.toSet()
            if (kept.size != pinned.size) {
                com.psyche.memo.ui.writePinnedModels(this, kept)
            }
        }
    }

    /**
     * Provider-wide sweep — `settings_provider.removeProviderConfig` +
     * `chatService.clearConversationModelOverrides(providerKey)`
     * (provider_detail_page.dart L283-299): deleting a provider must drop **every**
     * reference to it, not only the ids currently listed on it (a conversation can
     * still pin a model that was already removed from the provider).
     */
    fun clearProviderReferences(providerKey: String) {
        runCatching {
            val store = assistantStore
            store.getAll()
                .filter { it.chatModelProvider == providerKey }
                .forEach { store.update(it.copy(chatModelProvider = null, chatModelId = null)) }
        }
        runCatching {
            conversationDao.getAll()
                .filter { it.chatModelProvider == providerKey }
                .forEach { conversationDao.setChatModel(it.id, null, null) }
        }
        runCatching {
            val pinned = com.psyche.memo.ui.readPinnedModels(this)
            val kept = pinned.filterNot { entry ->
                val separator = entry.indexOf("::")
                separator > 0 && entry.substring(0, separator) == providerKey
            }.toSet()
            if (kept.size != pinned.size) com.psyche.memo.ui.writePinnedModels(this, kept)
        }
    }
}

/**
 * assistant_provider.load() 94-100 — the persisted id is restored only while it
 * still exists among the assistant ids; a stale/deleted id falls back to null.
 */
internal fun resolveCurrentAssistantId(savedId: String?, existingIds: List<String>): String? =
    if (savedId != null && existingIds.contains(savedId)) savedId else null
