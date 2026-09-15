package com.psyche.memo.data.settings

import android.content.SharedPreferences
import com.psyche.memo.data.db.MemoDatabase

/**
 * Key-value store mirroring BusinessSettingsRouter routing semantics:
 * entity/providerOrder/discarded keys never land here; localOnly keys
 * (including any `restore_*`) live in SharedPreferences; everything else
 * (registered preference keys plus unknown passthrough keys) lives in
 * preference_rows as JSON text with microsecond timestamps.
 */
class PreferenceRepository(
    private val db: MemoDatabase,
    private val prefs: SharedPreferences,
) {
    /**
     * `preference_rows` 的进程内读缓存 —— 与 `ProviderConfigCache` 同一套路。
     *
     * `readJson` 每次调用都是一条 SQLite 查询，而它在 **ui 层的组合期**被调用约 195 处
     * （`remember { readJson(...) }`）：每次进页面、每次重建都要在主线程上打一轮
     * SQLite 往返，用户 2026-09-15「我这个 app 只要遇到加载显示场景就会卡」的一半根因
     * 在这里（另一半见 PORTING §5.13）。
     *
     * 失效由写入侧负责：[writeJson] / [remove] 单键失效，另有 [invalidateCache] 给
     * 「绕过本类直接改 preference_rows」的调用方（目前只有启动期的 `AssetDirMigration`
     * 一条裸 UPDATE）。**用哨兵记「查过、库里没有」** —— 否则未写过的键（默认值那批，
     * 恰恰是大多数）每次都要再查一遍。
     */
    private class Cached(val value: String?)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    private fun nowMicros(): Long = System.currentTimeMillis() * 1000L

    fun readJson(key: String): String? {
        return when (classifyBusinessKey(key)) {
            KeyDisposition.PREFERENCE, KeyDisposition.UNKNOWN, KeyDisposition.PROVIDER_ORDER -> {
                cache[key]?.let { return it.value }
                val value = db.readableDatabase.query(
                    "preference_rows", arrayOf("value"), "key = ?", arrayOf(key),
                    null, null, null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                cache[key] = Cached(value)
                value
            }
            // Symmetric with writeJson: LOCAL_ONLY keys live in SharedPreferences.
            KeyDisposition.LOCAL_ONLY -> prefs.getString(key, null)
            else -> null
        }
    }

    /** 给「直接改 preference_rows 的裸 SQL」用（备份恢复走 [writeJson]，不必调）。 */
    fun invalidateCache(key: String? = null) {
        if (key == null) cache.clear() else cache.remove(key)
    }

    fun writeJson(key: String, valueJson: String) {
        when (classifyBusinessKey(key)) {
            KeyDisposition.PREFERENCE, KeyDisposition.UNKNOWN, KeyDisposition.PROVIDER_ORDER -> {
                val db = db.writableDatabase
                db.execSQL(
                    "INSERT OR REPLACE INTO preference_rows (key, value, updated_at) VALUES (?, ?, ?)",
                    arrayOf<Any>(key, valueJson, nowMicros()),
                )
                cache.remove(key)
            }
            KeyDisposition.LOCAL_ONLY -> prefs.edit().putString(key, valueJson).apply()
            else -> Unit
        }
    }

    fun remove(key: String) {
        when (classifyBusinessKey(key)) {
            KeyDisposition.PREFERENCE, KeyDisposition.UNKNOWN, KeyDisposition.PROVIDER_ORDER -> {
                db.writableDatabase.delete("preference_rows", "key = ?", arrayOf(key))
                cache.remove(key)
            }
            KeyDisposition.LOCAL_ONLY -> prefs.edit().remove(key).apply()
            else -> Unit
        }
    }

    /** All preference_rows as key -> JSON text (for backup export / runtime needs). */
    fun readAllRows(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        db.readableDatabase.query(
            "preference_rows", arrayOf("key", "value"), null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return result
    }

    fun readLocal(key: String): String? = prefs.getString(key, null)

    fun writeLocal(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** Removes a device-local key (the sibling of [writeLocal]). */
    fun removeLocal(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun readAllLocal(): Map<String, String> = prefs.all
        .filterValues { it is String }
        .mapValues { it.value as String }

    /**
     * 一次性迁移：`display_*` 与 `user_name`/`avatar_type`/`avatar_value` 曾经落在
     * SharedPreferences（与 classifyBusinessKey 的 PREFERENCE 分类不符，备份采集只
     * 遍历 preference_rows → 备份不含它们）。现在这些键统一走 DB：把 SharedPreferences
     * 里残留的旧值搬进 preference_rows（DB 已有值不覆盖，保留新写入的），并清掉本地位。
     *
     * 反向段：早前的迁移条件误把 LOCAL_ONLY 键（如 `display_chat_font_scale_v1`）
     * 也搬进了 DB —— 把 DB 里这类键的值送回 SharedPreferences 并删掉 DB 行
     * （`remove()` 对 LOCAL_ONLY 会删 SharedPreferences，所以这里直接 SQL 删）。
     *
     * 幂等：迁移后本地位已清，再跑不会重复搬。
     */
    fun migrateLegacyLocalSettings() {
        // 反向：LOCAL_ONLY 键误入 DB → 搬回 SharedPreferences。
        val misplaced = readAllRows().keys
            .filter { classifyBusinessKey(it) == KeyDisposition.LOCAL_ONLY }
        for (key in misplaced) {
            val value = db.readableDatabase.query(
                "preference_rows", arrayOf("value"), "key = ?", arrayOf(key),
                null, null, null,
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (!value.isNullOrEmpty()) {
                prefs.edit().putString(key, value).apply()
            }
            db.writableDatabase.delete("preference_rows", "key = ?", arrayOf(key))
            cache.remove(key)
        }

        // 正向：PREFERENCE 键从 SharedPreferences 搬进 preference_rows。
        val legacyKeys = prefs.all.keys.filter { key ->
            (key.startsWith("display_") || key in LEGACY_USER_KEYS) &&
                // Only keys the classifier files under the DB; LOCAL_ONLY
                // display_* (e.g. display_chat_font_scale_v1) must stay put.
                classifyBusinessKey(key) == KeyDisposition.PREFERENCE
        }
        for (key in legacyKeys) {
            val raw = prefs.getString(key, null)
            if (raw.isNullOrEmpty()) {
                prefs.edit().remove(key).apply()
                continue
            }
            if (readJson(key) == null) {
                writeJson(key, raw)
            }
            prefs.edit().remove(key).apply()
        }
    }

    private companion object {
        val LEGACY_USER_KEYS = setOf("user_name", "avatar_type", "avatar_value")
    }
}
