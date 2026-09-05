package com.psyche.memo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Theme selection mirroring Flutter SettingsProvider.themeMode /
 * themePalette semantics (settings keys theme_mode_v1 / theme_palette_v1),
 * plus the ColorScheme pipeline from `lib/theme/theme_factory.dart`.
 *
 * Compose's [ColorScheme] carries no `brightness`, so the resolved dark flag
 * travels next to it instead of being read back off the scheme.
 *
 * Pure functions so core:ui stays dependency-free; the caller (app) reads the
 * preferences and passes the raw values in.
 */
object MemoTheme {
    const val MODE_KEY = "theme_mode_v1"
    const val PALETTE_KEY = "theme_palette_v1"

    /**
     * @param paletteId raw stored value ("default", "blue", …) or null
     * @param mode "system" | "light" | "dark" or null
     * @param systemDark effective system dark flag
     * @return resolved palette + dark flag
     *
     * Mirrors Flutter SettingsProvider: unset mode defaults to system.
     */
    fun resolve(
        paletteId: String?,
        mode: String?,
        systemDark: Boolean,
    ): Pair<Palette, Boolean> {
        val pid = paletteId?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "default"
        val palette = paletteById(pid)
        val dark = when (mode?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: "system") {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
        return palette to dark
    }

    /**
     * The scheme a `ThemeData` is built from: the palette's brightness variant,
     * the pure-background rewrites, the page-surface sink and the derived
     * `surfaceContainer*` roles — `buildLightThemeForScheme` /
     * `buildDarkThemeForScheme` in that order.
     */
    fun colorScheme(
        palette: Palette,
        dark: Boolean,
        pureBackground: Boolean = false,
        layeredSurfaces: Boolean = false,
    ): ColorScheme {
        var scheme = if (dark) palette.dark else palette.light
        if (pureBackground) {
            scheme = if (dark) {
                scheme.copy(
                    surface = Color.Black,
                    inverseSurface = Color.White,
                    inverseOnSurface = Color.Black,
                )
            } else {
                scheme.copy(
                    inverseSurface = Color.Black,
                    inverseOnSurface = Color.White,
                )
            }
        }
        scheme = applyPageSurface(scheme, dark, pureBackground, layeredSurfaces)
        return withDerivedSurfaceContainers(scheme, dark, layeredSurfaces)
    }

    /**
     * `theme_factory.dart` `_applyPageSurface`: the palette-declared `surface`
     * is the card, so the page is that color sunk 4 tones (light + layered only,
     * skipped for a pure-white background).
     */
    fun applyPageSurface(
        scheme: ColorScheme,
        dark: Boolean,
        pureBackground: Boolean,
        layered: Boolean = false,
    ): ColorScheme {
        if (dark) return scheme
        if (pureBackground) return scheme.copy(surface = Color.White)
        if (!layered) return scheme
        return scheme.copy(surface = shiftTone(scheme.surface, -4.0))
    }

    /** `theme_factory.dart` `_withDerivedSurfaceContainers`. */
    fun withDerivedSurfaceContainers(
        scheme: ColorScheme,
        dark: Boolean,
        layered: Boolean = false,
    ): ColorScheme {
        val ladder = SurfaceLadder.fromScheme(scheme, dark, layered)
        return scheme.copy(
            surfaceContainerLowest = ladder.surfaceContainerLowest,
            surfaceContainerLow = ladder.surfaceContainerLow,
            surfaceContainer = ladder.surfaceContainer,
            surfaceContainerHigh = ladder.surfaceContainerHigh,
            surfaceContainerHighest = ladder.surfaceContainerHighest,
        )
    }
}
