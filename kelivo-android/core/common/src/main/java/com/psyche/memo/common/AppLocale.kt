package com.psyche.memo.common

import java.util.Locale

/**
 * The UI language the user picked in settings.
 *
 * Mirrors the four values Flutter's SettingsProvider stores under
 * `app_locale_v1` (`system`, `zh_CN`, `zh_Hant`, `en_US`) so a backup taken on
 * either platform restores the same language on the other.
 */
enum class AppLocale(val tag: String) {
    SYSTEM("system"),
    ZH_CN("zh_CN"),
    ZH_HANT("zh_Hant"),
    EN_US("en_US");

    /**
     * The locale to render in, or null when the platform decides — the same
     * contract as Flutter's `appLocaleForMaterialApp`, which passes null to
     * MaterialApp so the OS language (and iOS per-app language) wins.
     */
    fun toLocale(): Locale? = when (this) {
        SYSTEM -> null
        ZH_CN -> Locale.forLanguageTag("zh-CN")
        ZH_HANT -> Locale.forLanguageTag("zh-Hant")
        EN_US -> Locale.forLanguageTag("en-US")
    }

    companion object {
        /** First launch and unreadable values fall back to following the system. */
        val DEFAULT: AppLocale = SYSTEM

        fun fromTag(raw: String?): AppLocale =
            entries.firstOrNull { it.tag == raw } ?: DEFAULT
    }
}
