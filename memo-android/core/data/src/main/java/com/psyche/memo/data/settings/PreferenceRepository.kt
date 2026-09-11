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
    private fun nowMicros(): Long = System.currentTimeMillis() * 1000L

    fun readJson(key: String): String? {
        return when (classifyBusinessKey(key)) {
            KeyDisposition.PREFERENCE, KeyDisposition.UNKNOWN, KeyDisposition.PROVIDER_ORDER ->
                db.readableDatabase.query(
                    "preference_rows", arrayOf("value"), "key = ?", arrayOf(key),
                    null, null, null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            else -> null
        }
    }

    fun writeJson(key: String, valueJson: String) {
        when (classifyBusinessKey(key)) {
            KeyDisposition.PREFERENCE, KeyDisposition.UNKNOWN, KeyDisposition.PROVIDER_ORDER -> {
                val db = db.writableDatabase
                db.execSQL(
                    "INSERT OR REPLACE INTO preference_rows (key, value, updated_at) VALUES (?, ?, ?)",
                    arrayOf<Any>(key, valueJson, nowMicros()),
                )
            }
            KeyDisposition.LOCAL_ONLY -> prefs.edit().putString(key, valueJson).apply()
            else -> Unit
        }
    }

    fun remove(key: String) {
        when (classifyBusinessKey(key)) {
            KeyDisposition.PREFERENCE, KeyDisposition.UNKNOWN, KeyDisposition.PROVIDER_ORDER ->
                db.writableDatabase.delete("preference_rows", "key = ?", arrayOf(key))
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

    fun readAllLocal(): Map<String, String> = prefs.all
        .filterValues { it is String }
        .mapValues { it.value as String }

    /**
     * 一次性迁移：`display_*` 与 `user_name`/`avatar_type`/`avatar_value` 曾经落在
     * SharedPreferences（与 classifyBusinessKey 的 PREFERENCE 分类不符，备份采集只
     * 遍历 preference_rows → 备份不含它们）。现在这些键统一走 DB：把 SharedPreferences
     * 里残留的旧值搬进 preference_rows（DB 已有值不覆盖，保留新写入的），并清掉本地位。
     *
     * 幂等：迁移后本地位已清，再跑不会重复搬。
     */
    fun migrateLegacyLocalSettings() {
        val legacyKeys = prefs.all.keys.filter { key ->
            key.startsWith("display_") || key in LEGACY_USER_KEYS
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
