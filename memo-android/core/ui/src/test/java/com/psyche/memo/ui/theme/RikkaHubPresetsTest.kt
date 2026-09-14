package com.psyche.memo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the RikkaHub preset transcription (tools/rikkahub_presets_gen.py) and the
 * deliberate choice behind it: the presets contribute the **colour identity**, while
 * Memo's own SurfaceLadder still derives the surface/panel layer at runtime.
 *
 * 背景（用户 2026-09-13）：「我们这个八个效果不好，用 RikkaHub 那个主题，他那个更全面」，
 * 同时要求默认主题保留、主题设置页 UI/UX 不动。
 */
class RikkaHubPresetsTest {

    @Test
    fun registryIsDefaultPlusSevenPresets() {
        assertEquals(8, themeChoices.size)
        assertSame(defaultPalette, themeChoices.first())
        assertEquals(
            listOf("default", "sakura", "ocean", "spring", "autumn", "black", "minimal", "claude"),
            themeChoices.map { it.id },
        )
        // RikkaHub 的注册顺序（PresetTheme.kt:24-34）。
        assertEquals(
            listOf("sakura", "ocean", "spring", "autumn", "black", "minimal", "claude"),
            rikkahubPresets.map { it.id },
        )
    }

    @Test
    fun presetIdsAreUnique() {
        assertEquals(rikkahubPresets.size, rikkahubPresets.map { it.id }.toSet().size)
        assertEquals(themeChoices.size, themeChoices.map { it.id }.toSet().size)
    }

    @Test
    fun everyChoiceIsResolvableAndUnknownIdsFallBackToDefault() {
        for (palette in themeChoices) {
            assertSame(palette, themePaletteById(palette.id))
        }
        assertSame(defaultPalette, themePaletteById(""))
        assertSame(defaultPalette, themePaletteById("neon"))
    }

    /** Memo 自己那 8 套不再出现在列表里，但 id 仍要能解析（老用户已选的主题不失效）。 */
    @Test
    fun legacyMemoPalettesStayResolvableButAreNotListed() {
        assertSame(bluePalette, themePaletteById("blue"))
        assertSame(monochromePalette, paletteById("monochrome"))
        assertSame(docThemePalette, themePaletteById("doc_theme"))
        assertTrue(themeChoices.none { it.id == "blue" })
        assertTrue(themeChoices.none { it.id == "monochrome" })
    }

    @Test
    fun eachPresetHasDistinctLightAndDarkSchemes() {
        for (palette in rikkahubPresets) {
            assertNotEquals(palette.id, palette.light.primary, palette.dark.primary)
            assertNotEquals(palette.id, palette.light.surface, palette.dark.surface)
            assertNotEquals(palette.id, palette.light.onSurface, palette.dark.onSurface)
        }
    }

    /** 逐值对照 RikkaHub 源码（presets 目录下的各 Theme.kt）的抽样，防止生成器改坏。 */
    @Test
    fun presetValuesMatchRikkaHubSources() {
        assertEquals(Color(0xFFC96442), rikkahubClaudePalette.light.primary)
        assertEquals(Color(0xFFE4906E), rikkahubClaudePalette.dark.primary)
        assertEquals(Color(0xFFFAF9F5), rikkahubClaudePalette.light.surface)
        assertEquals(Color(0xFF1F1E1D), rikkahubClaudePalette.dark.surface)

        assertEquals(Color(0xFF8E4955), rikkahubSakuraPalette.light.primary)
        assertEquals(Color(0xFF116682), rikkahubOceanPalette.light.primary)
        assertEquals(Color(0xFF4C662B), rikkahubSpringPalette.light.primary)
        assertEquals(Color(0xFF735C0C), rikkahubAutumnPalette.light.primary)
        assertEquals(Color(0xFF606060), rikkahubBlackPalette.light.primary)
        assertEquals(Color(0xFF2563EB), rikkahubMinimalPalette.light.primary)

        // 所有预设的 scrim 都是纯黑（上游 14 个 scheme 里唯一一致的槽位）。
        for (palette in rikkahubPresets) {
            assertEquals(Color(0xFF000000), palette.light.scrim)
            assertEquals(Color(0xFF000000), palette.dark.scrim)
        }
    }

    /** 主题名跟随语言（zhName/enName），Claude 保持品牌名不翻译。 */
    @Test
    fun presetNamesMatchRikkaHubL10n() {
        assertEquals("樱花粉", rikkahubSakuraPalette.zhName)
        assertEquals("Sakura Pink", rikkahubSakuraPalette.enName)
        assertEquals("原野绿", rikkahubSpringPalette.zhName)
        assertEquals("Meadow Green", rikkahubSpringPalette.enName)
        assertEquals("Claude", rikkahubClaudePalette.zhName)
        assertEquals("Claude", rikkahubClaudePalette.enName)
    }

    // ---- 「原样表面」通道（用户：要像 RikkaHub 一样覆盖多，不然主题不好看）----

    /** 预设必须真的带一层中性阶梯，否则「原样表面」通道等于没生效。 */
    @Test
    fun presetsCarryTheirOwnSurfaceLadder() {
        for (palette in rikkahubPresets) {
            val light = palette.light
            val dark = palette.dark
            // 卡片层（surfaceContainer）与页面层（surface）必须能分开 —— 这正是
            // Memo 生成的那 9 套做不到的（它们的容器被压平到 surface）。
            assertNotEquals(palette.id, light.surface, light.surfaceContainer)
            assertNotEquals(palette.id, dark.surface, dark.surfaceContainer)
            assertNotEquals(palette.id, light.surface, light.surfaceContainerHigh)
            assertNotEquals(palette.id, dark.surface, dark.surfaceContainerHigh)
        }
    }

    @Test
    fun onlyPresetsUseTheAuthoredSurfaceChannel() {
        assertEquals(rikkahubPresets.map { it.id }.toSet(), authoredSurfacePaletteIds)
        assertEquals(7, authoredSurfacePaletteIds.size)
        // Memo 默认（和它那 8 套）继续走 SurfaceLadder，观感不变。
        assertTrue(defaultPalette.id !in authoredSurfacePaletteIds)
        assertTrue(bluePalette.id !in authoredSurfacePaletteIds)
    }

    /** 语义 token 也直接采信预设的角色：卡片 = surfaceContainer，填充 = surfaceContainerHigh。 */
    @Test
    fun authoredSemanticColorsFollowThePresetRoles() {
        val dark = AppSemanticColors.authored(rikkahubClaudePalette.dark, dark = true)
        assertEquals(rikkahubClaudePalette.dark.surfaceContainer, dark.surfaceCard)
        assertEquals(rikkahubClaudePalette.dark.surfaceContainerHigh, dark.surfaceFill)
        assertEquals(rikkahubClaudePalette.dark.surfaceContainerHigh, dark.surfaceCardFill)
        assertTrue(dark.isDark)
        assertTrue(dark.layered)

        val light = AppSemanticColors.authored(rikkahubClaudePalette.light, dark = false)
        assertEquals(rikkahubClaudePalette.light.surfaceContainer, light.surfaceCard)
        assertEquals(rikkahubClaudePalette.light.surfaceContainerHigh, light.surfaceFill)
        // 与走 SurfaceLadder 的那条通道确实不同（否则这个通道白加）。
        val ladder = AppSemanticColors.light(rikkahubClaudePalette.light)
        assertNotEquals("ladder-vs-authored", ladder.surfaceCard, light.surfaceCard)
    }
}
