package com.psyche.memo.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 设置页分组标题必须**跟随主题色**。
 *
 * 用户 2026-09-14：「主题设置里面那个分类的字的颜色没有跟着主题走呀 rikkhub就可以呀」。
 * 照 RikkaHub：所有设置页的骨架 `CardGroup` 标题一律 `colorScheme.primary`
 * （`CardGroup.kt:157` 的 `LocalContentColor provides …primary`），他们主题页的
 * 「预设主题 / 自定义主题」标题也直接写 `colorScheme.primary`
 * （`SettingThemePage.kt:150/181`）。
 *
 * 关键判据是「跟随 primary、**不**跟随 onSurface」—— Memo 改之前写的正是
 * `onSurface@80%`，所以换主题时这行字纹丝不动。谁改回去，这里就会红。
 */
class SettingsSectionHeaderColorTest {

    private val base = lightColorScheme(
        primary = Color(0xFF8E4955),
        onSurface = Color(0xFF1B1B1B),
    )

    @Test
    fun headerUsesTheSchemePrimary() {
        assertEquals(base.primary, settingsSectionHeaderColor(base))
    }

    @Test
    fun headerFollowsPrimaryWhenTheThemeChanges() {
        val other = base.copy(primary = Color(0xFF116682))
        assertEquals(other.primary, settingsSectionHeaderColor(other))
        assertNotEquals(settingsSectionHeaderColor(base), settingsSectionHeaderColor(other))
    }

    /** 回归判据：改 onSurface 不该再影响标题（旧实现读的就是它）。 */
    @Test
    fun headerIgnoresOnSurface() {
        val onSurfaceChanged = base.copy(onSurface = Color(0xFFEFEFEF))
        assertEquals(
            settingsSectionHeaderColor(base),
            settingsSectionHeaderColor(onSurfaceChanged),
        )
    }

    @Test
    fun darkSchemeAlsoUsesPrimary() {
        val dark = darkColorScheme(
            primary = Color(0xFFE4906E),
            onSurface = Color(0xFFE6E1E5),
        )
        assertEquals(dark.primary, settingsSectionHeaderColor(dark))
    }
}
