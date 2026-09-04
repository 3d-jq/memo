package com.psyche.memo.ui.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import com.psyche.memo.common.AppLocale

/**
 * A Context whose resources resolve against [appLocale], or the receiver
 * unchanged when the app follows the system language.
 */
fun Context.withAppLocale(appLocale: AppLocale): Context {
    val target = appLocale.toLocale() ?: return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(target))
    // createConfigurationContext drops the theme, so wrap it back on — themed
    // dialogs and Material3 components still read the original one.
    return ContextThemeWrapper(createConfigurationContext(config), theme)
}
