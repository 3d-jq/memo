package com.psyche.memo.ui.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.psyche.memo.common.AppLocale

/**
 * A Context whose resources resolve against [appLocale], or the receiver
 * unchanged when the app follows the system language.
 *
 * **Only safe from `Activity.attachBaseContext`** — call it on the `newBase`
 * that the framework hands in:
 * - The returned Context is *not* the Activity, so it must never be pushed into
 *   `LocalContext`. Compose derives `ActivityResultRegistryOwner` from
 *   `LocalContext`, and every `rememberLauncherForActivityResult` (chat export,
 *   avatar gallery, …) throws `No ActivityResultRegistryOwner was provided` once
 *   `LocalContext` stops being an Activity.
 * - Reading `theme` here would also throw: `ContextImpl.getTheme()` asks for
 *   `getOuterContext().getApplicationInfo()`, and during attach the outer
 *   context is not ready. Activity theming comes from the manifest
 *   `android:theme` after attach, so nothing is needed from this Context.
 *
 * Switching the language at runtime therefore recreates the Activity (see
 * `MemoApp`) instead of swapping the Compose `LocalContext`.
 */
fun Context.withAppLocale(appLocale: AppLocale): Context {
    val target = appLocale.toLocale() ?: return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(target))
    return createConfigurationContext(config)
}
