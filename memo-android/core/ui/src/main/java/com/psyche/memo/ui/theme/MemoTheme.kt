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
     * RikkaHub 预设主题的通道（`RikkaHubPresets.kt` 的 `authoredSurfacePaletteIds`）：
     * **原样使用**预设声明的表面 —— 不 `applyPageSurface`、也不
     * `withDerivedSurfaceContainers`。
     *
     * 用户 2026-09-13：「语义 token 226 处…我们要更 rikkhub 一样覆盖多 不然主题不好看」
     * —— 我们的 SurfaceLadder 会把面板色压成「白/黑 alpha 混合」（light 下卡片≈96% 白），
     * 于是换主题只换得到强调色、整块表面几乎不动。预设自带完整的中性阶梯
     * （`surface`、`surfaceContainerLowest`…`surfaceContainerHighest`、`surfaceDim`、
     * `surfaceBright`、`surfaceVariant`），
     * 直接采信它们，主题才真的铺满整个界面（配合 [AppSemanticColors.authored]）。
     * Memo 自己那 9 套调色板仍走 [colorScheme]（它们的容器曾被生成器压平，走原样会没有层次）。
     */
    fun authoredColorScheme(
        palette: Palette,
        dark: Boolean,
        pureBackground: Boolean = false,
    ): ColorScheme {
        val base = if (dark) palette.dark else palette.light
        var scheme = base.copy(
            // 「页面底 = surfaceContainer、卡片 = surfaceBright」—— 照 RikkaHub 实测取色
            // （他们 Claude 浅色下页面 #F2F0E8 = surfaceContainer、卡片 #FFFFFF =
            // surfaceBright；他们自己的命名 `CustomColors.cardColorsOnSurfaceContainer`
            // = surfaceBright 就是这个意思）。Memo 全站的页面底读 `scheme.surface`，
            // 所以这里把 surface 换成 surfaceContainer，卡片那边由
            // [AppSemanticColors.authored] 取 surfaceBright。
            // 实测对比（2026-09-14，真机取色）：改之前我们页面 #FAF9F5 / 卡片 #F2F0E8，
            // 与 RikkaHub 正好相反（用户：「背景人家用的黄的 卡边是白色 我这个做反了吧」）。
            surface = base.surfaceContainer,
        )
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
        return scheme
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
