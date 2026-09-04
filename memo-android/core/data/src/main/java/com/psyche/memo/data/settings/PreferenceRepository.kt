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
}
