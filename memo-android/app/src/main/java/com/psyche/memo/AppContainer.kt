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

    private val appContext = context.applicationContext

    override val appName: String = "Memo"
    override val platform: com.psyche.memo.common.Platform = com.psyche.memo.common.Platform.ANDROID

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // long SSE reads
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val database: MemoDatabase by lazy { MemoDatabase(appContext) }
    val preferenceRepository: PreferenceRepository by lazy {
        PreferenceRepository(
            database,
            appContext.getSharedPreferences("memo_preferences", Context.MODE_PRIVATE),
        )
    }
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

    /** Search service settings (search_service_rows + preference keys). */
    val searchSettingsRepository: com.psyche.memo.data.repo.SearchSettingsRepository by lazy {
        com.psyche.memo.data.repo.SearchSettingsRepository(database.writableDatabase, preferenceRepository)
    }

    /** HTTP search dispatch (ported provider subset). */
    val searchEngine: com.psyche.memo.provider.search.SearchEngine by lazy {
        com.psyche.memo.provider.search.HttpSearchEngine(httpClient)
    }

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

    /** assistant_provider.dart setCurrentAssistant 278-284 — no-op when unchanged. */
    fun setCurrentAssistant(id: String) {
        if (_currentAssistantId.value == id) return
        _currentAssistantId.value = id
        appScope.launch { preferenceRepository.writeJson(currentAssistantKey(), "\"$id\"") }
    }

    private fun currentAssistantKey(): String = "current_assistant_id_v1"

    val cancellations: CancellationRegistry = CancellationRegistry()

    val retryOptions: AutoRetryOptions = AutoRetryOptions()

    val llmClients: List<LlmClient> = listOf(
        OpenAiChatCompletionsClient(httpClient, retryOptions, cancellations),
        ClaudeClient(httpClient, retryOptions, cancellations),
        GeminiClient(httpClient, retryOptions, cancellations),
    )

    fun clientFor(providerId: String): LlmClient =
        llmClients.firstOrNull { it.supports(providerId) } ?: llmClients.first()

    /** Provider API key from the provider_rows payload (multi-key aware). */
    fun providerConfig(providerId: String): com.psyche.memo.data.model.ProviderConfig? {
        val row = PayloadEntityDao(database.readableDatabase, "provider_rows", primaryKey = "provider_key")
            .get(providerId) ?: return null
        return com.psyche.memo.data.model.ProviderConfig.fromJsonString(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            row.payload,
        )
    }

    /** First enabled provider from provider_rows (DB order). */
    fun firstEnabledProviderConfig(): com.psyche.memo.data.model.ProviderConfig? =
        com.psyche.memo.data.db.PayloadEntityDao(database.readableDatabase, "provider_rows", primaryKey = "provider_key")
            .getAll()
            .mapNotNull { row ->
                runCatching {
                    com.psyche.memo.data.model.ProviderConfig.fromJsonString(
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                        row.payload,
                    )
                }.getOrNull()
            }
            .firstOrNull { it.enabled }

    fun apiKeyFor(providerId: String): String? =
        providerConfig(providerId)?.effectiveApiKey()
            ?: appContext.getSharedPreferences("memo_providers", Context.MODE_PRIVATE)
                .getString("api_key_$providerId", null)

    fun baseUrlFor(providerId: String): String {
        providerConfig(providerId)?.baseUrl?.takeIf { it.isNotEmpty() }?.let { return it }
        val prefs = appContext.getSharedPreferences("memo_providers", Context.MODE_PRIVATE)
        return prefs.getString("base_url_$providerId", null)
            ?: com.psyche.memo.llm.client.LlmDefaults.baseUrlFor(providerId)
    }
}

/**
 * assistant_provider.load() 94-100 — the persisted id is restored only while it
 * still exists among the assistant ids; a stale/deleted id falls back to null.
 */
internal fun resolveCurrentAssistantId(savedId: String?, existingIds: List<String>): String? =
    if (savedId != null && existingIds.contains(savedId)) savedId else null
