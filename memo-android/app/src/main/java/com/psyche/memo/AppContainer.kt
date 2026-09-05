package com.psyche.memo

import android.content.Context
import com.psyche.memo.data.db.ConversationDao
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.db.MessageDao
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.settings.AppLocaleStore
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.llm.client.LlmClient
import com.psyche.memo.llm.core.CancellationRegistry
import com.psyche.memo.llm.provider.ClaudeClient
import com.psyche.memo.llm.provider.GeminiClient
import com.psyche.memo.llm.provider.OpenAiChatCompletionsClient
import com.psyche.memo.llm.retry.AutoRetryOptions
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
