package com.psyche.memo.common

import android.view.HapticFeedbackConstants
import android.view.View

/** The four feedback levels `lib/core/services/haptics.dart` exposes. */
enum class HapticKind { LIGHT, MEDIUM, SOFT, DRAWER_PULSE }

/**
 * Port of `lib/core/services/haptics.dart`: centralized gentle haptics,
 * fire-and-forget, silenced by one master switch that the settings layer owns
 * (`settings_provider.dart` L1129 calls `Haptics.setEnabled`).
 *
 * Flutter reaches the platform through its `HapticFeedback` channel, whose
 * Android embedding (`PlatformPlugin.performSystemHapticFeedbackFeedback`) maps
 * `lightImpact` to [HapticFeedbackConstants.VIRTUAL_KEY], `mediumImpact` to
 * `KEYBOARD_TAP` and `selectionClick` to `CLOCK_TICK` — so those constants are
 * what the Dart calls actually fire, and what is reproduced here.
 */
object Haptics {
    /**
     * Live reader for the master switch. The Dart service caches a bool pushed by
     * the settings provider; reading the preference instead keeps the native side
     * correct without a notification channel between the two.
     */
    @Volatile
    var enabledProvider: () -> Boolean = { true }

    val enabled: Boolean
        get() = enabledProvider()

    /** Dart parity for `Haptics.setEnabled(v)` — pins the switch to a constant. */
    fun setEnabled(value: Boolean) {
        enabledProvider = { value }
    }

    /** The platform constant for [kind], or null when haptics are suppressed. */
    fun constantFor(kind: HapticKind): Int? {
        if (!enabled) return null
        return when (kind) {
            HapticKind.LIGHT -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticKind.MEDIUM -> HapticFeedbackConstants.KEYBOARD_TAP
            // The Dart `soft()` and `drawerPulse()` both fall back to
            // selectionClick on Android — "closest built-in equivalent to a very
            // gentle tap".
            HapticKind.SOFT, HapticKind.DRAWER_PULSE -> HapticFeedbackConstants.CLOCK_TICK
        }
    }

    /**
     * Fire-and-forget: never awaited, never throws. The Dart `_safe` wrapper
     * swallows platform-channel errors for the same reason.
     */
    fun fire(view: View?, kind: HapticKind) {
        val constant = constantFor(kind) ?: return
        if (view == null) return
        try {
            view.performHapticFeedback(constant)
        } catch (_: RuntimeException) {
            // Detached view or an OEM that rejects the constant.
        }
    }

    /** Very light tap feedback (small UI taps, success tick). */
    fun light(view: View?) = fire(view, HapticKind.LIGHT)

    /** Medium tap feedback (opening/closing drawer, toggles). */
    fun medium(view: View?) = fire(view, HapticKind.MEDIUM)

    /** Gentlest available tap. */
    fun soft(view: View?) = fire(view, HapticKind.SOFT)

    /** Drawer-specific pulse. */
    fun drawerPulse(view: View?) = fire(view, HapticKind.DRAWER_PULSE)
}
