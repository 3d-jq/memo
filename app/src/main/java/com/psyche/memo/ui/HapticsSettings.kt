package com.psyche.memo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics

/**
 * The per-event haptics toggles the Flutter widgets read through
 * `context.read<SettingsProvider>()`: `hapticsIosSwitch` (ios_switch.dart L178),
 * `hapticsOnListItemTap` (providers_page.dart L1841), `hapticsOnCardTap`
 * (ios_tactile.dart `IosCardPress._handleTap`). Keys and defaults are
 * settings_provider.dart L1120-1127.
 */
class HapticsSettings(private val read: (String, Boolean) -> Boolean) {
    val globalEnabled: Boolean get() = read(KEY_GLOBAL, true)
    val iosSwitch: Boolean get() = read(KEY_IOS_SWITCH, true)
    val onDrawer: Boolean get() = read(KEY_DRAWER, true)
    val onListItemTap: Boolean get() = read(KEY_LIST_ITEM_TAP, true)
    val onCardTap: Boolean get() = read(KEY_CARD_TAP, true)
    val onGenerate: Boolean get() = read(KEY_GENERATE, false)

    companion object {
        const val KEY_GENERATE = "display_haptics_on_generate_v1"
        const val KEY_DRAWER = "display_haptics_on_drawer_v1"
        const val KEY_GLOBAL = "display_haptics_global_enabled_v1"
        const val KEY_IOS_SWITCH = "display_haptics_ios_switch_v1"
        const val KEY_LIST_ITEM_TAP = "display_haptics_on_list_item_tap_v1"
        const val KEY_CARD_TAP = "display_haptics_on_card_tap_v1"
    }
}

/** Every flag defaults the way settings_provider.dart does when the key is absent. */
val LocalHapticsSettings = staticCompositionLocalOf {
    HapticsSettings { _, default -> default }
}

/**
 * Installs the master switch into the [Haptics] service — settings_provider.dart
 * L1129 does the same on load — and publishes the per-event flags.
 */
@Composable
fun ProvideHapticsSettings(container: AppContainerImpl, content: @Composable () -> Unit) {
    val settings = HapticsSettings { key, default ->
        container.preferenceRepository.readJson(key)?.let { it == "1" } ?: default
    }
    SideEffect { Haptics.enabledProvider = { settings.globalEnabled } }
    CompositionLocalProvider(LocalHapticsSettings provides settings) { content() }
}
