package com.psyche.memo.data.settings

import com.psyche.memo.common.AppLocale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reads and writes the UI language.
 *
 * `app_locale_v1` is a registered PREFERENCE key, so the value lives in
 * preference_rows as JSON text — the same row the Flutter app writes, which
 * keeps the setting intact across a backup/restore between the two ports.
 */
class AppLocaleStore(private val preferences: PreferenceRepository) {

    fun read(): AppLocale = AppLocale.fromTag(preferences.readJson(KEY)?.let(::decode))

    fun write(locale: AppLocale) {
        preferences.writeJson(KEY, Json.encodeToString(locale.tag))
    }

    private fun decode(raw: String): String? =
        runCatching { Json.decodeFromString<String>(raw) }.getOrNull()

    companion object {
        const val KEY = "app_locale_v1"
    }
}
