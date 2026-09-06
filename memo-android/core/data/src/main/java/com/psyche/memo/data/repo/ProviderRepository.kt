package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.KeyManagementConfig
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.model.ProviderGroup
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * - grouping: `provider_group_map_v1` (providerKey -> groupId),
 *   `provider_group_collapsed_v1` (groupId|__ungrouped__ -> bool),
 *   `provider_ungrouped_position_v1` (display index of the ungrouped
 *   section), and provider_group_rows for the group entities.
 *
 * New rows take sort_order = max+1 (PayloadEntityDao.nextSortOrder) — never
 * a truncated timestamp, which could violate the schema CHECK(sort_order>=0).
 */
class ProviderRepository(
    db: SQLiteDatabase,
    private val prefs: PreferenceRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val providerDao = PayloadEntityDao(db, "provider_rows", primaryKey = "provider_key")
    private val groupDao = PayloadEntityDao(db, "provider_group_rows", primaryKey = "id")

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

    // ---- groups (provider_group_rows + provider_group_map_v1) ----

    fun groups(): List<ProviderGroup> =
        groupDao.getAll().mapNotNull { row -> ProviderGroup.fromJsonString(json, row.payload) }

    fun groupById(groupId: String): ProviderGroup? = groups().firstOrNull { it.id == groupId }

    fun saveGroup(group: ProviderGroup) {
        val isNew = groupDao.get(group.id) == null
        val sortOrder = if (isNew) groupDao.nextSortOrder() else groupDao.get(group.id)!!.sortOrder
        groupDao.upsert(group.id, group.toJsonString(json), sortOrder)
    }

    /** Rewrites every group row so sort_order follows the given list order. */
    private fun replaceGroups(groups: List<ProviderGroup>) {
        val existing = groupDao.getAll().associateBy { it.id }
        groups.forEachIndexed { index, group ->
            groupDao.upsert(group.id, group.toJsonString(json), index)
        }
        // Rows for groups no longer in the list (rename/create callers always
        // pass the full list) are removed to keep the table authoritative.
        val keep = groups.map { it.id }.toSet()
        for (row in existing.values) {
            if (row.id !in keep) groupDao.delete(row.id)
        }
    }

    /**
     * Port of SettingsProvider.createGroup: case-insensitive duplicate names
     * return the existing id; otherwise the group is inserted at the end and
     * the ungrouped display index shifts right if it was after the insertion.
     */
    fun createGroup(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val key = trimmed.lowercase()
        for (g in groups()) {
            if (g.name.trim().lowercase() == key) return g.id
        }
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val res = ProviderGroupLogic.insertProviderGroup(
            groups = groups(),
            ungroupedIndex = ungroupedPosition(),
            group = ProviderGroup(id = id, name = trimmed, createdAt = now),
        )
        replaceGroups(res.groups)
        setUngroupedPosition(res.ungroupedIndex)
        cleanupProviderGrouping()
        return id
    }

    /** Port of SettingsProvider.renameGroup (duplicate-name no-op included). */
    fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val all = groups()
        val idx = all.indexOfFirst { it.id == groupId }
        if (idx < 0) return
        val key = trimmed.lowercase()
        for (g in all) {
            if (g.id != groupId && g.name.trim().lowercase() == key) return
        }
        if (all[idx].name == trimmed) return
        val mut = all.toMutableList()
        mut[idx] = mut[idx].copy(name = trimmed)
        replaceGroups(mut)
        cleanupProviderGrouping()
    }

    /** Port of SettingsProvider.reorderProviderGroupsWithUngrouped. */
    fun reorderGroupsWithUngrouped(oldIndex: Int, newIndex: Int) {
        val displayCount = groups().size + 1
        if (displayCount <= 1) return
        if (oldIndex < 0 || oldIndex >= displayCount) return
        if (newIndex < 0 || newIndex > displayCount) return
        if (oldIndex == newIndex) return
        val res = ProviderGroupLogic.reorderProviderGroupDisplayWithUngrouped(
            groups = groups(),
            ungroupedIndex = ungroupedPosition(),
            oldIndex = oldIndex,
            newIndex = newIndex,
        )
        replaceGroups(res.groups)
        setUngroupedPosition(res.ungroupedIndex)
        cleanupProviderGrouping()
    }

    /** Port of SettingsProvider.deleteGroup: members fall back to ungrouped. */
    fun deleteGroupFully(groupId: String) {
        if (groupById(groupId) == null) return
        val res = ProviderGroupLogic.deleteProviderGroup(
            groups = groups(),
            ungroupedIndex = ungroupedPosition(),
            providerGroupMap = groupMap(),
            collapsed = collapsedAll(),
            groupId = groupId,
        )
        replaceGroups(res.groups)
        setUngroupedPosition(res.ungroupedIndex)
        setGroupMap(res.providerGroupMap)
        setCollapsedAll(res.collapsed)
        cleanupProviderGrouping()
    }

    /**
     * Light port of SettingsProvider._cleanupProviderOrderAndGrouping: drop
     * group-map entries whose provider key or group id no longer resolves.
     */
    fun cleanupProviderGrouping() {
        val knownKeys = providerDao.getAll().map { it.id }.toSet()
        val validGroupIds = groups().map { it.id }.toSet()
        val map = groupMap().filter { (k, v) -> k in knownKeys && v in validGroupIds }
        if (map != groupMap()) setGroupMap(map)
    }

    /** Full collapsed map (provider_group_collapsed_v1). */
    fun collapsedAll(): Map<String, Boolean> {
        val raw = prefs.readJson(COLLAPSED_KEY) ?: return emptyMap()
        return runCatching {
            (json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject)
                ?.entries
                ?.associate { it.key to ((it.value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false) }
                .orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun setCollapsedAll(map: Map<String, Boolean>) {
        prefs.writeJson(
            COLLAPSED_KEY,
            JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString(),
        )
    }

    fun deleteGroup(groupId: String) {
        groupDao.delete(groupId)
        // Members fall back to ungrouped.
        val map = groupMap().toMutableMap()
        map.entries.removeIf { it.value == groupId }
        setGroupMap(map)
    }

    fun groupMap(): Map<String, String> {
        val raw = prefs.readJson(GROUP_MAP_KEY) ?: return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(raw)
            (obj as? kotlinx.serialization.json.JsonObject)?.entries?.associate {
                it.key to (it.value as? JsonPrimitive)?.content.orEmpty()
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun setGroupMap(map: Map<String, String>) {
        prefs.writeJson(
            GROUP_MAP_KEY,
            kotlinx.serialization.json.JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString(),
        )
    }

    fun groupFor(providerKey: String): String? = groupMap()[providerKey]

    fun setGroupFor(providerKey: String, groupId: String?) {
        val map = groupMap().toMutableMap()
        if (groupId == null) map.remove(providerKey) else map[providerKey] = groupId
        setGroupMap(map)
    }

    // ---- collapsed state (provider_group_collapsed_v1) ----

    fun isCollapsed(groupKey: String): Boolean {
        val raw = prefs.readJson(COLLAPSED_KEY) ?: return false
        return runCatching {
            (json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject)
                ?.get(groupKey)?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: false
        }.getOrDefault(false)
    }

    fun setCollapsed(groupKey: String, collapsed: Boolean) {
        val obj = runCatching {
            json.parseToJsonElement(prefs.readJson(COLLAPSED_KEY) ?: "{}")
        }.getOrDefault(json.parseToJsonElement("{}")) as? kotlinx.serialization.json.JsonObject
        val next = (obj ?: kotlinx.serialization.json.JsonObject(emptyMap())).toMutableMap()
        next[groupKey] = JsonPrimitive(collapsed)
        prefs.writeJson(
            COLLAPSED_KEY,
            kotlinx.serialization.json.JsonObject(next).toString(),
        )
    }

    // ---- ungrouped display position ----

    fun ungroupedPosition(): Int {
        val raw = prefs.readJson(UNGROUPED_POS_KEY)?.replace("\"", "") ?: return Int.MAX_VALUE
        return raw.toIntOrNull() ?: Int.MAX_VALUE
    }

    fun setUngroupedPosition(index: Int) {
        prefs.writeJson(UNGROUPED_POS_KEY, JsonPrimitive(index).toString())
    }

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
         * Verbatim port of Flutter ProviderConfig.defaultsFor
         * (settings_provider.dart L6449-6636, incl. _defaultBase /
         * defaultEnabled / balance defaults) — the pristine first-run config a
         * built-in key gets when it has no row (ensureProviderConfig
         * semantics, defaultName = key). KelivoIN is matched under its port
         * brand MemoIN; its public key and seed models are copied unchanged.
         */
        fun defaultsFor(key: String): ProviderConfig {
            val lowerKey = key.lowercase()
            val isKelivoIn = lowerKey.contains("kelivoin") || lowerKey == "memoin"

            // settings_provider.dart L6450-6459 defaultEnabled
            val enabled = lowerKey.contains("tensdaq") ||
                lowerKey.contains("openai") ||
                lowerKey.contains("gemini") || lowerKey.contains("google") ||
                lowerKey.contains("silicon") ||
                lowerKey.contains("openrouter") ||
                isKelivoIn

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
                    // Special-case KelivoIN default models and overrides (L6518-6568)
                    isKelivoIn -> ProviderConfig(
                        id = key,
                        enabled = enabled,
                        name = key,
                        apiKey = "kelivo", // _kelivoInPublicApiKey (L6056)
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

        /** chat model override map entry of the KelivoIN / SiliconFlow seeds. */
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
            if (k.contains("kelivoin") || k == "memoin") return "https://text.pollinations.ai/openai"
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
        const val GROUP_MAP_KEY = "provider_group_map_v1"
        const val COLLAPSED_KEY = "provider_group_collapsed_v1"
        const val UNGROUPED_POS_KEY = "provider_ungrouped_position_v1"
        const val UNGROUPED_KEY = "__ungrouped__"
    }
}
