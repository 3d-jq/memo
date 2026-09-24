package com.psyche.memo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Theme resolution semantics, plus a guard on the built-in palette values.
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
    fun colorSchemeKeepsThePaletteSurfaceInFlatMode() {
        val light = MemoTheme.colorScheme(defaultPalette, dark = false)
        val dark = MemoTheme.colorScheme(defaultPalette, dark = true)
        assertEquals(defaultPalette.light.surface, light.surface)
        assertEquals(defaultPalette.dark.surface, dark.surface)
    }

    @Test
    fun layeredModeSinksTheLightPageSurfaceFourTones() {
        // theme_factory.dart `_applyPageSurface`, values from
        // material_color_utilities 0.13.0 (shiftTone(surface, -4.0)).
        assertEquals(
            Color(0xFFEBECEC),
            MemoTheme.colorScheme(defaultPalette, dark = false, layeredSurfaces = true).surface,
        )
        assertEquals(
            Color(0xFFF1F0F3),
            MemoTheme.colorScheme(bluePalette, dark = false, layeredSurfaces = true).surface,
        )
        // Dark schemes come back from `_applyPageSurface` untouched.
        assertEquals(
            defaultPalette.dark.surface,
            MemoTheme.colorScheme(defaultPalette, dark = true, layeredSurfaces = true).surface,
        )
    }

    @Test
    fun pureBackgroundRewritesTheSurfaces() {
        val light = MemoTheme.colorScheme(defaultPalette, dark = false, pureBackground = true)
        assertEquals(Color.White, light.surface)
        assertEquals(Color.Black, light.inverseSurface)
        assertEquals(Color.White, light.inverseOnSurface)

        val dark = MemoTheme.colorScheme(defaultPalette, dark = true, pureBackground = true)
        assertEquals(Color.Black, dark.surface)
        assertEquals(Color.White, dark.inverseSurface)
        assertEquals(Color.Black, dark.inverseOnSurface)
    }

    @Test
    fun pureBackgroundWinsOverTheLayeredSink() {
        // `_applyPageSurface` checks pureBackground before the layered branch.
        assertEquals(
            Color.White,
            MemoTheme.colorScheme(
                defaultPalette,
                dark = false,
                pureBackground = true,
                layeredSurfaces = true,
            ).surface,
        )
    }

    @Test
    fun surfaceContainersComeFromTheLadderOfTheResolvedSurface() {
        for (palette in allPalettes) {
            for (dark in listOf(false, true)) {
                for (layered in listOf(false, true)) {
                    val scheme = MemoTheme.colorScheme(palette, dark, layeredSurfaces = layered)
                    val ladder = SurfaceLadder.fromScheme(scheme, dark, layered)
                    val label = palette.id + (if (dark) " dark" else " light") +
                        (if (layered) " layered" else " flat")
                    assertEquals(label, ladder.surfaceContainerLowest, scheme.surfaceContainerLowest)
                    assertEquals(label, ladder.surfaceContainerLow, scheme.surfaceContainerLow)
                    assertEquals(label, ladder.surfaceContainer, scheme.surfaceContainer)
                    assertEquals(label, ladder.surfaceContainerHigh, scheme.surfaceContainerHigh)
                    assertEquals(
                        label,
                        ladder.surfaceContainerHighest,
                        scheme.surfaceContainerHighest,
                    )
                }
            }
        }
    }
}
