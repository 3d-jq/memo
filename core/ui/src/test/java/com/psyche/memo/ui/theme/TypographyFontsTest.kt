package com.psyche.memo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/** `applyAppFont`（main.dart:868+）—— App 字体必须落到全部 15 个 TextTheme 槽位。 */
class TypographyFontsTest {

    @Test
    fun everySlotCarriesTheFamily() {
        val family = FontFamily.Monospace
        val t = Typography().withFontFamily(family)
        val slots = listOf(
            t.displayLarge, t.displayMedium, t.displaySmall,
            t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall,
            t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium, t.labelSmall,
        )
        assertEquals(15, slots.size)
        slots.forEach { assertEquals(family, it.fontFamily) }
    }

    @Test
    fun applyingAFamilyActuallyReplacesTheDefault() {
        // Material3 的默认 Typography 自带平台族名，所以「应用后」必须真的换掉它。
        val base = Typography().bodyMedium.fontFamily
        assertEquals(FontFamily.Monospace, Typography().withFontFamily(FontFamily.Monospace).bodyMedium.fontFamily)
        org.junit.Assert.assertNotEquals(FontFamily.Monospace, base)
    }
}
