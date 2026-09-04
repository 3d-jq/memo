package com.psyche.memo.ui.theme

import androidx.compose.material3.ColorScheme

/**
 * Theme selection mirroring Flutter SettingsProvider.themeMode /
 * themePalette semantics (settings keys theme_mode_v1 / theme_palette_v1).
 *
 * Pure function so core:ui stays dependency-free; the caller (app) reads the
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

    fun colorScheme(palette: Palette, dark: Boolean): ColorScheme =
        if (dark) palette.dark else palette.light
}
