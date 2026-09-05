package com.psyche.memo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dart:ui color arithmetic, pinned to values the engine itself produces. The
 * expected literals come from the same generated fixture as IosWidgetColorsTest
 * (pure-Dart harness over painting.dart / theme_data.dart), so a rounding
 * regression here shows up as a concrete palette + channel, not a guess.
 */
class ColorMathTest {

    // default palette, light: surface F7F7F7, onSurface 202020, primary 4D5C92.
    private val lightSurface = Color(0xFFF7F7F7)
    private val lightOnSurface = Color(0xFF202020)
    private val lightPrimary = Color(0xFF4D5C92)

    // default palette, dark: surface 121213, onSurface F9F9F9, primary B6C4FF.
    private val darkSurface = Color(0xFF121213)
    private val darkOnSurface = Color(0xFFF9F9F9)
    private val darkPrimary = Color(0xFFB6C4FF)

    @Test
    fun withAlphaKeepsChannelsAndQuantizesAlpha() {
        // ios_switch.dart OFF track (light) — onSurface @ 0.08.
        assertEquals(Color(0x14202020), withAlpha(lightOnSurface, 0.08))
        // track border, enabled: 0.20 * 0.65.
        assertEquals(Color(0x21202020), withAlpha(lightOnSurface, 0.20 * 0.65))
        // track border, disabled in dark: 0.24 * 0.35.
        assertEquals(Color(0x15F9F9F9), withAlpha(darkOnSurface, 0.24 * 0.35))
        // ios_checkbox.dart disabled fill: activeColor @ 0.5.
        assertEquals(Color(0x804D5C92), withAlpha(lightPrimary, 0.5))
        assertEquals(Color(0x80B6C4FF), withAlpha(darkPrimary, 0.5))
        // ios_tile_button.dart neutral border: outlineVariant(000000) @ 0.35.
        assertEquals(Color(0x59000000), withAlpha(Color.Black, 0.35))
        assertEquals(Color(0x00202020), withAlpha(lightOnSurface, 0.0))
    }

    @Test
    fun alphaBlendOverOpaqueBackgroundMatchesDartUi() {
        // ios_switch.dart dark OFF track and OFF thumb.
        assertEquals(Color(0xFF171718), alphaBlend(darkOnSurface, 0.02, darkSurface))
        assertEquals(Color(0xFF656566), alphaBlend(darkOnSurface, 0.36, darkSurface))
        // ios_tile_button.dart neutral press: onSurface @ 0.05 over surfaceFill.
        assertEquals(Color(0xFFE2E2E2), alphaBlend(lightOnSurface, 0.05, Color(0xFFECECEC)))
        assertEquals(Color(0xFF434344), alphaBlend(darkOnSurface, 0.06, Color(0xFF373738)))
    }

    @Test
    fun alphaBlendShortCircuitsOnZeroAlpha() {
        assertEquals(darkSurface, alphaBlend(darkOnSurface, 0.0, darkSurface))
    }

    @Test
    fun alphaBlendTranslucentDividesByOutputAlpha() {
        // ios_tile_button.dart tinted press: onSurface @ 0.05 over primary @ 0.12.
        assertEquals(
            Color(0x2A3F4A6F),
            alphaBlendTranslucent(lightOnSurface, 0.05, lightPrimary, 0.12),
        )
        // Same widget, dark: onSurface @ 0.06 over primary @ 0.20.
        assertEquals(
            Color(0x3FC6D1FE),
            alphaBlendTranslucent(darkOnSurface, 0.06, darkPrimary, 0.20),
        )
    }

    @Test
    fun alphaBlendTranslucentDegradesToTheSimplerBranches() {
        val tinted = Color(0x33B6C4FF)
        assertEquals(tinted, alphaBlendTranslucent(darkOnSurface, 0.0, darkPrimary, 0.20))
        assertEquals(
            alphaBlend(darkOnSurface, 0.06, darkSurface),
            alphaBlendTranslucent(darkOnSurface, 0.06, darkSurface, 1.0),
        )
    }

    @Test
    fun lerpColorInterpolatesEveryChannelIncludingAlpha() {
        // providers_page.dart _AnimatedPressColor: lerp(onSurface, surface, 0.55).
        assertEquals(Color(0xFF969696), lerpColor(lightOnSurface, lightSurface, 0.55))
        assertEquals(Color(0xFF7A7A7A), lerpColor(darkOnSurface, darkSurface, 0.55))
        assertEquals(lightOnSurface, lerpColor(lightOnSurface, lightSurface, 0.0))
        assertEquals(lightSurface, lerpColor(lightOnSurface, lightSurface, 1.0))
        assertEquals(Color(0x80102030), lerpColor(Color(0x00000000), Color(0xFF204060), 0.5))
    }

    @Test
    fun estimateBrightnessFollowsTheMaterialThreshold() {
        // ThemeData.estimateBrightnessForColor: (luminance + 0.05)^2 > 0.15.
        assertFalse(estimateBrightnessIsLight(lightPrimary))
        assertFalse(estimateBrightnessIsLight(Color.Black))
        assertTrue(estimateBrightnessIsLight(Color.White))
        assertTrue(estimateBrightnessIsLight(darkPrimary))
        // doc_theme primary — bright enough that the checkmark flips to black.
        assertTrue(estimateBrightnessIsLight(Color(0xFF00B96B)))
    }
}
