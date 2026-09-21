package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.KeyManagementConfig
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Provider data layer — mirrors the provider-facing surface of Flutter
 * SettingsProvider + the providers_page merge logic:
 *
 * - provider rows live in provider_rows (payload = ProviderConfig JSON),
 * - display order comes from preference key `providers_order_v1`
 *   (JSON array of keys); keys not recorded are appended after,
 *
 * New rows take sort_order = max+1 (PayloadEntityDao.nextSortOrder) — never
 * a truncated timestamp, which could violate the schema CHECK(sort_order>=0).
 */
class ProviderRepository(
    private val db: SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val providerDao = PayloadEntityDao(db, "provider_rows", primaryKey = "provider_key")

    init {
        // One-time sweep of empty builtin rows left by earlier test builds
        // (idempotent: re-running matches nothing once removed). Pristine
        // defaults seeded at startup are exempt — see the body.
        cleanupEmptyBuiltinRows()
    }

    /** Base provider keys shown before any user-added config (providers_page._providers). */
    val builtinKeys: List<String> get() = BUILTIN_KEYS

    /**
     * Startup seeding — port of the Flutter per-key fill-missing pass
     * (SettingsProvider.setProvidersOrder L2271-2291 → ensureProviderConfig):
     * every built-in key without a provider_rows row gets a pristine default
     * config (defaultsFor), so the default baseUrl / label / enabled state is
     * available to row-based consumers. Idempotent; existing rows (user data)
     * are never overwritten.
     */
    fun ensureBuiltinDefaultsSeeded() {
        val existing = providerDao.getAll().map { it.id }.toSet()
        for (key in BUILTIN_KEYS) {
            if (key in existing) continue
            saveConfig(defaultsFor(key))
        }
    }

    // ---- provider configs ----

    fun getConfig(key: String): ProviderConfig? {
        val row = providerDao.get(key) ?: return null
        return ProviderConfig.fromJsonString(json, row.payload)
    }

    fun getConfigs(): Map<String, ProviderConfig> =
        providerDao.getAll().mapNotNull { row ->
            ProviderConfig.fromJsonString(json, row.payload)?.let { it.id to it }
        }.toMap()

    /** Insert or update; assigns the next sort_order for new rows. */
    fun saveConfig(config: ProviderConfig) {
        val isNew = providerDao.get(config.id) == null
        val sortOrder = if (isNew) providerDao.nextSortOrder() else providerDao.get(config.id)!!.sortOrder
        providerDao.upsert(config.id, json.encodeToString(ProviderConfig.serializer(), config), sortOrder)
    }

    fun deleteConfig(key: String) {
        providerDao.delete(key)
    }

    /**
     * Remove rows left by earlier test builds: builtin keys whose config has
     * no API key and no models carry no user data (the virtual builtin entry
     * already renders), so dropping them de-duplicates the list. Pristine
     * defaults seeded by [ensureBuiltinDefaultsSeeded] are byte-identical to
     * [defaultsFor] and are kept — only leftovers that differ are removed.
     */
    fun cleanupEmptyBuiltinRows() {
        val builtinLower = BUILTIN_KEYS.map { it.lowercase() }.toSet()
        for (row in providerDao.getAll()) {
            if (row.id.lowercase() !in builtinLower) continue
            val cfg = runCatching { ProviderConfig.fromJsonString(json, row.payload) }.getOrNull() ?: continue
            if (cfg.apiKey.isBlank() && cfg.models.isEmpty()) {
                val pristine = runCatching {
                    json.encodeToString(ProviderConfig.serializer(), defaultsFor(row.id))
                }.getOrNull()
                if (pristine == null || pristine != row.payload) {
                    providerDao.delete(row.id)
                }
            }
        }
    }

    /**
     * Collapse builtin rows stored under a non-canonical spelling
     * (`"zhipu ai"` → `"Zhipu AI"`) onto the canonical key. Older builds let
     * the user type a key verbatim, so the same provider could exist twice:
     * once seeded, once hand-written. The list then rendered two rows sharing
     * one LazyList key, which corrupts item reuse (cards collapse/overlap).
     *
     * The non-canonical row usually carries the real user data (API key,
     * models), so it wins: it is renamed when the canonical row is absent or
     * carries no user data, otherwise the two are merged field-wise. The old
     * preference order entry is rewritten too.
     *
     * Idempotent; returns the keys whose spelling changed.
     */
    fun migrateNonCanonicalBuiltinKeys(): List<String> {
        val renamed = mutableListOf<String>()
        val orderBefore = order()
        val orderAfter = orderBefore.toMutableList()

        for (row in providerDao.getAll()) {
            val canonical = canonicalBuiltinKey(row.id) ?: continue
            if (canonical == row.id) continue

            val incoming = runCatching { ProviderConfig.fromJsonString(json, row.payload) }.getOrNull() ?: continue
            val target = getConfig(canonical)

            val merged = resolveMigrationTarget(target, incoming)
            if (merged != null) {
                saveConfig(merged.copy(id = canonical))
            }
            providerDao.delete(row.id)

            renamed += row.id
        }

        if (renamed.isNotEmpty()) {
            val next = renameOrderEntries(orderBefore, renamed.map { it to canonicalizeKey(it) }.toMap())
            if (next != orderBefore) setOrder(next)
        }

        // A renamed row leaves every stored model selection pointing at the old
        // spelling. Lookups fold it back (AppContainer.providerConfig), but the
        // stored value is also what the picker compares against to show the
        // current choice — and what an exact-match lookup downstream would miss.
        canonicalizeModelBindings(renamed.associateWith { canonicalizeKey(it) })
        return renamed
    }

    /** Preference keys whose value is a `provider::model` pair. */
    private val modelSelectionKeys = listOf(
        "selected_model_v1",
        "memory_model_v1",
        "title_model_v1",
        "summary_model_v1",
        "suggestion_model_v1",
        "translate_model_v1",
        "ocr_model_v1",
        "compress_model_v1",
    )

    /**
     * Rewrites every stored provider key that a rename invalidated: the
     * `provider::model` preferences, the conversation-level chat model, and the
     * assistant-level one inside its payload.
     *
     * Also folds non-canonical builtin spellings it finds without a rename, so a
     * restored backup carrying upstream spellings is cleaned up on the next
     * launch.
     */
    private fun canonicalizeModelBindings(renames: Map<String, String>) {
        fun fold(key: String): String? {
            val canonical = canonicalizeKey(key)
            if (canonical != key) return canonical
            return renames[key]
        }

        for (prefKey in modelSelectionKeys) {
            val raw = prefs.readJson(prefKey) ?: continue
            val value = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull() ?: continue
            val separator = value.indexOf("::")
            if (separator <= 0) continue
            val folded = fold(value.substring(0, separator)) ?: continue
            prefs.writeJson(prefKey, JsonPrimitive(folded + value.substring(separator)).toString())
        }

        // Value-driven: an earlier launch may already have renamed the row, so
        // the stale spelling only exists in the stored bindings by now.
        val staleConversations = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT id, chat_model_provider FROM conversation_rows WHERE chat_model_provider IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val provider = cursor.getString(1) ?: continue
                val folded = fold(provider) ?: continue
                staleConversations.add(id to folded)
            }
        }
        for ((id, folded) in staleConversations) {
            db.execSQL(
                "UPDATE conversation_rows SET chat_model_provider = ? WHERE id = ?",
                arrayOf<Any>(folded, id),
            )
        }

        val assistantDao = PayloadEntityDao(db, "assistant_rows", primaryKey = "id")
        for (row in assistantDao.getAll()) {
            val obj = runCatching { Json.parseToJsonElement(row.payload) as? JsonObject }.getOrNull() ?: continue
            val provider = (obj["chatModelProvider"] as? JsonPrimitive)?.content ?: continue
            val folded = fold(provider) ?: continue
            val updated = JsonObject(obj.toMutableMap().apply { put("chatModelProvider", JsonPrimitive(folded)) })
            assistantDao.upsert(row.id, updated.toString(), row.sortOrder)
        }
    }

    // ---- ordering (providers_order_v1) ----

    fun order(): List<String> {
        val raw = prefs.readJson(ORDER_KEY) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).let { el ->
                (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun setOrder(order: List<String>) {
        prefs.writeJson(
            ORDER_KEY,
            JsonArray(order.map { JsonPrimitive(it) }).toString(),
        )
    }

    /** Merge semantics of providers_page.build: ordered first, leftovers appended. */
    fun applyOrder(keys: List<String>): List<String> = mergeOrder(keys, order())


    companion object {
        /**
         * Base provider keys (providers_page._providers). Upstream-branded
         * KelivoIN is MemoIN; sponsor seed entries (随想AI中转站/MaruCode) are
         * intentionally not carried over per port rules.
         */
        val BUILTIN_KEYS = listOf(
            "OpenAI", "SiliconFlow", "Gemini", "OpenRouter", "MemoIN", "Tensdaq",
            "DeepSeek", "AIhubmix", "Aliyun", "Zhipu AI", "Claude", "Grok", "ByteDance",
        )

        /**
         * Case/space-insensitive lookup from any stored key to the canonical
         * built-in spelling. Older builds persisted hand-typed keys verbatim
         * (the device DB carries `"zhipu ai"` next to the seeded `"Zhipu AI"`),
         * which made the list render the same provider twice and — worse —
         * handed two LazyList items the same render key. Returns null when the
         * key belongs to no built-in.
         */
        fun canonicalBuiltinKey(key: String): String? {
            val normalized = key.trim().lowercase()
            return BUILTIN_KEYS.firstOrNull { it.lowercase() == normalized }
        }

        /**
         * Fold a stored key onto its canonical built-in spelling, leaving
         * user-added providers untouched.
         */
        fun canonicalizeKey(key: String): String = canonicalBuiltinKey(key) ?: key

        /**
         * Rewrite every occurrence of a renamed key, dropping the duplicates
         * that collapse into one canonical entry while keeping first-seen
         * position (so the user's ordering survives the merge).
         */
        fun renameOrderEntries(order: List<String>, renames: Map<String, String>): List<String> {
            if (renames.isEmpty()) return order
            return order.map { renames[it] ?: it }.distinct()
        }

        /** True when the config holds data a pristine default would not. */
        fun hasUserData(cfg: ProviderConfig): Boolean =
            cfg.apiKey.isNotBlank() || cfg.models.isNotEmpty() || !cfg.apiKeys.isNullOrEmpty()

        /**
         * Decide what the canonical row should become after folding a
         * non-canonical row onto it:
         * - no canonical row yet → the incoming config becomes canonical,
         * - canonical row exists and the incoming row holds user data → merge
         *   field-wise ([mergeOnto]),
         * - canonical row exists and the incoming row is empty/default → null
         *   (nothing to write; the canonical row already has everything).
         */
        fun resolveMigrationTarget(
            canonical: ProviderConfig?,
            incoming: ProviderConfig,
        ): ProviderConfig? = when {
            canonical == null -> incoming
            hasUserData(incoming) -> mergeOnto(canonical, incoming)
            else -> null
        }

        /**
         * Fold [incoming] (non-canonical row) over [base] (canonical row):
         * non-blank scalars and non-empty collections from [incoming] win, so
         * the row the user actually filled in is preserved.
         */
        fun mergeOnto(base: ProviderConfig, incoming: ProviderConfig): ProviderConfig = base.copy(
            name = incoming.name.takeIf { it.isNotBlank() } ?: base.name,
            apiKey = incoming.apiKey.takeIf { it.isNotBlank() } ?: base.apiKey,
            baseUrl = incoming.baseUrl.takeIf { it.isNotBlank() } ?: base.baseUrl,
            providerType = incoming.providerType ?: base.providerType,
            chatPath = incoming.chatPath ?: base.chatPath,
            useResponseApi = incoming.useResponseApi ?: base.useResponseApi,
            vertexAI = incoming.vertexAI ?: base.vertexAI,
            location = incoming.location ?: base.location,
            projectId = incoming.projectId ?: base.projectId,
            serviceAccountJson = incoming.serviceAccountJson ?: base.serviceAccountJson,
            models = (base.models + incoming.models).distinct(),
            modelOverrides = base.modelOverrides + incoming.modelOverrides,
            customHeaders = if (incoming.customHeaders.isNotEmpty()) incoming.customHeaders else base.customHeaders,
            customBody = if (incoming.customBody.isNotEmpty()) incoming.customBody else base.customBody,
            multiKeyEnabled = incoming.multiKeyEnabled ?: base.multiKeyEnabled,
            apiKeys = if (!incoming.apiKeys.isNullOrEmpty()) incoming.apiKeys else base.apiKeys,
            avatarType = incoming.avatarType ?: base.avatarType,
            avatarValue = incoming.avatarValue ?: base.avatarValue,
        )

        /**
         * Verbatim port of Flutter ProviderConfig.defaultsFor
         * (settings_provider.dart L6449-6636, incl. _defaultBase /
         * defaultEnabled / balance defaults) — the pristine first-run config a
         * built-in key gets when it has no row (ensureProviderConfig
         * semantics, defaultName = key). The upstream KelivoIN seed is ported
         * as MemoIN and carries the Memo brand throughout.
         */
        fun defaultsFor(key: String): ProviderConfig {
            val lowerKey = key.lowercase()
            val isMemoIn = lowerKey.contains("memoin")

            // settings_provider.dart L6450-6459 defaultEnabled
            val enabled = lowerKey.contains("tensdaq") ||
                lowerKey.contains("openai") ||
                lowerKey.contains("gemini") || lowerKey.contains("google") ||
                lowerKey.contains("silicon") ||
                lowerKey.contains("openrouter") ||
                isMemoIn

            // settings_provider.dart L6398-6411 classify
            val kind = when {
                lowerKey.contains("gemini") || lowerKey.contains("google") -> "google"
                lowerKey.contains("claude") || lowerKey.contains("anthropic") -> "claude"
                else -> "openai"
            }
            val base = defaultBaseUrl(key)
            return when (kind) {
                "google" -> ProviderConfig(
                    id = key,
                    enabled = enabled,
                    name = key,
                    apiKey = "",
                    baseUrl = base,
                    providerType = kind,
                    vertexAI = false,
                    location = "",
                    projectId = "",
                    serviceAccountJson = "",
                    models = emptyList(),
                    modelOverrides = emptyMap(),
                    proxyEnabled = false,
                    proxyHost = "",
                    proxyPort = "8080",
                    proxyUsername = "",
                    proxyPassword = "",
                    multiKeyEnabled = false,
                    apiKeys = emptyList(),
                    keyManagement = KeyManagementConfig(),
                    aihubmixAppCodeEnabled = false,
                    balanceEnabled = false,
                    balanceApiPath = "/credits",
                    balanceResultPath = "data.total_usage",
                    claudePromptCachingEnabled = false,
                )
                "claude" -> ProviderConfig(
                    id = key,
                    enabled = enabled,
                    name = key,
                    apiKey = "",
                    baseUrl = base,
                    providerType = kind,
                    models = emptyList(),
                    modelOverrides = emptyMap(),
                    proxyEnabled = false,
                    proxyHost = "",
                    proxyPort = "8080",
                    proxyUsername = "",
                    proxyPassword = "",
                    multiKeyEnabled = false,
                    apiKeys = emptyList(),
                    keyManagement = KeyManagementConfig(),
                    aihubmixAppCodeEnabled = false,
                    balanceEnabled = false,
                    balanceApiPath = "/credits",
                    balanceResultPath = "data.total_usage",
                    claudePromptCachingEnabled = false,
                )
                else -> when {
                    // Special-case MemoIN default models and overrides (L6518-6568)
                    isMemoIn -> ProviderConfig(
                        id = key,
                        enabled = enabled,
                        name = key,
                        apiKey = "memo", // upstream seeds its public token _kelivoInPublicApiKey (L6056)
                        baseUrl = base,
                        providerType = kind,
                        chatPath = null, // keep empty in UI; code uses default '/chat/completions'
                        useResponseApi = false,
                        models = listOf("mistral", "qwen-coder"),
                        modelOverrides = mapOf(
                            "mistral" to chatModelOverride(withReasoning = false),
                            "qwen-coder" to chatModelOverride(withReasoning = false),
                        ),
                        proxyEnabled = false,
                        proxyHost = "",
                        proxyPort = "8080",
                        proxyUsername = "",
                        proxyPassword = "",
                        multiKeyEnabled = false,
                        apiKeys = emptyList(),
                        keyManagement = KeyManagementConfig(),
                        aihubmixAppCodeEnabled = false,
                        balanceEnabled = defaultBalanceEnabled(key),
                        balanceApiPath = defaultBalanceApiPath(key),
                        balanceResultPath = defaultBalanceResultPath(key),
                        claudePromptCachingEnabled = false,
                    )
                    // Special-case SiliconFlow: prefill two partnered models (L6570-6608)
                    lowerKey.contains("silicon") -> ProviderConfig(
                        id = key,
                        enabled = enabled,
                        name = key,
                        apiKey = "",
                        baseUrl = base,
                        providerType = kind,
                        chatPath = "/chat/completions",
                        useResponseApi = false,
                        models = listOf("THUDM/GLM-4-9B-0414", "Qwen/Qwen3-8B"),
                        modelOverrides = mapOf(
                            "THUDM/GLM-4-9B-0414" to chatModelOverride(withReasoning = false),
                            "Qwen/Qwen3-8B" to chatModelOverride(withReasoning = true),
                        ),
                        proxyEnabled = false,
                        proxyHost = "",
                        proxyPort = "8080",
                        proxyUsername = "",
                        proxyPassword = "",
                        multiKeyEnabled = false,
                        apiKeys = emptyList(),
                        keyManagement = KeyManagementConfig(),
                        aihubmixAppCodeEnabled = false,
                        balanceEnabled = defaultBalanceEnabled(key),
                        balanceApiPath = defaultBalanceApiPath(key),
                        balanceResultPath = defaultBalanceResultPath(key),
                        claudePromptCachingEnabled = false,
                    )
                    else -> ProviderConfig(
                        id = key,
                        enabled = enabled,
                        name = key,
                        apiKey = "",
                        baseUrl = base,
                        providerType = kind,
                        chatPath = "/chat/completions",
                        useResponseApi = false,
                        models = emptyList(),
                        modelOverrides = emptyMap(),
                        proxyEnabled = false,
                        proxyHost = "",
                        proxyPort = "8080",
                        proxyUsername = "",
                        proxyPassword = "",
                        multiKeyEnabled = false,
                        apiKeys = emptyList(),
                        keyManagement = KeyManagementConfig(),
                        aihubmixAppCodeEnabled = lowerKey.contains("aihubmix"),
                        balanceEnabled = defaultBalanceEnabled(key),
                        balanceApiPath = defaultBalanceApiPath(key),
                        balanceResultPath = defaultBalanceResultPath(key),
                        claudePromptCachingEnabled = false,
                    )
                }
            }
        }

        /** chat model override map entry of the MemoIN / SiliconFlow seeds. */
        private fun chatModelOverride(withReasoning: Boolean): JsonObject = buildJsonObject {
            put("type", "chat")
            put("input", buildJsonArray { add(JsonPrimitive("text")) })
            put("output", buildJsonArray { add(JsonPrimitive("text")) })
            put("abilities", buildJsonArray {
                add(JsonPrimitive("tool"))
                if (withReasoning) add(JsonPrimitive("reasoning"))
            })
        }

        /** Verbatim port of settings_provider.dart L6413-6447 _defaultBase. */
        fun defaultBaseUrl(key: String): String {
            val k = key.lowercase()
            if (k.contains("tensdaq")) return "https://tensdaq-api.x-aio.com/v1"
            if (k.contains("memoin")) return "https://text.pollinations.ai/openai"
            if (k.contains("openrouter")) return "https://openrouter.ai/api/v1"
            if (k.contains("aihubmix")) return "https://aihubmix.com/v1"
            if (k.contains("随想")) return "https://sui-xiang.com/v1"
            if (k.contains("marucode") || k.contains("muteki")) {
                return "https://api.muteki.site/v1"
            }
            if (Regex("qwen|aliyun|dashscope").containsMatchIn(k)) {
                return "https://dashscope.aliyuncs.com/compatible-mode/v1"
            }
            if (Regex("bytedance|doubao|volces|ark").containsMatchIn(k)) {
                return "https://ark.cn-beijing.volces.com/api/v3"
            }
            if (Regex("kimi|moonshot|月之暗面").containsMatchIn(k)) {
                return "https://api.moonshot.cn/v1"
            }
            if (k.contains("silicon")) return "https://api.siliconflow.cn/v1"
            if (k.contains("grok") || k.contains("x.ai") || k.contains("xai")) {
                return "https://api.x.ai/v1"
            }
            if (k.contains("deepseek")) return "https://api.deepseek.com/v1"
            if (Regex("zhipu|智谱|glm").containsMatchIn(k)) {
                return "https://open.bigmodel.cn/api/paas/v4"
            }
            if (k.contains("gemini") || k.contains("google")) {
                return "https://generativelanguage.googleapis.com/v1beta"
            }
            if (k.contains("claude") || k.contains("anthropic")) {
                return "https://api.anthropic.com/v1"
            }
            return "https://api.openai.com/v1"
        }

        /** Verbatim port of L6638-6649 _defaultBalanceApiPath. */
        private fun defaultBalanceApiPath(key: String): String {
            val k = key.lowercase()
            if (k.contains("aihubmix")) return "/user/balance"
            if (k.contains("deepseek")) return "/user/balance"
            if (k.contains("openrouter")) return "/credits"
            if (k.contains("vercel")) return "/credits"
            if (k.contains("silicon")) return "/user/info"
            if (Regex("kimi|moonshot|月之暗面").containsMatchIn(k)) {
                return "/users/me/balance"
            }
            return "/credits"
        }

        /** Verbatim port of L6651-6664 _defaultBalanceResultPath. */
        private fun defaultBalanceResultPath(key: String): String {
            val k = key.lowercase()
            if (k.contains("aihubmix")) return "balance_infos[0].total_balance"
            if (k.contains("deepseek")) return "balance_infos[0].total_balance"
            if (k.contains("openrouter")) {
                return "data.total_credits - data.total_usage"
            }
            if (k.contains("vercel")) return "balance"
            if (k.contains("silicon")) return "data.totalBalance"
            if (Regex("kimi|moonshot|月之暗面").containsMatchIn(k)) {
                return "data.available_balance"
            }
            return "data.total_usage"
        }

        /** Verbatim port of L6666-6674 _defaultBalanceEnabled. */
        private fun defaultBalanceEnabled(key: String): Boolean {
            val k = key.lowercase()
            return k.contains("aihubmix") ||
                k.contains("deepseek") ||
                k.contains("openrouter") ||
                k.contains("vercel") ||
                k.contains("silicon") ||
                Regex("kimi|moonshot|月之暗面").containsMatchIn(k)
        }

        /** Pure merge: ordered keys first (only those present), leftovers appended. */
        fun mergeOrder(keys: List<String>, order: List<String>): List<String> {
            val remaining = keys.toMutableList()
            val out = ArrayList<String>(keys.size)
            for (k in order) {
                if (remaining.remove(k)) out.add(k)
            }
            out.addAll(remaining)
            return out
        }

        const val ORDER_KEY = "providers_order_v1"
    }
}
