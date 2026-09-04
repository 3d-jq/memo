package com.psyche.memo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Theme resolution semantics, plus a guard on the palette values transcribed by
 * tools/palettes_gen.py from lib/theme/palettes.dart.
 */
class MemoThemeTest {

    @Test
    fun unsetModeFollowsTheSystemFlag() {
        assertEquals(false, MemoTheme.resolve(null, null, systemDark = false).second)
        assertEquals(true, MemoTheme.resolve(null, null, systemDark = true).second)
    }

    @Test
    fun systemModeFollowsTheSystemFlag() {
        assertEquals(true, MemoTheme.resolve(null, "system", systemDark = true).second)
        assertEquals(false, MemoTheme.resolve(null, "system", systemDark = false).second)
    }

    @Test
    fun explicitLightAndDarkOverrideTheSystem() {
        assertEquals(false, MemoTheme.resolve(null, "light", systemDark = true).second)
        assertEquals(true, MemoTheme.resolve(null, "dark", systemDark = false).second)
    }

    @Test
    fun unknownModeFallsBackToSystem() {
        assertEquals(true, MemoTheme.resolve(null, "oled", systemDark = true).second)
        assertEquals(false, MemoTheme.resolve(null, "oled", systemDark = false).second)
    }

    @Test
    fun jsonQuotedPreferenceValuesAreStripped() {
        // preference_rows keeps values as JSON text, so "light" arrives quoted.
        assertEquals(false, MemoTheme.resolve(null, "\"light\"", systemDark = true).second)
        assertSame(defaultPalette, MemoTheme.resolve("\"default\"", null, false).first)
    }

    @Test
    fun unknownOrEmptyPaletteIdFallsBackToDefault() {
        assertSame(defaultPalette, MemoTheme.resolve("", null, false).first)
        assertSame(defaultPalette, MemoTheme.resolve("neon", null, false).first)
        assertSame(defaultPalette, paletteById("neon"))
    }

    @Test
    fun everyPaletteIdIsResolvable() {
        assertEquals(9, allPalettes.size)
        for (palette in allPalettes) {
            assertSame(palette, paletteById(palette.id))
        }
    }

    @Test
    fun generatedPrimaryColorsMatchTheFlutterSource() {
        assertEquals(Color(0xFF4D5C92), defaultPalette.light.primary)
        assertEquals(Color(0xFFB6C4FF), defaultPalette.dark.primary)
        assertEquals(Color(0xFF000000), monochromePalette.light.primary)
        assertEquals(Color(0xFFFFFFFF), monochromePalette.dark.primary)
        assertEquals(Color(0xFF00B96B), docThemePalette.light.primary)
    }

    @Test
    fun colorSchemePicksTheRequestedBrightness() {
        assertSame(defaultPalette.dark, MemoTheme.colorScheme(defaultPalette, dark = true))
        assertSame(defaultPalette.light, MemoTheme.colorScheme(defaultPalette, dark = false))
    }
}
