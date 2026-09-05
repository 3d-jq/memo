package com.psyche.memo.data.repo

import android.database.sqlite.SQLiteDatabase
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.data.model.ProviderGroup
import com.psyche.memo.data.settings.PreferenceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

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
        // (idempotent: re-running matches nothing once removed).
        cleanupEmptyBuiltinRows()
    }

    /** Base provider keys shown before any user-added config (providers_page._providers). */
    val builtinKeys: List<String> get() = BUILTIN_KEYS

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
     * already renders), so dropping them de-duplicates the list.
     */
    fun cleanupEmptyBuiltinRows() {
        val builtinLower = BUILTIN_KEYS.map { it.lowercase() }.toSet()
        for (row in providerDao.getAll()) {
            if (row.id.lowercase() !in builtinLower) continue
            val cfg = runCatching { ProviderConfig.fromJsonString(json, row.payload) }.getOrNull() ?: continue
            if (cfg.apiKey.isBlank() && cfg.models.isEmpty()) {
                providerDao.delete(row.id)
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

    fun saveGroup(group: ProviderGroup) {
        val isNew = groupDao.get(group.id) == null
        val sortOrder = if (isNew) groupDao.nextSortOrder() else groupDao.get(group.id)!!.sortOrder
        groupDao.upsert(group.id, group.toJsonString(json), sortOrder)
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
