package com.psyche.memo.common

import android.view.HapticFeedbackConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kind -> constant table is the whole contract: it has to agree with
 * PlatformPlugin.performSystemHapticFeedbackFeedback, otherwise every widget
 * that asks for `Haptics.light()` buzzes with the wrong weight.
 */
class HapticsTest {

    @After
    fun restoreMasterFlag() {
        Haptics.setEnabled(true)
    }

    @Test
    fun kindsMapToTheFlutterPlatformPluginConstants() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(HapticKind.LIGHT))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Haptics.constantFor(HapticKind.MEDIUM))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(HapticKind.SOFT))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(HapticKind.DRAWER_PULSE))
    }

    @Test
    fun masterFlagSuppressesEveryKind() {
        Haptics.setEnabled(false)
        for (kind in HapticKind.entries) {
            assertNull(kind.name, Haptics.constantFor(kind))
        }
    }

    @Test
    fun enabledProviderIsReadLiveSoSettingsTakeEffectImmediately() {
        var flag = true
        Haptics.enabledProvider = { flag }
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(HapticKind.LIGHT))
        flag = false
        assertNull(Haptics.constantFor(HapticKind.LIGHT))
    }

    @Test
    fun firingWithoutAViewIsANoOp() {
        Haptics.fire(null, HapticKind.LIGHT)
        Haptics.light(null)
        Haptics.medium(null)
        Haptics.soft(null)
        Haptics.drawerPulse(null)
    }
}
